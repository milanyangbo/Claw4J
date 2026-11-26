#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
外部 HTTP JSON 接口的薄封装：拼查询串、带 UA、超时、把失败翻成人话。

为什么单独一个文件：五个 server（weather / media / food / culture / life）都要发 GET，
各自写一遍 urllib 的话，"超时怎么办""对方返回非 JSON 怎么办"会有五份不同的答案。

失败一律返回 (None, 原因) 而不是抛异常：MCP 工具的返回值就是给模型看的那句话。
一个 `URLError` 冒到协议层，模型拿到的是空结果——它不知道该重试、该换个城市、还是该换工具。
"""

import json
import urllib.error
import urllib.parse
import urllib.request

USER_AGENT = "claw4j-mcp/1.0"


def _detail(failure):
    """对方返回错误时，正文里往往有一句能读懂的原因；只给状态码等于没说。"""
    try:
        body = failure.read().decode("utf-8", "replace").strip()
    except Exception:  # noqa: BLE001  读不到正文不该盖住真正的失败
        return ""
    return ("：" + body[:160]) if body else ""


def get_json(url, params=None, headers=None, timeout=15):
    """
    发一个 GET，把 JSON 解析出来。

    @return (数据, None) 或 (None, 失败原因)。**不抛异常**。
    """
    if params:
        query = urllib.parse.urlencode({k: v for k, v in params.items() if v is not None})
        url = url + ("&" if "?" in url else "?") + query

    request_headers = {"User-Agent": USER_AGENT, "Accept": "application/json"}
    if headers:
        request_headers.update(headers)

    try:
        request = urllib.request.Request(url, headers=request_headers)
        with urllib.request.urlopen(request, timeout=timeout) as response:
            payload = response.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as failure:
        return None, "对方返回 %d%s" % (failure.code, _detail(failure))
    except urllib.error.URLError as failure:
        return None, "连不上：%s" % failure.reason
    except TimeoutError:
        return None, "%d 秒超时：%s" % (timeout, url)

    try:
        return json.loads(payload), None
    except json.JSONDecodeError:
        return None, "对方返回的不是 JSON：%s" % payload[:120]


def text_of(value):
    """把 None / 空串 / 数字统一成一句可读的话，省得每个工具各写一遍。"""
    if value is None:
        return "—"
    if isinstance(value, str):
        text = value.strip()
        return text or "—"
    return str(value)


def lines(rows):
    """多行结果：用换行分隔。MCP 的返回值是纯文本，Markdown 表格在提示词里占的 token 是三倍。"""
    return "\n".join(row for row in rows if row)

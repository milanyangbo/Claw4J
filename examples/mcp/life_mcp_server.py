#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生活里的两种换算：钱与颜色。

放一个 server 里没有更深的道理，只是都只有两个工具、都很快、都属"顺手算一下"。
硬拆成两个进程要多两次握手，换不来任何隔离。

汇率的坑写在这里：open.er-api 的免费档**每天只更新一次**，而且所有报价都以一个基准货币
给出。所以 `fx_convert` 是"先取基准表，再做一次除法"，不是两次查询——两次查询之间汇率
可能不同，用户看到的买入卖出价会对不上。
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from http_json import get_json, lines, text_of  # noqa: E402
from mcp_stdio import Tool, serve  # noqa: E402

ER_API = "https://open.er-api.com/v6/latest/%s"
COLOR_API = "https://www.thecolorapi.com"

# 列汇率表时只给这些：166 种货币全列出来，模型读不完、用户也不想看。
COMMON = ["USD", "EUR", "CNY", "JPY", "GBP", "HKD", "KRW", "AUD", "CAD", "SGD", "CHF", "TWD"]


def rates_of(base):
    code = (base or "USD").strip().upper()
    data, error = get_json(ER_API % code, timeout=20)
    if error:
        return None, error
    if data.get("result") != "success":
        return None, "这个货币代码查不到：%s（要用三位代码，例如 USD / CNY / JPY）" % code
    return data, None


def fx_convert(arguments):
    amount = float(arguments.get("amount", 1))
    data, error = rates_of(arguments["from"])
    if error:
        return error
    target = arguments["to"].strip().upper()
    rate = (data.get("rates") or {}).get(target)
    if rate is None:
        return "这个货币代码查不到：%s" % target
    return lines([
        "%s %s = %s %s" % (amount, data.get("base_code"), round(amount * rate, 4), target),
        "汇率：1 %s = %s %s" % (data.get("base_code"), rate, target),
        "更新时间：%s" % text_of(data.get("time_last_update_utc")),
        "（免费源每天更新一次，不是实时行情）",
    ])


def fx_rates(arguments):
    data, error = rates_of(arguments.get("base", "USD"))
    if error:
        return error
    rates = data.get("rates") or {}
    wanted = arguments.get("targets") or COMMON
    if isinstance(wanted, str):
        wanted = [code.strip().upper() for code in wanted.split(",") if code.strip()]
    rows = ["1 %s 等于：" % data.get("base_code")]
    for code in wanted:
        code = code.upper()
        if code in rates:
            rows.append("  %s  %s" % (code, round(rates[code], 4)))
    rows.append("更新时间：%s" % text_of(data.get("time_last_update_utc")))
    return lines(rows)


def normalize_hex(value):
    text = (value or "").strip().lstrip("#")
    if len(text) == 3:
        text = "".join(character * 2 for character in text)
    return text.upper()


def describe(color):
    name = (color.get("name") or {}).get("value")
    rgb = color.get("rgb") or {}
    return "%s  %s  %s" % (
        (color.get("hex") or {}).get("clean", "—"), text_of(name), text_of(rgb.get("value")))


def color_info(arguments):
    code = normalize_hex(arguments["color"])
    data, error = get_json(COLOR_API + "/id", {"hex": code}, timeout=20)
    if error:
        return error
    name = (data.get("name") or {})
    return lines([
        "颜色 %s" % (data.get("hex") or {}).get("clean", code),
        "名称：%s%s" % (text_of(name.get("value")),
                      "" if name.get("exact_match_name") else "（最接近的名字）"),
        "RGB：%s" % text_of((data.get("rgb") or {}).get("value")),
        "HSL：%s" % text_of((data.get("hsl") or {}).get("value")),
        "CMYK：%s" % text_of((data.get("cmyk") or {}).get("value")),
    ])


def color_scheme(arguments):
    code = normalize_hex(arguments["color"])
    mode = arguments.get("mode", "analogic")
    data, error = get_json(COLOR_API + "/scheme", {
        "hex": code, "mode": mode, "count": max(2, min(int(arguments.get("count", 5)), 8)),
    }, timeout=20)
    if error:
        return error
    colors = data.get("colors") or []
    if not colors:
        return "配色生成失败：%s" % code
    rows = ["以 %s 为主色，%s 配色方案：" % (code, mode)]
    rows.extend("- " + describe(color) for color in colors)
    return lines(rows)


if __name__ == "__main__":
    serve("claw4j-life", [
        Tool("fx_convert",
             "把一种货币的金额换算成另一种货币（按最新汇率）。用户问「这个多少钱人民币」时用。",
             {"from": {"type": "string", "description": "原货币三位代码，例如 USD"},
              "to": {"type": "string", "description": "目标货币三位代码，例如 CNY"},
              "amount": {"type": "number", "description": "金额，默认 1"}},
             fx_convert, ["from", "to"]),
        Tool("fx_rates",
             "查某个货币对一篮子主要货币的汇率表。",
             {"base": {"type": "string", "description": "基准货币三位代码，默认 USD"},
              "targets": {"type": "string", "description": "逗号分隔的目标货币，不写则列常用 12 种"}},
             fx_rates),
        Tool("color_info",
             "查一个颜色的名称、RGB、HSL、CMYK。用户给一个色号想让人读懂它时用。",
             {"color": {"type": "string", "description": "十六进制色号，带不带 # 都行，例如 24B1E0"}},
             color_info, ["color"]),
        Tool("color_scheme",
             "以一个颜色为主色，生成一套配色方案（互补、类似、三角等）。",
             {"color": {"type": "string", "description": "主色的十六进制色号"},
              "mode": {"type": "string", "description": "方案：analogic / complement / triad / monochrome / quad，默认 analogic"},
              "count": {"type": "integer", "description": "要几种颜色，默认 5"}},
             color_scheme, ["color"]),
    ])

#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把邮件 CLI（himalaya）包成 MCP server。

这是 ADR-0003 里二层保留场景 `unread-mail` 与 `send-email` 的迁移形态。
它与 gh 那个 server 唯一的结构性区别是：**它导出一个有副作用的工具**
（{@code mail_send}）。所以接它进来的端点不能声明成只读权限——
"这个 server 能发邮件"是一件事，写在端点上，不写在工具里。

本机没装 himalaya 时，两个工具都会返回一句"怎么装"，而不是抛异常：
缺依赖在这一层的表现应当是"说清楚缺什么"，不是一个堆栈。
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from mcp_stdio import Tool, run_cli, serve  # noqa: E402

NOT_INSTALLED = "未安装 himalaya。安装：brew install himalaya（macOS）或 cargo install himalaya。"


def require_himalaya():
    from shutil import which

    return None if which("himalaya") else NOT_INSTALLED


def list_unread(arguments):
    problem = require_himalaya()
    if problem:
        return problem
    folder = arguments.get("folder", "INBOX")
    limit = str(int(arguments.get("limit", 10)))
    return run_cli(["himalaya", "envelope", "list", "--folder", folder, "--size", limit])


def send(arguments):
    problem = require_himalaya()
    if problem:
        return problem
    # 收件人与正文是结构化字段，不是拼出来的一行命令：这与 Java 侧那条规则同一条约束
    return run_cli(["himalaya", "message", "send",
                    "--to", arguments["to"],
                    "--subject", arguments["subject"],
                    "--body", arguments["body"]])


if __name__ == "__main__":
    serve("claw4j-mail", [
        Tool("mail_list_unread",
             "列出收件箱里最近的邮件（只读）。需要本机装了 himalaya 并配好账户。",
             {"folder": {"type": "string", "description": "文件夹，默认 INBOX"},
              "limit": {"type": "integer", "description": "最多几封，默认 10"}},
             list_unread),
        Tool("mail_send",
             "发一封邮件（有副作用）。需要本机装了 himalaya 并配好账户。",
             {"to": {"type": "string", "description": "收件人"},
              "subject": {"type": "string", "description": "主题"},
              "body": {"type": "string", "description": "正文"}},
             send, ["to", "subject", "body"]),
    ])

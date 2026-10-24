#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
演示用的 MCP server：不依赖任何外部 CLI，用来验证"Java 真的起了另一个进程、
真的用 stdio 说通了 MCP"。

它的两个工具一个回话、一个报时。报时那个每次结果都不一样，
于是"这条结果确实来自刚起的那个子进程"是可判定的——
拿一个固定字符串的 server 验证不了这一点（缓存、回放、甚至没起进程都会给出同样的字符串）。
"""

import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from mcp_stdio import Tool, serve, text_result  # noqa: E402

SERVER_NAME = os.environ.get("CLAW4J_MCP_DEMO_NAME", "claw4j-demo")


def echo(arguments):
    return "echo: %s" % arguments.get("text", "")


def now(_arguments):
    return "子进程 %d 在 %.3f 报时" % (os.getpid(), time.time())


if __name__ == "__main__":
    serve(SERVER_NAME, [
        Tool("demo_echo", "把给进去的话原样带回来（演示用）",
             {"text": {"type": "string", "description": "要带回的话"}}, echo, ["text"]),
        Tool("demo_now", "报告子进程自己的 pid 与当前时间戳（演示用，每次都不同）",
             {}, now),
    ])

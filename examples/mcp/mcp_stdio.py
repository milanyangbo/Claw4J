#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
MCP stdio server 的最小骨架（零第三方依赖）。

这里只做三件事：读 stdin 上的 JSON-RPC 帧、按方法名分派、把结果写回 stdout。
它存在的理由是让 examples 下的三个演示 server 不必各自重复这一百行，
也让"Java 侧连的到底是个什么"从头到尾看得见——没有框架藏起来的握手。

刻意不做的事：不做并发（一次一帧，MCP 的 stdio 传输本来就是顺序的）、
不认识任何具体工具（工具由 server 自己注册）、不读环境变量当配置。

协议版本用 2024-11-05：它是 MCP stdio 传输最广泛支持的一版，
Java 侧的 mcp-core 2.1.0 也认它。
"""

import json
import sys

PROTOCOL_VERSION = "2024-11-05"


class Tool:
    """一个工具：名字、描述、参数 schema、以及真正干活的函数。"""

    def __init__(self, name, description, properties, handler, required=None):
        self.name = name
        self.description = description
        self.properties = properties
        self.handler = handler
        self.required = required or []

    def spec(self):
        return {
            "name": self.name,
            "description": self.description,
            "inputSchema": {
                "type": "object",
                "properties": self.properties,
                "required": self.required,
            },
        }

    def call(self, arguments):
        try:
            return text_result(self.handler(arguments or {}))
        except Exception as failure:  # noqa: BLE001  演示 server：原话说清楚就够了
            return {"content": [{"type": "text", "text": "调用失败：%s" % failure}],
                    "isError": True}


def text_result(text):
    return {"content": [{"type": "text", "text": str(text)}], "isError": False}


def run_cli(command, timeout=30):
    """
    调一个外部 CLI 并把它的输出原样带回来。

    参数是 argv 数组，不经 shell：这与 Java 侧那条规则是同一条约束
    （ADR-0002），只是换了个语言写。模型的输入永远进不到这里——
    它走的是 MCP 的 JSON-RPC 帧，到这里已经是结构化的字段值。
    """
    import subprocess

    completed = subprocess.run(command, capture_output=True, text=True, timeout=timeout)
    output = (completed.stdout or "").strip()
    if completed.returncode != 0:
        error = (completed.stderr or "").strip() or output or "无输出"
        return "命令失败（退出码 %d）：%s" % (completed.returncode, error)
    return output or "（命令没有输出）"


def serve(server_name, tools):
    """主循环：一帧进来、一帧出去。"""
    by_name = {tool.name: tool for tool in tools}
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            request = json.loads(line)
        except json.JSONDecodeError:
            continue
        response = dispatch(request, server_name, by_name)
        # 通知类（没有 id）不回：回了反而会让客户端认为这是一条响应
        if response is not None:
            sys.stdout.write(json.dumps(response, ensure_ascii=False) + "\n")
            sys.stdout.flush()


def dispatch(request, server_name, by_name):
    method = request.get("method")
    identifier = request.get("id")

    if method == "initialize":
        return {
            "jsonrpc": "2.0",
            "id": identifier,
            "result": {
                "protocolVersion": PROTOCOL_VERSION,
                "capabilities": {"tools": {}},
                "serverInfo": {"name": server_name, "version": "1.0.0"},
            },
        }

    if method in ("notifications/initialized", "initialized"):
        return None

    if method == "tools/list":
        return {"jsonrpc": "2.0", "id": identifier,
                "result": {"tools": [tool.spec() for tool in by_name.values()]}}

    if method == "tools/call":
        params = request.get("params") or {}
        tool = by_name.get(params.get("name"))
        if tool is None:
            return {"jsonrpc": "2.0", "id": identifier,
                    "error": {"code": -32601, "message": "没有这个工具：%s" % params.get("name")}}
        return {"jsonrpc": "2.0", "id": identifier, "result": tool.call(params.get("arguments"))}

    if method == "ping":
        return {"jsonrpc": "2.0", "id": identifier, "result": {}}

    return {"jsonrpc": "2.0", "id": identifier,
            "error": {"code": -32601, "message": "不支持的方法：%s" % method}}

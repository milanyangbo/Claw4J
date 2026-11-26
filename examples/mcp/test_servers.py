#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
六个生活域 server 的契约测试。零第三方依赖，`python3 -m unittest` 直接跑。

判的只有两类事：

1. **协议面**：`tools/list` 能列出、名字唯一、描述非空、`required` 都在 `properties` 里。
   这些错了不会报错，只会让 Java 侧的 MCP 客户端少一个工具、或者让模型看见一堆必填字段
   却没有对应属性——排障时要翻三层日志才找得到。
2. **不联网也能判的事**：纯计算的数值（BMI、饮水量）、以及"对方连不上时返回原因而不是抛栈"。
   需要出网的工具**不在这里测**——那会让测试结果依赖当时的网络，红了也不知道是代码坏了
   还是对方挂了。它们的可用性是 `examples/mcp/README` 里记的实测结果。
"""

import json
import os
import subprocess
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import http_json  # noqa: E402

SERVERS = ["weather", "media", "food", "culture", "life", "health"]

# 各 server 应有的工具数。写死是有意的：加工具时这条会提醒你回来改，
# 而不是让"26 个工具"变成一个没人核过的说法。
EXPECTED = {"weather": 3, "media": 6, "food": 5, "culture": 5, "life": 4, "health": 3}


def rpc(server, method, params=None, timeout=60):
    """起一个 server 子进程，发一帧，收回响应里 id 对得上的那一条。"""
    frames = "\n".join([
        json.dumps({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {}}),
        json.dumps({"jsonrpc": "2.0", "id": 2, "method": method, "params": params or {}}),
    ]) + "\n"
    script = os.path.join(HERE, "%s_mcp_server.py" % server)
    completed = subprocess.run([sys.executable, script], input=frames,
                               capture_output=True, text=True, timeout=timeout)
    if completed.returncode != 0:
        raise AssertionError("%s 起不来：%s" % (server, completed.stderr[-400:]))
    for line in completed.stdout.splitlines():
        if not line.strip():
            continue
        message = json.loads(line)
        if message.get("id") == 2:
            if "error" in message:
                raise AssertionError("%s 的 %s 返回错误：%s" % (server, method, message["error"]))
            return message["result"]
    raise AssertionError("%s 的 %s 没有响应" % (server, method))


def tools_of(server):
    return rpc(server, "tools/list")["tools"]


def call(server, tool, arguments):
    result = rpc(server, "tools/call", {"name": tool, "arguments": arguments})
    return result["content"][0]["text"], result.get("isError", False)


class ToolsListTest(unittest.TestCase):

    def test_every_server_lists_its_tools(self):
        for server in SERVERS:
            with self.subTest(server=server):
                tools = tools_of(server)
                self.assertEqual(len(tools), EXPECTED[server],
                                 "%s 的工具数变了，改 EXPECTED 之前先想想为什么" % server)

    def test_names_are_unique_within_a_server(self):
        for server in SERVERS:
            with self.subTest(server=server):
                names = [tool["name"] for tool in tools_of(server)]
                self.assertEqual(len(names), len(set(names)), "%s 里有重名" % server)

    def test_names_are_unique_across_servers(self):
        """跨 server 重名会让 Java 侧的目录把两个工具混成一个——而它不会报错。"""
        seen = {}
        for server in SERVERS:
            for tool in tools_of(server):
                self.assertNotIn(tool["name"], seen,
                                 "%s 与 %s 都有 %s" % (server, seen.get(tool["name"]), tool["name"]))
                seen[tool["name"]] = server

    def test_descriptions_are_present(self):
        """描述为空 = 检索层看不见它。这一条比名字错更隐蔽：工具在，但永远搜不到。"""
        for server in SERVERS:
            for tool in tools_of(server):
                with self.subTest(tool=tool["name"]):
                    self.assertTrue(tool["description"].strip())

    def test_required_fields_exist_in_properties(self):
        for server in SERVERS:
            for tool in tools_of(server):
                schema = tool["inputSchema"]
                with self.subTest(tool=tool["name"]):
                    for required in schema.get("required", []):
                        self.assertIn(required, schema.get("properties", {}),
                                      "%s 的 required 里有 %s，但 properties 里没有" % (tool["name"], required))

    def test_every_parameter_has_a_description(self):
        """参数描述会进模型看到的 schema。缺了它，模型只能猜这个字段填什么。"""
        for server in SERVERS:
            for tool in tools_of(server):
                for name, spec in tool["inputSchema"].get("properties", {}).items():
                    with self.subTest(tool=tool["name"], param=name):
                        self.assertTrue(spec.get("description", "").strip())


class HealthCalculationTest(unittest.TestCase):
    """纯计算，与网络无关，所以数值可以钉死。"""

    def test_bmi_matches_the_formula(self):
        text, failed = call("health", "bmi_calc", {"height_cm": 175, "weight_kg": 80})
        self.assertFalse(failed, text)
        self.assertIn("26.1", text, "80 / 1.75² = 26.12")
        self.assertIn("超重", text, "26.1 落在中国的 24–27.9 区间")

    def test_bmi_rejects_non_positive(self):
        text, _ = call("health", "bmi_calc", {"height_cm": 0, "weight_kg": 80})
        self.assertIn("正数", text)

    def test_water_intake_adds_exercise(self):
        text, failed = call("health", "water_intake", {"weight_kg": 70, "exercise_minutes": 30})
        self.assertFalse(failed, text)
        self.assertIn("2.80", text, "70×35ml=2.45L，加一次 30 分钟运动的 0.35L")

    def test_calorie_needs_rejects_unknown_activity(self):
        text, _ = call("health", "calorie_needs",
                       {"height_cm": 175, "weight_kg": 70, "age": 30, "activity": "napping"})
        self.assertIn("activity", text)


class HttpFailureTest(unittest.TestCase):
    """连不上时要给一句话，不能抛栈——栈冒到 MCP 层，模型拿到的是空结果。"""

    def test_unreachable_host_returns_reason(self):
        data, error = http_json.get_json("https://no-such-host.invalid/x", timeout=3)
        self.assertIsNone(data)
        self.assertTrue(error)
        self.assertIsInstance(error, str)

    def test_weather_reports_unknown_city_instead_of_crashing(self):
        """连不上/查不到都要落在一句话上。这条走的是真网络，但它判的是"有没有崩"。"""
        text, failed = call("weather", "weather_now", {"city": "zzzz-not-a-city"})
        self.assertFalse(failed, text)
        self.assertTrue("找不到" in text or "连不上" in text or "超时" in text, text)


if __name__ == "__main__":
    unittest.main(verbosity=2)

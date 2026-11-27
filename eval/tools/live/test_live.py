#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
`run_live.py` 的判据测试。零第三方依赖，`python3 eval/tools/live/test_live.py` 直接跑。

判的只有"分数是怎么算的"，不调模型、不读 candidates.json——**分母错是最隐蔽的一种错**：
一题调两次被算成两题，分数会随"话多"变好看，而没人看得出。
"""

import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import run_live  # noqa: E402


class VerdictTest(unittest.TestCase):

    def test_no_call_is_clean(self):
        self.assertEqual(run_live.verdict([]), (False, False))
        self.assertEqual(run_live.verdict(None), (False, False))

    def test_read_only_tool_counts_as_called_but_not_side_effect(self):
        """调 weather_now 是「话多」，不是「造成后果」。两个数必须分开。"""
        self.assertEqual(run_live.verdict(["weather_now"]), (True, False))

    def test_write_tool_is_side_effect(self):
        self.assertEqual(run_live.verdict(["mail_send"]), (True, True))

    def test_bash_is_exec_not_write(self):
        """Bash 能做不可逆的事，但"调了 Bash"不等于"改了外部世界"——实测里 9 次 Bash
        有 3 次是算正弦。判据按"改了没有"算，Bash 单列成 exec 诊断项。"""
        self.assertEqual(run_live.verdict(["Bash"]), (True, False))


class ScoreTest(unittest.TestCase):

    def test_denominator_is_questions_not_calls(self):
        """一题调两次仍算一题。分母写成调用次数会让 100 题 200 次调用看起来是 200 题。"""
        results = [{"id": "a", "calls": ["weather_now", "joke_random"]},
                   {"id": "b", "calls": []}]
        measured = run_live.score(results)
        self.assertEqual(measured["total"], 2)
        self.assertEqual(measured["called"], 1)
        self.assertEqual(measured["bfcl"], 0.5)

    def test_production_only_counts_side_effects(self):
        results = [{"id": "a", "calls": ["weather_now"]},
                   {"id": "b", "calls": ["mail_send"]},
                   {"id": "c", "calls": []}]
        measured = run_live.score(results)
        self.assertEqual(measured["bfcl"], 1 / 3)
        self.assertEqual(measured["production"], 2 / 3)
        self.assertEqual(measured["writes"], 1)
        self.assertEqual(measured["exec"], 0)

    def test_errors_are_visible(self):
        """请求失败了却被算成「没调用」，分数会虚高——所以错数要单独计。"""
        results = [{"id": "a", "calls": [], "error": "boom"}]
        self.assertEqual(run_live.score(results)["errors"], 1)

    def test_empty_candidates_are_not_the_models_merit(self):
        """检索一个候选都没给的题，模型必然「没调用」。把它算进模型质量就是自欺。"""
        results = [{"id": "a", "calls": [], "empty_candidates": True},
                   {"id": "b", "calls": ["weather_now"]}]
        measured = run_live.score(results)
        self.assertEqual(measured["bfcl"], 0.5, "240 条口径：两条里一条没调")
        self.assertEqual(measured["model"], 0.0, "有候选的那一条它调了，模型口径是 0")


class ReviewTableTest(unittest.TestCase):
    """第三个口径（票 29）：BFCL 判"有没有解出函数调用"，人工表判"用户会不会觉得它在干活"。"""

    def test_real_table_parses(self):
        table = run_live.load_review()
        self.assertGreater(len(table), 30, "复核表至少该覆盖首版的 48 条误调")
        self.assertTrue(set(table.values()) <= {"reasonable", "off_target"},
                        "verdict 只有两档：多一档就没人再看第二遍")
        self.assertIn("irrelevance_17", table, "问「现在几点」那一条必须在表里")

    def test_missing_file_is_not_a_crash(self):
        self.assertEqual(run_live.load_review("/nonexistent/review.yaml"), {},
                         "复核表是可选的诊断输入：缺了报「没复核」，不能让主流程挂")

    def test_unreviewed_calls_are_not_free(self):
        results = [{"id": "seen-ok", "calls": ["world_clock"]},
                   {"id": "seen-bad", "calls": ["Bash"]},
                   {"id": "never-seen", "calls": ["Bash"]}]
        table = {"seen-ok": "reasonable", "seen-bad": "off_target"}
        measured = run_live.score(results, table)
        self.assertEqual(measured["bfcl"], 0.0, "三条全都调了工具，BFCL 口径是 0")
        self.assertEqual(measured["unreviewed"], 1, "没复核过的那一条要单独报出来")
        self.assertAlmostEqual(measured["reasonable"], 1 / 3,
                               msg="未复核的一律算不合理：靠「没看过」抬高分数比压低更糟")

    def test_no_table_means_no_number(self):
        measured = run_live.score([{"id": "a", "calls": ["Bash"]}])
        self.assertIsNone(measured["reasonable"], "没复核表就不给这个数，别默认成 100%")


class SideEffectListTest(unittest.TestCase):

    def test_write_list_has_no_read_only_tool(self):
        for name in ("weather_now", "food_search", "querySales", "tv_show_search", "Bash"):
            self.assertNotIn(name, run_live.WRITE_TOOLS,
                             "%s 不改外部世界；放进写入名单会让 production 变成不可能达标的数" % name)

    def test_write_and_exec_do_not_overlap(self):
        self.assertFalse(run_live.WRITE_TOOLS & run_live.EXEC_TOOLS,
                         "同一个工具只能归一类，否则两个数会把同一次调用算两遍")


if __name__ == "__main__":
    unittest.main(verbosity=2)

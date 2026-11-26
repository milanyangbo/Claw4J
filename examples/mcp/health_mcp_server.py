#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
身体相关的三个估算：BMI、每日热量、饮水量。

这三个不联网，纯算术。放在 MCP 里而不是让模型自己算，理由只有一个：
**模型心算数字经常错，而且错得很自信**。公式写死在代码里，同一组输入永远同一个答案。

BMI 的分档用中国标准（WS/T 428-2013）而不是 WHO 的国际标准：两者在 24–28 这一段不同
（中国的"超重"从 24 起，WHO 从 25 起）。使用者说中文，按中国标准答更不容易造成误解，
所以这里明确写出来，而不是含糊地给一个数字。
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from http_json import lines, text_of  # noqa: E402
from mcp_stdio import Tool, serve  # noqa: E402

# 活动系数：Mifflin-St Jeor 算出来的是基础代谢，要乘这个才是"一天消耗"。
ACTIVITY = {
    "sedentary": (1.2, "久坐（几乎不运动）"),
    "light": (1.375, "轻度（每周 1–3 次）"),
    "moderate": (1.55, "中度（每周 3–5 次）"),
    "active": (1.725, "高强度（每周 6–7 次）"),
    "very_active": (1.9, "极高（体力工作或一天两练）"),
}


def bmi_level(bmi):
    """中国标准：<18.5 偏瘦 / 18.5–23.9 正常 / 24–27.9 超重 / ≥28 肥胖。"""
    if bmi < 18.5:
        return "偏瘦"
    if bmi < 24:
        return "正常"
    if bmi < 28:
        return "超重"
    return "肥胖"


def bmi_calc(arguments):
    height = float(arguments["height_cm"]) / 100.0
    weight = float(arguments["weight_kg"])
    if height <= 0 or weight <= 0:
        return "身高和体重都要是正数（身高按厘米填，例如 175）。"
    bmi = weight / (height * height)
    return lines([
        "BMI：%.1f" % bmi,
        "分级：%s（中国标准 WS/T 428-2013）" % bmi_level(bmi),
        "正常区间：18.5 – 23.9；按你的身高，对应体重 %.1f – %.1f kg" % (
            18.5 * height * height, 23.9 * height * height),
    ])


def calorie_needs(arguments):
    weight = float(arguments["weight_kg"])
    height = float(arguments["height_cm"])
    age = int(arguments["age"])
    male = str(arguments.get("sex", "male")).lower() in ("male", "m", "男")
    key = str(arguments.get("activity", "sedentary")).lower()
    if key not in ACTIVITY:
        return "activity 只能是：%s。" % " / ".join(ACTIVITY)
    factor, label = ACTIVITY[key]

    # Mifflin-St Jeor：目前临床上对普通人群误差最小的一条，比 Harris-Benedict 修订版更准。
    bmr = 10 * weight + 6.25 * height - 5 * age + (5 if male else -161)
    tdee = bmr * factor
    return lines([
        "基础代谢（BMR）：约 %d kcal/天" % round(bmr),
        "每日总消耗（%s）：约 %d kcal/天" % (label, round(tdee)),
        "减脂参考：%d – %d kcal/天（在总消耗上减 15%%–20%%）" % (round(tdee * 0.8), round(tdee * 0.85)),
        "公式：Mifflin-St Jeor。估算值，不是医学建议。",
    ])


def water_intake(arguments):
    weight = float(arguments["weight_kg"])
    minutes = float(arguments.get("exercise_minutes", 0))
    base = weight * 35
    extra = (minutes / 30.0) * 350
    total = base + extra
    return lines([
        "建议每日饮水：约 %.2f 升" % (total / 1000.0),
        "其中基础部分 %.2f 升（体重 × 35 ml），运动补充 %.2f 升（每 30 分钟 350 ml）" % (
            base / 1000.0, extra / 1000.0),
        "天热、出汗多、喝咖啡多时要再多一些。",
    ])


if __name__ == "__main__":
    serve("claw4j-health", [
        Tool("bmi_calc",
             "按身高体重算 BMI 与体型分级（中国标准）。用户问「我胖不胖」时用。",
             {"height_cm": {"type": "number", "description": "身高，厘米"},
              "weight_kg": {"type": "number", "description": "体重，公斤"}},
             bmi_calc, ["height_cm", "weight_kg"]),
        Tool("calorie_needs",
             "按身高体重年龄性别与活动量，算基础代谢与每日热量需求。",
             {"height_cm": {"type": "number", "description": "身高，厘米"},
              "weight_kg": {"type": "number", "description": "体重，公斤"},
              "age": {"type": "integer", "description": "年龄"},
              "sex": {"type": "string", "description": "male 或 female"},
              "activity": {"type": "string",
                           "description": "sedentary / light / moderate / active / very_active，默认 sedentary"}},
             calorie_needs, ["height_cm", "weight_kg", "age"]),
        Tool("water_intake",
             "按体重与运动时长，算每天建议喝多少水。",
             {"weight_kg": {"type": "number", "description": "体重，公斤"},
              "exercise_minutes": {"type": "number", "description": "每天运动多少分钟，默认 0"}},
             water_intake, ["weight_kg"]),
    ])

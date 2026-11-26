#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
吃喝两件事：菜谱（TheMealDB）与包装食品营养（Open Food Facts）。

两个工具组放在一个 server 里，因为它们回答的是同一类问题——"这口东西"：
一个是"怎么做"，一个是"里面有什么"。用户说"我想吃低碳水的"时，两边都用得上。

TheMealDB 的配料是 `strIngredient1..20` 这种平铺字段（空串表示用完），
不是数组。按 20 个位置扫、遇到空串就停，比写死数量稳：有的菜 6 样，有的 15 样。
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from http_json import get_json, lines, text_of  # noqa: E402
from mcp_stdio import Tool, serve  # noqa: E402

MEALDB = "https://www.themealdb.com/api/json/v1/1"
OFF_PRODUCT = "https://world.openfoodfacts.org/api/v2/product/%s.json"
OFF_SEARCH = "https://world.openfoodfacts.org/cgi/search.pl"

# Open Food Facts 的营养字段名带单位后缀，取每 100g 的值：用户问的是"这东西糖多不多"，
# 不是"这包 400g 里有多少糖"。按整包算要额外一次乘法，而包装规格经常缺失。
NUTRIENTS = [
    ("energy-kcal_100g", "热量", "kcal"),
    ("proteins_100g", "蛋白质", "g"),
    ("carbohydrates_100g", "碳水", "g"),
    ("sugars_100g", "糖", "g"),
    ("fat_100g", "脂肪", "g"),
    ("saturated-fat_100g", "饱和脂肪", "g"),
    ("salt_100g", "盐", "g"),
]


def ingredients(meal):
    rows = []
    for index in range(1, 21):
        name = (meal.get("strIngredient%d" % index) or "").strip()
        if not name:
            break
        measure = (meal.get("strMeasure%d" % index) or "").strip()
        rows.append("  - %s %s" % (measure, name) if measure else "  - %s" % name)
    return rows or ["  - （这个菜谱没有配料表）"]


def recipe_search(arguments):
    data, error = get_json(MEALDB + "/search.php", {"s": arguments["name"]})
    if error:
        return error
    meals = data.get("meals") or []
    if not meals:
        return "没有找到这个菜：%s。试试英文名或主要食材（例如 chicken、pasta）。" % arguments["name"]
    rows = []
    for meal in meals[:8]:
        rows.append("#%s  %s  [%s / %s]" % (
            meal.get("idMeal"), text_of(meal.get("strMeal")),
            text_of(meal.get("strCategory")), text_of(meal.get("strArea"))))
    rows.append("看某一道的做法用 recipe_detail，编号取上面的 #号。")
    return lines(rows)


def recipe_detail(arguments):
    data, error = get_json(MEALDB + "/lookup.php", {"i": arguments["meal_id"]})
    if error:
        return error
    meals = data.get("meals") or []
    if not meals:
        return "没有这个菜谱编号：%s。先用 recipe_search 查编号。" % arguments["meal_id"]
    meal = meals[0]
    rows = [
        "%s  [%s / %s]" % (text_of(meal.get("strMeal")),
                           text_of(meal.get("strCategory")), text_of(meal.get("strArea"))),
        "配料：",
    ]
    rows.extend(ingredients(meal))
    rows.append("做法：")
    rows.append(text_of(meal.get("strInstructions"))[:700])
    if meal.get("strSource"):
        rows.append("原方来源：%s" % meal.get("strSource"))
    return lines(rows)


def recipe_by_ingredient(arguments):
    data, error = get_json(MEALDB + "/filter.php", {"i": arguments["ingredient"]})
    if error:
        return error
    meals = data.get("meals") or []
    if not meals:
        return "用「%s」没找到菜。食材名用英文单数形式试试（例如 tomato 而不是 tomatoes）。" % arguments["ingredient"]
    rows = ["用「%s」能做的菜（共 %d 道，列前 12 道）：" % (arguments["ingredient"], len(meals))]
    for meal in meals[:12]:
        rows.append("#%s  %s" % (meal.get("idMeal"), text_of(meal.get("strMeal"))))
    return lines(rows)


def food_barcode(arguments):
    fields = "product_name,brands,quantity,nutriscore_grade,nutriments,ingredients_text"
    data, error = get_json(OFF_PRODUCT % arguments["barcode"], {"fields": fields}, timeout=20)
    if error:
        return error
    if data.get("status") != 1 or not data.get("product"):
        return "这个条形码在库里没有：%s。可以试试 food_search 按名字找。" % arguments["barcode"]
    return render_product(data["product"])


def food_search(arguments):
    params = {
        "search_terms": arguments["query"], "search_simple": 1, "action": "process",
        "json": 1, "page_size": max(1, min(int(arguments.get("limit", 5)), 15)),
        "fields": "product_name,brands,quantity,nutriscore_grade,nutriments",
    }
    data, error = get_json(OFF_SEARCH, params, timeout=25)
    if error:
        return error
    products = [item for item in (data.get("products") or []) if item.get("product_name")]
    if not products:
        return "没有找到这种食品：%s。这个库偏欧洲市场，中文品牌可能查不到。" % arguments["query"]
    return lines([render_product(item).split("\n")[0] for item in products])


def render_product(product):
    nutriments = product.get("nutriments") or {}
    rows = [
        "%s（%s）  %s" % (text_of(product.get("product_name")),
                        text_of(product.get("brands")), text_of(product.get("quantity"))),
        "营养分级：%s" % text_of(product.get("nutriscore_grade")).upper(),
        "每 100g：",
    ]
    for key, label, unit in NUTRIENTS:
        value = nutriments.get(key)
        if value is not None:
            rows.append("  - %s：%s %s" % (label, value, unit))
    return lines(rows)


if __name__ == "__main__":
    serve("claw4j-food", [
        Tool("recipe_search",
             "按菜名搜菜谱，返回菜谱编号、菜系、分类。用户问「某道菜怎么做」先用这个。",
             {"name": {"type": "string", "description": "菜名，建议英文，例如 pasta / curry"}},
             recipe_search, ["name"]),
        Tool("recipe_detail",
             "看某道菜的完整做法：配料表（含量）与步骤。需要菜谱编号。",
             {"meal_id": {"type": "string", "description": "菜谱编号，来自 recipe_search 的结果"}},
             recipe_detail, ["meal_id"]),
        Tool("recipe_by_ingredient",
             "手上有什么食材，能做什么菜。按食材名搜菜谱列表。",
             {"ingredient": {"type": "string", "description": "食材名，英文单数，例如 tomato"}},
             recipe_by_ingredient, ["ingredient"]),
        Tool("food_barcode",
             "扫条形码查包装食品的营养成分（每 100g 的热量、蛋白质、糖、脂肪、盐）与营养分级。",
             {"barcode": {"type": "string", "description": "13 位商品条形码"}},
             food_barcode, ["barcode"]),
        Tool("food_search",
             "按名字搜包装食品，看它的营养分级与热量。用户问「这个牌子的酸奶糖多不多」时用。",
             {"query": {"type": "string", "description": "食品名或品牌"},
              "limit": {"type": "integer", "description": "最多几条，默认 5"}},
             food_search, ["query"]),
    ])

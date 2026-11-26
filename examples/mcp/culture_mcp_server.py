#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
文化与语言：艺术品（Cleveland 艺术馆）、冷笑话（icanhazdadjoke）、英文词义（dictionaryapi.dev）。

三个工具挤在一个 server 里，是因为它们都回答"这是什么/这有什么意思"——
不是同一类数据，但是同一类**好奇**。分开成三个进程只会多两次握手。

选 Cleveland 而不是 Met 或 Art Institute：Met 的接口已经 410 下线，
Art Institute 对这台机器返回 403（带官方要求的 `AIC-User-Agent` 也一样）。
Cleveland 的开放数据不需要 key、有图片直链、字段里有中文能看懂的年代与材质。
"""

import os
import sys
import urllib.parse

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from http_json import get_json, lines, text_of  # noqa: E402
from mcp_stdio import Tool, serve  # noqa: E402

CLEVELAND = "https://openaccess-api.clevelandart.org/api/artworks"
DADJOKE = "https://icanhazdadjoke.com"
DICTIONARY = "https://api.dictionaryapi.dev/api/v2/entries/en"


def creators(artwork):
    """作者字段是列表，每项一句 "Claude Monet (French, 1840–1926)"。只要人名那半截。"""
    names = []
    for creator in artwork.get("creators") or []:
        description = creator.get("description") or ""
        names.append(description.split("(")[0].strip() or description)
    return "、".join(name for name in names if name) or "佚名"


def art_search(arguments):
    params = {"q": arguments["query"], "limit": max(1, min(int(arguments.get("limit", 5)), 15))}
    if arguments.get("only_with_image", True):
        params["has_image"] = 1
    data, error = get_json(CLEVELAND, params, timeout=20)
    if error:
        return error
    artworks = data.get("data") or []
    if not artworks:
        return "没有找到相关的艺术品：%s。试试作者名（monet）或主题（sunflowers）。" % arguments["query"]
    rows = ["共 %s 件，列前 %d 件：" % (text_of((data.get("info") or {}).get("total")), len(artworks))]
    for artwork in artworks:
        rows.append("#%s  %s — %s（%s，%s）" % (
            artwork.get("id"), text_of(artwork.get("title")), creators(artwork),
            text_of(artwork.get("creation_date")), text_of(artwork.get("type"))))
    rows.append("看某一件的详情用 art_detail，编号取上面的 #号。")
    return lines(rows)


def art_detail(arguments):
    data, error = get_json("%s/%s" % (CLEVELAND, arguments["artwork_id"]), timeout=20)
    if error:
        return error
    artwork = data.get("data") or {}
    if not artwork:
        return "没有这个作品编号：%s。先用 art_search 查编号。" % arguments["artwork_id"]
    image = ((artwork.get("images") or {}).get("web") or {}).get("url")
    return lines([
        text_of(artwork.get("title")),
        "作者：%s" % creators(artwork),
        "年代：%s" % text_of(artwork.get("creation_date")),
        "材质：%s" % text_of(artwork.get("technique")),
        "类型：%s" % text_of(artwork.get("type")),
        "部门：%s" % text_of(artwork.get("department")),
        "文化：%s" % text_of(artwork.get("culture")),
        "图片：%s" % text_of(image),
    ])


def joke_random(_arguments):
    data, error = get_json(DADJOKE + "/", headers={"Accept": "application/json"}, timeout=15)
    if error:
        return error
    return text_of(data.get("joke"))


def joke_search(arguments):
    params = {"term": arguments["term"], "limit": max(1, min(int(arguments.get("limit", 3)), 10))}
    data, error = get_json(DADJOKE + "/search", params,
                           headers={"Accept": "application/json"}, timeout=15)
    if error:
        return error
    total = data.get("total_jokes") or 0
    if total == 0:
        return "没有找到含「%s」的笑话。" % arguments["term"]
    rows = ["含「%s」的笑话共 %d 条：" % (arguments["term"], total)]
    for result in data.get("results") or []:
        rows.append("- " + text_of(result.get("joke")))
    return lines(rows)


def word_define(arguments):
    word = urllib.parse.quote(arguments["word"].strip())
    data, error = get_json("%s/%s" % (DICTIONARY, word), timeout=15)
    if error:
        # 404 在这里是"查不到这个词"，不是故障——把区别说出来，模型才知道不该重试。
        if "404" in error:
            return "词典里没有「%s」。换个拼写，或确认它是英文单词。" % arguments["word"]
        return error
    entry = (data or [{}])[0]
    rows = ["%s %s" % (text_of(entry.get("word")), text_of(entry.get("phonetic")))]
    for meaning in (entry.get("meanings") or [])[:3]:
        rows.append("【%s】" % text_of(meaning.get("partOfSpeech")))
        for definition in (meaning.get("definitions") or [])[:2]:
            rows.append("- " + text_of(definition.get("definition")))
            if definition.get("example"):
                rows.append("  例：" + text_of(definition.get("example")))
    return lines(rows)


if __name__ == "__main__":
    serve("claw4j-culture", [
        Tool("art_search",
             "按主题、作者或关键词搜艺术品（绘画、雕塑等），返回作品名、作者、年代。",
             {"query": {"type": "string", "description": "关键词，例如 monet / sunflowers / landscape"},
              "limit": {"type": "integer", "description": "最多几件，默认 5"},
              "only_with_image": {"type": "boolean", "description": "是否只要带图片的，默认 true"}},
             art_search, ["query"]),
        Tool("art_detail",
             "看某件艺术品的详情：作者、年代、材质、类型、馆藏部门、图片链接。需要作品编号。",
             {"artwork_id": {"type": "integer", "description": "作品编号，来自 art_search 的结果"}},
             art_detail, ["artwork_id"]),
        Tool("joke_random",
             "随便讲一个英文冷笑话（dad joke）。用户说「讲个笑话」时用。",
             {}, joke_random),
        Tool("joke_search",
             "按关键词找相关的英文冷笑话。",
             {"term": {"type": "string", "description": "关键词，英文"},
              "limit": {"type": "integer", "description": "最多几条，默认 3"}},
             joke_search, ["term"]),
        Tool("word_define",
             "查一个英文单词的意思：音标、词性、释义、例句。用户问某个英文词怎么讲时用。",
             {"word": {"type": "string", "description": "要查的英文单词"}},
             word_define, ["word"]),
    ])

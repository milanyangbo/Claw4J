#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
读书相关的四件事：找书、看详情、按作者列、按心情挑。

**这里不做全网搜书，是故意的。** 票 26 实测过三个公开书源：Open Library 时通时不通
（十分钟内 10.5 秒成功与 502 各一次）、Google Books 502、Gutendex 超时。一个调不通的
工具等于往目录里塞一个假能力——模型调了它，拿到"连不上"，用户的问题还是没答。
所以这一版带一份 24 本的**本地书架**：规模小，但**每一本都真的查得到**。

书架不是随机凑的书单，是按"用户真的会来问"的方向挑的：小说、科幻、历史、心理、
经济投资、科普、哲学、传记、诗歌，九个方向各两三本。

`reading_pick` 不是推荐算法。它按心情关键词映射到主题标签，再按篇幅挑短的——
规则写死在代码里，同一句话永远给同一本。它的价值是"能给出一个具体的书名"，
不是"懂你"。
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from http_json import lines  # noqa: E402
from mcp_stdio import Tool, serve  # noqa: E402

# 书架：(书名, 作者, 初版年, 页数, 主题标签, 一句话)
SHELF = [
    ("三体", "刘慈欣", 2008, 302, ["科幻", "小说"], "地球文明向宇宙发出的第一声啼鸣，引来了三体舰队。"),
    ("沙丘", "弗兰克·赫伯特", 1965, 600, ["科幻", "小说"], "沙漠星球上的香料、预言与权力更替。"),
    ("银河系漫游指南", "道格拉斯·亚当斯", 1979, 216, ["科幻", "幽默"], "地球被拆了，答案早就有了，问题是问题是什么。"),
    ("活着", "余华", 1993, 191, ["小说", "悲伤"], "一个人和他的一家，在时代里一个一个地少下去。"),
    ("百年孤独", "加西亚·马尔克斯", 1967, 360, ["小说", "魔幻"], "马孔多七代人的兴衰，孤独是家族遗传病。"),
    ("围城", "钱钟书", 1947, 359, ["小说", "幽默"], "婚姻是座城，城外的人想进去，城里的人想出来。"),
    ("平凡的世界", "路遥", 1986, 1631, ["小说", "励志"], "黄土高原上两兄弟，把日子一天一天挣出来。"),
    ("红楼梦", "曹雪芹", 1791, 1606, ["古典", "小说"], "一个大家族的繁华与散场，写得比谁都细。"),
    ("万历十五年", "黄仁宇", 1981, 300, ["历史"], "看似无事的一年，明朝的制度已经走到尽头。"),
    ("人类简史", "尤瓦尔·赫拉利", 2011, 440, ["历史", "科普"], "智人靠讲故事赢了这个星球，然后被故事困住。"),
    ("枪炮、病菌与钢铁", "贾雷德·戴蒙德", 1997, 480, ["历史", "科普"], "为什么是欧洲人去了美洲，而不是反过来。"),
    ("思考，快与慢", "丹尼尔·卡尼曼", 2011, 418, ["心理学"], "人有两套系统，慢的那套常常在放假。"),
    ("影响力", "罗伯特·西奥迪尼", 1984, 320, ["心理学"], "六个开关，按下去人就会答应。"),
    ("被讨厌的勇气", "岸见一郎", 2013, 200, ["心理学", "治愈"], "把别人的期待还给别人，用对话体讲阿德勒。"),
    ("经济学原理", "曼昆", 1997, 900, ["经济", "教材"], "十大原理开篇，把供需讲得能上手算。"),
    ("穷查理宝典", "查理·芒格", 2005, 500, ["经济", "投资"], "多元思维模型与「反过来想」的合集。"),
    ("漫步华尔街", "伯顿·马尔基尔", 1973, 400, ["投资", "经济"], "随机漫步理论对主动管理的长期质疑。"),
    ("时间简史", "史蒂芬·霍金", 1988, 256, ["科普"], "从大爆炸到黑洞，几乎没用一个公式。"),
    ("自私的基因", "理查德·道金斯", 1976, 360, ["科普"], "把视角从个体挪到基因上，很多事一下就通了。"),
    ("苏菲的世界", "乔斯坦·贾德", 1991, 512, ["哲学", "入门"], "一封神秘来信，把西方哲学史讲成小说。"),
    ("沉思录", "马可·奥勒留", 180, 208, ["哲学"], "一个罗马皇帝写给自己的睡前笔记。"),
    ("乔布斯传", "沃尔特·艾萨克森", 2011, 656, ["传记"], "作者采访了四十多次，包括他最不讲理的那些面。"),
    ("邓小平时代", "傅高义", 2011, 700, ["传记", "历史"], "一个外国人写的、材料最全的那一段。"),
    ("飞鸟集", "泰戈尔", 1916, 120, ["诗歌", "治愈"], "三百多首短诗，一页一首，适合睡前。"),
]

# 心情 → 主题标签。找不到就按"篇幅短"给，别硬猜。
MOODS = [
    (("难过", "想哭", "emo", "失落", "失恋", "沮丧"), ["悲伤", "治愈"]),
    (("睡不着", "放松", "轻松", "治愈", "睡前", "安静"), ["治愈", "诗歌"]),
    (("科幻", "太空", "未来", "宇宙"), ["科幻"]),
    (("历史", "古代", "朝代", "过去"), ["历史"]),
    (("学点", "涨知识", "科普", "原理", "为什么"), ["科普"]),
    (("赚钱", "理财", "投资", "股票", "钱"), ["投资", "经济"]),
    (("哲学", "意义", "人生", "思考"), ["哲学"]),
    (("传记", "人物", "名人", "谁写的"), ["传记"]),
    (("短", "通勤", "碎片", "没时间", "快"), ["诗歌", "幽默"]),
]


def find(text):
    """书名或作者里包含这段文字的书。返回 (命中列表, 原查询)。"""
    query = (text or "").strip()
    if not query:
        return [], query
    lowered = query.lower()
    hits = [book for book in SHELF
            if lowered in book[0].lower() or lowered in book[1].lower()
            or any(lowered in tag.lower() for tag in book[4])
            or lowered in book[5].lower()]
    return hits, query


def describe(book):
    title, author, year, pages, tags, blurb = book
    return "%s / %s（%s，%d 页，%s）" % (title, author, year, pages, "/".join(tags))


def book_search(arguments):
    hits, query = find(arguments["query"])
    if not hits:
        return "书架里没有跟「%s」对得上的书。这里只有 24 本，不是全网检索。" % query
    rows = ["找到 %d 本：" % len(hits)]
    rows.extend("- " + describe(book) for book in hits[:8])
    return lines(rows)


def book_detail(arguments):
    hits, query = find(arguments["title"])
    if not hits:
        return "书架里没有「%s」这本书。" % query
    if len(hits) > 1:
        rows = ["「%s」对上好几本，先确认是哪一本：" % query]
        rows.extend("- " + describe(book) for book in hits[:5])
        return lines(rows)
    title, author, year, pages, tags, blurb = hits[0]
    return lines([
        "《%s》" % title,
        "作者：%s" % author,
        "初版：%s 年    篇幅：%d 页    主题：%s" % (year, pages, "/".join(tags)),
        "简介：%s" % blurb,
    ])


def book_by_author(arguments):
    author = (arguments["author"] or "").strip()
    lowered = author.lower()
    hits = [book for book in SHELF if lowered in book[1].lower()]
    if not hits:
        return "书架里没有作者「%s」的书（只有 24 本，不是全网检索）。" % author
    rows = ["%s 在架上有 %d 本：" % (hits[0][1], len(hits))]
    rows.extend("- 《%s》%s 年，%d 页，%s" % (book[0], book[2], book[3], book[5]) for book in hits)
    return lines(rows)


def reading_pick(arguments):
    mood = (arguments.get("mood") or "").strip()
    limit = max(1, min(int(arguments.get("count", 3)), 5))
    tags = []
    for keywords, wanted in MOODS:
        if any(keyword in mood.lower() for keyword in keywords):
            tags = wanted
            break
    if tags:
        pool = [book for book in SHELF if set(tags) & set(book[4])]
        reason = "按「%s」这个方向" % "/".join(tags)
    else:
        pool = SHELF
        reason = "没看出具体方向，按篇幅从短到长"
    pool = sorted(pool, key=lambda book: book[3])[:limit]
    rows = ["%s，挑了 %d 本：" % (reason, len(pool))]
    rows.extend("- 《%s》%s，%d 页——%s" % (book[0], book[1], book[3], book[5]) for book in pool)
    return lines(rows)


if __name__ == "__main__":
    serve("claw4j-books", [
        Tool("book_search",
             "按书名、作者或主题在本地书架上找书。用户问「有什么讲明朝的书」时用。",
             {"query": {"type": "string", "description": "书名、作者或主题词，例如「明朝」「刘慈欣」"}},
             book_search, ["query"]),
        Tool("book_detail",
             "查某本书的简介、作者、初版年、页数与主题。用户已经说出书名时用。",
             {"title": {"type": "string", "description": "书名，例如「三体」"}},
             book_detail, ["title"]),
        Tool("book_by_author",
             "列出某个作者在书架上的全部作品。用户问「这个作家还写过什么」时用。",
             {"author": {"type": "string", "description": "作者名，例如「余华」"}},
             book_by_author, ["author"]),
        Tool("reading_pick",
             "按心情或场景推荐一本书（例如睡不着、通勤、想学点东西）。用户说「推荐本书」时用。",
             {"mood": {"type": "string", "description": "心情或场景，例如「睡不着」「想了解历史」"},
              "count": {"type": "integer", "description": "要几本，默认 3，最多 5"}},
             reading_pick),
    ])

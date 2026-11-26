#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
娱乐内容查询：剧集（TVmaze）、音乐与播客与有声书（iTunes Search）。

两个数据源分工明确，因为**它们各自缺一半**：

- TVmaze 只有**剧集**，没有电影。iTunes 的 `entity=movie` / `entity=tvShow` 在 2026 年
  已经返回 0 条（Apple 下架了影视搜索），带 `country=US` 也一样。所以这一组里没有"电影"。
- iTunes 覆盖音乐、专辑、播客、有声书、电子书；TVmaze 那边一样都没有。

两边都免费、不需要 key。播客与有声书走 iTunes 而同属"声音内容"，是因为
TVmaze 完全不碰这类——换源不是选择，是唯一能用的。
"""

import html
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from http_json import get_json, lines, text_of  # noqa: E402
from mcp_stdio import Tool, serve  # noqa: E402

TVMAZE = "https://api.tvmaze.com"
ITUNES = "https://itunes.apple.com/search"

TAG = re.compile(r"<[^>]+>")


def strip_html(text):
    """
    去掉 TVmaze 摘要里的 HTML。

    它的 summary 是 `<p><b>Breaking Bad</b> follows…`，带着标签直接回给模型，
    token 会多一半，而且模型会以为这是要它渲染的东西。
    """
    if not text:
        return "—"
    plain = html.unescape(TAG.sub("", text)).strip()
    return plain or "—"


def minutes(millis):
    if not isinstance(millis, (int, float)):
        return "—"
    seconds = int(millis // 1000)
    return "%d:%02d" % (seconds // 60, seconds % 60)


def tv_show_search(arguments):
    data, error = get_json(TVMAZE + "/search/shows", {"q": arguments["query"]})
    if error:
        return error
    if not data:
        return "没有找到这个剧名：%s" % arguments["query"]
    rows = []
    for entry in data[:8]:
        show = entry.get("show") or {}
        rating = (show.get("rating") or {}).get("average")
        rows.append("#%s  %s（%s）  %s 分  类型：%s" % (
            show.get("id"), text_of(show.get("name")),
            text_of(show.get("premiered"))[:4],
            text_of(rating), "、".join(show.get("genres") or []) or "—"))
        rows.append("    " + strip_html(show.get("summary"))[:160])
    return lines(rows)


def tv_show_detail(arguments):
    data, error = get_json(TVMAZE + "/shows/%s" % arguments["show_id"], {"embed": "cast"})
    if error:
        return error
    if not data or "name" not in data:
        return "没有这个剧集编号：%s。先用 tv_show_search 查编号。" % arguments["show_id"]
    network = (data.get("network") or data.get("webChannel") or {}).get("name")
    rating = (data.get("rating") or {}).get("average")
    # 结构是 [{"person": {"name": …}, "character": {"name": …}}]，名字在 person 里面不是外层。
    # 第一版写成 cast[i]["name"]，跑出来主演永远是"—"——不报错，只是数据丢了。
    cast = [entry["person"]["name"]
            for entry in ((data.get("_embedded") or {}).get("cast") or [])
            if (entry.get("person") or {}).get("name")][:5]
    return lines([
        "%s（%s 首播）" % (text_of(data.get("name")), text_of(data.get("premiered"))),
        "播出平台：%s" % text_of(network),
        "评分：%s 分" % text_of(rating),
        "类型：%s" % ("、".join(data.get("genres") or []) or "—"),
        "状态：%s" % text_of(data.get("status")),
        "主演：%s" % ("、".join(cast) or "—"),
        "简介：%s" % strip_html(data.get("summary"))[:300],
    ])


def _itunes(entity, query, limit, extra=None):
    params = {"term": query, "entity": entity, "limit": max(1, min(limit, 15)), "country": "US"}
    if extra:
        params.update(extra)
    data, error = get_json(ITUNES, params, timeout=20)
    if error:
        return None, error
    return (data.get("results") or []), None


def music_search(arguments):
    results, error = _itunes("musicTrack", arguments["query"], int(arguments.get("limit", 5)))
    if error:
        return error
    if not results:
        return "没有找到这首歌或歌手：%s" % arguments["query"]
    return lines([
        "%s — %s《%s》 %s  %s" % (
            text_of(item.get("trackName")), text_of(item.get("artistName")),
            text_of(item.get("collectionName")), minutes(item.get("trackTimeMillis")),
            text_of(item.get("primaryGenreName")))
        for item in results
    ])


def artist_lookup(arguments):
    results, error = _itunes("album", arguments["name"], int(arguments.get("limit", 6)))
    if error:
        return error
    if not results:
        return "没有找到这个歌手或乐队：%s" % arguments["name"]
    return lines([
        "%s  《%s》  %s  %s 首" % (
            text_of(item.get("artistName")), text_of(item.get("collectionName")),
            text_of(item.get("releaseDate"))[:10], text_of(item.get("trackCount")))
        for item in results
    ])


def podcast_search(arguments):
    results, error = _itunes("podcast", arguments["query"], int(arguments.get("limit", 5)))
    if error:
        return error
    if not results:
        return "没有找到相关的播客：%s" % arguments["query"]
    return lines([
        "%s（主播：%s）  %s 集  最近更新 %s" % (
            text_of(item.get("trackName")), text_of(item.get("artistName")),
            text_of(item.get("trackCount")), text_of(item.get("releaseDate"))[:10])
        for item in results
    ])


def audiobook_search(arguments):
    results, error = _itunes("audiobook", arguments["query"], int(arguments.get("limit", 5)))
    if error:
        return error
    if not results:
        return "没有找到这本有声书：%s" % arguments["query"]
    return lines([
        "%s — 作者 %s  出版 %s" % (
            text_of(item.get("collectionName") or item.get("trackName")),
            text_of(item.get("artistName")), text_of(item.get("releaseDate"))[:10])
        for item in results
    ])


if __name__ == "__main__":
    serve("claw4j-media", [
        Tool("tv_show_search",
             "按剧名搜电视剧或动画剧集，返回每部的编号、首播年份、评分与类型。查「某部剧」先用这个拿编号。",
             {"query": {"type": "string", "description": "剧名，中英文都行"}},
             tv_show_search, ["query"]),
        Tool("tv_show_detail",
             "看某部剧集的详情：播出平台、评分、类型、主演、简介。需要先有剧集编号。",
             {"show_id": {"type": "integer", "description": "剧集编号，来自 tv_show_search 的结果"}},
             tv_show_detail, ["show_id"]),
        Tool("music_search",
             "搜歌曲，返回歌名、歌手、专辑、时长、曲风。用户说「有首歌」想不起名字时用这个。",
             {"query": {"type": "string", "description": "歌名或歌手名"},
              "limit": {"type": "integer", "description": "最多几条，默认 5"}},
             music_search, ["query"]),
        Tool("artist_lookup",
             "按歌手或乐队名查他/她的专辑列表（专辑名、发行年份、曲目数）。",
             {"name": {"type": "string", "description": "歌手或乐队名"},
              "limit": {"type": "integer", "description": "最多几张，默认 6"}},
             artist_lookup, ["name"]),
        Tool("podcast_search",
             "按关键词搜播客节目，返回节目名、主播、集数与最近更新时间。",
             {"query": {"type": "string", "description": "关键词或节目名"},
              "limit": {"type": "integer", "description": "最多几个，默认 5"}},
             podcast_search, ["query"]),
        Tool("audiobook_search",
             "按书名或作者搜有声书，返回书名、作者、出版时间。",
             {"query": {"type": "string", "description": "书名或作者名"},
              "limit": {"type": "integer", "description": "最多几本，默认 5"}},
             audiobook_search, ["query"]),
    ])

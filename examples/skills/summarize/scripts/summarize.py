#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
抽取式摘要：抓正文 → 断句 → 按词频打分 → 挑最重要的几句原文。

只依赖标准库，也不需要任何凭据。这一点是被执行环境逼出来的，
而它是脚本层一条真实的边界（见 run.sh 里的说明）。
"""

import re
import sys
import urllib.request

TIMEOUT = 15
# 逐段取出"到标点为止"的片段，而不是在标点后面 split。两种写法在这里不等价：
# 用零宽的 `(?<=[…])` 加 `\s*` 去 split 时，Python 3.7 以前会告
# `FutureWarning: split() requires a non-empty pattern match`，而且老版本的
# re.split 对零宽匹配的处理不同——同一份文件在 3.6 上会被切成 5 句、3.13 上 7 句。
# 脚本包的输出不该随宿主装的是哪个 python 变化（票 13）。
SENTENCE = re.compile(r"[^。！？!?.；;]+[。！？!?.；;]?")
TAG = re.compile(r"<script.*?</script>|<style.*?</style>|<[^>]+>", re.S)
SPACE = re.compile(r"\s+")


def read_source(source):
    if source.startswith("http://") or source.startswith("https://"):
        request = urllib.request.Request(source, headers={"User-Agent": "claw4j/0.1"})
        with urllib.request.urlopen(request, timeout=TIMEOUT) as response:
            raw = response.read().decode("utf-8", errors="ignore")
        return strip_html(raw)
    with open(source, encoding="utf-8", errors="ignore") as handle:
        return handle.read()


def strip_html(raw):
    text = TAG.sub(" ", raw)
    for entity, character in (("&nbsp;", " "), ("&amp;", "&"), ("&lt;", "<"),
                              ("&gt;", ">"), ("&quot;", "\""), ("&#39;", "'")):
        text = text.replace(entity, character)
    return SPACE.sub(" ", text).strip()


def sentences(text):
    return [sentence.strip() for sentence in SENTENCE.findall(text)
            if len(sentence.strip()) > 8]


def score(sentence, frequency):
    words = re.findall(r"[\u4e00-\u9fff]+|[A-Za-z]+", sentence)
    if not words:
        return 0.0
    return sum(frequency.get(word, 0) for word in words) / len(words)


def main():
    if len(sys.argv) < 2:
        print("用法：summarize <URL 或文件路径>")
        return 2
    try:
        text = read_source(sys.argv[1])
    except Exception as failure:  # noqa: BLE001
        print("读不到内容：%s" % failure)
        return 1

    candidates = sentences(text)
    if not candidates:
        print("没读到可摘要的正文。")
        return 1

    frequency = {}
    for sentence in candidates:
        for word in re.findall(r"[\u4e00-\u9fff]+|[A-Za-z]+", sentence):
            frequency[word] = frequency.get(word, 0) + 1

    top = sorted(candidates, key=lambda item: score(item, frequency), reverse=True)[:5]
    top = [item for item in candidates if item in top]

    print("摘要（%d 句，摘自 %d 句原文）：" % (len(top), len(candidates)))
    for sentence in top:
        print("  - %s" % sentence)
    return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
从 BFCL 的 irrelevance 题库里抽出 240 条问句，落一份入库的 `questions.jsonl`。

**只抽问句，不抽它那个 `function`。** 带上函数就等于把"候选"提前给了模型，
这一票要量的正是"候选由我们的检索决定"之后的决策，不是在 BFCL 的原题上再考一遍。
那一栏（1 个函数、2 个口径）已经由 `eval/tools/bfcl/` 出了数。

为什么要有这份入库副本而不是每次从 venv 里读：

- venv 的路径带 Python 版本与包版本（`lib/python3.12/site-packages/...`），
  升级一次 `bfcl-eval` 就换地方，而"评测输入变了吗"这件事必须可 diff。
- 240 条问句是这一票的**输入**，输入得跟着仓库走，跟 `catalogue.txt` 一个道理。

用法：
    python3 eval/tools/live/make_questions.py            # 重新生成 questions.jsonl
    python3 eval/tools/live/make_questions.py --check    # 只校验入库那份（不读 venv）

退出码：0 正常 / 1 校验不过 / 2 找不到 BFCL 数据文件。
"""

import glob
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
OUT = os.path.join(HERE, "questions.jsonl")
EXPECTED = 240

DATA_GLOB = os.path.join(
    REPO, "eval/tools/bfcl/.venv/lib/python*/site-packages/bfcl_eval/data/BFCL_v4_irrelevance.json")


def find_data():
    hits = sorted(glob.glob(DATA_GLOB))
    if not hits:
        print("找不到 BFCL 的 irrelevance 数据文件。先跑一次 ./eval/tools/bfcl/run.sh --smoke "
              "把 venv 建起来，或用 --check 只校验入库那份。")
        sys.exit(2)
    return hits[-1]


def first_user_text(turns):
    """多轮题取第一轮的第一条 user 内容。这一票只判"该不该调"，第二轮没有新信息。"""
    if not turns:
        return ""
    for message in turns[0]:
        if message.get("role") == "user":
            content = message.get("content")
            return content.strip() if isinstance(content, str) else ""
    return ""


def generate():
    rows = []
    skipped = []
    with open(find_data(), encoding="utf-8") as handle:
        for line in handle:
            if not line.strip():
                continue
            raw = json.loads(line)
            text = first_user_text(raw.get("question") or [])
            if not text:
                skipped.append(raw.get("id"))
                continue
            rows.append({"id": raw["id"], "question": text})
    with open(OUT, "w", encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False) + "\n")
    print("写入 %s：%d 条%s" % (OUT, len(rows),
                             "，跳过 %d 条空问句：%s" % (len(skipped), skipped) if skipped else ""))
    return check()


def check():
    """校验入库那份。**这是唯一判"输入没被人手改坏"的地方**，所以判得比生成时更严。"""
    problems = []
    if not os.path.exists(OUT):
        problems.append("缺 %s" % OUT)
        rows = []
    else:
        with open(OUT, encoding="utf-8") as handle:
            rows = [json.loads(line) for line in handle if line.strip()]

    if len(rows) != EXPECTED:
        problems.append("条数是 %d，应为 %d（BFCL 换版就重跑生成，并在这里改 EXPECTED）"
                        % (len(rows), EXPECTED))
    seen = set()
    for row in rows:
        if set(row) != {"id", "question"}:
            problems.append("%s 的键是 %s，只该有 id 与 question"
                            "（多了 function 就等于把候选提前给模型）" % (row.get("id"), sorted(row)))
        if not str(row.get("question", "")).strip():
            problems.append("%s 的问句是空的" % row.get("id"))
        if row.get("id") in seen:
            problems.append("%s 重复" % row.get("id"))
        seen.add(row.get("id"))

    if problems:
        for problem in problems[:10]:
            print("  - " + problem)
        return 1
    print("questions.jsonl 校验通过：%d 条，id 唯一，只有 id 与 question 两个键。" % len(rows))
    return 0


if __name__ == "__main__":
    if "--check" in sys.argv[1:]:
        sys.exit(check())
    sys.exit(generate())

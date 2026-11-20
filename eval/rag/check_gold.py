#!/usr/bin/env python3
"""gold.yaml 的本地自检：span 能不能在文档里找到。

跑真导出（`./eval/rag/run.sh`）要起容器、建索引，几分钟才知道 span 写错了。
这个脚本在本地把错字、写串行的 span 当场挑出来。

归一化与分片照 `RetrievalGold` 抄：
  normalize     去掉 * _ ` 与所有空白
  pieces        按中文句读 ，。；：、！？ 切
  最短长度      span 归一化后 >= 8；每片 >= 6 才算有区分度

但两件事它做不到，别把它当等价物：
  - Java 侧是**按块**比，允许最长公共子串 >= max(6, 30%) 的近似命中；这里要求整篇逐字包含。
    所以这里报错的不一定在 Java 侧会失败（span 里错一个字，Java 仍可能落到块上）——
    它是更严的提示，不是同一个判定。
  - 它不知道块边界，验证不了「这段落在哪一块」。那一步只有真导出会做
    （`RetrievalGold#resolveIds`，落不到会抛并指出哪条哪片）。

用法：eval/rag/.venv/bin/python eval/rag/check_gold.py [gold.yaml]
退出码：0 全部找得到；1 有找不到的（逐条打印）。
"""

from __future__ import annotations

import sys
import unicodedata
from pathlib import Path

MIN_SPAN_CHARS = 8
MIN_PIECE_CHARS = 6
PIECE_DELIMITERS = "，。；：、！？"


def normalize(text: str) -> str:
    """与 RetrievalGold#normalize 一致：去 Markdown 标记与空白，保留标点与全角字符。"""
    return "".join(ch for ch in text if ch not in "*_`" and not ch.isspace())


def pieces(span: str) -> list[str]:
    """与 RetrievalGold#pieces 一致：按中文句读切，过短的片丢掉。"""
    out: list[str] = []
    current: list[str] = []
    for ch in span:
        if ch in PIECE_DELIMITERS:
            _add(out, current)
            current = []
        else:
            current.append(ch)
    _add(out, current)
    return out or [span]


def _add(out: list[str], current: list[str]) -> None:
    piece = "".join(current).strip()
    if piece and len(normalize(piece)) >= MIN_PIECE_CHARS:
        out.append(piece)


def load_gold(path: Path) -> list[dict]:
    import yaml

    data = yaml.safe_load(path.read_text(encoding="utf-8"))
    if not isinstance(data, list):
        raise SystemExit(f"{path} 顶层不是列表")
    return data


def main() -> int:
    root = Path(__file__).resolve().parent.parent.parent
    gold_path = Path(sys.argv[1]) if len(sys.argv) > 1 else root / "eval/rag/gold.yaml"
    entries = load_gold(gold_path)

    problems: list[str] = []
    ids: set[str] = set()
    for entry in entries:
        gid = entry.get("id", "<无 id>")
        if gid in ids:
            problems.append(f"{gid}: id 重复")
        ids.add(gid)

        span = entry.get("gold_span", {}).get("span", "")
        doc = entry.get("gold_span", {}).get("doc", "")
        if not span or not doc:
            problems.append(f"{gid}: 缺 span 或 doc")
            continue

        normalized = normalize(span)
        if len(normalized) < MIN_SPAN_CHARS:
            problems.append(f"{gid}: span 归一化后只有 {len(normalized)} 字（至少 {MIN_SPAN_CHARS}）")
            continue

        target = root / "examples/knowledge" / doc
        if not target.exists():
            problems.append(f"{gid}: 文档不存在 {doc}")
            continue
        haystack = normalize(target.read_text(encoding="utf-8"))

        missing = [p for p in pieces(span) if normalize(p) not in haystack]
        if missing:
            for piece in missing:
                problems.append(f"{gid}: 这片在 {doc} 里找不到 -> {piece}")

    total = len(entries)
    print(f"gold: {total} 条，来自 {gold_path.relative_to(root)}")
    if problems:
        print(f"\n有 {len(problems)} 处落不到：")
        for line in problems:
            print(f"  {line}")
        return 1
    print("全部能落到文档里。")
    return 0


if __name__ == "__main__":
    sys.exit(main())

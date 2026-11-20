#!/usr/bin/env python3
"""gold.yaml 的本地自检：每处证据能不能在对应文档里找到。

跑真导出（`./eval/rag/run.sh`）要起容器、建索引，几分钟才知道 span 写错了。
这个脚本在本地把错字、写串行的 span 当场挑出来。

归一化与分片照 `RetrievalGold` 抄：
  normalize     去掉 * _ ` 与所有空白
  pieces        按中文句读 ，。；：、！？ 切
  最短长度      span 归一化后 >= 8；每片 >= 6 才算有区分度

但两件事它做不到，别把它当等价物：
  - Java 侧是按块比，允许最长公共子串 >= max(6, 30%) 的近似命中；这里要求整篇逐字包含。
    所以这里报错的不一定在 Java 侧会失败（span 里错一个字，Java 仍可能落到块上）——
    它是更严的提示，不是同一个判定。
  - 它不知道块边界，验证不了「这段落在哪一块」。那一步只有真导出会做
    （`RetrievalGold#resolveIds`，落不到会抛并指出哪条哪片）。

用法：eval/rag/.venv/bin/python eval/rag/check_gold.py [gold.yaml]
退出码：0 全部找得到；1 有找不到的（逐条打印）。
"""

from __future__ import annotations

import sys
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


def check_evidence(gid: str, index: int | None, item: object, root: Path,
                   problems: list[str]) -> None:
    """验一处证据。index 为 None 表示这条只有一处（错误信息里不提第几处）。"""
    where = gid if index is None else f"{gid} 第 {index} 处证据"
    if not isinstance(item, dict):
        problems.append(f"{where}: 应当是 doc/span 两个键，实际是 {type(item).__name__}")
        return
    unknown = set(item) - {"doc", "span"}
    if unknown:
        problems.append(f"{where}: 认不出的键 {sorted(unknown)}")
    span = str(item.get("span") or "")
    doc = str(item.get("doc") or "")
    if not span or not doc:
        problems.append(f"{where}: 缺 span 或 doc")
        return

    normalized = normalize(span)
    if len(normalized) < MIN_SPAN_CHARS:
        problems.append(f"{where}: span 归一化后只有 {len(normalized)} 字（至少 {MIN_SPAN_CHARS}）")
        return

    target = root / "examples/knowledge" / doc
    if not target.exists():
        problems.append(f"{where}: 文档不存在 {doc}")
        return
    haystack = normalize(target.read_text(encoding="utf-8"))

    for piece in pieces(span):
        if normalize(piece) not in haystack:
            problems.append(f"{where}: 这片在 {doc} 里找不到 -> {piece}")


def main() -> int:
    root = Path(__file__).resolve().parent.parent.parent
    gold_path = Path(sys.argv[1]) if len(sys.argv) > 1 else root / "eval/rag/gold.yaml"
    entries = load_gold(gold_path)

    problems: list[str] = []
    ids: set[str] = set()
    evidence_count = 0
    for entry in entries:
        gid = entry.get("id", "<无 id>")
        if gid in ids:
            problems.append(f"{gid}: id 重复")
        ids.add(gid)

        spans = entry.get("gold_spans")
        if spans is None:
            problems.append(f"{gid}: 缺 gold_spans（证据写成列表，只有一处也写成列表）")
            continue
        if not isinstance(spans, list) or not spans:
            problems.append(f"{gid}: gold_spans 应当是非空列表，实际是 {type(spans).__name__}")
            continue

        evidence_count += len(spans)
        if len(spans) == 1:
            check_evidence(gid, None, spans[0], root, problems)
        else:
            for i, item in enumerate(spans, 1):
                check_evidence(gid, i, item, root, problems)

    total = len(entries)
    multi = sum(1 for e in entries if isinstance(e.get("gold_spans"), list) and len(e["gold_spans"]) > 1)
    print(f"gold: {total} 条 / {evidence_count} 处证据（其中 {multi} 条是多处证据），"
          f"来自 {gold_path.relative_to(root)}")
    if problems:
        print(f"\n有 {len(problems)} 处落不到：")
        for line in problems:
            print(f"  {line}")
        return 1
    print("全部能落到文档里。")
    return 0


if __name__ == "__main__":
    sys.exit(main())

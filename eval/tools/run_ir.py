#!/usr/bin/env python3
"""工具检索评测（票 12）：Java 只导出，指标交给 ir_measures。

链路与 ``eval/rag`` 同构：``run.sh`` → Java ``ToolRetrievalExportTest`` 导出
``reports/qrels.txt`` / ``reports/<config>.run`` / ``reports/no-search.jsonl`` → 本脚本算分。

三件与 RAG 侧不同的事，都是"更简单"：

- **不要 judge、不要 embedding**：指标是纯集合运算，零成本、同输入同输出。
- **名次类指标是原生的**：``RR`` / ``nDCG@10`` 直接由 ir_measures 给。Ragas 的
  ``IDBased*`` 是集合型，那一侧的名次只能自算。
- **零候选题单独判**：TREC 表达不了"期望零候选"，那两题不进 qrels，读
  ``no-search.jsonl``，判据是 ``returned == 0``。

门槛只有两层，不是 RAG 侧的三层：工具侧的指标全是确定性的，**没有 judge 噪声**，所以
不需要"相对门 + 容差"去吸收抖动。留下的两层是：

- **回归门**：每档 ``R@5`` 不低于 ``baseline.json`` 减 ``TOLERANCE``。基线是"上次被接受的
  版本"，没有它就不是评测，只是打印。
- **零候选率也是回归门，不是绝对门**：当前 ``regex`` 对「谢谢」就会给候选（票 09 记录过），
  把 1.0 当门槛等于假装它已经修好，一落地就是红的。基线记当前值，掉下去才算回归。
  明细（哪几题给了候选）每轮都打印——它是观测项，不该被一个数字盖住。

``--cross-check`` 用票 09 的自研公式重算一遍 ``R@5`` / ``RR``，与 ir_measures 逐档比。
两份实现之间差 > 1e-9 就当场退出 1：两套指标各说一套时，第一个问题是"信谁"，
这条自检把那个问题变成一条报错。
"""

from __future__ import annotations

import argparse
import json
import math
import sys
from datetime import datetime, timezone
from pathlib import Path

try:
    import ir_measures
except ImportError:  # pragma: no cover - 环境没搭起来时走这里
    print("缺 ir_measures：先跑 ./eval/tools/run.sh（它会建 venv 并装 requirements.txt）")
    sys.exit(2)

ROOT = Path(__file__).resolve().parents[2]
REPORTS = ROOT / "eval/tools/reports"
DEFAULT_BASELINE = ROOT / "eval/tools/baseline.json"
DEFAULT_SCORE = REPORTS / "score.json"
DEFAULT_REPORT = REPORTS / "report.md"

# 输出的指标名与报告顺序。R@k 与票 09 的表能对上，nDCG@10 与 ToolRet（ACL 2025）同口径。
MEASURES = ["R@1", "R@3", "R@5", "RR", "nDCG@10"]

# 回归容差：确定性指标本来可以不设，留 0.05 是为了"一格题"（20 题的 1/20 = 0.05）不反复告警。
TOLERANCE = 0.05

# 交叉校验的容忍：两套实现算同一批整数除法，浮点差只该在末位。
CROSS_CHECK_EPS = 1e-9


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="工具检索评测（ir_measures）")
    parser.add_argument("--baseline", type=Path, default=DEFAULT_BASELINE,
                        help="回归基线；缺失时退出 2，除非 --update-baseline")
    parser.add_argument("--update-baseline", action="store_true",
                        help="把这一轮的分数写成新基线（确认改好了再用）")
    parser.add_argument("--cross-check", action="store_true",
                        help="用自研公式重算 R@5 / RR，与 ir_measures 逐档比")
    parser.add_argument("--configs", default="",
                        help="只跑这些配置（逗号分隔）；空 = reports 下所有 .run")
    return parser.parse_args()


def run_files(only: str) -> list[Path]:
    wanted = [name.strip() for name in only.split(",") if name.strip()]
    files = sorted(REPORTS.glob("*.run"))
    if wanted:
        files = [path for path in files if path.stem in wanted]
    if not files:
        print(f"{REPORTS} 下没有 .run：先跑 Java 导出（./eval/tools/run.sh）")
        sys.exit(2)
    return files


def score_one(run_path: Path) -> dict[str, float]:
    """一档的分数。qrels 每轮重读：它只有两百来字节，读缓存反而多一处状态。"""
    qrels = list(ir_measures.read_trec_qrels(str(REPORTS / "qrels.txt")))
    run = list(ir_measures.read_trec_run(str(run_path)))
    measures = [ir_measures.parse_measure(name) for name in MEASURES]
    result = ir_measures.calc_aggregate(measures, qrels, run)
    return {str(measure): float(result[measure]) for measure in measures}


def zero_candidate() -> dict[str, object]:
    """
    零候选题的判定。

    ``returned`` 是 Java 侧记的实际候选数，不是写死的 0——写死它，这条判据就永远通过。
    """
    path = REPORTS / "no-search.jsonl"
    if not path.exists():
        print(f"缺 {path}：没有它就判不了「不该给候选」的那两题")
        sys.exit(2)
    rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    failed = [row["qid"] for row in rows if row.get("returned") != 0]
    return {
        "total": len(rows),
        "passed": len(rows) - len(failed),
        "rate": (len(rows) - len(failed)) / len(rows) if rows else 1.0,
        "failed": failed,
    }


def self_metrics(run_path: Path) -> dict[str, float]:
    """票 09 那套公式：命中任一即算中，MRR 取第一个 gold 的名次倒数。"""
    qrels: dict[str, set[str]] = {}
    for line in (REPORTS / "qrels.txt").read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        qid, _, docno, _ = line.split()
        qrels.setdefault(qid, set()).add(docno)

    ranked: dict[str, list[str]] = {}
    for line in run_path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        qid, _, docno, rank, _, _ = line.split()
        ranked.setdefault(qid, []).append((int(rank), docno))
    for qid in ranked:
        ranked[qid] = [docno for _, docno in sorted(ranked[qid])]

    hit5 = 0
    mrr = 0.0
    for qid, gold in qrels.items():
        got = ranked.get(qid, [])
        rank = next((i + 1 for i, docno in enumerate(got) if docno in gold), 0)
        if 0 < rank <= 5:
            hit5 += 1
        mrr += 1.0 / rank if rank else 0.0
    total = len(qrels)
    return {"R@5": hit5 / total if total else 0.0, "RR": mrr / total if total else 0.0}


def cross_check(scores: dict[str, dict[str, float]]) -> list[str]:
    """两套实现逐档比，返回对不上的行（空 = 一致）。"""
    problems = []
    for config, measured in scores.items():
        mine = self_metrics(REPORTS / f"{config}.run")
        for name in ("R@5", "RR"):
            diff = abs(measured[name] - mine[name])
            if diff > CROSS_CHECK_EPS:
                problems.append(
                    f"{config} 的 {name}：ir_measures={measured[name]:.6f} 自研={mine[name]:.6f} 差={diff:.2e}")
    return problems


def judge(scores: dict[str, dict[str, float]], zero: dict[str, object],
          baseline: dict | None) -> list[str]:
    """回归门。返回失败项；空 = 全过。"""
    if baseline is None:
        return []
    failures = []
    accepted_rate = baseline.get("no_search_rate")
    if accepted_rate is not None and zero["rate"] < accepted_rate - TOLERANCE:
        failures.append(f"零候选率从 {accepted_rate:.3f} 掉到 {zero['rate']:.3f}"
                        f"（下限 {accepted_rate - TOLERANCE:.3f}）；给了候选的题："
                        + (", ".join(zero["failed"]) or "无"))
    for config, measured in scores.items():
        accepted = baseline.get("metrics", {}).get(config)
        if accepted is None:
            continue  # 新加的档没有基线：不算回归，打印时标出来
        floor = accepted.get("R@5", 0.0) - TOLERANCE
        if measured["R@5"] < floor:
            failures.append(f"{config} 的 R@5 从 {accepted['R@5']:.3f} 掉到 {measured['R@5']:.3f}"
                            f"（下限 {floor:.3f}）")
    return failures


def table(scores: dict[str, dict[str, float]]) -> str:
    header = f"{'配置':<14}" + "".join(f"{name:>9}" for name in MEASURES)
    lines = [header]
    for config, measured in scores.items():
        lines.append(f"{config:<14}" + "".join(f"{measured[name]:>9.3f}" for name in MEASURES))
    return "\n".join(lines)


def main() -> int:
    args = parse_args()
    scores = {path.stem: score_one(path) for path in run_files(args.configs)}
    zero = zero_candidate()

    if args.cross_check:
        problems = cross_check(scores)
        if problems:
            print("交叉校验不一致：")
            for problem in problems:
                print("  " + problem)
            return 1
        print(f"交叉校验一致（{len(scores)} 档，R@5 与 RR 误差 < {CROSS_CHECK_EPS}）")

    baseline = None
    if args.baseline.exists():
        baseline = json.loads(args.baseline.read_text(encoding="utf-8"))
    elif not args.update_baseline:
        print(f"缺基线 {args.baseline}：没有它，「跟上次比」这句话没有对象。"
              f"确认这一版可以接受之后用 --update-baseline 建一份。")
        return 2

    # 更新基线时不判回归：这一轮的语义是"接受当前值"，拿旧基线挡自己会自相矛盾。
    failures = [] if args.update_baseline else judge(scores, zero, baseline)

    text = table(scores)
    print(text)
    print(f"no-search 零候选率 {zero['rate']:.3f}（{zero['passed']}/{zero['total']}）"
          + (f"；给了候选：{', '.join(zero['failed'])}" if zero["failed"] else ""))

    if args.update_baseline:
        payload = {
            "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
            "metrics": scores,
            "no_search_rate": zero["rate"],
        }
        args.baseline.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
                                 encoding="utf-8")
        print(f"已写入基线 {args.baseline}")

    verdict = "通过" if not failures else "不通过"
    report = [
        "# 工具检索评测",
        "",
        f"- 时间：{datetime.now(timezone.utc).isoformat(timespec='seconds')}",
        f"- 判定：**{verdict}**",
        f"- 零候选率：{zero['rate']:.3f}（{zero['passed']}/{zero['total']}）"
        + (f"；给了候选：{', '.join(zero['failed'])}" if zero["failed"] else ""),
        "",
        "```",
        text,
        "```",
    ]
    if failures:
        report += ["", "## 不通过的原因", ""] + [f"- {item}" for item in failures]
    DEFAULT_SCORE.write_text(json.dumps({"metrics": scores, "no_search": zero},
                                        ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    DEFAULT_REPORT.write_text("\n".join(report) + "\n", encoding="utf-8")

    if failures:
        print("不通过：")
        for item in failures:
            print("  " + item)
        return 1
    print(f"全部通过（层数 {len(scores)}）。报告：{DEFAULT_REPORT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

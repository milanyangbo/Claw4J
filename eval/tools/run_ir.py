#!/usr/bin/env python3
"""工具检索评测（票 12 / 票 15）：Java 只导出，指标交给 ir_measures，报告负责讲明白。

链路与 ``eval/rag`` 同构：``run.sh`` → Java ``ToolRetrievalExportTest`` 导出
``reports/qrels.txt`` / ``reports/<config>.run`` / ``reports/categories.txt`` /
``reports/no-search.jsonl`` → 本脚本算分。

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

# 分类的固定顺序与释义（票 15）。顺序写死是为了两次跑出来的报告能逐行 diff。
CATEGORY_ORDER = ["base", "business", "zh-en", "no-search"]
CATEGORY_MEANING = {
    "base": "基座工具（Read/Grep/Glob/Bash/WebFetch/TodoWrite）。它们现在常驻（票 10），"
            "检索掉分不影响线上，但它是「索引还能不能工作」的底线",
    "business": "中文问法 × 中文描述。掉分说明索引本身坏了，是最不该掉的一档",
    "zh-en": "中文问法 × 英文描述。关键字那一路几乎全空，只有向量那一路能看",
    "no-search": "闲聊（「你好」「谢谢」）。期望一个候选都不给，给了就是噪声；不进 qrels，单独判",
}

# 每档配置是怎么算的。键同时支持 hybrid-<权重> 的通配（见 describe_config）。
CONFIG_MEANING = {
    "regex": "中文二元组 + 同义词表，纯关键字。默认那一档",
    "lucene": "全文检索打分（中文按字切），靠词频，不看语义",
}

# 回归容差：确定性指标本来可以不设，留 0.05 是为了"一格题"不反复告警。
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


def config_order(name: str) -> tuple:
    """报告里的列序：关键字在前、纯向量在后，未知档排最后（按字典序）。"""
    known = ["regex", "lucene", "hybrid-1.0", "hybrid-0.5", "hybrid-0.0"]
    if name in known:
        return (0, known.index(name), name)
    return (1, 0, name)


def describe_config(name: str) -> str:
    """一档配置的"人话"说明。hybrid-<x> 的 x 是**关键字那一路的权重**。"""
    if name in CONFIG_MEANING:
        return CONFIG_MEANING[name]
    if name.startswith("hybrid-"):
        weight = name.split("-", 1)[1]
        try:
            value = float(weight)
        except ValueError:
            return "关键字与向量的融合（权重没解析出来）"
        if value >= 1.0:
            return "关键字 100% + 向量 0%：与 regex 逐格相同，用来证明「对照」是真对照"
        if value <= 0.0:
            return "纯向量：完全按语义相似度找，不要求字面重合"
        return f"关键字 {value:g} + 向量 {1 - value:g}（线上默认权重）"
    return "自定义档（报告不认识它，只给数）"


def run_files(only: str) -> list[Path]:
    wanted = [name.strip() for name in only.split(",") if name.strip()]
    files = sorted(REPORTS.glob("*.run"), key=lambda path: config_order(path.stem))
    if wanted:
        files = [path for path in files if path.stem in wanted]
    if not files:
        print(f"{REPORTS} 下没有 .run：先跑 Java 导出（./eval/tools/run.sh）")
        sys.exit(2)
    return files


def read_qrels() -> list:
    """qrels 每轮重读：它只有几百字节，读缓存反而多一处状态。"""
    return list(ir_measures.read_trec_qrels(str(REPORTS / "qrels.txt")))


def score_one(qrels: list, run_path: Path) -> dict[str, float]:
    """一档的总分。"""
    run = list(ir_measures.read_trec_run(str(run_path)))
    measures = [ir_measures.parse_measure(name) for name in MEASURES]
    result = ir_measures.calc_aggregate(measures, qrels, run)
    return {str(measure): float(result[measure]) for measure in measures}


def load_categories() -> dict[str, str]:
    """
    分类清单：``qid<TAB>category``，由 Java 导出（票 15）。

    缺它就是缺"按分类看"的全部结论，所以直接退出 2，不静默降级成"只有总表"——
    那份总表恰恰是读不懂的那一份。
    """
    path = REPORTS / "categories.txt"
    if not path.exists():
        print(f"缺 {path}：没有它就算不了「按分类」。重跑导出（./eval/tools/run.sh）")
        sys.exit(2)
    mapping = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        qid, category = line.split("\t")
        mapping[qid] = category
    return mapping


def score_categories(qrels: list, run_path: Path, categories: dict[str, str]) -> dict[str, dict[str, float]]:
    """
    按分类算分：把 qrels 与 run 各切一份，仍交给 ir_measures，不另写公式。

    两个实现已经够多了（见 ``--cross-check``）。这里的切片只决定"哪些题进这一档"，
    怎么算分仍然只有 ir_measures 一家。
    """
    run = list(ir_measures.read_trec_run(str(run_path)))
    measures = [ir_measures.parse_measure(name) for name in MEASURES]
    result = {}
    for category in CATEGORY_ORDER:
        qids = {qid for qid, value in categories.items() if value == category}
        part_qrels = [row for row in qrels if row.query_id in qids]
        part_run = [row for row in run if row.query_id in qids]
        if not part_qrels:
            # no-search 不进 qrels：它的判据是"零候选率"，不是 recall。
            result[category] = {}
            continue
        measured = ir_measures.calc_aggregate(measures, part_qrels, part_run)
        result[category] = {str(measure): float(measured[measure]) for measure in measures}
    return result


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
    """定宽总表。行序按 config_order 重排：基线那份 JSON 的键序取决于当初写它的顺序，不能信。"""
    ordered = sorted(scores.items(), key=lambda item: config_order(item[0]))
    header = f"{'配置':<14}" + "".join(f"{name:>9}" for name in MEASURES)
    lines = [header]
    for config, measured in ordered:
        lines.append(f"{config:<14}" + "".join(f"{measured[name]:>9.3f}" for name in MEASURES))
    return "\n".join(lines)


def category_table(by_category: dict[str, dict[str, dict[str, float]]], configs: list[str],
                   measure: str) -> str:
    """行=分类、列=各档的某一指标。no-search 没有 recall，写 n/a。"""
    header = f"{'分类':<12}" + "".join(f"{config:>12}" for config in configs)
    lines = [header]
    for category in CATEGORY_ORDER:
        cells = []
        for config in configs:
            value = by_category.get(config, {}).get(category, {}).get(measure)
            cells.append(f"{value:>12.3f}" if value is not None else f"{'n/a':>12}")
        lines.append(f"{category:<12}" + "".join(cells))
    return "\n".join(lines)


def report_text(scores: dict[str, dict[str, float]], by_category: dict[str, dict[str, dict[str, float]]],
                zero: dict[str, object], failures: list[str], baseline: dict | None,
                question_count: int, tool_count: int | None, configs: list[str]) -> str:
    """整份报告。读者定成"第一次看这份东西的人"，所以每一节都要能独立读懂。"""
    verdict = "通过" if not failures else "不通过"
    scored_questions = question_count - int(zero["total"])
    lines = [
        "# 工具检索评测报告",
        "",
        f"- 时间：{datetime.now(timezone.utc).isoformat(timespec='seconds')}",
        f"- 判定：**{verdict}**",
        "",
        "## 这次测的是什么",
        "",
        f"- 题库 {question_count} 条：{scored_questions} 条给了\"期望的工具\"，"
        f"{zero['total']} 条期望\"一个候选都不给\"（闲聊）。",
        f"- 工具目录 {tool_count if tool_count is not None else '?'} 个——是**当轮装配出来的**那份，"
        "不是代码里写死的名单。",
        "- 每条题检索一次、取前 5 个候选，看期望的那个工具在不在里面。",
        "- **不调用工具、不调用模型**：所以零成本，同样输入基本同样输出（向量档有并列抖动，见文末）。",
        "",
        "## 一分钟读法",
        "",
        "1. 先看上面那行「判定」。不通过时会列出哪一档掉了、掉到多少。",
        "2. 看「总表」的 `R@5` 列：**前 5 个候选里有对的那个工具的比例**，这是主判据。",
        "3. 数字不对时看「按分类」：哪一档掉，决定该改描述还是换索引。",
        "   `business` 掉 = 索引坏了；`zh-en` 掉 = 跨语言这条路不行（换向量、或中文化）。",
        "",
        "## 五个指标是什么意思",
        "",
        "| 指标 | 大白话 | 举例 |",
        "| --- | --- | --- |",
        "| `R@1` | 第一名就是对的，占多少 | 20 题里 4 题第一命中 → 0.200 |",
        "| `R@3` | 前三名里有对的，占多少 | 20 题里 8 题 → 0.400 |",
        "| `R@5` | **前五名里有对的，占多少（主判据）** | 20 题里 12 题 → 0.600 |",
        "| `RR` | 对的排第几的倒数，取平均：第 1 名记 1.0、第 2 名记 0.5、第 3 名记 0.33，没命中记 0 |"
        " 两题分别第 1、第 4 → (1.0 + 0.25) / 2 = 0.625 |",
        "| `nDCG@10` | 位置加权的总分，越靠前得分越高；与 ToolRet（ACL 2025）同口径，方便跟公开数字对话 |"
        " 0.54 |",
        "",
        "读的时候注意两件事：",
        "",
        "1. **题少的时候一格很贵**："
        f"{scored_questions} 题的 1 格 ≈ {1 / scored_questions:.3f}，所以 0.01 的差别不是信号。",
        "2. **`R@1`/`R@3` 在向量档会抖**（近分并列，PostgreSQL 取 top-k 没有 tie-breaker），"
        "`R@5` 稳定。别照着 `R@1` 的最后一位改参数。",
        "",
        "## 各档配置是什么（`hybrid` 后面那个数是**关键字那一路的权重**）",
        "",
        "| 配置 | 怎么找工具 |",
        "| --- | --- |",
    ]
    for config in configs:
        lines.append(f"| `{config}` | {describe_config(config)} |")

    lines += [
        "",
        "所以 `hybrid-1.0` 与 `regex` 应当逐格相同——如果不同，说明融合那层写错了。"
        "`hybrid-0.0` 是纯向量，`hybrid-0.5` 是各一半。",
        "",
        "## 总表",
        "",
        "```",
        table(scores),
        "```",
        "",
        f"no-search 零候选率 {zero['rate']:.3f}（{zero['passed']}/{zero['total']}）"
        + (f"；给了候选：{', '.join(zero['failed'])}" if zero["failed"] else ""),
        "",
        "## 按分类（`R@5`：前五里有对的）",
        "",
        "```",
        category_table(by_category, configs, "R@5"),
        "```",
        "",
        "## 按分类（`R@1`：第一名就对）",
        "",
        "```",
        category_table(by_category, configs, "R@1"),
        "```",
        "",
        "各档是什么意思：",
        "",
    ]
    for category in CATEGORY_ORDER:
        lines.append(f"- **`{category}`** — {CATEGORY_MEANING[category]}")

    lines += [
        "",
        "## 怎么算达标",
        "",
        f"1. **回归门**：每一档的 `R@5` 不低于 `baseline.json` 里记的值减 {TOLERANCE}"
        f"（= 一格题的量级）。基线是\"上次被接受的版本\"，没有它就不是评测，只是打印。",
        "2. **零候选率也是回归门**：基线记当前值，掉下去才失败。它不是"
        "\"必须 1.0\"——`regex` 现在对「谢谢」就会给候选，把 1.0 当门槛等于假装它已经修好。",
        "3. **基线里没有的档不算回归**：新加的档只打印，第一次跑完用 `--update-baseline` 接受它。",
        "",
        "退出码：`0` 全过；`1` 有回归（看下面的「不通过的原因」）；`2` 环境没搭起来"
        "（缺 key / 缺依赖 / **缺基线**）。",
        "",
        "## 已知噪声",
        "",
        "- **向量档的名次会抖**：`INTENT`/`list_schedules` 这一类工具描述在向量空间里几乎同一点，"
        "取 top-k 没有 tie-breaker，两次跑可能互换名次。`R@5` 不受影响，`R@1`/`RR` 第 2 位小数会动。",
        "- **开了中文化之后更抖**：译文不落表（票 16），每个进程重译一次，措辞不同 embedding 就不同。"
        "要拿它当门，先得把译文钉住。",
        "- **样本小**：题量决定分辨率。现在的结论是\"方向可信、数值不可引\"。",
        "",
    ]
    if baseline is not None:
        lines += ["## 基线（上次被接受的）", "", "```", table(baseline.get("metrics", {})), "```", ""]
    if failures:
        lines += ["## 不通过的原因", ""] + [f"- {item}" for item in failures] + [""]
    return "\n".join(lines)


def main() -> int:
    args = parse_args()
    qrels = read_qrels()
    categories = load_categories()
    paths = run_files(args.configs)
    configs = [path.stem for path in paths]
    scores = {config: score_one(qrels, REPORTS / f"{config}.run") for config in configs}
    by_category = {config: score_categories(qrels, REPORTS / f"{config}.run", categories)
                   for config in configs}
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

    catalogue = REPORTS / "catalogue.txt"
    tool_count = len([line for line in catalogue.read_text(encoding="utf-8").splitlines() if line.strip()]) \
        if catalogue.exists() else None
    report = report_text(scores, by_category, zero, failures, baseline,
                         question_count=len(categories), tool_count=tool_count, configs=configs)

    DEFAULT_SCORE.write_text(json.dumps(
        {"metrics": scores, "by_category": by_category, "no_search": zero},
        ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    DEFAULT_REPORT.write_text(report + "\n", encoding="utf-8")

    if failures:
        print("不通过：")
        for item in failures:
            print("  " + item)
        return 1
    print(f"全部通过（层数 {len(scores)}）。报告：{DEFAULT_REPORT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

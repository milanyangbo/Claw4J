#!/usr/bin/env python3
"""RAG 评测：L1 检索层 + L2 生成层，指标全部来自 Ragas。

输入是 Java 侧导出的 JSONL（``./eval/rag/run.sh`` 会先跑导出）。
两层分开跑，因为成本与用途不同：

- **L1（检索）**：``IDBasedContextRecall/Precision``，不调模型、零成本、可复现。
  参数对照（hybrid / rerank / top-k）全在这一层扫。
- **L2（生成）**：``Faithfulness`` / ``LLMContextRecall`` / ``LLMContextPrecision``，要 judge。
  它回答"模型有没有照着检索到的内容答"，与 L1 的"检索给对没有"是两件事。

跑完除了打印分数，还会给一份**判定**（达标 / 回归 / 存疑 + 怎么查），并落一份
``report.md``。

门槛分三层，混在一起看就会得出"这分数到底好不好"这种答不出的问题：

- **绝对门**：``Recall@k`` 与 ``judge 失败``。确定性指标，同输入同输出，配得上固定数字。
- **相对门**：L2 三项只跟 ``baseline.json`` 比，跌超 ``TOLERANCE`` 才算回归。要 judge 的
  指标分数会漂，绝对线一设就假。
- **分档（差/良/好）**：只显示不判定。Ragas 官方**不给**分档，边界来自实践文章的交叉。

改门槛要同时改 ``eval/rag/README.md`` 那张表，否则代码与文档会各说一套。

judge 默认 ``deepseek-flash``（DeepSeek 最便宜的档，2026-11 价格：输入 $0.15 / 输出 $0.60
每百万 token，高峰翻倍）。换 judge 必须重建基线：不同 judge 的分数不能横向比。
换 key 走 ``EVAL_JUDGE_API_KEY``，换端点走 ``EVAL_JUDGE_BASE_URL``，不用改代码。
"""

from __future__ import annotations

import argparse
import asyncio
import json
import math
import os
import sys
import unicodedata
import warnings
from datetime import datetime
from pathlib import Path

# 抑制必须赶在 import ragas 之前：IDBasedContextRecall 这一套在 ragas.metrics 的老 API 里
# （collections 版没有 ID 型），一 import 就抛两条 DeprecationWarning。放到 import 之后抑制，
# 声音已经出来了，报告首屏就是两行噪音。
warnings.simplefilter("ignore", DeprecationWarning)

from ragas import SingleTurnSample  # noqa: E402
from ragas.metrics import IDBasedContextPrecision, IDBasedContextRecall  # noqa: E402

DEFAULT_INPUT = "eval/rag/reports/run.jsonl"
DEFAULT_OUTPUT = "eval/rag/reports/score.json"
DEFAULT_REPORT = "eval/rag/reports/report.md"
# 基线进库、结果不进库：基线是"上一次被接受的版本"，它得跟着代码一起被审；
# 每次跑的原始结果只是产物。gilzor 那条 "Pin what you can" 说的就是这个。
DEFAULT_BASELINE = "eval/rag/baseline.json"
DEFAULT_JUDGE = "deepseek-flash"
DEFAULT_BASE_URL = "https://api.deepseek.com"

# ------------------------------------------------------------------ 门槛：分三层
#
# 先记一条容易被忽略的事实：**RAG 评测没有公认的绝对门槛。**
# qaskills.sh 的 2026 RAG QA 指南把"直接抄文档里的门槛"列为典型错误——原文是
# "Example thresholds demonstrate syntax. They are not universal acceptance criteria.
#  Score distributions depend on task, dataset, model, prompt, language, and implementation."
# 所以下面分成三种东西，混在一起看就会得出"这分数到底好不好"这种答不出的问题。

# ① 分档：差 / 良 / 好。只用来"读数字"，不参与判定。
#
# 先把一件事说清，免得以后有人以为这是官方口径：**Ragas 官方文档不给分档。**
# 它只给公式和方向。faithfulness 页原文是 "It ranges from 0 to 1, with higher scores
# indicating better consistency."；context_recall 页是 "values range between 0 and 1,
# with higher values indicating better performance."；本地装的 ragas 0.4.4 里，
# 这几个 metric 的 docstring 也只有定义与公式。整个文档里没有一句 "above 0.8 is good"。
#
# 所以下面的边界是几篇实践文章交叉出来的，各家**并不一致**，取的是多数派下限：
#
#   faithfulness      0.85 —— benchmarkagents 与 ai-tldr 的生产线；更严的还有
#                             sapota 0.90（通用）/ 0.95（合规）、callsphere 0.92。
#                             下限 0.70 来自 labhub 的"可接受"上界，也没有任何来源
#                             把 0.7 以下说成可用。
#   context_recall    0.80 —— ai-tldr 与 sapota 的目标线，callsphere 要 0.85。
#                             下限 0.70 是 sapota 的原话：低于它就"先别动提示词，去修检索"。
#   context_precision 0.75 —— callsphere 与 benchmarkagents 的线，ai-tldr 用 0.70。
#                             取严的。下限 0.60 在 labhub 的"可接受"区间内。
#
# 值是 (差的上界, 好的下界)：value < poor 判"差"，< good 判"良"，否则"好"。
BANDS = {
    "faithfulness": (0.70, 0.85),
    "context_recall": (0.70, 0.80),
    "context_precision": (0.60, 0.75),
}

# 各家分歧写清楚，免得有人把 0.85 当成共识。报告里逐行列出来。
DISAGREEMENT = {
    "faithfulness": "好线下限 0.85（benchmarkagents、ai-tldr）／0.90（sapota 通用）／"
                    "0.92（callsphere）／0.95（合规场景）",
    "context_recall": "好线下限 0.80（ai-tldr、sapota）／0.85（callsphere）；"
                      "sapota 另说低于 0.70 就先修检索、别动提示词",
    "context_precision": "好线下限 0.70（ai-tldr）／0.75（callsphere、benchmarkagents）",
}

# ② 绝对硬门：只给确定性指标与体检项。
#
# 这两类不依赖 judge，同样的输入永远同样的分，所以才配得上一个固定数字。
# 门槛值本身仍是本仓约定（行业在 IR 侧也不给统一线），但依据不是感觉，是这条：
# 漏掉一成的正确段，模型就只能靠常识补——那已经不是"检索略差"，是"检索不能用"。
GATE_RECALL5 = 0.90
GATE_JUDGE_FAILURES = 0

# ③ 相对门：L2 的 judge 指标只跟自己的基线比。
#
# 所有来源都指向同一句 "Compare with a baseline, not an absolute target"（gilzor）。
# 原因是 judge 分数会随模型版本、温度、提示词漂移，绝对线一设就假。
# 容差 0.05：dataaspirant 的回归脚本用 TOLERANCE = 0.05 吸收 judge 噪声，
# callsphere 的 CI 用 --max_drop 0.02——那是 150 条样本下的噪声水平；
# 我们只有 12 条，噪声更大，所以取宽的那个，宁可漏报也别报假警。
# progressiverobot 的原话是，小样本 + 均值门会 "produce false alarms that erode team trust"。
TOLERANCE = 0.05

# 每个数背后的出处。报告末尾会印一份，别让它只活在注释里。
SOURCES = [
    ("Ragas 官方文档", "只给公式与方向（0–1，越高越好），**没有任何阈值或分档**"),
    ("labhub RAGAS 指南", "唯一直接给四档的：危险 / 可接受 / 好 / 优秀；本仓三档的下限取自它"),
    ("benchmarkagents.com", "faithfulness >0.85（生产）、>0.95（高风险）；context precision >0.75"),
    ("ai-tldr.dev", "faithfulness>0.85 / context recall>0.80 / context precision>0.70，"
                    "并补一句「取决于领域与风险偏好」"),
    ("callsphere.ai", "faithfulness ≥0.92、context recall ≥0.85、context precision ≥0.75；"
                      "另给 max_drop 0.02 的回归门"),
    ("sapota 实践记录", "faithfulness >0.90（合规要 >0.95）；context recall 目标 0.80，"
                        "低于 0.70 先修检索、别动提示词"),
    ("qaskills.sh 2026", "抄文档门槛是典型错误；门槛随任务/数据集/模型/语言而变"),
    ("gilzor.com", "与基线比，不与绝对目标比；跌幅要超过本样本量的噪声才算回归"),
    ("progressiverobot.com", "静态门 + 小样本均值门 = 假警报，会耗掉团队对红灯的信任"),
    ("aimec.io", "实测数只能当 reference baseline，不是通用生产阈值"),
    ("dataaspirant.com", "基线 + TOLERANCE=0.05；看移动方向，不看小数点后第三位"),
]

def band_of(name: str, value: float | None) -> str:
    """把分数翻成「差 / 良 / 好」。没有分档依据的（Recall@k 就属于这类）返回 —。

    这是**阅读辅助**，永不参与 ``ok`` 的判定。让它参与，就等于把外部经验值当成
    本仓的验收标准——而它们的出身是几篇博客，不是规范。
    """
    if name not in BANDS or value is None or math.isnan(value):
        return "—"
    poor, good = BANDS[name]
    if value < poor:
        return "差"
    return "良" if value < good else "好"


def band_hint(name: str) -> str:
    """一行写清档位边界，给报告的对照表用。"""
    if name not in BANDS:
        return "无通用分档"
    poor, good = BANDS[name]
    return f"差 <{poor:.2f}｜良 {poor:.2f}–{good:.2f}｜好 ≥{good:.2f}"


L2_ADVICE = {
    "faithfulness": "回答里有主张撑不住上下文（在编）。先看是不是把常识写进了答案，"
                    "再看上下文是不是被截断丢掉了一段。",
    "context_recall": "参考答案的要点没被上下文覆盖。多数时候是检索漏段（回到 Recall 看），"
                      "少数是 reference 写得比文档还宽。",
    "context_precision": "上下文里相关段占比低，也就是夹带了无关段。调小 top-k、提高相似度下限、"
                         "或让重排把 gold 顶到前面。",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="RAG 评测（L1 检索 + L2 生成）")
    parser.add_argument("--input", default=DEFAULT_INPUT, help="Java 导出的 JSONL 路径")
    parser.add_argument("--cutoffs", default="3,5,8", help="L1 按前 k 段算 Recall/Precision，逗号分隔")
    parser.add_argument("--skip-judge", action="store_true", help="只跑 L1（不调模型、零成本）")
    parser.add_argument("--judge", default=os.environ.get("EVAL_JUDGE_MODEL", DEFAULT_JUDGE),
                        help=f"L2 的 judge 模型，默认 {DEFAULT_JUDGE}")
    # deepseek-flash 的并发上限是 2500，并发 4 远远够不着；之前 qwen 那边是被 RPM 卡住的，
    # 那类失败会整轮变成失败条数，比慢更糟。真要再往上加，先确认 judge 供应商的限额。
    parser.add_argument("--concurrency", type=int, default=4, help="L2 的并发数，默认 4（撞限流就降到 1）")
    parser.add_argument("--per-case", action="store_true", help="把 L1 逐条明细也写进结果文件")
    parser.add_argument("--output", default=DEFAULT_OUTPUT, help="机器可读结果的落盘处")
    parser.add_argument("--report", default=DEFAULT_REPORT, help="人读报告的落盘处")
    # 改门槛、改报告文案、或想按新口径重看旧结果时用它：不调模型、零成本。
    parser.add_argument("--from-score", help="不调模型，从已有的 score.json 重建判定与报告")
    parser.add_argument("--baseline", default=DEFAULT_BASELINE,
                        help=f"L2 的对照基线，默认 {DEFAULT_BASELINE}")
    # 只有确认这次的变化是"改好了"才更新基线。它一更新，下次的红灯标准就跟着动了。
    parser.add_argument("--update-baseline", action="store_true",
                        help="把本次结果写为新基线（确认变好才用）")
    return parser.parse_args()


def load_rows(path: Path) -> list[dict]:
    if not path.exists():
        raise SystemExit(f"找不到 {path}：先跑导出（./eval/rag/run.sh 会先跑它）")
    rows: list[dict] = []
    with path.open(encoding="utf-8") as handle:
        for number, line in enumerate(handle, start=1):
            stripped = line.strip()
            if not stripped:
                continue
            try:
                rows.append(json.loads(stripped))
            except json.JSONDecodeError as broken:
                raise SystemExit(f"{path} 第 {number} 行不是合法 JSON：{broken}") from broken
    if not rows:
        raise SystemExit(f"{path} 是空的")
    return rows


# ---------------------------------------------------------------- L1：检索层


def as_sample(row: dict, cutoff: int) -> SingleTurnSample:
    ids = (row.get("retrieved_context_ids") or [])[:cutoff]
    texts = (row.get("retrieved_contexts") or [])[:cutoff]
    return SingleTurnSample(
        user_input=row["question"],
        retrieved_context_ids=ids,
        retrieved_contexts=texts,
        reference_context_ids=row.get("reference_context_ids") or [],
    )


def rank_of(row: dict, chunk_id: str) -> int | None:
    """某一段在导出列表里的名次（从 1 数）。不在列表里就是 None。

    逐段算而不是取"最好名次"：一条 gold 可能落在两个块上（答案跨块），
    取最好名次会把"另一块排第 9"说成"第 1 位"，那是两件完全不同的事。
    """
    retrieved = row.get("retrieved_context_ids") or []
    return retrieved.index(chunk_id) + 1 if chunk_id in retrieved else None


async def score_l1(rows: list[dict], cutoffs: list[int], per_case: bool) -> dict:
    recall = IDBasedContextRecall()
    precision = IDBasedContextPrecision()

    result: dict = {"cases": len(rows), "cutoffs": {}, "per_case": [], "cases_detail": {}}
    for cutoff in cutoffs:
        recalls: list[float] = []
        precisions: list[float] = []
        detail: list[dict] = []
        for row in rows:
            sample = as_sample(row, cutoff)
            value_r = float(await recall.single_turn_ascore(sample))
            value_p = float(await precision.single_turn_ascore(sample))
            recalls.append(value_r)
            precisions.append(value_p)
            gold_ids = row.get("reference_context_ids") or []
            detail.append({
                "id": row["id"],
                "recall": value_r,
                "precision": value_p,
                "reference_ids": gold_ids,
                "retrieved_ids": sample.retrieved_context_ids,
                # 缺哪几段 + 它们在完整候选里的名次。名次要紧：排第 9 而 k=8 是 top-k 太小，
                # 压根不在列表是切块 / 检索那一路的问题，两者要改的地方完全不同。
                "missing": [
                    {"id": rid, "rank": rank_of(row, rid)}
                    for rid in gold_ids if rid not in sample.retrieved_context_ids
                ],
            })
        result["cutoffs"][str(cutoff)] = {
            "recall": sum(recalls) / len(recalls),
            "precision": sum(precisions) / len(precisions),
            "recall_hits": sum(1 for value in recalls if value >= 1.0),
            "missed": [item["id"] for item in detail if item["recall"] < 1.0],
        }
        if per_case:
            result["per_case"].append({"cutoff": cutoff, "rows": detail})
        # 每个 k 都留一份精简明细：报告里"没召全"那条要按 k 给出位次，
        # 只留最后一份会让 k=3 的说明用上 k=8 的名次。
        result["cases_detail"][str(cutoff)] = [
            {key: item[key] for key in ("id", "missing")}
            for item in detail
        ]
    return result


# ---------------------------------------------------------------- L2：生成层


def make_judge(model: str):
    """建 judge。走 OpenAI 兼容端点，不引 langchain 那一层。

    key 与端点都能用环境变量换：``EVAL_JUDGE_API_KEY`` / ``EVAL_JUDGE_BASE_URL``，
    不设就回落到 DeepSeek。这样换 judge 供应商只改环境变量，不改这个文件。
    """
    api_key = os.environ.get("EVAL_JUDGE_API_KEY") or os.environ.get("DEEPSEEK_API_KEY")
    if not api_key:
        raise SystemExit("缺 DEEPSEEK_API_KEY（或 EVAL_JUDGE_API_KEY）：L2 要一个 judge 模型。"
                         "只想跑 L1 就加 --skip-judge。")
    try:
        from openai import AsyncOpenAI
        from ragas.llms import llm_factory
    except ImportError as missing:
        raise SystemExit(f"缺依赖（{missing}）：重装 eval/rag/requirements.txt") from missing

    # 必须用 AsyncOpenAI：Ragas 的 *_ascore 是协程，而同步客户端在协程里一调就把事件循环堵住，
    # 于是 asyncio.Semaphore 形同虚设——并发设成 4 实际仍是 1 路排队，12 条要跑十几分钟。
    client = AsyncOpenAI(
        base_url=os.environ.get("EVAL_JUDGE_BASE_URL", DEFAULT_BASE_URL),
        api_key=api_key,
        # 失败重试要给足：faithfulness 一条内部要发好几次请求。
        # SDK 会按 retry-after 退避，不然整轮全是失败，而失败条数看着像"这指标算不了"。
        max_retries=8,
        timeout=120.0,
    )
    # deepseek-flash 默认开思考（reasoning_effort=high）。judge 干的是"照格式填判断"，
    # 不需要推理链：关掉之后同样的样例行内耗时就降下来，且少花推理 token 的钱。
    #
    # max_tokens 必须显式给：默认值只够短回答，而 faithfulness 要把回答拆成主张逐条判，
    # 十几条主张的 JSON 会超出上限、被服务端截断，instructor 抛 IncompleteOutputException
    # （它的 repr 是空的，不打类型名会看到"算不出来："后面什么都没有）。
    return llm_factory(model, client=client, max_tokens=2048,
                       extra_body={"thinking": {"type": "disabled"}})


def l2_sample(row: dict) -> SingleTurnSample:
    if not row.get("response"):
        raise SystemExit(f"{row['id']} 没有 response 字段：重跑导出（Java 侧已产出它，老 JSONL 没有）")
    return SingleTurnSample(
        user_input=row["question"],
        response=row["response"],
        retrieved_contexts=row.get("retrieved_contexts") or [],
        reference=row.get("reference"),
    )


async def score_l2(rows: list[dict], judge, model: str, concurrency: int) -> dict:
    from ragas.metrics import Faithfulness, LLMContextPrecisionWithReference, LLMContextRecall

    metrics = [
        ("faithfulness", Faithfulness(llm=judge)),
        ("context_recall", LLMContextRecall(llm=judge)),
        ("context_precision", LLMContextPrecisionWithReference(llm=judge)),
    ]
    semaphore = asyncio.Semaphore(max(1, concurrency))

    async def one(row: dict, name: str, metric) -> float:
        async with semaphore:
            try:
                return float(await metric.single_turn_ascore(l2_sample(row)))
            except Exception as failure:  # noqa: BLE001 - judge 失败记 NaN，不中断整轮
                # 带上类型名：IncompleteOutputException 的 repr 是空的，
                # 只打 {failure} 会变成"算不出来："后面什么都没有，等于没报错。
                print(f"  {row['id']} 的 {name} 算不出来：{type(failure).__name__} {failure}",
                      file=sys.stderr)
                return math.nan

    ids = [row["id"] for row in rows]
    scores: dict[str, list[float]] = {}
    for name, metric in metrics:
        print(f"  L2 跑 {name}（{len(rows)} 条，并发 {concurrency}）…", file=sys.stderr)
        scores[name] = list(await asyncio.gather(*(one(row, name, metric) for row in rows)))

    summary: dict = {"judge": model, "cases": len(rows), "metrics": {}}
    for name, values in scores.items():
        valid = [(case, value) for case, value in zip(ids, values) if not math.isnan(value)]
        mean = sum(value for _, value in valid) / len(valid) if valid else math.nan
        worst = sorted(valid, key=lambda pair: pair[1])[:3]
        summary["metrics"][name] = {
            "mean": mean,
            "failed": len(values) - len(valid),
            "worst": [{"id": case, "score": value} for case, value in worst],
            "per_case": [{"id": case, "score": value} for case, value in zip(ids, values)],
        }
    return summary


# ---------------------------------------------------------------- 判定与报告


def load_baseline(path: Path) -> dict | None:
    """读基线。没有就是 None——首次跑没有基线是正常状态，不是错误。"""
    if not path.exists():
        return None
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as broken:
        raise SystemExit(f"基线 {path} 不是合法 JSON：{broken}") from broken


def snapshot(result: dict) -> dict:
    """把一次结果压成基线。

    只留判定要用的数：均值 + 没召全的题号。不存原文与逐条分数——基线是"标准"，
    不是"存档"，存多了会让人以为它是一份可以翻查的历史。
    """
    l1 = result["l1"]
    cutoff = main_cutoff(l1)
    values = l1["cutoffs"][cutoff]
    snap: dict = {
        "recorded_at": datetime.now().strftime("%Y-%m-%d"),
        "cases": l1["cases"],
        "cutoff": cutoff,
        "l1": {"recall": values["recall"], "missed": list(values["missed"])},
        "l2": {},
    }
    if result.get("l2"):
        snap["judge"] = result["l2"]["judge"]
        for name, item in result["l2"]["metrics"].items():
            mean = item["mean"]
            # NaN 不是合法 JSON，也不能当 0 存——那等于把"没算出来"钉成"很差"。
            snap["l2"][name] = None if math.isnan(mean) else mean
    return snap


def check_baseline_worthy(snap: dict) -> str | None:
    """基线本身不能太低。

    纯相对门的已知毛病：只要基线够烂，之后怎么跑都是"稳定"。所以立基线时要过一道
    地板——要求每项至少到「好」档。连"好"都够不着的配置，不配当"被接受的版本"；
    这道限制在只看 delta 的 CI 里通常没人写，代价是基线悄悄定在坑里。
    """
    low = [f"{name}={value:.3f}（好档要 ≥{BANDS[name][1]:.2f}）"
           for name, value in (snap.get("l2") or {}).items()
           if name in BANDS and value is not None and value < BANDS[name][1]]
    if low:
        return "这批分数还不配当基线：" + "、".join(low)
    return None


def diff_cases(baseline: dict | None, result: dict) -> dict:
    """与基线逐题比召回，给出新翻红与已修好的。

    为什么是逐题而不是看均值：均值不动可能藏着五条新失败抵五条新通过。
    gilzor 那条 "An unchanged average can hide five new failures and five new passes"
    说的就是这个，所以长尾必须按题看。
    """
    l1 = result["l1"]
    cutoff = main_cutoff(l1)
    if not baseline or baseline.get("cutoff") != cutoff:
        return {"comparable": False, "new_fail": [], "fixed": []}
    if baseline.get("cases") != l1["cases"]:
        # 题集变了还逐题比，比的是两个不同的卷子：旧基线里没有的题会被一律算成"翻红"，
        # 而它们从来没被基线考过。这不是回归，是换了尺子——先 --update-baseline 再谈逐题。
        return {"comparable": False, "new_fail": [], "fixed": [],
                "why": f"基线是 {baseline.get('cases')} 条题的，这次是 {l1['cases']} 条"}
    now = set(l1["cutoffs"][cutoff]["missed"])
    was = set(baseline.get("l1", {}).get("missed") or [])
    return {"comparable": True, "new_fail": sorted(now - was), "fixed": sorted(was - now)}


def main_cutoff(l1: dict) -> str:
    """判定用哪个 k：优先 5（常规上下文预算），没有就取最大。"""
    return "5" if "5" in l1["cutoffs"] else max(l1["cutoffs"], key=int)


def verdicts(result: dict, baseline: dict | None = None) -> list[dict]:
    """把分数翻成判定。三类门槛对应三种口径，见文件头的说明。

    每行字段：metric / current / gate / reference / state / ok / advice，
    L2 的行另有 delta（与基线的差）。``reference`` 只显示，**永不参与 ok 的计算**——
    一旦让分档参与判定，就等于把它当成本仓的验收标准，而它恰恰不是。
    """
    l1 = result["l1"]
    cutoff = main_cutoff(l1)
    values = l1["cutoffs"][cutoff]
    recall = values["recall"]
    hits = values["recall_hits"]
    cases = l1["cases"]
    missed = values["missed"]

    recall_ok = recall >= GATE_RECALL5
    rows: list[dict] = [{
        "metric": f"Recall@{cutoff}",
        "current": f"{recall:.3f}",
        "gate": f"≥{GATE_RECALL5:.2f}",
        "reference": "—",
        "state": "达标" if recall_ok else "不达标",
        "ok": recall_ok,
        "advice": "正确段没进上下文，模型只能靠常识编。先看下面「没召全」那几条的位次："
                  "排在 k 之外是 top-k 或重排的问题，压根不在列表里是切块或检索那一路的问题。",
    }, {
        # 这一行刻意不判达标。原先写的是"=满分"，那等于把 Recall@5 = 1.000 换句话又说一遍，
        # 而且永远红：12 条里只要有一条 gold 落在两块上就够不着。行业对长尾的做法是逐题 diff，
        # 不是给均值打补丁——补丁式的门一旦常红，红灯就没有信息了。
        "metric": f"召回完整性@{cutoff}",
        "current": f"{hits}/{cases}",
        "gate": "诊断项，不作门",
        "reference": "—",
        "state": "—",
        "ok": True,
        "advice": (f"没召全 {len(missed)} 条：{'、'.join(missed)}。它不是门槛，"
                   f"是用来盯长尾的——均值会把「某条只召到一半」抹平。"
                   if missed else "全部召全。这只是诊断列，别当成绩单看。"),
    }]

    l2 = result.get("l2")
    if not l2:
        return rows

    for name in ("faithfulness", "context_recall", "context_precision"):
        item = l2["metrics"][name]
        mean = item["mean"]
        failed = item["failed"]
        usable = l2["cases"] - failed
        base = (baseline or {}).get("l2", {}).get(name)
        # 档位只作阅读参考（好 / 良 / 差），来自几篇实践文章，不参与下面的 ok 判定。
        reference = band_of(name, mean)
        # 有失败时把覆盖条数写进当前值：5 条算出的均值不能冒充全部 12 条的成绩。
        current = "n/a" if math.isnan(mean) else (
            f"{mean:.3f}" if failed == 0 else f"{mean:.3f}（{usable}/{l2['cases']}）")

        if failed:
            # judge 缺样本时这均值既不能判达标也不能判不达标——它是"没测全"。
            rows.append({
                "metric": name,
                "current": current,
                "gate": "待 judge 修好",
                "reference": reference,
                "state": "存疑",
                "ok": False,
                "advice": f"有 {failed} 条算不出来，这均值只覆盖 {usable}/{l2['cases']} 条，"
                          f"而失败的往往是最难判的那些（长回答、多段上下文）。先把 judge 修好，"
                          f"这个数才有意义。",
            })
            continue

        if not baseline:
            rows.append({
                "metric": name,
                "current": current,
                "gate": "首次建基线",
                "reference": reference,
                "state": "基准",
                "ok": True,
                "advice": f"还没有基线，这次只记基准值。以后跟它比，跌超 {TOLERANCE:.2f} 才算回归。",
            })
            continue

        if base is None:
            rows.append({
                "metric": name,
                "current": current,
                "gate": "基线里没有此项",
                "reference": reference,
                "state": "存疑",
                "ok": False,
                "advice": "基线文件里缺这一项（可能是新增指标，或当时的 judge 没跑完）。"
                          "用 --update-baseline 重建一份再比。",
            })
            continue

        delta = mean - base
        if delta < -TOLERANCE:
            state, ok = "回归", False
            advice = (f"比基线低 {abs(delta):.3f}，超过 {TOLERANCE:.2f} 的容差。"
                      f"先确认不是 judge 换了口气（换 judge 必须重建基线），"
                      f"再看最低三条是不是同一个失效模式。")
        elif delta > TOLERANCE:
            state, ok = "提升", True
            advice = (f"比基线高 {delta:.3f}。确认是改好了，就用 --update-baseline "
                      f"把新基线钉下来——否则下次它会自己变成「退步」。")
        else:
            # 差在容差内不动结论：12 条样本下 0.02 的起伏是 judge 换了口气，不是代码变了。
            state, ok = "稳定", True
            advice = L2_ADVICE[name]
        rows.append({
            "metric": name,
            "current": current,
            "gate": f"基线 {base:.3f} ±{TOLERANCE:.2f}",
            "reference": reference,
            "state": state,
            "ok": ok,
            "delta": delta,
            "advice": advice,
        })

    failed_total = sum(item["failed"] for item in l2["metrics"].values())
    judge_ok = failed_total == GATE_JUDGE_FAILURES
    rows.append({
        "metric": "judge 失败",
        "current": str(failed_total),
        "gate": f"={GATE_JUDGE_FAILURES}",
        "reference": "—",
        "state": "达标" if judge_ok else "不达标",
        "ok": judge_ok,
        "advice": "有失败就先修 judge：402 是账户余额不足，401 是 key 失效，"
                  "429 是限流，IncompleteOutputException 是输出被截断（把 max_tokens 调大）。"
                  "修好之前，别拿剩下的均值当结论。",
    })
    return rows


def pad(text: str, width: int) -> str:
    """按显示宽度补空格。中文字符在终端占两格，``:^20`` 只数字符，表会歪。"""
    shown = sum(2 if unicodedata.east_asian_width(ch) in "WF" else 1 for ch in text)
    return text + " " * max(0, width - shown)


def print_verdicts(rows: list[dict], flips: dict | None = None, cases: int = 0) -> None:
    print("\n判定（口径见 eval/rag/README.md：L1 用绝对门，L2 用相对基线）\n")
    print(f"{pad('指标', 24)}{pad('当前', 16)}{pad('判定口径', 20)}{pad('档位', 6)}结论")
    for row in rows:
        print(f"{pad(row['metric'], 24)}{pad(row['current'], 16)}"
              f"{pad(row['gate'], 20)}{pad(row.get('reference', '—'), 6)}{row['state']}")

    if flips and flips.get("comparable"):
        if flips["new_fail"]:
            print(f"\n翻红（基线里召全、这次没召全）：{'、'.join(flips['new_fail'])}")
        if flips["fixed"]:
            print(f"\n修好（基线里没召全、这次召全）：{'、'.join(flips['fixed'])}")

    failing = [row for row in rows if not row["ok"]]
    if not failing:
        if any(row["state"] == "基准" for row in rows):
            print("\n没有不达标项，但 L2 还在建基线：这次的均值只被记下来，还没跟谁比过。"
                  "下次起才判回归。")
        else:
            print(f"\n全部达标。但这不等于「没问题」：它只说明当前配置在这 {cases} 条上没有明显塌方，"
                  f"而 {cases} 条里一条就是 {100 / cases:.1f} 个百分点。")
        return
    print("\n不达标或存疑的怎么查：")
    for row in failing:
        print(f"\n  {row['metric']}（{row['state']}）：{row['advice']}")


def missed_lines(result: dict) -> list[str]:
    """没召全的用例，逐段给出它在完整候选里的名次。

    名次要紧：排第 9 而 k=8，是 top-k 太小或重排没顶上来；压根不在列表，是切块 / 检索那一路的问题。
    """
    detail_by_cutoff = result["l1"].get("cases_detail") or {}
    lines: list[str] = []
    for cutoff, values in result["l1"]["cutoffs"].items():
        if not values["missed"]:
            continue
        lines.append(f"k={cutoff} 没召全 {len(values['missed'])} 条：")
        by_id = {row["id"]: row for row in detail_by_cutoff.get(cutoff, [])}
        for case in values["missed"]:
            missing = by_id.get(case, {}).get("missing") or []
            parts = []
            for item in missing:
                rank = item.get("rank")
                where = f"完整候选里第 {rank} 位" if rank else "完整候选里没有"
                parts.append(f"{item['id']}（{where}）")
            lines.append(f"  - {case}：缺 {'；'.join(parts) or '（未知）'}")
    return lines


def command_line(argv: list[str]) -> str:
    """把本次调用重建成一行能复制的命令。

    去掉 `--input/--output/--report` 三个参数：它们只决定这次的落盘位置，
    留在报告里会让下一个人以为要照抄那几个路径（`/tmp/probe.json` 之类）。
    """
    path_flags = ("--input", "--output", "--report")
    kept: list[str] = []
    skip_next = False
    for token in argv:
        if skip_next:
            skip_next = False
            continue
        if token in path_flags:
            skip_next = True
            continue
        if token.startswith(tuple(flag + "=" for flag in path_flags)):
            continue
        kept.append(token)
    return " ".join(["./eval/rag/run.sh", *kept])


def build_report(result: dict, rows: list[dict], command: str, rebuilt: bool = False,
                 flips: dict | None = None) -> str:
    """拼一份人读的报告。数字都在上面，这里只补「怎么读」。"""
    l1 = result["l1"]
    cutoff = main_cutoff(l1)
    cases = l1["cases"]
    lines = [
        f"# RAG 评测报告（{datetime.now().strftime('%Y-%m-%d %H:%M')}）",
        "",
        f"- 用例数：{cases} 条，判定用的 k = {cutoff}",
        f"- 命令：`{command}`",
    ]
    if rebuilt:
        # 说明白：这份只是按当前门槛重算的旧分数，没有重新问过模型。
        lines.append("- 说明：本次从已有分数重建，**没有重新调用模型**")
    lines += [
        "- 机器可读结果：与本文同目录的 `score.json`",
        "",
        "## 一、判定",
        "",
        "| 指标 | 当前 | 判定口径 | 档位 | 结论 |",
        "| --- | --- | --- | --- | --- |",
    ]
    for row in rows:
        lines.append(f"| {row['metric']} | {row['current']} | {row['gate']} | "
                     f"{row.get('reference', '—')} | {row['state']} |")

    failing = [row for row in rows if not row["ok"]]
    lines += [
        "",
        "**两类口径不能混着看**：`Recall@k` 与 `judge 失败` 是确定性指标，同样的输入永远同样的分，"
        "所以配绝对门；L2 那三项要 judge，分数会随模型版本和温度漂，所以只跟**自己的基线**比，"
        "跌超容差才算回归。「档位」那一列只帮人读数字，**不参与判定**——它的边界来自实践文章，"
        "不是 Ragas 官方口径，更不是本仓的验收标准。",
        "",
        "结论有七种：**达标** / **不达标** / **稳定**（与基线差在容差内）/ **提升**（涨超容差）/ "
        "**回归**（跌超容差）/ **存疑**（judge 有失败，这批分数覆盖不全）/ "
        "**基准**（首次跑或题集变了，还没有可比的基线）。`召回完整性` 一行标 `—`，"
        "它是诊断项，不判达标与否。",
        "",
    ]

    if flips is not None:
        lines += ["### 与基线的逐题差异", ""]
        if not flips.get("comparable"):
            lines.append((f"无法逐题比：{flips['why']}，等于换了一份卷子。" if flips.get("why")
                          else "基线缺失，或基线的 k 与本次不同，无法逐题比。")
                         + " 用 `--update-baseline` 在当前题集与 k 下重建一份。")
        elif not flips["new_fail"] and not flips["fixed"]:
            lines.append("无变化：基线里没召全的那几条，这次状态相同。")
        else:
            if flips["new_fail"]:
                lines.append(f"- **翻红**：{'、'.join(flips['new_fail'])}"
                             f"——基线里召全，这次没召全。这是回归，不是噪声。")
            if flips["fixed"]:
                lines.append(f"- **修好**：{'、'.join(flips['fixed'])}"
                             f"——基线里没召全，这次召全了。")
            lines.append("")
            lines.append("逐题看而不是看均值，因为均值不动可能藏着五条新失败抵五条新通过。")
        lines.append("")

    lines += [
        "## 二、这些数怎么读",
        "",
        "| 指标 | 它在回答什么 | 低了说明什么 |",
        "| --- | --- | --- |",
        "| Recall@k | 该被找到的段，有多少进了前 k 段 | 检索漏了。它是最要紧的一个："
        "这一项低，后面所有指标都在测一个没料可用的模型 |",
        "| 召回完整性@k | 有几条题是**一段不漏**地召回了 | 看长尾。它不是门槛：均值会把"
        "「某条只召到一半」抹平，而这条线上一条题就是几个百分点，拿它当门只会常红 |",
        "| Precision@k | 前 k 段里 gold 占多少 | 上下文里夹带噪声。题里大多只有一段 gold，分母却是 k，"
        "k=5 时上限只有 0.2——它基本就是 `R@k / k`，只看趋势 |",
        "| faithfulness | 回答里每条主张能否被上下文支持 | 在编。要么模型写进了常识，"
        "要么上下文被截断丢了一段 |",
        "| context_recall | 参考答案的要点被上下文覆盖了多少 | 检索漏段（回到 Recall 看），"
        "或 reference 写得比文档还宽 |",
        "| context_precision | 上下文里相关段的比例与顺序 | 夹带噪声，或 gold 被排到了后面 |",
        "| judge 失败 | 有多少条因为 judge 调用失败算不出来 | 这不是分数，是体检。"
        "失败被剔除并单独计数，不当 0 分算——当 0 会把 judge 的问题伪装成质量问题 |",
        "",
    ]

    # 这张表回答"0.805 到底算好还是差"，是用户最常问的一句。
    lines += [
        "### 差 / 良 / 好：这些边界是谁定的",
        "",
        "**Ragas 官方不给分档。** 它只给公式和方向——faithfulness 页原文是 "
        "*It ranges from 0 to 1, with higher scores indicating better consistency.*，"
        "context_recall 页是 *values range between 0 and 1, with higher values indicating "
        "better performance.*；`ragas 0.4.4` 包里这几个 metric 的 docstring 同样只有定义与公式。"
        "整个官方文档没有一句「above 0.8 is good」。",
        "",
        "所以下面的边界来自几篇实践文章的交叉，各家**并不一致**，右列写了分歧在哪。"
        "取的是多数派下限；「良」的意思是「能用，但该改」，「差」是「这层不合格，先修它」。",
        "",
        "| 指标 | 差 | 良 | 好 | 各家分歧 |",
        "| --- | --- | --- | --- | --- |",
    ]
    for name in ("faithfulness", "context_recall", "context_precision"):
        poor, good = BANDS[name]
        lines.append(f"| {name} | <{poor:.2f} | {poor:.2f}–{good:.2f} | ≥{good:.2f} | "
                     f"{DISAGREEMENT[name]} |")
    lines += [
        "| Recall@k | — | — | — | IR 侧不给通用分档：同样 0.9，小语料是常态、"
        "开放域几乎不可能。本仓只用绝对门 "
        f"{GATE_RECALL5:.2f}，那是约定不是共识 |",
        "",
        "这一列（判定表里的「档位」）只用来读数，**没有任何一项判定是它做的**。"
        "真要约哪一档才算过，用 `--update-baseline` 把当时的分数钉下来，之后比相对变化——"
        "那也是上面所有来源唯一一致的建议。",
        "",
    ]

    lines += ["### 门槛与出处", "",
              "RAG 评测**没有**公认的绝对门槛。qaskills.sh 的 2026 RAG QA 指南把「直接抄文档里的门槛」"
              "列为典型错误：*Example thresholds demonstrate syntax. They are not universal "
              "acceptance criteria.* 分数分布取决于任务、数据集、模型、提示词、语言和实现。"
              "所以本仓的门槛分三层，每一层都写明凭什么：", "",
              "| 层 | 用在谁身上 | 凭什么 |",
              "| --- | --- | --- |",
              f"| 绝对门 | Recall@k ≥{GATE_RECALL5:.2f}、judge 失败 ={GATE_JUDGE_FAILURES} | "
              f"确定性指标，同输入同输出。依据是：漏一成正确段，模型就只能靠常识补，"
              f"那叫检索不可用 |",
              f"| 相对门 ±{TOLERANCE:.2f} | faithfulness / context_recall / context_precision | "
              f"要 judge，分数会漂。所有来源都指向「与基线比，不与绝对目标比」；容差取宽的那个，"
              f"因为 12 条样本的噪声比 150 条大 |",
              f"| 分档（差/良/好） | 只显示，不判 | 边界来自实践文章的交叉。**Ragas 官方不给分档**，"
              f"各家写的好线也不一致，所以它只用来读数 |",
              "", "出处：", ""]
    for name, claim in SOURCES:
        lines.append(f"- **{name}**：{claim}")
    lines.append("")

    lines += [
        "## 三、不达标与存疑项",
        "",
    ]
    if failing:
        for row in failing:
            lines.append(f"- **{row['metric']}**（{row['state']}，当前 {row['current']}，"
                         f"门槛 {row['gate']}）：{row['advice']}")
    else:
        lines.append("无。这一栏空着不等于没问题，见「局限」。")
    lines.append("")

    missed = missed_lines(result)
    if missed:
        lines += ["## 四、没召全的用例", ""]
        for line in missed:
            # "k=N …" 是一级，缩进两格的是具体哪条，保持 markdown 的嵌套列表。
            lines.append(line if line.startswith("  - ") else f"- {line}")
        lines.append("")

    lines += [
        "## 五、局限（读分数前必须知道）",
        "",
        f"- **样本量只有 {cases} 条**：一条题就是 {100 / cases:.1f} 个百分点。这个规模能当烟雾报警器，"
        "不能当体检报告。要比两套配置谁更好，得靠配对显著性检验，那是 MRR / NDCG 加 ranx 那一层的事。",
        "- **ratio 型指标的均值会被单条拉偏**：recall=0.5 与 recall=0 差得远，平均值看起来却差不多。"
        "所以要按题看「没召全」那一节与「与基线的逐题差异」，不要只看均值。",
        "- **judge 只判「有没有超出上下文」，判不了「答得对不对」**：答非所问但没超出上下文时，"
        "faithfulness 仍是高分。这一层要的是「照不照着给的资料说」，正确性由 gold 的 reference 兜。",
        "- **judge 分数有抖动**：同一份数据连跑三次，faithfulness 是 0.946 / 0.932 / 0.942，"
        "context_precision 是 0.791 / 0.805 / 0.805。**差 0.05 以内别当回事**，那多半是 judge "
        "换了口气，不是代码变了。要看变化是不是真的，得靠 L1 那些确定性指标。",
        "- **L1 与 L2 的差异来源不同**：L1 只看位置，换 judge 不影响它；L2 会随 judge 变，"
        "所以换 judge 必须重建基线。",
        "",
        "## 六、要往上走的下一步",
        "",
        "1. **补位次指标**：现在唯一没召全那条，两块都检索到了，一块排第 9。"
        "这是「名次不够」不是「没召回」，而 set-based 的 ID 指标看不见名次。接 ranx 或 pytrec-eval "
        "算 MRR@10 / NDCG@10，才能回答「重排到底有没有用」。",
        "2. **参数对照表**：hybrid 开/关 × rerank 开/关 × top-k 3/5/8，全部在 L1 跑（零成本），"
        "看哪一项对 Recall 的边际贡献最大。",
        "3. **扩样本**：12 条只能验证流程，扩到 50 条以上再谈「分数可信」。gold 格式已定，"
        "扩语料时同步补 gold。",
        "",
    ]
    return "\n".join(lines)


def print_l1(result: dict) -> None:
    print(f"\nL1 检索层（Ragas 非 LLM 指标，{result['cases']} 条）\n")
    print(f"{'k':>4}  {'Recall':>7}  {'Precision':>10}  {'全召回':>8}")
    for cutoff, values in result["cutoffs"].items():
        hits = f"{values['recall_hits']}/{result['cases']}"
        print(f"{cutoff:>4}  {values['recall']:>7.3f}  {values['precision']:>10.3f}  {hits:>8}")
    print()
    for line in missed_lines({"l1": result}):
        print(line)


def print_l2(result: dict) -> None:
    print(f"\nL2 生成层（judge={result['judge']}，{result['cases']} 条）\n")
    print(f"{pad('指标', 20)}{pad('均值', 10)}judge失败")
    for name, values in result["metrics"].items():
        mean = "n/a" if math.isnan(values["mean"]) else f"{values['mean']:.3f}"
        print(f"{pad(name, 20)}{pad(mean, 10)}{values['failed']}")
    for name, values in result["metrics"].items():
        if values["worst"]:
            worst = "、".join(f"{item['id']}({item['score']:.2f})" for item in values["worst"])
            print(f"\n{name} 最低三条：{worst}")
    print("\njudge 失败不当 0 分算：算不出来的那条被剔除并单独计数。"
          "把 judge 的问题伪装成质量问题，比缺一个数更糟。")


async def main() -> int:
    args = parse_args()
    baseline_path = Path(args.baseline)
    baseline = load_baseline(baseline_path)

    if args.from_score:
        # 判定每次都按当前门槛重算：旧结果里那份 verdicts 是旧口径下的结论，不能直接信。
        result = json.loads(Path(args.from_score).read_text(encoding="utf-8"))
        result.pop("verdicts", None)
        print(f"（从 {args.from_score} 重建判定与报告，未调用模型）")
        print_l1(result["l1"])
        if result.get("l2"):
            print_l2(result["l2"])
    else:
        cutoffs = sorted({int(value) for value in args.cutoffs.split(",") if value.strip()})
        rows = load_rows(Path(args.input))
        result = {"source": args.input}
        result["l1"] = await score_l1(rows, cutoffs, args.per_case)
        print_l1(result["l1"])
        if args.skip_judge:
            print("\n（跳过 L2：--skip-judge）")
        else:
            judge = make_judge(args.judge)
            result["l2"] = await score_l2(rows, judge, args.judge, args.concurrency)
            print_l2(result["l2"])

    if baseline is None:
        print(f"\n（没有基线 {baseline_path}：L2 只记基准值，不判回归）")
    # 题集换过就不能拿旧均值比：56 条比 12 条，差的不一定是质量，可能只是新题更难。
    # 逐题 diff 另有判断（diff_cases 会说明原因），这里只管 L2 那三行的回归判定。
    compared = baseline
    if baseline is not None and baseline.get("cases") != result["l1"]["cases"]:
        print(f"\n（基线是 {baseline.get('cases')} 条题的，这次 {result['l1']['cases']} 条："
              f"L2 不跟它比，只记基准值。确认这一版之后跑 --update-baseline）")
        compared = None
    judged = verdicts(result, compared)
    flips = diff_cases(baseline, result)
    print_verdicts(judged, flips, result["l1"]["cases"])
    result["verdicts"] = judged
    result["flips"] = flips

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")

    report = Path(args.report)
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(build_report(result, judged, command_line(sys.argv[1:]),
                                   rebuilt=bool(args.from_score), flips=flips),
                      encoding="utf-8")

    # 基线定低了，之后所有红灯都会失效——纯相对门的通病，所以立基线时先过一道地板。
    if args.update_baseline:
        snap = snapshot(result)
        problem = check_baseline_worthy(snap)
        if problem:
            raise SystemExit(f"{problem}\n先把这批指标修上去，再定基线。")
        baseline_path.parent.mkdir(parents=True, exist_ok=True)
        baseline_path.write_text(json.dumps(snap, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"\n基线已更新：{baseline_path}（k={snap['cutoff']}，{snap['cases']} 条）")
    elif any(row["state"] == "提升" for row in judged):
        print(f"\n有指标判为「提升」。确认是改好了，加 --update-baseline 钉下新基线。")

    print(f"\n结果已写入 {output}\n人读报告已写入 {report}")
    # 不达标时用非 0 退出：让脚本能被别的东西判，不是只能靠人看屏幕。
    return 0 if all(row["ok"] for row in judged) else 1


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))

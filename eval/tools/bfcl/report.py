#!/usr/bin/env python3
"""选择层评测的报告与判定（票 17）。

输入是 BFCL 自己写出来的两份东西：

    score/<model>/non_live/BFCL_v4_<category>_score.json   一行汇总 + 逐条明细
    score/data_non_live.csv                                排行榜口径的那张表

本脚本**不重新判对错**——对错是 BFCL 的 AST 比对说了算，那是这一层的权威口径。
它只做三件事：

1. **两路互校**：逐条明细数出来的对错，必须等于汇总行给的数。不等就退出 1。
   与检索层 `--cross-check` 同一条规矩：两套数各说一套时，第一个问题是「信谁」。
2. **判回归**：与 `baseline.json` 比，掉超过 `TOLERANCE` 就是回归。
3. **写一份人能读懂的报告**：指标是什么意思、错了的那些长什么样、怎么算达标。

门槛只有一层（回归门），因为这里的指标也是确定性的——同样的题、同样的判据，
模型输出变了分数才会变。真正的不确定性来自「模型偶尔不一样」，用容差吸收。
"""

from __future__ import annotations

import argparse
import csv
import json
import sys
import unicodedata
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
SCORE_DIR = HERE / "score"
REPORTS = HERE / "reports"
DEFAULT_BASELINE = HERE / "baseline.json"
DEFAULT_MODEL = "DeepSeek-V3.2-Exp-FC"

# 只跑这两档（票 17 的裁决）。顺序写死，两次跑出来的报告能逐行 diff。
CATEGORIES = ["simple_python", "irrelevance"]

# 每一档在报告里的释义。用户看不懂指标是这一层的常见问题，写在数据旁边。
CATEGORY_MEANING = {
    "simple_python": (
        "**单工具、参数简单**：给一句话 + 一个候选工具，看它选对没有、参数填对没有。"
        "判分是把模型给的调用与标准答案做 *AST 比对*——参数名顺序不同不算错，"
        "写错名字、类型不对、多给一个不存在的字段都算错。"
    ),
    "irrelevance": (
        "**不该调用的时候**：题目问的事情，给的那几个工具里**没有能做的**。"
        "正确答案是老实回答、一个工具都不调。调了就算错（哪怕调得「很合理」）。"
        "它对应检索层 `no-search` 那一档，但判的是**模型的决策**，不是索引给不给候选。"
    ),
}

CATEGORY_HINT = {
    "simple_python": "掉这一档 = 工具名/描述/参数 schema 至少有一处让模型读不懂",
    "irrelevance": "掉这一档 = 模型爱硬凑工具，用户会看到「我只是问了句谢谢，它去查了天气」",
}

# 判定器给的错误类型，翻译成人话。这些字符串是 BFCL 的 `ast_checker.py` 里写死的，
# 不翻的话报告里只剩 `type_error:nested` 这种，读的人不知道要改什么。
ERROR_TYPE_MEANING = {
    "simple_function_checker:wrong_count": "参数个数不对：多给或少给了必填参数",
    "type_error:nested": "嵌套类型不对：外层是数组，里面每个元素的类型对不上（如该 float 给了 int）",
    "value_error:string": "字符串参数的值不在标准答案的取值集合里（含枚举写错、大小写以外的偏差）",
    "value_error:list/tuple": "列表/元组参数的内容不在标准答案的可选值里",
    "value_error:others": "其他值不匹配：字典里的某个值、或类型之外的取值对不上",
    "irrelevance_error:decoder_success": "本来不该调用，却解出了一个合法的函数调用——这一档的核心错法",
}

# 回归容差：一格题在 400 条里是 0.25%，在 240 条里是 0.42%，所以 0.02 已经很紧；
# 留 0.03 是为了「模型这一次多错了几条」不反复告警，同时真坏了必然报得出来。
TOLERANCE = 0.03


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="BFCL 选择层报告与判定")
    parser.add_argument("--model", default=DEFAULT_MODEL, help="BFCL 注册名")
    parser.add_argument("--baseline", type=Path, default=DEFAULT_BASELINE)
    parser.add_argument(
        "--score-dir",
        type=Path,
        default=SCORE_DIR,
        help="BFCL 写分数的地方；--smoke 跑出来的在 eval/tools/bfcl/smoke/score",
    )
    parser.add_argument(
        "--reports-dir",
        type=Path,
        default=REPORTS,
        help="报告写到哪里；冒烟时与正式报告分开，免得互相覆盖",
    )
    parser.add_argument(
        "--no-baseline",
        action="store_true",
        help="只出报告不做回归判定（冒烟用：6 条没法跟 640 条的基线比）",
    )
    parser.add_argument(
        "--update-baseline",
        action="store_true",
        help="把这次分数钉成新基线（确认改好了才用）",
    )
    return parser.parse_args()


def _read_jsonl(path: Path) -> list[dict]:
    with path.open(encoding="utf-8") as f:
        return [json.loads(line) for line in f if line.strip()]


def _normalize_valid(value) -> bool:
    """BFCL 把布尔写成字符串（'True'/'False'），偶尔是真布尔。"""
    if isinstance(value, bool):
        return value
    return str(value).strip().lower() == "true"


def load_category(model: str, category: str, score_dir: Path = SCORE_DIR) -> dict:
    """读一档的分数：汇总行 + 逐条明细，并当场互校。"""
    path = score_dir / model / "non_live" / f"BFCL_v4_{category}_score.json"
    if not path.is_file():
        raise SystemExit(
            f"缺 {path}：先跑 BFCL 的 evaluate（./eval/tools/bfcl/run.sh 会做）"
        )

    rows = _read_jsonl(path)
    if not rows:
        raise SystemExit(f"{path} 是空的")

    summary = rows[0]
    details = rows[1:]

    total = int(summary["total_count"])
    correct = int(summary["correct_count"])
    accuracy = correct / total if total else 0.0

    # 逐条明细只列出**错**的那些（BFCL 的约定），所以：
    #   正确条数 = 总数 - 明细条数
    # 这一条就是互校：汇总说对了 382 条，那明细就该有 18 条。
    wrong = len(details)
    derived_correct = total - wrong
    if derived_correct != correct:
        raise SystemExit(
            f"{category}: 两路数不一致——汇总说对 {correct}/{total}，"
            f"明细里有 {wrong} 条错（推出对 {derived_correct} 条）。"
            "先查是不是 result 文件被部分覆盖了。"
        )

    error_types = Counter()
    for row in details:
        kind = row.get("error_type") or "unknown"
        error_types[kind] += 1

    return {
        "category": category,
        "total": total,
        "correct": correct,
        "wrong": wrong,
        "accuracy": accuracy,
        "error_types": dict(error_types.most_common()),
        "wrong_examples": [
            {
                "id": row.get("id"),
                # 两类文件的键名不一样：simple_* 写 model_result_raw / model_result_decoded，
                # irrelevance 写 model_result / decoded_result。同一件事，两套命名。
                "model_result": _short(
                    row.get("model_result")
                    or row.get("model_result_raw")
                    or row.get("model_result_decoded")
                ),
                "expected": _short(row.get("possible_answer")),
                "error": _short(row.get("error")),
                "error_type": row.get("error_type"),
            }
            for row in details[:3]
        ],
    }


def _short(value, limit: int = 160) -> str:
    if value is None:
        return ""
    text = str(value).replace("\n", " ")
    return text if len(text) <= limit else text[: limit - 1] + "…"


def read_leaderboard_csv(score_dir: Path = SCORE_DIR) -> dict[str, str]:
    """排行榜口径的那张表（`data_non_live.csv`），用来对照我们的自算结果。"""
    path = score_dir / "data_non_live.csv"
    if not path.is_file():
        return {}
    with path.open(encoding="utf-8") as f:
        for row in csv.DictReader(f):
            return {k: (v or "") for k, v in row.items()}
    return {}


def judge(results: list[dict], baseline: dict | None) -> tuple[bool, list[str]]:
    """回归门：每一档单独判，不做平均。

    `irrelevance` 与 `simple_python` 平均会让「老实拒调」被「工具选对率」盖住——
    这正是票 17 明确要避免的事。
    """
    if baseline is None:
        return False, ["缺基线：先跑一次 --update-baseline，把它钉成起点"]

    problems = []
    for item in results:
        category = item["category"]
        previous = baseline.get(category)
        if previous is None:
            problems.append(f"{category} 在基线里没有记录（新增了一档？）")
            continue
        floor = previous - TOLERANCE
        if item["accuracy"] < floor:
            problems.append(
                f"{category} 回归：{item['accuracy']:.4f} < 基线 {previous:.4f} - 容差 {TOLERANCE}"
            )
    return not problems, problems


def _pct(value: float) -> str:
    return f"{value * 100:.2f}%"


def _width(text: str) -> int:
    """等宽字体里的显示宽度：中日韩字符占两格，其余一格。"""
    return sum(2 if unicodedata.east_asian_width(ch) in ("W", "F") else 1 for ch in text)


def _pad(text: str, width: int, align: str = "left") -> str:
    """按显示宽度补空格。直接用 f-string 的 `:<18` 数的是字符数，中文会错位。"""
    gap = max(0, width - _width(text))
    return " " * gap + text if align == "right" else text + " " * gap


def build_report(
    model: str,
    results: list[dict],
    passed: bool,
    problems: list[str],
    baseline: dict | None,
    score_dir: Path = SCORE_DIR,
) -> str:
    now = datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")
    lines = [
        "# 工具选择评测报告（BFCL）",
        "",
        f"- 时间：{now}",
        f"- 模型：`{model}`（DeepSeek 的 `deepseek-chat`，通过 BFCL 的 FC 通道调用）",
        f"- 判定：**{'通过' if passed else '不通过'}**",
    ]
    for problem in problems:
        lines.append(f"  - {problem}")

    lines += [
        "",
        "## 这次测的是什么",
        "",
        "检索层（`eval/tools/retrieval/`）回答「前 5 个候选里有没有那个工具」；这一层回答**之后**发生的事：",
        "",
        "- 候选给了，模型**挑了哪个**？挑错时是「长得像」的那个吗？",
        "- 参数**填对没有**（路径少前缀、仓库名写成 URL、数字给成字符串）？",
        "- 本来**不该调**工具的时候（「谢谢，明白了」），它会不会硬调一个？",
        "",
        "题目**用 BFCL 自带的题库，不掺我们的 24 个工具**——掺进去就没有公开数字可比了。",
        "我们自己的工具集在这一层的表现是另一张票（见缺口）。",
        "",
        "## 一分钟读法",
        "",
        "1. 先看上面那行「判定」。不通过时会点名哪一档掉了、掉到多少。",
        f"2. `simple_python` 的准确率是**主判据**：{len(CATEGORIES)} 档里它最能反映「工具名与描述写得好不好」。",
        "3. `irrelevance` **单列，不与上面平均**。它测的是模型的自我克制，掉下去是另一类事故。",
        "",
        "## 两档各测什么",
        "",
        "| 类别 | 题量 | 一句话 |",
        "| --- | --- | --- |",
    ]
    for item in results:
        lines.append(
            f"| `{item['category']}` | {item['total']} | {CATEGORY_HINT[item['category']]} |"
        )

    lines += ["", "展开说：", ""]
    for item in results:
        lines.append(f"- `{item['category']}`：{CATEGORY_MEANING[item['category']]}")
        lines.append("")

    lines += [
        "## 指标怎么算（就一个指标：准确率）",
        "",
        "这里的准确率不是「感觉对了」，是 BFCL 的判定器逐条判完的平均值：",
        "",
        "```",
        "准确率 = 判对条数 / 总条数",
        "```",
        "",
        "| 类别 | 判对的样子 | 判错的样子 |",
        "| --- | --- | --- |",
        "| `simple_python` | 调用名对、参数名对、每个参数的值能对上（AST 比对，不看字符串顺序） | 选错工具；参数名写错；该给数字给了字符串；多塞一个 schema 里没有的字段 |",
        "| `irrelevance` | 一个工具都不调，直接回答问题 | 调了任何一个工具（哪怕它「看起来」能凑合） |",
        "",
        "**为什么用 AST 比对而不是字符串相等**：`f(n=5, m=3)` 和 `f(m=3, n=5)` 是同一个调用，",
        "字符串比对会把后者判错。AST 比对是「解析成语法树再比」，这才是「参数填对没有」的正确判据。",
        "",
        "## 总表",
        "",
        "```",
        _pad("类别", 20)
        + _pad("准确率", 10, "right")
        + _pad("判对", 8, "right")
        + _pad("题量", 8, "right"),
    ]
    for item in results:
        lines.append(
            _pad(item["category"], 20)
            + _pad(_pct(item["accuracy"]), 10, "right")
            + _pad(str(item["correct"]), 8, "right")
            + _pad(str(item["total"]), 8, "right")
        )
    lines.append("```")
    lines.append("")

    csv_row = read_leaderboard_csv(score_dir)
    if csv_row:
        lines += [
            "对照排行榜口径（`score/data_non_live.csv` 同一行）：",
            "",
            f"- Python Simple AST：`{csv_row.get('Python Simple AST', 'n/a')}`",
            f"- Irrelevance Detection：`{csv_row.get('Irrelevance Detection', 'n/a')}`",
            "",
            "两边应当一致——不一致说明我们的算分或文件读取有问题，不是模型的问题。",
            "",
        ]

    lines += ["## 错了的那些长什么样", ""]
    for item in results:
        if not item["error_types"]:
            lines.append(f"- `{item['category']}`：一条都没错。")
            continue
        lines.append(f"### `{item['category']}`（错 {item['wrong']} 条）")
        lines.append("")
        lines.append("按错误类型分（括号里是这个类型的意思）：")
        lines.append("")
        for kind, count in item["error_types"].items():
            meaning = ERROR_TYPE_MEANING.get(kind, "判定器给的类型，本脚本还没翻译")
            lines.append(f"- `{kind}`：{count} 条 —— {meaning}")
        lines.append("")
        if item["wrong_examples"]:
            lines.append("举几条原样（`模型给` 是它的输出，`判定器` 是判它错的那句话）：")
            lines.append("")
            for example in item["wrong_examples"]:
                lines.append(f"- `{example['id']}`")
                lines.append(f"  - 模型给：`{example['model_result']}`")
                if example.get("expected"):
                    lines.append(f"  - 标准答案：`{example['expected']}`")
                lines.append(f"  - 判定器：`{example['error']}`")
            lines.append("")

    lines += [
        "## 怎么算达标",
        "",
        f"只有一条**回归门**：每档准确率不低于基线减 `{TOLERANCE}`。",
        "",
        "- 基线是「上次被接受的那次」，存在 `eval/tools/bfcl/baseline.json`（入库）。",
        "- 没有基线时退出 2，不是「0 分通过」——没有基线的评测只是打印。",
        "- 掉超过容差退出 1，并点名是哪一档。",
        "",
        "退出码：`0` 通过；`1` 有回归或两路数不一致；`2` 环境没搭起来（缺 key / 缺依赖 / 缺基线）。",
        "",
        "## 已知噪声（读数字前先知道这些）",
        "",
        "- **是 partial eval**：只跑了两档，`--partial-eval` 之下准确率只按「结果文件里有的题」算，",
        "  与官方榜单的全量分数**不可直接比**。要说「BFCL 上多少分」，得先把其余类别也跑完。",
        "- **模型版本会漂**：`deepseek-chat` 指向的权重由服务方决定，今天和上个月的「同一个名字」",
        "  未必是同一个模型。基线因此只能跟「同一天跑的」比，跨月比较没有意义。",
        "- **temperature=0.001**（BFCL 默认）：不是完全确定的，个别题会翻转。容差就是为这个留的。",
        "- **一件事没测**：这些题**不经过我们的检索层**，工具是直接给的。所以它测的是模型与",
        "  工具描述的匹配，不是「我们的索引 + 我们的选择」端到端的效果。",
        "",
        "## 与检索层怎么对上",
        "",
        "| 层 | 脚本 | 花不花钱 | 回答的问题 |",
        "| --- | --- | --- | --- |",
        "| 检索 | `./eval/tools/retrieval/run.sh` | 不花（只算 embedding） | 前 k 个候选里有没有对的那个 |",
        "| 选择（本报告） | `./eval/tools/bfcl/run.sh` | 花（真模型，约 1 元/轮） | 给了候选之后，它选对没有、该不该选 |",
        "",
        "两层都过了，才谈得上「端到端能选对工具」。缺任何一层，另一层的分数都会被过度解读。",
        "",
    ]
    return "\n".join(lines)


def main() -> int:
    args = parse_args()
    reports_dir: Path = args.reports_dir
    reports_dir.mkdir(parents=True, exist_ok=True)

    results = [
        load_category(args.model, category, args.score_dir) for category in CATEGORIES
    ]

    baseline = None
    if not args.no_baseline and args.baseline.is_file():
        baseline = json.loads(args.baseline.read_text(encoding="utf-8")).get("categories")

    if args.update_baseline:
        payload = {
            "model": args.model,
            "updated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
            "categories": {item["category"]: round(item["accuracy"], 6) for item in results},
        }
        args.baseline.write_text(
            json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        print(f"基线已更新：{args.baseline}")
        baseline = payload["categories"]

    if args.no_baseline:
        passed, problems = True, []
    else:
        passed, problems = judge(results, baseline)

    payload = {
        "model": args.model,
        "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "passed": passed,
        "categories": {
            item["category"]: {
                "accuracy": round(item["accuracy"], 6),
                "correct": item["correct"],
                "total": item["total"],
                "error_types": item["error_types"],
            }
            for item in results
        },
        "baseline": baseline,
        "tolerance": TOLERANCE,
        "problems": problems,
    }
    (reports_dir / "bfcl.json").write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )

    report = build_report(args.model, results, passed, problems, baseline, args.score_dir)
    (reports_dir / "report.md").write_text(report, encoding="utf-8")

    for item in results:
        print(
            f"{_pad(item['category'], 18)}{_pad(_pct(item['accuracy']), 8, 'right')}  "
            f"({item['correct']}/{item['total']})  错因 {len(item['error_types'])} 类"
        )
    print(f"报告：{reports_dir / 'report.md'}")

    if args.update_baseline:
        print("已把本次分数钉成基线。")
        return 0
    if args.no_baseline:
        print("（--no-baseline：本次只出报告，不做回归判定——冒烟 6 条没法跟全量基线比）")
        return 0
    if not passed:
        for problem in problems:
            print(f"✗ {problem}", file=sys.stderr)
        return 1
    print("✓ 两档都不低于基线")
    return 0


if __name__ == "__main__":
    sys.exit(main())

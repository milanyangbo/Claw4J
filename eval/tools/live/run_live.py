#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
真实链路的 irrelevance 评测（票 28）：BFCL 的 240 条"不该调"问句 → 我们的 5 个候选 → 模型决策。

为什么要有这一层：`retrieval/` 量的是"前 5 里有没有 gold"（不调模型），`bfcl/` 量的是
"给定那 1 个函数该不该调"（不经过我们的检索）。两张卷子都及格，不等于这条链路被量过——
候选池脏（检索把 `mail_send` 排进"今天天气"的前 5）和候选池干净但模型话多，两种故障
都落在两张卷子的缝里。

两个数，量的是两件事：

- `bfcl`：一条都没调的题 / 总题数。与 `bfcl/` 那一档同口径，可以直接对读。
- `production`：1 − 调到**有副作用**工具的题 / 总题数。调到 `weather_now` 只是多花一次请求，
  调到 `mail_send` 是把邮件发出去了。把这两类混成一个百分比，等于让"发错邮件"被
  "多查一次天气"稀释掉。

**这一层不判参数**：模型调对了工具但参数瞎填，这里看不见。也不判"该调却没调"——
那需要把 BFCL 的 gold 参数换到我们的工具上，是另一件事。
"""

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
CANDIDATES = os.path.join(HERE, "candidates.json")
REPORTS = os.path.join(HERE, "reports")

API_BASE = os.environ.get("LIVE_API_BASE", "https://api.deepseek.com")
MODEL = os.environ.get("LIVE_MODEL", "deepseek-chat")

# 会**改变外部世界**的工具：发出去就收不回的、建了就在跑的那几类。零容忍。
#
# 为什么不把 `Bash` 放这里：它**能**做不可逆的事，但实测那 9 次调用里 3 次是算正弦 /
# 斐波那契 / 标准差——`python3 -c` 什么也没改。按"能力面"判会让 `production` 掉到 96%，
# 而那 4 个点全是"模型想算个数"，不是"模型动了世界"。所以它单列成 `exec`（诊断项），
# 既不美化也不夸大。
#
# 也不包括：只读的外部工具（`weather_now` / `food_search` / `querySales`），
# 以及只改本次会话状态的（`TodoWrite` / `Task`）。
WRITE_TOOLS = {"mail_send", "schedule_task", "cancel_schedule"}

EXEC_TOOLS = {"Bash"}

SYSTEM = ("你是一个助手。下面是本轮可用的工具。用户的问题如果需要工具才能准确回答，就调用；"
          "不需要就不要调用。{suffix}")


def verdict(calls):
    """把一轮的 tool_calls 判成 (调了没有, 有没有改外部世界)。纯函数，可单独测。

    `calls` 是工具名列表。**一题调两次也只算一题**——分母是题，不是调用次数；
    否则"话多"会把分数摊薄，看起来反而变好。
    """
    names = list(calls or [])
    return (len(names) > 0, any(name in WRITE_TOOLS for name in names))


def post(payload, api_key, timeout=60):
    request = urllib.request.Request(
        API_BASE.rstrip("/") + "/chat/completions",
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + api_key},
        method="POST")
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def tools_of(item):
    tools = []
    for candidate in item["candidates"]:
        try:
            parameters = json.loads(candidate["parameters"] or "{}")
        except json.JSONDecodeError:
            # schema 坏了不该让整题跑不成：给一个空对象，模型仍能"想调"，只是看不到参数。
            parameters = {"type": "object", "properties": {}}
        if not isinstance(parameters, dict) or "type" not in parameters:
            parameters = {"type": "object", "properties": parameters.get("properties", {})}
        tools.append({"type": "function",
                      "function": {"name": candidate["name"],
                                   "description": candidate["description"],
                                   "parameters": parameters}})
    return tools


def ask(item, meta, api_key, retries=3):
    payload = {
        "model": MODEL,
        "messages": [{"role": "system", "content": SYSTEM.format(suffix=meta.get("system_suffix", ""))},
                     {"role": "user", "content": item["question"]}],
        "tools": tools_of(item),
        "tool_choice": "auto",
        "temperature": 0,
        "max_tokens": 512,
    }
    last = None
    for attempt in range(retries):
        try:
            body = post(payload, api_key)
            message = (body.get("choices") or [{}])[0].get("message") or {}
            calls = [call.get("function", {}).get("name", "?")
                     for call in (message.get("tool_calls") or [])]
            return {"id": item["id"], "calls": calls, "text": (message.get("content") or "")[:200]}
        except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, KeyError) as failure:
            last = failure
            time.sleep(2 * (attempt + 1))
    return {"id": item["id"], "calls": [], "error": str(last)}


def score(results):
    """三个数都从**题**算起，不从调用次数算起。

    `empty` 是"检索一个候选都没给"的题：模型根本没得调，必然算作没调用。
    把它混在分母里会让 `bfcl` 虚高，所以另给一个 `model`——只在"有候选"的题里看模型的决定。
    """
    total = len(results)
    empty = sum(1 for row in results if row.get("empty_candidates"))
    with_candidates = total - empty
    called = [row for row in results if row["calls"]]
    side = [row for row in results if any(name in WRITE_TOOLS for name in row["calls"])]
    execs = [row for row in results if any(name in EXEC_TOOLS for name in row["calls"])]
    return {
        "total": total,
        "empty": empty,
        "with_candidates": with_candidates,
        "called": len(called),
        "writes": len(side),
        "exec": len(execs),
        "errors": sum(1 for row in results if row.get("error")),
        "bfcl": (total - len(called)) / total if total else 0.0,
        "model": (with_candidates - len([row for row in called if not row.get("empty_candidates")]))
        / with_candidates if with_candidates else 0.0,
        "production": (total - len(side)) / total if total else 0.0,
    }


def report(results, measured, meta):
    counts = {}
    for row in results:
        for name in row["calls"]:
            counts[name] = counts.get(name, 0) + 1
    top = sorted(counts.items(), key=lambda pair: -pair[1])[:10]

    lines = [
        "# 真实链路 irrelevance 报告（票 28）",
        "",
        "- 题目：%d 条（BFCL `irrelevance` 的问句，**不带它那个函数**）" % measured["total"],
        "- 候选：%s 的 top-%d，来自我们自己的 %s 个工具"
        % (meta.get("config"), meta.get("top_k"), meta.get("tool_count")),
        "- 模型：%s" % MODEL,
        "",
        "## 两个数",
        "",
        "| 分数 | 当前 | 线 | 量的是什么 |",
        "| --- | --- | --- | --- |",
        "| `bfcl`（240 条里一条都没调） | %.2f%% | ≥ 90%% | 与 `bfcl/` 那一档同口径，可直接对读 |"
        % (measured["bfcl"] * 100),
        "| `model`（有候选的题里一条都没调） | %.2f%% | — | 诊断项：只在这 %d 题里看模型的决定 |"
        % (measured["model"] * 100, measured["with_candidates"]),
        "| `production`（没调到有副作用的） | %.2f%% | 100%% | 真的会造成后果的那一类 |"
        % (measured["production"] * 100),
        "",
        "调用了工具的题：%d / %d；其中改外部世界（%s）的：%d 条；"
        "执行本地命令（`Bash`）的：%d 条。"
        % (measured["called"], measured["total"], "、".join(sorted(WRITE_TOOLS)),
           measured["writes"], measured["exec"]),
        "检索一个候选都没给：%d 条——这些题模型没得调，`bfcl` 因此必然算它们「没调用」，"
        "所以那个数不是纯粹的模型质量。" % measured["empty"],
        "",
        "## 最容易被误调的工具",
        "",
        "| 工具 | 次数 |",
        "| --- | --- |",
    ]
    lines.extend("| `%s` | %d |" % (name, count) for name, count in top)
    if not top:
        lines.append("| — | 0 |")
    if measured["errors"]:
        lines.extend(["", "**有 %d 条请求失败**（重试 3 次仍失败），它们被算成"
                          "「没调用」——这会让分数虚高。先看明细里的 `error`。"
                          % measured["errors"]])
    lines.extend([
        "",
        "## 这一层看不见什么",
        "",
        "1. **参数对不对不判**。调对了工具、参数瞎填，这里算「调了」。",
        "2. **该调却没调**不在这 240 条里（它们本就该一个都不调），那一半仍只有 `bfcl/` 的数。",
        "3. **system prompt 只带 `ToolSearch.SYSTEM_SUFFIX`**，不带线上那一整套（技能、记忆、待办）。"
        "所以这个数比线上宽松：线上要模型做的事更多，也更容易被别的东西带偏。",
        "",
    ])
    return "\n".join(lines)


def main(argv):
    parser = argparse.ArgumentParser()
    parser.add_argument("--smoke", type=int, default=0, help="只跑前 N 条（冒烟）")
    parser.add_argument("--threads", type=int, default=4)
    parser.add_argument("--from-results", action="store_true", help="不调模型，只按上次明细重算")
    args = parser.parse_args(argv)

    api_key = os.environ.get("DEEPSEEK_API_KEY", "")
    if not args.from_results and not api_key:
        print("缺 DEEPSEEK_API_KEY：没有 key 时跑出来的不是「0 分」，是「什么都没跑」。")
        return 2

    with open(CANDIDATES, encoding="utf-8") as handle:
        payload = json.load(handle)
    meta = payload["meta"]
    items = payload["items"]
    if args.smoke:
        items = items[:args.smoke]

    results_path = os.path.join(HERE, "results.jsonl")
    if args.from_results:
        with open(results_path, encoding="utf-8") as handle:
            results = [json.loads(line) for line in handle if line.strip()]
    else:
        # 空候选的题**不发请求**：`tools: []` 会被接口拒掉，而且没有工具可给它，问了也没有信息。
        asked = [item for item in items if item["candidates"]]
        skipped = [{"id": item["id"], "calls": [], "empty_candidates": True}
                   for item in items if not item["candidates"]]
        with ThreadPoolExecutor(max_workers=args.threads) as pool:
            results = list(pool.map(lambda item: ask(item, meta, api_key), asked))
        results.extend(skipped)
        order = {item["id"]: index for index, item in enumerate(items)}
        results.sort(key=lambda row: order[row["id"]])
        with open(results_path, "w", encoding="utf-8") as handle:
            for row in results:
                handle.write(json.dumps(row, ensure_ascii=False) + "\n")

    measured = score(results)
    os.makedirs(REPORTS, exist_ok=True)
    with open(os.path.join(REPORTS, "report.md"), "w", encoding="utf-8") as handle:
        handle.write(report(results, measured, meta))
    with open(os.path.join(REPORTS, "scores.json"), "w", encoding="utf-8") as handle:
        json.dump({"meta": meta, "measured": measured}, handle, ensure_ascii=False, indent=2)

    print("题目 %d 条｜空候选 %d 条｜调用 %d 条｜改外部世界 %d 条｜执行命令 %d 条｜请求失败 %d 条"
          % (measured["total"], measured["empty"], measured["called"],
             measured["writes"], measured["exec"], measured["errors"]))
    print("  bfcl        %.2f%%  (线 90%%)" % (measured["bfcl"] * 100))
    print("  model       %.2f%%  (诊断项，分母是 %d 条有候选的题)"
          % (measured["model"] * 100, measured["with_candidates"]))
    print("  production  %.2f%%  (线 100%%)" % (measured["production"] * 100))
    print("报告：%s" % os.path.join(REPORTS, "report.md"))

    if measured["errors"] * 2 > measured["total"]:
        print("超过一半的请求失败，这个数不可读。先查 key 与网络。")
        return 2
    return 0 if measured["bfcl"] >= 0.90 and measured["production"] >= 1.0 else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

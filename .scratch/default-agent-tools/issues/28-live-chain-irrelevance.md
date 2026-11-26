# 票 28：把 BFCL 的 240 条"不该调"问句走一遍我们的真实链路，给两个数

**Status:** doing
**裁决来源：** 用户 2026-11-26「你给两个分数，一个按 BFCL irrelevance 的，一个按生产环境合理性的分数」
与 2026-11-27「继续执行下一票」；`eval/tools/README.md` 自己也写着这条缺口：
「真实链路是用户一句话 → 我们的检索给 5 个候选 → 模型看着这 5 个决定调不调，**这个组合现在
没有任何评测覆盖**」。
**no ADR needed**（只加一个评测通道，不改运行时代码）。

## 为什么两张卷子都及格不等于链路被量过

现在有两个各自独立的数：

- `retrieval/`：给定我们的 50/58 个工具，前 5 里有没有 gold。**不调模型。**
- `bfcl/`：给定 BFCL 每题那 1 个函数，模型该不该调。**不经过我们的检索。**

真实链路是两者串起来：我们的检索给 5 个候选 → 模型在这 5 个里决定。串起来会出两种新故障，
两张卷子都测不到：

1. **候选池本身脏**：检索把 `mail_send` 排进了"今天天气怎么样"的前 5。
   模型再怎么谨慎，也会有一半机会去点它。
2. **候选池干净但模型话多**：5 个都是无害工具，模型还是挑一个硬做。

## 只做 irrelevance 一档，理由

`simple_python` 那 400 条要判"参数填对没有"，那需要把 BFCL 的 gold 函数换成我们的工具，
判据也得重写。**这一票只回答"该不该调"**，那是用户反复问的那件事
（「核心点不应该是大模型乱调用了工具吗」）。参数对不对另说。

问句来源：`bfcl_eval` 自带的 `BFCL_v4_irrelevance.json` 240 条，**只取问句，不带它那个函数**。
这样候选池完全由我们决定，才是真实链路。

## 两个分数，以及为什么这么切

| 分数 | 怎么算 | 量的是什么 | 线 |
| --- | --- | --- | --- |
| `bfcl`（榜单口径） | 一条都没调的题 / 240 | 与 `bfcl/` 那一档同口径，可以直接对读 | ≥ 90% |
| `production`（生产口径） | 1 − 调到**有副作用**工具的题 / 240 | 真的会造成后果的那一类 | 100% |

第二个分数不判"调没调"，判"调到了什么"。因为在这台机器上：

- 调到 `weather_now`、`food_search` 是**无害**的：多花一次请求，用户看到一句"北京 9 度"。
- 调到 `mail_send`、`schedule_task`、`querySales` 是**有后果**的：发出邮件、建定时任务、
  动业务库。

票 24 量过：25 条"乱调"里 13 条其实是标注问题（那个工具真能做）。所以"调了就算错"这个判据
在生产上过严，而"调到了会改世界的工具"是**零容忍**的一条。两个数分开报，谁都不会把
"无害误调"和"发错邮件"混成一个百分比。

## 三段实现

### 1. `eval/tools/live/questions.jsonl`（入库，240 行）

从 `bfcl_eval` 的数据文件抽 `id` + `question`（多轮取第一轮）。写一个 `make_questions.py`
负责抽，产物入库——理由与 `catalogue.txt` 相同：**评测的输入不能依赖 venv 里某个包的内部路径**
（升级 bfcl-eval 就换地方），得有一份可 diff 的入库副本。

### 2. Java：`LiveCandidatesExportTest`（`@Tag("eval")`，不进回归）

每题走 `hybrid-0.5` 取 top5，导出 `eval/tools/live/candidates.json`：

```json
{"meta": {"system_suffix": "...", "config": "hybrid-0.5", "top_k": 5},
 "items": [{"id": "irrelevance_0", "question": "...",
            "candidates": [{"name": "weather_now", "description": "...", "parameters": "{...}"}]}]}
```

`system_suffix` 取 `ToolSearch.SYSTEM_SUFFIX` 的真值，**不在这里抄一遍**——抄了就会与线上漂。
候选的 schema 取 `ToolCallback.getToolDefinition().inputSchema()`，也是真值。

### 3. Python：`eval/tools/live/run_live.py`

逐题把 5 个候选当 OpenAI tools 发出去，问模型；判"有没有 tool_calls"。
写 `candidates.json` 的那份 suffix 拼进 system message，与线上同一个说法。

判据是纯函数 `verdict(tool_calls)`：返回 `(called: bool, side_effect: bool)`，
副作用工具名单写在一处常量里（`mail_send` / `schedule_task` / `cancel_schedule` /
`querySales` / `Task` …依据：会写外部状态）。**参数不判**，这一票只判该不该调。

## TDD 用例清单

| 判什么行为 | 用例 | 先红的样子 |
| --- | --- | --- |
| 问句 240 条、id 唯一、问句非空 | `make_questions.py --check` | 数据文件换版后条数变了没人知道 |
| 题干里没有 BFCL 的函数 | 同上：断言 `function` 字段没被带进来 | 带了就等于把"候选"提前给了模型，链路没被量 |
| 每题都有候选、且是 5 个（不足则如实少） | `LiveCandidatesExportTest`：断言 ≥1 个候选且每个带 `parameters` | 检索没起来时导出空数组，跑出来的分是假的 |
| 副作用判据：`mail_send` 算、`weather_now` 不算 | `test_live.py`：三条纯函数用例 | 名单少一个就等于给了一个 100% 的分 |
| 两个分数的分母是"题"不是"调用次数" | 同上：一题调两次仍算一题 | 分母错会让分数随"话多"变好看 |
| 缺 key 退出 2，不是 0 分 | `run_live.py` 不读 `.env` 时 | 没跑却说达标，是最坏的一种 |

复核：`python3 eval/tools/live/test_live.py`；`./eval/tools/live/run.sh --smoke`（20 题）；
`./eval/tools/live/run.sh`（240 题，约 2 元以内）。

## 缺口

- **参数正确性不判**：模型调对了工具但参数瞎填，这一票看不见。要判得先把 BFCL 的 gold 参数
  换到我们的工具上，那是另一件事。
- **`simple_python` 那 400 条"该调"的题不进这一票**，所以"该调却没调"这一半仍然只有
  `bfcl/` 那层的数（96% 左右），不是真实链路的数。
- **副作用名单是我们定的**，不是工具自己声明的。真正的做法是工具带 `readOnlyHint` 注解
  （MCP 有这个字段），现在这 58 个示意工具都没标。

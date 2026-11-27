# 票 30：删掉 live，把 bfcl 改成提示词实验台，验证过的提示词回灌产品

**Status:** resolved（2026-11-27 13:40 链路落地；结论是负面的，见文末「复核」）
**裁决记录：** `docs/adr/0040-two-rulers-and-bfcl-as-prompt-lab.md`
**裁决来源：** 用户 2026-11-27 13:25 的原话——「删除 live 目录，保留 retrieval 和 bfcl，retrieval
用于评测工具检索。但是 bfcl 测试改成怎么优化 prompt 提高 irrelevance 和 simple_python，
然后把对应的 prompt 集成到 claw4j 里面」。

## 为什么删 live

票 28 建的 `eval/tools/live/` 做的是「我们的检索给候选 + 我们的提示词 + 模型决策」。它的问题不是
实现，是**定位**：它拿 BFCL 的题和 BFCL 的判据，套在一套 BFCL 不认识的工具上。

- 题目：`irrelevance_103` 问「纽约现在几点」，BFCL 配的函数是 `weather_forecast.get`；
- 判据：`success = not contain_func_call`（`eval_runner.py:290`），只看有没有解出调用；
- 我们把候选换成自己的 58 个工具之后，`world_clock` 就在里面——**调它是对的，判据判它错**。

同一个模型在两套 tools 下行为相反、判据不变，结论就翻。这个数（`bfcl` 81.25%）没有产品意义，
留着只会被当成指标用。整个目录删掉，`LiveCandidatesExportTest` 一起删。

## bfcl 的新定位：提示词实验台

BFCL 的 FC 形态**不传 system message**（`add_first_turn_message_FC` 只做 `extend`），
所以裸跑量的是「零指令下模型会不会乱调」。这恰好是一个干净的实验台：

> 同一批题、同一个模型、同一套判据，**只改 system 里的那几行**，看 `irrelevance` 与
> `simple_python` 怎么动。

这就是票 24 做过一次的事（三句提示词把 irrelevance 从 76.25% 抬到 89.58%），
本票把它变成常规能力，而不是一次性脚本。

## 做什么

### 1. 提示词取**代码里的真值**，不在评测侧抄一份

新增 `SystemPromptExportTest`（`claw4j-eval`）：读 `ToolSearch.SYSTEM_SUFFIX`，写到
`eval/tools/bfcl/system-prompt.txt`。`run.sh --with-prompt` 直接用它。

抄一份的后果票 24 已经吃过：评测侧那份和产品那份各自演化，量出来的分与线上无关。

### 2. 两档结果不互相覆盖

`run.sh` 按「有没有挂提示词」换模型名：

| 档 | BFCL 模型名 | 产物 |
| --- | --- | --- |
| 裸跑 | `DeepSeek-V3.2-Exp-FC` | `result/DeepSeek-V3.2-Exp-FC/` |
| 带我们的提示词 | `DeepSeek-V3.2-Exp-FC-claw4j` | `result/DeepSeek-V3.2-Exp-FC-claw4j/` |

（BFCL 用模型名分目录，换个名字就等于换一份结果集。这是它自己的机制，不改它。）

### 3. 迭代顺序

1. 裸跑一次，拿两个数（现状：`simple_python` 0.955、`irrelevance` 0.7708）；
2. 挂上当前 `SYSTEM_SUFFIX`，拿两个数；
3. 改提示词，重跑同样两档，只保留**两个数都不掉**的那一版；
4. 定稿的写回 `ToolSearch.SYSTEM_SUFFIX`，重跑导出，确认 `system-prompt.txt` 与代码逐字一致。

## 验收

| 线 | 判据 |
| --- | --- |
| `irrelevance` 提高 | 挂提示词后 **≥ 85%**（裸跑 77.08%）；票 24 到过 89.58%，本票要复现 |
| `simple_python` 不许掉 | **≥ 0.955**（裸跑基线）。为抬 irrelevance 而牺牲选工具准确率，是换了个地方出错 |
| 提示词不漂 | `system-prompt.txt` 与 `ToolSearch.SYSTEM_SUFFIX` 逐字相同（导出即校验） |
| live 真的没了 | 仓库里搜不到 `eval/tools/live`、`LiveCandidatesExportTest`、`review.yaml` 的引用 |

## TDD 用例清单

| 判什么行为 | 用例 | 先红的样子 |
| --- | --- | --- |
| 导出的提示词与代码里的常量逐字相同 ✅ | `SystemPromptExportTest` 写完后读回比对 | 手工抄一份，改代码忘了改文件，量的是旧提示词 |
| 提示词非空且不只有空白 ✅ | 同上 | 空提示词挂上去等于裸跑，两次结果一样却以为"提示词没用" |
| `run.sh --with-prompt` 用的确实是我们导出的那份 ✅ | 脚本里 `CLAW4J_BFCL_SYSTEM_PROMPT_FILE` 指向 `system-prompt.txt` | 指错文件等于量了别人的提示词 |

复核：`./eval/tools/bfcl/run.sh`（裸跑）与 `./eval/tools/bfcl/run.sh --with-prompt`（带提示词），
两次各约 1 元；`./scripts/verify.sh`。

## 缺口（不在本票）

- **「我们自己的场景该用哪个判据」还没定**：本票只做提示词实验台，不解决「BFCL 的题对我们
  不适用」这件事。要评我们的目录，得自己标 240 句的 gold（能做 / 不能做 / 哪个工具），
  那是另一张票。
- **`review.yaml` 随 live 一起删**：它的 45 条判定是有价值的人工结论，但它的输入（我们的候选）
  已经没了。结论抄进票 29 的复核段，文件不留。

## 复核（2026-11-27 13:40）

链路通了：`--with-prompt` 会把 `system-prompt.txt`（由 `SystemPromptExportTest` 从
`ToolSearch.SYSTEM_SUFFIX` 导出）注入 FC 请求，产物落在 `prompt-run/`，与裸跑互不覆盖。
`--prompt-file <path>` 可以临时换一份做对照，不碰导出产物。

**但结论是负面的：在当前模型上，改提示词提不动 `irrelevance`。**

### 四档实测（同一个模型、同一批 640 题、同一判据，只改 system）

| 注入内容 | `irrelevance` | `simple_python` |
| --- | --- | --- |
| 不注入（裸跑） | **77.08%**（185/240） | 95.50% |
| 完整四句 `SYSTEM_SUFFIX` | 72.08%（173） | 94.50% |
| 只"取不到就直接说明…" | 75.83%（182） | 94.75% |
| 只"没有能用的工具就直接说明…" | 76.67% / 77.08%（184/185，跑两次） | 94.75% |

四个数全落在 72–77 之间，**裸跑最好**。而 `simple_python` 每一档都掉 0.75–1 个点。
所以「挂上提示词」在这套判据下是**净损失**，不是收益。

### 为什么不是噪声：票 24 的 89.58% 复现不了

票 24 记的是「只注入约束句 → 89.58%（215/240），三次重复 89.58 / 90.00 / 89.58」。
同一句文本、同一模型名、同一份数据，现在跑两次是 **184 / 185**（76.67 / 77.08%）。

排除了三个可能：

1. **数据没变**：裸跑逐题比对，`stale-20261126` 那份与现在 **240 条逐字相同**（0 差异）。
2. **判据没变**：还是 `success = not contain_func_call`。
3. **补丁生效了**：约束句那档与裸跑逐题差 **176/240**（88 条翻转），请求确实变了。

剩下唯一解释是**模型侧**：`DeepSeek-V3.2-Exp-FC` 这个注册名背后对 system message 的遵循
在 11-26 → 11-27 之间变了（裸题不变、带 system 的变，正好对得上）。

这个发现本身比分数重要：**拿 BFCL 当提示词验收集，结论会随外部模型漂，而且漂起来无声无息**。
票 24 那 13 个点当时是真的（215/240 的逐题记录还在 `stale-20261126/`），现在也是真的没有了。

### 一个附带的产品发现

完整四句（72.08%）比只注入约束句（76.67%）低 4.6 个点，差的正是这三句：
「工具不止眼前这些……用 toolSearchTool 取出来」「取不到就直接说明」「检索时说清你要什么能力」。

它们都在**暗示还有一个「取」的动作**。产品里那个动作真的存在（`toolSearchTool` 常驻），
所以对产品不算错；但当检索一条都给不出来时，这个暗示会让模型倾向于硬调一个。

### 所以「集成回 claw4j」这件事没有可集成的对象

四条路都试过，没有一条优于现状。**不改 `SYSTEM_SUFFIX`**——为了一次测不出差异的改动去改
产品文案，等于凭手感改线上行为。

如果将来要动，唯一有理由（但没有数据支撑）的一处是把「取不到就直接说明」改成
「检索之后仍然没有能用的工具，就直接说明」：明确「取」是一个有结果的动作，而不是
一个可以反复试的动作。这是语义澄清，不是提分手段，要单独讨论。

## 缺口

- **BFCL 只能量不依赖检索层的那部分提示词**。产品里那三句是关于 `toolSearchTool` 的，
  BFCL 没有这个工具，一注入就是毒药（票 24 的 63.33% 也是这个原因）。
- **读数会随模型漂**（见上）。所以它只能当「这一次的对照」，不能当跨月的验收线。
- **`--num-threads` 与 spawn**：BFCL 用 `mp.set_start_method("spawn")` 起子进程，
  monkeypatch 要能传过去靠的是 boot.py 在 import 时就 `apply()`——这一点目前能用，
  但上游一改启动方式就会静默失效。判据：注入后逐题结果与裸跑**有差异**；
  完全没差异就是补丁没生效。（`input_token_count` 不能当判据：它不含 system 部分，
  实测注入前后完全相同。）

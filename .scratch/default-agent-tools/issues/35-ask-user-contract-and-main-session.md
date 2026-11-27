# 票 35：提问工具的契约与可用范围对齐 OpenClaw

**Status:** in_progress（2026-11-27 22:50 开票）

**依据**：ADR-0042（澄清属会话策略，不写进 `SYSTEM_SUFFIX`）、ADR-0043（控制类工具不进检索闸、默认常驻）。
**无需新 ADR**：本票不改架构决定——不加策略层、不改检索链路，只把工具的**描述契约、可用范围、入参形态**
对齐 OpenClaw 的 `ask_user`（`docs.openclaw.ai/zh-CN/tools/ask-user`）。

## 为什么

B7「给两个选项让我选」一直是红的，而且两半都红：链路通（点名探针能登记问题、`answer` 204、回「预发」），
但**不点名时模型自己在正文写两个选项**，根本不调工具。

看 OpenClaw 的做法，它没有"强制模型调用"的机关；它做的是三件事，正好是三处缺口：

| 维度 | OpenClaw `ask_user` | 我们（改之前） | 差在哪 |
| --- | --- | --- | --- |
| 描述契约 | 双边：只在**真正属于用户的决策**上问；**不得**问能否继续、**不得**用来确认自己的计划；推荐项排第一标 `(Recommended)`；不许自写 `Other` | 库给的四条全是"何时该用"，**一句"何时不该用"都没有**，也没说"正文列选项不算" | **主因**：描述读起来像"想问就问"，正文写两条更省事 |
| 可用范围 | 只在主会话；子 Agent 与其它非主运行**根本拿不到** | 注册给所有运行；子 Agent 拿到后回 `unsupported`，那句文案还在教它"用文字问也行" | 多给一次白调用，且文案反向引导 |
| 入参形态 | 1–3 问，每题 `id`(snake_case)、`header`(≤12)、`question`(一句话)、`options` 2–4（label ≤64）；`Other` 自动注入 | 1–4 问、2–4 选项；**答案按"题目原文"当 key** | 原文当 key 很脆：模型改一个字、或两题原文相同，答案就串了 |

## 做什么

1. **描述契约**（两边都要）：保留库的四条"该用"，加上 OpenClaw 的四条"不该用 / 怎么说"——
   该由用户拍板就用它、别在正文里列选项代替、别问"能否继续"、别用来确认自己的计划、推荐项排第一标
   `(Recommended)`、别自写 `Other`、`no_answer` 之后按自己的判断继续并说明选了哪个。
2. **主会话限定**：子 Agent 的工具面里**没有**这个工具（在 `SubagentRunner` 选工具那一步剔掉）。
   运行期的 `unsupported` 保留，用于"没有提问面"的运行（headless / cron）——这与 OpenClaw 对
   "surfaces without question input" 的处理一致，但不再出现在子 Agent 里。
3. **形态**（OpenClaw 的优点，代码可行才做）：加 `id`、`header` 截 12 字、`label` 截 64 字、
   答案按 `id` 回来、`description` 改可选。**不做** 1–3 问的限制（它的 3 是为面板分步，我们的渲染是
   编号文本，任何条数都摆得下，为它砍成 3 是"为了对齐而对齐"），也不加 `id` 的正则强校验——
   模型给非法 id 时补成 `q1..qn` 而不是抛异常，工具失误不该把这一轮打成 500。
4. **文档与判据**：`README-capability-tests.md` 的 B7 补两句——描述契约变了、子 Agent 里不该有这个工具；
   并新增一条"子 Agent 拿不到提问工具"的验证命令。

## 不做什么

- **不做 UI**（用户明确）：HTTP 仍然靠轮询 `/api/chat/questions`，IM 仍走纯文本。
  代价记在票底：HTTP 上模型真调了工具，这次 `POST /api/chat` 会挂着等（最长 900 秒），
  curl 用户屏幕上什么都没有——这是 A 上线后要认的成本。
- 不改 `SYSTEM_SUFFIX`（ADR-0042 仍然有效）。
- 不动 `pinned`（ADR-0043 已经把 `AskUserQuestionTool` 常驻）。

## 验收

| # | 验什么 | 判据 |
| --- | --- | --- |
| 1 | 答案是按 `id` 分组的 | 单测：两题原文相同、id 不同，答案不串 |
| 2 | 缺 `id` / `id` 重复时补成 `q1..qn`，不抛异常 | 单测 |
| 3 | `header` 超 12 字被截断、`label` 超 64 字被截断 | 单测 |
| 4 | 子 Agent 的工具面里没有 `AskUserQuestionTool` | 单测 + 实测日志 |
| 5 | B7 不点名那一半 | 实测：模型调工具而不是正文写选项；仍红则如实记，不改提示词绕过 |

## 量具说明

BFCL 不进这次评估：它的 240 题只给 BFCL 自带的函数，我们的工具描述与常驻名单都不进那份请求
（`--with-prompt` 只加系统提示）。所以本票**不需要重跑 BFCL**。

## 复核（2026-11-27 22:56，`claw4j_accept_33` / 日志 `/tmp/claw4j-35.log`）

### 落了什么

| # | 文件 | 改了什么 |
| --- | --- | --- |
| 1 | `AskUserTool`（重写，不再用库的 `AskUserQuestionTool`） | 自建 `@Tool`：描述 = 库那四条"该用" + OpenClaw 四条反向约束（该用户拍板时用它且**别在正文里列选项代替**、别问"能否继续"、别用来确认自己的计划、推荐项排第一标 `(Recommended)`、别自写 `Other`、`no_answer` 后按判断继续）；入参加 `id`/`header`；`header` 截 12 字、`label` 截 64 字、`description` 改可选 |
| 2 | `AskUserTool.mainSessionOnly(...)` + `SubagentRunner` | 子 Agent 的工具面里剔掉提问工具（照 OpenClaw 只在主会话） |
| 3 | `QuestionGate.Question` | 加 `id`；`Outcome.answers` 的 key 从**题目原文**改成 **id** |
| 4 | `PendingQuestions.parse` | 按 `id` 分组 |
| 5 | `AskUserAutoConfiguration` | 用 `new AskUserTool(gate)` 建回调；`TOOL_NAME` 改成引用 `AskUserTool.TOOL_NAME`，不再两处各写一份 |
| 6 | `AskUserQuestionTest` | 6 → 10 条：按 id 分组（同文不同 id 不串）、形状修好而不抛、子 Agent 不给、描述里那三句反向约束 |

校验一律"改好"不抛异常：`id` 缺失/重复/不合规补 `q1..qn`，超长截断，问题条数超 4 才回一句文字让它拆开。
工具里抛异常会把这一轮打成 500，而模型给错形状是常事。

### 实测

| 验什么 | 结果 |
| --- | --- |
| **B7 不点名那一半** | ✅ **转绿**。用户原话"给我两个选项让我选，别替我决定，推荐预发" → 模型**主动调了工具**，`/questions` 返回 `{"header":"发布方式","question":"新版本发布走哪条路？","options":[{"label":"先发预发（推荐）",…},{"label":"直接全量",…}]}`；回 `1` → **204** → 挂住那轮回「你选了**先发预发**」 |
| 子 Agent 工具面 | ✅ 日志 `委派 task 给 Subagent incident-triage：它这趟能用的工具是 [...]` 里 **0 次** `AskUserQuestionTool`（剔之前它会在，因为该定义只有 `disallowedTools`、拿到的是全量清单） |
| 单测 | `claw4j-agent` **248/248**；`ApplicationAssemblyTest` **25/25** |

`header` 的 12 字上限实际生效：日志里那条 `header=发布方式` 四个字。推荐项用了中文「（推荐）」而不是
`(Recommended)`——模型按中文语境本地化了，语义对，不追。

### 顺带发现一个已存在的缺陷（不属于本票，没动）

触发 `incident-triage` 时子 Agent **启动即失败**，父链把原因原样回报给用户：

```
工具注册表报重名冲突（systemStatus、Read、BashOutput 等一批工具各注册了两份），subagent 启动即失败
```

那行日志可数出来：子 Agent 的工具面 **102 个名字、每个正好两遍**（`world_clock` 2、`word_define` 2…），
而父链同一时刻 `Indexed 66 tools`（无重复）。也就是说重复出在 `SubagentDefinition.selectTools(...)`——
只给 `disallowedTools`、没给 `tools` 的那种定义会拿到全量清单的两份拷贝。
`examples/subagents` 里其余五个都写了显式 `tools:`，所以平时跑不到这条路。
**与本票改动无关**（这里只做过滤，不会复制），要修另开票。

### 没跑

- 检索评测没重跑：它吃的是 `eval/tools/retrieval/catalogue.txt` 快照，不是活代码；这次改的是一句描述，
  快照没重生成。**哪天重生成快照，`gold.yaml` 里那条"让人先问清楚（AskUserQuestionTool）"要复核。**
- BFCL 不需要（理由见票面「量具说明」）。
- C/D/E/F/G/H/I/J 各节照旧没动。

# 02: AskUserQuestion 按 OpenClaw 的 ask_user 实现（运行内等待 + HTTP / IM 答复）

**What to build:** `ask_user` 工具在 `claw4j-agent` 里装起来，模型调用它就把问题推到「这一轮来自的那段对话」，
**在同一轮里等答案**（默认 900 秒，钳 30–3600）。答案是：IM 里回一句话，或 HTTP 上 `POST /api/chat/answer`。
没有提问面的通道不装这个能力，工具返回一句「直接在回答里问」。

**裁决来源：** 2026-11-16 用户「参考 openclaw 怎么实现类似的功能的，与 ADR-0027 冲突，以 openclaw 的设计为准」。

**Blocked by:** 无（ADR-0027 需要一条修订记录，见下）。

**Type:** 新增交互能力（跨 core / agent / channel 三个模块）

**Status:** resolved

## OpenClaw 的原始事实（`docs/tools/ask-user.md`）

| 事实 | 原文 |
| --- | --- |
| 是**运行内等待**，不是持久账本 | "The wait is attributed to the original run and retains its cancellation and current-authority checks. It uses the existing **process-local** question lifecycle, **not a durable recovery ledger**. A Gateway restart or an aborted/replaced run does not turn an unresolved step into success." |
| 默认 900 秒，钳 30–3600 | "The default timeout is 900 seconds. `timeoutSeconds` is clamped to the range 30 through 3600 seconds." |
| 到期 / 取消 → `no_answer`，模型自行判断 | "if it expires or is cancelled before an answer arrives, the tool returns `status: "no_answer"`. The agent then continues with its best judgment." |
| 只有支持提问的面才有这个工具 | "available … on a surface that supports question input: Discord, Slack, Telegram, and WebChat … On the ACP bridge, iMessage, Mattermost, Signal, WhatsApp, and other surfaces without question input, the agent **asks in ordinary plain text** instead of calling `ask_user`." |
| 纯文本回复就能答 | "a plain-text reply can answer a question created by an active OpenClaw run **when your current permissions match the creator's**." |
| 接受答案之前不授新权 | "Answering a question does not grant the agent additional permissions." |

## 这一票推翻了 ADR-0027 的哪一句

ADR-0027 当时写的是「它的审批是运行内等待，**或** approval-pending + 系统事件唤醒会话」，本项目只取了后者，
于是 `ApprovalGate` 的类注释写着「不落 awaiting、不把某一轮改成 suspended」。这条对**审批**仍然成立。
`ask_user` 不同：OpenClaw 自己就是运行内等待，而且明确说用进程内生命周期而不是持久账本。

所以修订只落在**提问**这一件事上：审批照旧不挂起；提问会挂起这一轮，挂在进程内（没有表、没有状态机、
重启就没了）。已写进 ADR-0027 的修订记录。

## 落点

| 模块 | 新增/改动 |
| --- | --- |
| `claw4j-core` | `domain/question/QuestionGate`（端口：`ask` / `answer`）；`InboundDispatcher` 在 `accept` 最前面拦一句「这条是在回答待答问题吗」 |
| `claw4j-agent` | `question/PendingQuestions`（进程内登记表 + 超时）、`AskUserTool`（库的 `AskUserQuestionTool` + 一个把 `ToolContext` 带进去的包装）、`AskUserAutoConfiguration`、`AskProperties` |
| `claw4j-channel` | `HttpChatController` 加 `GET /api/chat/questions` 与 `POST /api/chat/answer`；四个 IM 自动配置把 gate 交给 dispatcher |
| 配置 | `claw4j.tools.ask.enabled`（默认 true）、`claw4j.tools.ask.timeout-seconds`（默认 900，钳 30–3600） |

**为什么写一个 `ToolCallback` 包装而不是直接用库的类**：库的 `QuestionHandler` 是
`Map<String,String> handle(List<Question>)`，**拿不到 `ToolContext`**，也就拿不到「哪个通道、哪段对话、
谁在问」。包装只做一件事：调用期间把这一轮的 `channel / chatRef / principal / runId` 放进 `ThreadLocal`，
handler 从那儿读，调完就清。

**答复形态**：IM 一律发**纯文本 + 编号选项**（四家通道都能收），用户回数字、选项名或任意文字；
HTTP 没有推送面，靠客户端在请求挂着的时候轮询 `GET /api/chat/questions` 拿到问题，再 `POST /api/chat/answer`。
多选按逗号拆；多题按行拆。

## 用例清单

| 判什么行为 | 用例名 | 先红的样子 |
| --- | --- | --- |
| 回数字 / 选项名 / 自由文本都能答对，答案按题目分组 | `AskUserQuestionTest#plainTextAnswersResolvePendingQuestion` | 不做选项匹配 → 回来的值是 `"2"` 而不是选项名，red |
| 答复人的权限与提问人不一致时**不**算答案，那条消息走正常一轮 | `AskUserQuestionTest#answererMustMatchTheAsker` | 去掉指纹比对 → 别人的「2」把问题答掉了，red |
| 没人答 → 超时返回 `no_answer`，登记表里不留残渣 | `AskUserQuestionTest#timeoutYieldsNoAnswer` | 不加超时 → 用例卡死（本来就该卡死） |
| 通道推不出去（没有 `OutboundDelivery` 且不是 http）→ `unsupported` | `AskUserQuestionTest#surfaceWithoutPushIsUnsupported` | 不判 → 等到超时才回，red |
| 同一段对话已经有一个在等：不开第二个 | `AskUserQuestionTest#onePendingPerConversation` | 不判 → 后一个把前一个顶掉，前一轮永远等 |

## 验收判据

| 判什么 | 判据 |
| --- | --- |
| HTTP 真能答 | 起实例，让模型问一个问题（同步请求挂着），另一个终端 `GET /api/chat/questions` 看得到，`POST /api/chat/answer` 之后第一个请求带着答案返回 |
| 超时真的放开 | `timeout-seconds=30` 起实例，不答，30 秒后回答里是「按自己的判断继续」那一句 |
| 不支持的面 | 没有 `OutboundDelivery` 的通道调 `ask` → 立刻 `unsupported`，模型改口直接问 |
| 门禁 | `scripts/verify.sh` 全绿 |

## 缺口

- 库的 schema 没有 `requiredAction`（OpenClaw 有：等人做完一步再回来复查），也没有 per-call `timeoutSeconds`。
  这两样要另写工具，不在这一票。
- HTTP 的同步接口在等待期间**占着一个 Tomcat 线程**（最多 15 分钟 × 并发数）。OpenClaw 那边是事件循环，
  没有这个代价。要走 SSE 推问题本可以更省，但 `ChatChannel#stream` 的回调只带 `OutboundMessage` 分片，
  加一个事件类型是另一件事。
- 运行被中止（客户端断开、5 分钟租约超时）时不会主动 `cancel` 掉待答问题：只能等它自己超时。
  OpenClaw 有 "An aborted agent run cancels its pending Gateway question"，我们缺一个 abort 钩子。
- 飞书/钉钉/企微/Slack 都只发纯文本，没有原生按钮。飞书有卡片能力（`FeishuApprovalCard`），
  要做按钮是第二块工作。
- 重启即丢：这是 OpenClaw 自己的选择（process-local），不是偷懒；但意味着重启那一刻在等答案的那一轮
  会以 `no_answer` 收场。

## 实现记录

- `claw4j-core`：`domain/question/QuestionGate`（`ask` / `answer` / `pending` + `Ask` / `Question` /
  `Option` / `Outcome`）；`InboundDispatcher` 在 `accept` 的**入队之前**拦一句「这是不是在回答待答问题」，
  消费掉就回一句「收到你的回答，接着做。」并不再开新一轮。
- `claw4j-agent`：`question/PendingQuestions`（进程内表 + 超时 + 权限指纹比对 + 文本解析）、
  `AskUserTool`（库的 `AskUserQuestionTool` + `ThreadLocal` 带 `ToolContext` + 按三种结局组返回值）、
  `AskUserAutoConfiguration`（工具、权限 `ask_user`、档位 `MINIMAL`）、`AskProperties`（默认 900，钳 30–3600）。
- `claw4j-channel`：`GET /api/chat/questions`、`POST /api/chat/answer`（204 收下 / 409 没人在等）；
  四个 IM 自动配置把登记表交给 `InboundDispatcher`。
- 配置：`claw4j.tools.ask.enabled`、`claw4j.tools.ask.timeout-seconds`；`read-only` / `guest` 的动作表里
  加了 `ask_user`（提问不碰数据，不给它的后果是"只有 admin 能被追问"）。
- **端到端（HTTP，`--claw4j.cache.enabled=false`，仓库根起实例）**：
  `POST /api/chat` 里模型调工具 → 请求挂住 → 另一条连接 `GET /api/chat/questions` 拿到
  `{"header":"部署环境","question":"这次部署去生产还是预发？","options":[...]}` →
  `POST /api/chat/answer {"text":"2"}` 回 **204** → 挂着的那个请求立刻返回
  「你选择了**预发**环境」。`claw4j.question{outcome=answered}=1`、
  `claw4j.tool.calls{tool=AskUserQuestionTool,outcome=ok}=1`。
  另一次不等答案跑满 120 秒，模型拿到 `no_answer` 之后自己选了一个并说明理由。
- **变异验证**：去掉权限指纹比对 → 「答复人必须与提问人一致」红；把断言的 `containsOnlyOnce` 之类拿掉
  不影响（已确认六条各自对着一条行为）。`GoalTools` 那处引号改回不转义 → `ToolGovernanceTest` 的
  schema 用例红（见下）。
- **顺带修掉两个既有缺陷**（都挡住了这次的验收，不修看不到真实行为）：
  1. `GoalTools` 的 `create_goal` 手写 Schema 里 `更"专业"的说法` 少转义一个引号，整份 Schema 不是合法 JSON。
     DeepSeek/OpenAI 在发请求前要把它转成 Map，**坏一个，那一轮所有工具都发不出去**，而模型那边只看到
     「没有工具」。新增 `ToolGovernanceTest#handwrittenToolSchemasAreValidJson` 盯着它。
  2. `HttpChatController` 的登记表一直拿到 `null`：它在 `HttpChannelAutoConfiguration` 里是
     `new HttpChatController(channel, resolver, properties)` 显式构造的，而那个三参构造把 provider 传成了
     null。症状是「工具确实在等，但 `/questions` 永远空、`/answer` 永远 409」。去掉三参构造、装配处传
     `ObjectProvider<QuestionGate>`，一条构造路径。
- 门禁：`scripts/verify.sh` 全绿（失败 0、错误 0、跳过 0）。`claw4j-agent` 11 个测试文件（软线外、硬线内）。


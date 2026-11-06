# 03 审批与投递按对话地址

**Status:** resolved（2026-11-06）
**依赖:** 01、02
**依据:** ADR-0027 决定一、四

## 背景

现在"批准之后怎么继续"这条路，整条是建在 run 上的：

```text
ApprovalService#decide
  → resumer.resume(runId, note)          // 拿 runId 找回来
  → runs.sessionOf(runId)                // 从 run 上读会话
  → runs.reopen(runId)                   // SUSPENDED → RUNNING
  → runs.stepsOf(runId) → recap          // 从 run 的步骤拼"做到哪了"
  → 同一 run 上 append + finish
```

OpenClaw 没有这条路。它的审批是**运行内等待**（原生聊天 / 网页）或 **approval-pending + 系统事件唤醒会话**
（节点与非原生网关）。唤醒的是**会话**，不是那一次运行：

> 核准的非同步執行會發出命令進度與完成系統事件（Exec running/Exec finished）；
> **遭拒絕或逾時的核准為終止狀態，不會以拒絕系統事件喚醒代理程式工作階段**。

所以：**批准 → 同一个会话里开新一轮；拒绝 / 超时 → 什么都不开。**

## 落点

### 1. `V21__approval_conversation.sql`

`approval_request` 加四列（都是它自己缺的，不是从 run 搬来的新概念）：

```text
session_key        varchar(255)   -- 回到哪一段对话
channel            varchar(32)    -- 卡片往哪个通道发
chat_ref           varchar(255)   -- 通道内的会话地址
principal_snapshot text           -- 发起人身份快照（JSON），从 agent_run.principal_snapshot 迁来
```

`principal_snapshot` 必须过来：批准之后要调模型，调模型要 `Principal`。用审批人的身份等于提权，
而只存 `owner`（`userId@权限指纹`）反推不出角色与可访问表。这条理由 V11 已经写过一遍，
本票只是把它从 run 挪到"审批行"上——**审批行才是那条记录真正的主人**。

`run_id` 列本票**不删**（票 05 随 run 一起清），但 `RunApprovalGate` 不再写它。

### 2. 删掉续跑这一整套

| 删什么 | 位置 |
| --- | --- |
| `RunStatus.SUSPENDED` | `RunStatus`；`ck_agent_run_status` 在票 05 的迁移里改 |
| `RunStore#suspend` / `reopen` / `resume` / `settleApprovalStep` / `sessionOf` / `sessionAddressOf` / `principalOf` | `RunStore` 与 `JdbcRunStore` |
| `RunResumer` 接口 | `agent/run/RunResumer.java` |
| `InboundMessage.runId`（第 6 个参数） | `claw4j-core`，并连带改五个便捷构造器 |
| `AskRequest.runId` | `HttpChatController`，连带 `AskResponse.runId` 改成 `turnId` |
| `ChatClientAgentPort#openRun` 的 resume 分支 | 只剩 `runs.start(...)` |
| `agent_run_delivery` 表与 `OutboundDelivery` 的登记 | `V21` 里 drop table |

`RunStore#finish` 保留（这一轮的结果要落），`append` 保留到票 05（审计还读它？不读——票 01 之后审计写
`audit_event`。`append` 只剩"收尾记一条"这一个调用方，票 05 一起删）。

### 3. 批准之后：同一会话开新一轮

`ApprovalService#decide` 改成：

```text
批准：
  1. 从审批行取 principal_snapshot / session_key / channel / chat_ref
  2. 组一条合成 InboundMessage：
       principal = 快照还原
       chatId    = chat_ref
       sessionId = session_key          -- 用现有会话，不换段
       text      = "[审批通过] " + note
       notifyPolicy = done_only
  3. 走 ChatClientAgentPort#reply（与普通消息同一条路径）
  4. 结果投递到 chat_ref（见第 4 节）
拒绝 / 超时：
  1. 只更新审批行（status + decided_at + decided_by）
  2. 回一句确认到 chat_ref，**不调模型、不开 run**
```

"同一会话开新一轮"不是偷懒：`session_key` 里就是那一段对话，上下文与 goal 都在，模型看到的是
"刚才我要调这个工具、现在批准了"——这正是 recap 想表达的东西，而它由记忆自己提供，不用我们从 step 拼。

**`recap()` 整个删掉。** 它存在的理由是"run 挂起了，上下文没了，得把做到哪了一步告诉模型"；
上下文一直在会话里，这个理由不成立。

### 4. 投递：用会话地址，不用登记表

`DeliveryTarget` 的解析从"按 runId 查 `agent_run_delivery`"改成"用 `channel + chat_ref` 直接投"。

- 定时任务：已经有 `channel_ref`（`agent_schedule` 表），用它；
- HTTP：`chat_ref` 就是请求头里的 chatId，结果由 `GET /api/turns/{turnId}` 取（票 05 改端点名）；
- 审批：用审批行上的 `channel + chat_ref`。

`agent_run.result` 与 `notify_policy` 不再写（列在票 05 随表删）。

## 判据

- [x] **批准开的是新一轮**：批准前后是两行 `agent_run`，而两行的 `session_id` 相同。
      证据：`ApprovalFlowTest#approvingStartsTheNextTurnInTheSameConversation`
      （`count(*) = 2`、`distinct session_id` 只有 1 个）。
- [x] **旧的一轮是终态**：被拦的那一轮 `status = done`，不是任何"挂起"。
      反例：把 `RunStatus.SUSPENDED` 加回去 → 枚举与 `V21` 的约束同时红。
      证据：同一条用例，`runs.find(first.runId()).status() == DONE`；
      库那一侧 `RunSchemaSqlTest#thereIsNoSuspendedStatus` 断言约束拒收 `suspended`。
- [x] **拒绝不执行，但那段对话要知道**：拒绝之后工具不执行、结论落库，**同一段对话收到
      「没有执行、不要重试」那一句**（复核时按 OpenClaw 源码改的，见「复核」一节）。
      反例：把拒绝那支的 `wake(...)` 换成"只落结论" →
      `denyingTellsTheSameConversationTheCallDidNotRun` 红（`prompts` 只 1 条）。
      证据：同一条用例（`[审批被拒]`、`不要重试`、`agent_run` 两行）。
- [x] **审批卡片落在原对话**：卡片按请求自己的 `channel + chat_ref` 推，没有登记表。
      反例：`announce` 回到"最近用过的通道"——这里没有那个概念，替代的反例是
      `origin == null` 时不推（子 Agent 那一层），`ChatPipelineTest#theGateRegistersAndAnnouncesWithoutSuspendingAnything`
      断言那一支不产生任何推。
      证据：同一条用例（`oc_1|sendEmail|to=张三|ap-1|pending`）。
- [x] **principal 是发起人不是审批人**：A（u-mailer）发起、approver-1 批准，
      执行那条审计的 `actor` 是 `u-mailer`、`actor_role` 是 `mailer`。
      证据：`ApprovalFlowTest#theExecutedCallCarriesTheApprovalItWasLetThroughBy`。
- [x] **两个 `runId` 都不在了**：`InboundMessage` 少一个参数、`AskRequest` 只剩
      `text` / `notifyPolicy`。
      证据：`HttpChatControllerTest#theRequestCarriesNoResumeToken` 用反射钉住参数名。
- [x] **显式给的整段会话键原样使用**：审批批准之后回到它自己那一段（含段后缀），
      不是按通道重新派生一个无段的键。
      反例：删掉 `sessionIdOf` 里那个 `if` → `ChatPipelineTest#anExplicitSessionKeyIsUsedAsGiven` 红。
      证据：同一条用例（`FakeRuns.sessionIds` 只收到那一个键）。
- [ ] **同一对话仍然串行**：**没做**。审批续跑现在走 `AgentPort#reply` → `inLane`，
      锁键由 `owner + channel + chatId` 算出，与用户那条消息同一把——**代码上成立**，
      但没有一条并发反例钉住它。`ConversationLane` 自己的并发用例在
      `ConversationLaneTest`（02 落地时验过），这里缺的是"审批与用户消息同时到达"这条组合。
      留给 A12 之前补，或第一次有人报"批准之后回答串了"时补。
- [x] `./scripts/verify.sh` 全绿，跳过 0，覆盖率 ≥60%。
      证据：667 条、失败 0、跳过 0，agent 68.5% / app 80.8%，10 文件 / 20 方法。

## 落地时注意

- `ApprovalFlowTest` 里"拒绝后模型收到拒绝理由"那条要按 OpenClaw 改：拒绝**不执行**，
  但要回灌一句"没有执行、不要重试"（见「复核」——我第一版把它做成了"什么都不说"，不对）。
  改之前先想清楚：这是"行为变更"，不是"测试放宽"——票面这一节就是依据。
- `ScheduleFlowTest`、`RunLifecycleTest`、`ChatPipelineTest`、`HttpChatControllerTest` 里都有
  `new InboundMessage(..., runId, ...)` 的位置参数，删参数时逐个改，**不要**加一个"接收但忽略"的重载
  去省事——那会让"还有谁在用 runId"查不出来。
- `ApplyPatch` 到 `ToolGovernanceTest` 时留意方法数上限（20），超了先合并同主题断言。

## 不做什么

- 不做"跨通道接着做"：一件事只属于发起它的那段对话。OpenClaw 没有这个能力，我们也不做。
- 不做审批超时后的自动重试。
- 不保留 `RunResumer` 的"降级实现"——它是这条路的唯一入口，删干净比分叉安全。


## 实现记录

### 落在哪儿

| 东西 | 位置 |
| --- | --- |
| 表 | `V21__approval_conversation.sql`：审批行加 `session_key / channel / chat_ref / principal_snapshot`；`agent_run.status` 去掉 `suspended`（先把旧行迁成 `done`）；drop `agent_run_delivery` |
| 请求来处 | `ApprovalLedger.Origin`（新的子 record）；`JdbcApprovalLedger` 写这四列，快照走 `PrincipalSnapshot.encode` |
| 登记 | `RunApprovalGate#ask` 只剩"登记 + 推卡片"；`announce` 用 `ask.origin()` 的地址 |
| 批准 | `ApprovalService#wake`：快照还原身份 → 组一条 `InboundMessage`（`sessionId` 显式给）→ `AgentPort#reply` |
| 拒绝 | 结论落库 + 工具不执行，**同一段对话也走 `wake`**，note 是"没有执行、不要重试、没有新输出" |
| 入口 | `ChatClientAgentPort#sessionIdOf` 认 `message.sessionId()`；`openRun` 只剩 `start` |
| 装配 | `RunPersistenceConfiguration`：`approvalGate(ledger, cards)`、`approvalDecider(jdbc, agentPort, props)`、`runDelivery(runs, deliveries, registry)` |

### 四处必须说明的

1. **`agent_run.session_id` 改成 `start` 时一起写**。票面只写"删 `attachSession`"，
   而预算是按会话累计的（ADR-0025）：删了那条 update 又没有替代，每一轮的额度会
   悄悄退回"按 run 算"。所以 `RunStore#start` 多一个 `sessionId` 参数——
   开的时候就写进去，比"先插再 update"少一次往返，也不会因为那条 update 失败而静默降级。
2. **`V21` 里必须先把历史 `suspended` 迁成 `done`**。票面说"`ck_agent_run_status` 在票 05 改"，
   但**删枚举值的那一刻起**，读回一行旧 `suspended` 会让 `RunStatus.fromColumn` 直接抛——
   一条历史记录能让列表页 500。迁移是不可逆的历史，所以这件事只能在本票做。
3. **`ContextKeys` 多了 `CHANNEL` / `CHAT_REF`，而且 Advisor 上下文与 ToolContext 都要放**。
   审批那一层（`AuthorizationAdvisor`）读的是 Advisor 上下文，定时任务工具读的是 ToolContext。
   只放一处时，症状是"批准之后说找不到来处"或者"任务没记住送回哪儿"——两个都不报错。
4. **`recap` 整个删掉**（票面要求），连 `clip` 一起：它们只服务"run 挂起之后上下文没了"，
   而那个前提不成立。

### 三个反例，各红一条

| # | 改坏什么 | 红了哪条 |
| --- | --- | --- |
| 1 | 拒绝那支也走 `continueInConversation` | `denyingLeavesTheDecisionAndNeverCallsTheModel`（`DENIED` → `GRANTED`） |
| 2 | `sessionIdOf` 忽略消息上的整段会话键 | `ChatPipelineTest#anExplicitSessionKeyIsUsedAsGiven` |
| 3 | 迁移里删掉 `ck_agent_run_status` 那一句 | `RunSchemaSqlTest#thereIsNoSuspendedStatus`（本来想写"读旧行不炸"，最后落成"约束拒收"） |

**反例 2 第一次是绿的**，这是这张票最值得记的一件事：当时那条断言叫"两轮在同一段会话里"，
而 `sessionIdOf` 忽略显式键之后，按通道派生出来的键**恰好与显式给的那个相同**——
在"同一段、pointer 为空"的形态下，两条实现的结果必然一致。
所以那条断言测不出这条代码路径，补了一条直接看 `FakeRuns` 收到什么会话键的用例之后才红。
**"结果一致"不等于"走的是同一段代码"**，与 04 里 `/new` 换段写两遍那次是同一个坑。

### 没做

- **同一对话串行的并发反例**（见判据）。
- **`agent_run.result` / `notify_policy` 两列仍被写**：投递不再读它们，票 05 随表删。
- **`GET /api/runs` 端点**仍在，票 05 换。
- **`/new` 换段之后的迟到结论不丢弃**：OpenClaw 在这一支上做了"会话被 `/new` / `/reset`
  改绑就丢掉这条 followup"（`expectedSessionId` / `isExecApprovalFollowupSessionRebound`），
  我们这边没有指针的只读入口（`ConversationPointerStore` 只有会写会轮换的 `segmentOf`），
  所以迟到的结论仍会落在请求原来那一段上。要补得先给指针开一个只读方法——
  单独一件，不塞进本票。

## 复核（2026-11-06，对着 openclaw/openclaw 源码）

拿 `https://github.com/openclaw/openclaw` 的源码逐条对了一遍，**一处读反了，已改**：

原文（`docs/tools/exec-approvals.md` §System events and denials）：

> When an approval can be delivered, ordinary agent runs wait for the decision and receive the
> exec result in the same turn. […] An operator denial returns a denied tool result without
> running the command.
>
> - For main-agent async approvals with an originating session, OpenClaw posts the denial back
>   into that session as an internal followup so the agent can stop waiting on the async command
>   and avoid a missing-result repair.

我这票早先引的是"遭拒絕或逾時的核准為終止狀態，不會以拒絕系統事件喚醒代理程式工作階段"，
按它把拒绝做成了"什么都不说"。**那句说的是 `Exec denied` 这个 system event 不发，
不是会话不收 followup**；源码里 `bash-tools.exec-approval-followup.ts` 的
`buildExecDeniedFollowupPrompt` 就是拒绝那一支的提示词：`An async command did not run.` /
`Do not run the command again.` / `There is no new command output.` / 让模型向用户解释。

我们这种"一轮在拦下时就结束"的形状对应的是异步后续那一支，所以拒绝也要回灌。
已改：`ApprovalService#decide` 的两支都走 `wake`，只换 note；判据与用例同步换成
`denyingTellsTheSameConversationTheCallDidNotRun`。

其余几条对得上：结果回原对话（`The final reply uses the original delivery path`）、
卡片推原对话、发起人身份不是审批人、拒绝后不自动重试。

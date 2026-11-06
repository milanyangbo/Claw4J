# 03 审批与投递按对话地址

**Status:** ready-for-agent
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

- [ ] **批准开的是新一轮**：批准前后 `turnId` 不同，库里 `agent_run` 多一行。
      反例：仍按旧 runId 续跑（`reopen` 留着）→ 同一行，红。
- [ ] **旧的一轮是终态**：工具停在审批上时，那一轮的状态是 `done`（它已经回答"我需要你批准"），
      不是任何"挂起"。
      反例：引入 `PAUSED` 之类的新状态去表达"等审批"→ 红（那就又回到 run 跨请求了）。
- [ ] **拒绝不开新一轮**：拒绝之后 `agent_run` 行数不变，且 `chat_ref` 收到一句确认。
      反例：拒绝也调 `reply` → 多一行，红。
- [ ] **审批卡片落在原对话**：HTTP 发起、审批通过、结果回到同一个 chatId，不落到 `lastChannel`。
      反例：投递时回退到"最近用过的通道"→ 换通道的用例红（OpenClaw 原文："never falls back to lastChannel"）。
- [ ] **principal 是发起人不是审批人**：A 发起、B 批准 → 新一轮的 `actor` 是 A。
      反例：用审批人的身份 → 红（这条现在是 ApprovalFlowTest 里已有的断言，别改松）。
- [ ] **两个 `runId` 都不在了**：`InboundMessage` 构造器参数少一个；`AskRequest` 里没有 `runId`；
      `grep -r "message.runId()"` 零命中。
- [ ] **同一对话仍然串行**：批准续跑与用户同时发来的消息走同一把 lane 锁（现在靠 `agent_run.session_address`，
      改后靠 `session_key` 反解出的地址）。
      反例：续跑不加锁 → 两条并发写同一段会话，红。
- [ ] `./scripts/verify.sh` 全绿，跳过 0，覆盖率 ≥60%。

## 落地时注意

- `ApprovalFlowTest` 里"拒绝后模型收到拒绝理由"那条要按 OpenClaw 改：**拒绝不再调模型**。
  改之前先想清楚：这是"行为变更"，不是"测试放宽"——票面这一节就是依据。
- `ScheduleFlowTest`、`RunLifecycleTest`、`ChatPipelineTest`、`HttpChatControllerTest` 里都有
  `new InboundMessage(..., runId, ...)` 的位置参数，删参数时逐个改，**不要**加一个"接收但忽略"的重载
  去省事——那会让"还有谁在用 runId"查不出来。
- `ApplyPatch` 到 `ToolGovernanceTest` 时留意方法数上限（20），超了先合并同主题断言。

## 不做什么

- 不做"跨通道接着做"：一件事只属于发起它的那段对话。OpenClaw 没有这个能力，我们也不做。
- 不做审批超时后的自动重试。
- 不保留 `RunResumer` 的"降级实现"——它是这条路的唯一入口，删干净比分叉安全。

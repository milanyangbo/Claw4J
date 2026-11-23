# 票 11 写侧归属：actor 记执行者、两把会话键、channel 只属于消息行

**Status:** resolved
Blocked by: —
裁决来源：`docs/adr/0037-audit-alignment-with-openclaw.md`（2026-11-23 第三批修订）。

## 对照（原文）

```ts
// src/audit/agent-event-audit.ts
function projectExplicitAttribution(event) {
  const eventAgentId = nonEmptyString(event.agentId);
  return { actorType: eventAgentId ? "agent" : "system", agentId: eventAgentId ?? "unknown", … };
}
// 运行/工具事件：actorType: provenance.actorType, actorId: provenance.agentId
// src/audit/audit-event-store.ts
session_id: … , channel: input.kind === "message" ? input.channel : null,
```

| OpenClaw | 改之前 | 改成 |
| --- | --- | --- |
| `actor_type` / `actor` 记**执行者**（agent / system / channel_sender） | `actor` 记提问的人 | 非消息行 `agent`+agentId（没有 agentId 时 `system`+`unknown`）；入站消息 `channel_sender`+发送者假名；出站 `agent`+agentId |
| 两把会话键：`session_key`（会话键）与 `session_id`（那一段） | 只有 `session_key`，写的是**含段**的 id | V14 加 `session_id`；`session_key` 改写成不含段的会话键 |
| `channel` 只在消息行 | 每一行都写 | 非消息行写 null，加 CHECK 钉住 |

## 做什么

1. **V14 迁移**：加 `session_id` / `requested_by`；历史行把人从 `actor` 挪到 `requested_by`，
   非消息行的 `actor_type` / `actor` 换成执行者语义，消息行的 `actor` 补成 `account_ref`；
   清掉非消息行上的 `channel`；加三条 CHECK（`actor` 必填、`channel` 只在消息行、`actor_type` 闭集）。
2. `AuditEvent.Scope` 改形状：`(runId, sessionKey, sessionId, agentId, requestedBy, channel)`，
   `of(...)` 三档重载保持旧调用点可用。
3. `AuditLedger` 按上面的规则算 `actor_type` / `actor`（`attributionOf`），写 `requested_by`；
   `channel` 只在消息行落值；孤儿补写带 `session_id` / `actor_type` / `actor` / `requested_by`。
4. 入口：`ChatClientAgentPort` 同时给会话键与段号；`MeteredToolCallback.scopeOf` 从工具上下文
   取两把（`CONVERSATION` 与 `CONVERSATION_ID`，后者兜底）。

## TDD 用例清单

| 判什么 | 用例名 | 先红的样子 | 结果 |
| --- | --- | --- | --- |
| 工具行的执行者是 `agent`+agentId，不是人 | `BuildContractTest#auditQueryReturnsNewestFirst` | 还是人时两条断言都红（`agent-qfilter` vs `u`） | ✅ |
| 工具行的 `channel` 是 null；消息行的 `channel` 有值 | 同上 | 每一行都写 channel 时第一条红 | ✅ |
| 库里 `actor` 空着被拒、非消息行带 channel 被拒 | `BuildContractTest#auditTerminalRowsAreConsistent` | 没有这两条 CHECK 时两条插入都成功 | ✅ |
| `session_id` 与 `session_key` 是两把 | 同上（`session-q-seg`） | 只写一把时 `sessionId()` 为 null | ✅ |
| 显式给的整段会话键落在 `session_id` 上 | `ChatPipelineTest#anExplicitSessionKeyIsUsedAsGiven` | 仍断言 `sessionKey()` 时红（它现在是会话键） | ✅ |
| 委派那一轮的人仍记在 `requestedBy` | `SubagentDelegationTest#subagentToolCallsAreAuditedInTheirOwnRun` | 改名的同时漏掉时 NPE/红 | ✅ |

## 复核

复核：2026-11-23 `./scripts/verify.sh` 全绿（失败 0 / 错误 0 / 跳过 0；agent 14 文件 / 26 方法、
覆盖 60.9%；app 88.4%）。**跑过反例**：把 `AuditLedger` 里的 `attribution` 换回旧语义
（`actor_type` 写 null、`actor` 写 `requestedBy`），`auditQueryReturnsNewestFirst` 红在
「运行/工具行的执行者是 agent」与 `actor` 那条（两条一起红，因为它同时说错了两格）。改回来绿。

顺带修了四条既有用例，它们断言的是"actor 是人"或"只有一把会话键"——那是旧语义：
`ChatPipelineTest#anExplicitSessionKeyIsUsedAsGiven`（改验 `sessionId`）、
`ApprovalFlowTest#theExecutedCallCarriesTheApprovalItWasLetThroughBy`（改验 `requested_by`）、
`ScheduleFlowTest` 两条（`aDueTaskRunsOnTheOwnersBehalf`、`eachFiringRunsWithTheOwnersIdentity`
——后者从"两条 session_key 不同"改成"两条 session_id 不同"，因为同一任务的两次到点共用会话键）。

## 缺口

- **`requested_by` 是扩展列**：OpenClaw 的事件里没有"谁触发的"，它放在 execution decision 那一层
  （`representedSubject`），我们没有那张表。放在库里、不进读接口，是为了消息审计默认关时
  "这一轮谁触发的"仍然查得到；代价是它与它的 schema 多一列。
- **`actor.id` 与 `agent_id` 在同一条运行行上通常同值**：它是它的投影规则（`actorId = agentId`），
  照抄；子 Agent 另有一轮时两者仍然一致，没有引入新的语义。
- **历史行的 `session_id` 永远空**：不回填，因为段号无从还原。

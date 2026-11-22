# 票 01 终态与归属：四个终态有生产者，`agentId` 不再为空

Status: claimed
裁决来源：`docs/adr/0037-audit-alignment-with-openclaw.md`（决定 1、2、3、4）

## 对照

| OpenClaw（出处） | 现在 | 改成 |
| --- | --- | --- |
| 七终态全有生产者（`audit-event-types.ts:75-110`） | 只有 started / succeeded / failed | 补 `blocked` / `cancelled` / `timed_out` / `unknown` |
| `agentId` 必填（`audit.ts:53`） | `Scope.of` 硬编码 null（`AuditEvent.java:52`） | `Scope` 带 agent，取不到写 `unknown` |
| 终态配对由类型锁死（`audit-event-types.ts:75-110`） | 只有 `action × status` 的 CHECK（`V5:366-368`） | 加 `kind × status × error_code` 配对 CHECK |
| 无 `actor_role` 这一列 | 死列（`V5:334-337`） | 删列，连带写入点 |

## 做什么

1. `AuditEvent.Scope` 增 `agentId`；`Scope.of(runId, sessionKey, agentId, principal, channel)`。
   运行侧取当前绑定的 agent（`claw4j.agent.default` / 会话绑定），工具侧从 `ToolContext` 取；
   取不到写 `unknown`。
2. `blocked` 生产者：工具被策略/权限拦下时写 `tool.action.finished / blocked / tool_blocked`，
   且**不写 started**（`ToolCallAudit#rejected` 已有规则，缺调用点）。
3. `cancelled`：流式调用方断开（`onChunk` 抛 / Reactor cancel）时写 `run_cancelled`，
   `timed_out`：HTTP 流超时写 `run_timed_out`。两者都替换现在的一律 `run_failed`。
4. `unknown`：启动时把"有 started 没有 finished 且超过 30 分钟"的 run 补成
   `finished / unknown / tool_outcome_unknown`（工具）与 `run` 的 `unknown`。
5. V9 迁移：删 `actor_role`，加配对 CHECK。

## TDD 用例清单

| 判什么 | 用例名 | 先红的样子 | 结果 |
| --- | --- | --- | --- |
| 被权限拦下的工具写一行 blocked、不写 started | `ToolGovernanceTest#blockedToolWritesOnlyFinished` | 库里 0 行 → 期望 1 行 `blocked/tool_blocked` | ❌ 未接生产调用点 |
| 工具事件带 agent 归属 | `ObservabilityTest#toolAuditCarriesAgentId` | `agent_id` 为 null → 期望 `unknown` 或绑定名 | ❌ turn 层拿不到 agent 绑定 |
| 运行事件带 agent 归属 | `ObservabilityTest#runAuditCarriesAgentId` | 同上 | ❌ 同上 |
| 客户端断流记 cancelled 而不是 failed | `ChatPipelineTest#cancelledStreamIsAuditedAsCancelled` | 现在写 `failed/run_failed` | ❌ 未做 |
| 流超时记 timed_out | `ChatPipelineTest#timedOutStreamIsAuditedAsTimedOut` | 现在写 `failed/run_failed` | ❌ 未做 |
| 库里拒绝 `kind` 与 `error_code` 不配的行 | `BuildContractTest#auditTerminalPairingIsEnforced` | 插一行 `agent_run + tool_failed` 现在能进库 | ❌ 未做 |
| `actor_role` 已不存在 | `BuildContractTest#auditEventHasNoActorRole` | 列还在 | ✅ |

## 缺口

- `unknown` 的扫尾窗口（30 分钟）是估的，没有上游依据；OpenClaw 靠"终态时成对原子落库"
  避免孤儿行（`audit-event-store.ts:327-391`），我们的形态是"开跑即落"，只能靠扫尾补。
- 客户端断开与"写库失败"在 Spring MVC 的异常类型上不好分；先按 `IOException` /
  `AsyncRequestTimeoutException` 分，分不出来的仍记 `failed`，并在票里记一笔。

## 复核

待实现后补：`复核：<日期> ./scripts/verify.sh 全绿`

# 票 01 终态与归属：四个终态有生产者，`agentId` 不再为空

**Status:** resolved
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

   **它从哪来（2026-11-22 查清）**：`AgentAutoConfiguration:102`
   `PersonaCatalog.load(Path.of(agent.directory()), agent.defaultName())` 返回的
   `Persona` 是一个 record，第一个字段就是 `name`（`Persona.java:41`）——部署级 agent 名。
   接法：`ChatClientAgentPort.Builder` 存它 → 两条 `Scope.of(...)` 带上 → 组装时往
   `ToolContext` 写一个 `AGENT_ID` 键 → `MeteredToolCallback` 从 `ToolContext` 读（与它现在读
   `RUN_ID`/`SESSION_KEY` 同一个形状）。子代理那一侧的归属要另取（`SubagentDelegation` 派发时的
   定义名），不在本步范围，先把部署级写对。
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
| 被权限拦下的工具写一行 blocked、不写 started | `ToolGovernanceTest#blockedToolWritesOnlyFinished` | 库里 0 行 → 期望 1 行 `blocked/tool_blocked` | ✅ 审批闸两处「再等也不会通过」的分支都写；登记上了就不写（等审批的真相在审批表） |
| 工具事件带 agent 归属 | `ObservabilityTest#toolAuditCarriesAgentId` | `agent_id` 为 null → 期望 `unknown` 或绑定名 | ✅ `PromptComposer` 往 ToolContext 写 `AGENT_ID`，回调读它；读不到落 `unknown` |
| 运行事件带 agent 归属 | `ObservabilityTest#auditScopeNeverLeavesAgentNull` | 四参 `Scope.of` 写 null → 期望 `unknown` | ✅（接线在 `ChatClientAgentPort.Builder#agentId`，装配取 `claw4j.agent.default`，没配目录时 `builtin`） |
| 客户端断流记 cancelled 而不是 failed | `ChatPipelineTest#cancelledStreamIsAuditedAsCancelled` | 现在写 `failed/run_failed` | ✅ 写失败的那一下抛 IOException（broken pipe），`RunOutcome` 归到取消 |
| 流超时记 timed_out | `ChatPipelineTest#timedOutStreamIsAuditedAsTimedOut` | 现在写 `failed/run_failed` | ✅ 链上有 Timeout 类型就记超时，优先于取消（`SocketTimeoutException` 也是 IOException） |
| 库里拒绝 `kind` 与 `error_code` 不配的行 | `BuildContractTest#auditTerminalRowsAreConsistent` | 插一行 `agent_run + tool_failed` 现在能进库 | ✅ V11 加了配对 check，历史行先归一 |
| 有 started 没 finished 的超时行补成 unknown | 同上（`sweepOrphans` 那段） | 孤儿行永远是 started | ✅ 启动 + 每次清理各扫一次，30 分钟窗口是估的 |
| `actor_role` 已不存在 | `BuildContractTest#auditReplayIsWrittenOnce` | 列还在 | ✅ |

## 缺口

- `unknown` 的扫尾窗口（30 分钟）是估的，没有上游依据；OpenClaw 靠"终态时成对原子落库"
  避免孤儿行（`audit-event-store.ts:327-391`），我们的形态是"开跑即落"，只能靠扫尾补。
- 客户端断开与"写库失败"在 Spring MVC 的异常类型上不好分；先按 `IOException` /
  `AsyncRequestTimeoutException` 分，分不出来的仍记 `failed`，并在票里记一笔。

## 复核

复核：2026-11-23 `./scripts/verify.sh` 全绿（失败 0 / 错误 0 / 跳过 0；V11 在 testcontainers 的真库上跑过）。

实现要点（落地时定的，票面原先没有）：

- 分类器是 `RunOutcome.of(Throwable)`：先看整条异常链，超时优先于取消（否则 `SocketTimeoutException`
  会被记成取消），`IOException` 记取消（流式写回客户端最常见的那种失败，代价是模型侧网络错误也归到这里）。
- `blocked` 只在「再等也不会通过」时写：没有身份、或者审批登记不上（没有审批设施）。等审批不写——
  那件事的真相在 `approval_request` 一张表里。
- 孤儿扫尾写成一条 `insert ... select ... where not exists`，`event_id` 由来源键算 `md5`，
  跑两遍也只会补一行。

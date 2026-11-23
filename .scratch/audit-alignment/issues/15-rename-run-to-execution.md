# 票 15 改名：run → execution（我们只有执行，没有 run）

**Status:** resolved
Blocked by: —
裁决来源：`docs/adr/0038-rename-run-to-execution.md`。

## 对照（原文）

```ts
// docs/cli/audit.md:102-106
Every admitted outer turn receives an opaque `executionId` …
the existing `runId` stays a possibly shared session, routing, or recovery correlation.
// packages/gateway-protocol/src/schema/audit-activity.ts:112-120 —— 线格式里 runId 必填
const agentProperties = { …, runId: NonEmptyString };
```

| 概念 | OpenClaw | 我们（改之前） | 改之后 |
| --- | --- | --- | --- |
| 每轮外层请求 | `executionId` | `runId` | `executionId` |
| 可能跨轮共享的关联 | `runId` | 没有 | 没有 |
| 线格式里的那一格 | `runId`（必填） | `runId` | `runId`（**不变**，翻译在视图层） |

## 做什么

1. **V16**：`audit_event` / `approval_request` / `execution_decision_fact` 的
   `run_id → execution_id`；索引与约束名一并改；历史行不动。
2. **代码**：`runId → executionId`、`run_id → execution_id`、`RUN_ID → EXECUTION_ID`、
   来源键前缀 `run: → exec:`；`AuditQuery` / `Scope` / 各端口 record 的组件名同步。
3. **线格式保持**：`AuditEventView` 的组件仍叫 `runId`（值取 `row.executionId()`），
   `/api/audit` 的参数名仍是 `runId`——依据见 ADR-0038 决定二。
4. **撤除**：`AuditRunInspect` / `JdbcAuditRunInspect` / `AuditRunView` / 端点与两条读侧用例
   （原票 14，见 ADR-0038 决定三）。
5. 文档：ADR-0037 的两段改成"已改名"与"已撤除"，`capability-tests.md` 同步；历史 ADR 只在
   0027 顶部加一行指针，不改正文（那是记录）。

## TDD 用例清单

| 判什么 | 用例名 | 先红的样子 | 结果 |
| --- | --- | --- | --- |
| 三张表的列都叫 `execution_id`，`run_id` 一个不剩 | `PersistenceSchemaTest#migrationLeavesOnlyTheTablesThatSurvived` | V16 空转时列名还是 `run_id`（实测三条） | ✅ |
| 缺执行编号的运行行仍被库拒绝（约束名也改了） | `PersistenceSchemaTest#anAuditEventWithoutAnExecutionIdDoesNotLand` | 约束还叫旧名时断言找不到那段错误文本 | ✅ |
| 读侧返回的执行编号是那一列的值 | `BuildContractTest#auditQueryReturnsNewestFirst` | 列没改名时 SQL 报"列不存在"，整类红 | ✅ |
| 审批绑定按新列名读写 | `ApprovalFlowTest#theExecutedCallCarriesTheApprovalItWasLetThroughBy` | 同上，`approval_request.run_id` 不存在 | ✅ |
| 端到端（起上下文、跑一轮、查审计）在改名后照常 | `ApplicationAssemblyTest` 整类 | 装配里任一旧列名都会让上下文起不来 | ✅ |

## 复核

复核：2026-11-23 `./scripts/verify.sh` 全绿（失败 0 / 错误 0 / 跳过 0）。**跑过反例**：把 V16 换成
一句 `select 1`（等价于没改名），`migrationLeavesOnlyTheTablesThatSurvived` 红在
「三张表都是 execution_id」——实际回来的是 `approval_request.run_id` / `audit_event.run_id` /
`execution_decision_fact.run_id`。改回真迁移后绿。

## 缺口

- **线格式没跟着改**：报文与参数仍是 `runId`，因为它的闭对象里 `runId` 必填、参数表里只有这个名
  （ADR-0038 决定二）。所以"统一"到边界为止，边界上是翻译。
- **历史来源键前缀是 `run:`**：`source_id` 只用于插入去重，不进报文；新旧前缀并存不影响唯一性。
- **外部手写 SQL 要跟着改**：库里的列名变了，`capability-tests.md` 里的示例 SQL 已同步。

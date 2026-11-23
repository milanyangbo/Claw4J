# 票 14 `audit.run.inspect`：一次 run 的全景

**Status:** resolved
Blocked by: —
裁决来源：`docs/adr/0037-audit-alignment-with-openclaw.md`（2026-11-23 第四批修订）。

## 对照（原文）

```ts
// packages/gateway-protocol/src/schema/audit-run.ts:313-330 —— 结果
export const AuditRunInspectResultSchema = closedObject({
  schemaVersion: Type.Literal(1),
  run: closedObject({ runId?: …, executionId?: …, status: "known" | "unknown" }),
  identity: AuditRunIdentityV1Schema,          // present | unknown | unsupported | ambiguous
  decisionDisplays: Type.Array(DecisionReceiptDisplayV1Schema, { maxItems: 100 }),
  coverage: closedObject({ state: …, missingEvidence: … }),
  nextDecisionCursor?: …, nextExecutionCursor?: …,
});
// :251-255 —— 没有采集执行身份时，它自己的写法
export const AuditRunIdentityUnsupportedV1Schema = closedObject({
  state: Type.Literal("unsupported"), reasonCode: …, missingEvidence: …, remediation: …,
});
```

`docs_cli_audit.md:338-370`：`run.inspect` 要 `operator.read`，入参 `runId` 或 `executionId`
**二选一** + `decisionLimit` 1–100；回执按固定顺序合并 `admission → approval → message →
generic → cron`，游标跨重启稳定；一个 run 有多个 execution 时只回 `ambiguous`。

我们有什么：`approval_request`（审批终态）、`audit_event`（消息终态、运行与工具事件）、
`execution_decision_fact`（执行依据了哪条批准，取走即删）。没有 execution identity 采集
（没有 `logging.audit.executionIdentity` 的对应物）、没有 admission、没有 cron 生命周期回执——
**它们的 schema 里有 `unsupported` 与"缺就是缺"，所以照实报，不编**。

## 做什么

1. 端口 `core/domain/port/AuditRunInspect`：`Optional<View> inspect(runId, decisionLimit, decisionCursor)`，
   `View` 带 `runId / coverage / decisions / nextCursor`，`Decision` 带
   `selectorId / occurredAt / family / operation / outcome / reasonCode / coverageState / producer`。
2. 实现 `agent/audit/JdbcAuditRunInspect`：三条查询（审批、消息终态、决策事实）在应用层按
   `occurredAt` 倒序合并（时间相同按 selectorId 倒序，保证跨重启稳定）；游标就是上一页最后一条的
   `selectorId`，认不出来时回 400（它的语义："游标的属主行过期了就拒"）。
3. 端点 `GET /api/audit/runs/{runId}?decisionLimit=&decisionCursor=`：报文按上面的闭对象拼，
   `identity` 恒为 `{state:"unsupported", reasonCode:"execution_identity_not_collected"}`，
   `coverage` 由这批回执推（有 `enforced` 就是 `enforced`，全是 `attribution-only` 就是它，
   一条都没有就 `unknown`）。没装实现时 404。
4. `spec.md` 总览与 ADR 第四批修订同步。

## TDD 用例清单

| 判什么 | 用例名 | 先红的样子 | 结果 |
| --- | --- | --- | --- |
| 报文是闭对象：`schemaVersion`/`run`/`identity`/`decisionDisplays`/`coverage` 都在，空字段不出现 | `AuditRunEndpointTest#anInspectionCarriesTheClosedShape` | 少拼一格时断言找不到该键 | ✅ |
| `identity` 是 `unsupported` 且带 reasonCode —— 不编 `present` | 同上 | 拼成 `present` 时这一条红 | ✅ |
| 游标认不出来 400、没装配 404、`executionId` 不支持也 400；这一轮不存在时仍是 200 + `status=unknown` | `AuditRunEndpointTest#refusedArgumentsAreExplained`、`AuditRunEndpointTest#anUnknownRunIsStillAnswered` | 当成"从头查"时返回 200；静默忽略 `executionId` 时返回 200 | ✅ |
| 真库里一次审批 + 一条消息终态能一起被 inspect 出来，顺序固定 | `ApprovalFlowTest#anInspectionMergesApprovalAndMessageReceipts` | 只查一张表时少一条 | ✅ |

前三条进新文件 `claw4j-channel` 的 `AuditRunEndpointTest`（该模块 11 → 12 个测试文件，上限 14）；
第四条是 `ApprovalFlowTest` 里新增的一个方法（14 → 15，上限 26）。

## 复核

复核：2026-11-23 `./scripts/verify.sh` 全绿（失败 0 / 错误 0 / 跳过 0）。**跑过两次反例**：

1. `identity` 写成 `present` → `anInspectionCarriesTheClosedShape` 红在「照实报 unsupported」，
   而报文其余部分仍然合法（说明这条红只因为那一格）。
2. 审批只按 `approval_request.run_id` 查 → 合并用例红在"只回来一条"：被拦的是 run A、
   执行的是 run B，**审批行挂在发起那一轮**。这条反例把一个真实的语义问题翻出来了，见下面缺口第一条。

## 缺口

- **审批行的 `run_id` 是发起那一轮，不是执行那一轮**：一次"被拦 → 批准 → 执行"跨两个 run，
  所以实现里两处都查（`approval_request.run_id` 与执行行上的 `approval_id`）。不这么做的话，
  执行那一跑的全景会说"没有任何审批"——而它明明是被批过才跑的。
- **`executionId` 不支持**：我们没有 execution 这一层（一次 run 就是一次执行）。请求里给
  `executionId` 时不猜，按"这个部署没有执行编号"回 404 + 说明。它的 `ambiguous` 分支
  因此也不会出现。
- **`identity` 恒 `unsupported`**：要变成 `present` 得先采集"谁发起的、代表谁、哪个信任域"，
  那是 execution identity 那一层，不是审计读侧能补的。
- **`admission` 与 `cron` 两类回执没有生产者**：合并顺序里留着位置，现在只会出现
  approval / message / generic 三类。
- **投递进度行（`queued` / `platform_started`）仍然不写**：它的唯一读者就是 `run.inspect`
  （`docs/gateway/protocol/ledgers.md:132-136`）。现在这个读者有了，但我们的出站是同步一次写终态、
  没有队列那一层，硬造进度行只会多一张只写不读（且不真实）的表。等真有了异步投递再说。
- **`nextExecutionCursor` 不出现**：没有 execution 这一层，它没有意义。
- **`operator.read` 仍然没有**（与 `/api/audit` 同一条缺口）：整个 http 层没有 operator 身份，
  见票 03 的缺口；这条不因为多一个端点而改变。

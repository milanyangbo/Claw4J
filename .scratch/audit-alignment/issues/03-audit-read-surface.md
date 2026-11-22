# 票 03 读侧：一个只读端口，形状照 `audit.list`

Status: resolved
Blocked by: 01
裁决来源：`docs/adr/0037-audit-alignment-with-openclaw.md`（决定 5）

## 对照

| OpenClaw（出处） | 现在 | 改成 |
| --- | --- | --- |
| `audit.list`：newest-first、`cursor`、`limit` 1–500、过滤 `agentId/sessionKey/runId/kind/status`（`audit.ts:63-73`、`ledgers.md:11-30`） | 无端口、无端点，只能手写 SQL | `AuditQuery` 端口 + `GET /api/audit` |
| `nextCursor` 来自最后一行 `sequence`（`audit-event-store.ts:393-409`） | — | 同形状：返回 `nextCursor`，无更多时为空 |
| 只读、`metadata_only` | — | 端点只读，不提供导出（`ledgers.md:108-110` 明说不是无损合规档案） |

## 做什么

1. `AuditQuery`（`claw4j-agent/audit`）：`Page query(Query q)`，`Query` 带六个可选过滤
   + `cursor` + `limit`（默认 100，超 500 截到 500，同 `audit.ts:71`）。
2. `JdbcAuditQuery`：`where seq < :cursor`（游标）与各过滤；`order by seq desc limit n+1`，
   多取一条判有无下一页；**索引已经齐**（`V5:382-395`）。
3. `GET /api/audit`（`claw4j-channel` 的 http 通道，与 `/api/chat` 同处），参数同 Query，
   返回 `{events, nextCursor}`；只读身份即可，不要求审批人。
4. 错误码与状态过滤走闭合词表枚举，词表外的值 → 400（同 OpenClaw 的 `INVALID_REQUEST`）。

## TDD 用例清单

| 判什么 | 用例名 | 先红的样子 | 结果 |
| --- | --- | --- | --- |
| 默认 newest-first、limit 生效 | `BuildContractTest#auditQueryReturnsNewestFirst` | 正序或 limit 不生效时断言直接红 | ✅ |
| 游标翻页不重不漏 | 同上（第二页剩一条、`nextCursor` 为空） | 不判 `limit+1` 时第二页要么多一条要么少一条 | ✅ |
| 过滤 runId / kind 生效 | `BuildContractTest#auditQueryFiltersAndClampsLimit` | 过滤条件不进 where 时命中数不对 | ✅ |
| `limit>500` 截到 500 | 同上（`Filter` 紧凑构造里钳位） | 不钳位时 999 原样下去 | ✅ |
| 端点返回 `{events,nextCursor}`，没装配时 404 | `HttpEndpointsTest#anAuditPageIsReadable` | 端点 404 / 500 | ✅ |

（用例落在 `BuildContractTest` 而不是新建 `AuditQueryTest`：`claw4j-app` 已是 14 个测试文件，
本票不再新增文件；原计划"先把 `ApprovalRequiredToolCallbackTest` 并进 `ToolGovernanceTest`"
因此没有做——`BuildContractTest` 里还有余量，且它本来就在真库上跑。）

## 门禁余量（实际怎么落的）

原计划"把 `ApprovalRequiredToolCallbackTest` 并进 `ToolGovernanceTest`、新建 `AuditQueryTest`"
**没有采纳**：`BuildContractTest` 还有余量且本来就在真库上跑，查询用例并进去了；
`HttpEndpointsTest`（channel，11 个文件）加一条也不越界。中间两次把单文件顶到 27 个方法，
都是**合并回 26**，没有抬上限。

## 缺口

- **端点已做**：`AuditController`（`GET /api/audit`）按 ADR-0010 放在通道层，端口在 `domain`、
  实现在 agent；没装配查询时 404（能力不在场），与 `/api/approvals` 同一个判断。
- `audit.activity.list` 那套 `eventType` 判别与消息过滤要等票 04 落地后补；
  本票只按 `audit.list` 的旧形状做（run / tool 两类）。
- `--explain` / `audit.run.inspect` 不做，理由见 spec「不做」。

## 复核

复核：2026-11-22 `./scripts/verify.sh` 全绿（`claw4j-app` 14 / 26、`claw4j-channel` 11 / 20）。

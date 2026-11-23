# ADR-0038 我们没有 run 这一层：把"一轮"的标识改成 execution

状态：accepted（2026-11-23）
取代：无
关联：ADR-0027（run 是一个句柄、不落表）、ADR-0037（审计对齐 OpenClaw）

## 背景

2026-11-23 收尾审计对齐时读 OpenClaw 的原文，发现两边**同名不同物**：

> Every admitted outer turn receives an opaque `executionId`. `contextId` identifies its immutable
> evidence record; **the existing `runId` stays a possibly shared session, routing, or recovery
> correlation.**
> —— `docs/cli/audit.md:102-106`

也就是说它有两层：**每轮外层请求 = `executionId`**，而 **`runId` 是可能跨轮共享的关联**
（会话、路由、恢复）。我们只有前者，却一直叫它 `runId`。

ADR-0027 当时定的"run 只是个句柄、不落表"仍然成立，但它没有回答"这个句柄代表什么"。
按它的口径，我们的句柄代表**一次执行**，不是一次 run。

## 决定

**一、把"一轮"的标识统一改名为 `executionId`**（库里 `execution_id`，Java 里 `executionId`，
上下文键 `ContextKeys.EXECUTION_ID`，内部端口与文档同）。只改名字，不改值也不改语义。

**二、线格式也改，叫 `executionId`**（票 16，推翻本 ADR 初稿的"线格式保持 `runId`"）。
初稿的理由是"它的闭对象里那一格必填、参数表里只有 `runId`"（`audit-activity.ts:112-120`、
`:410-420`）——那是对**照抄它的 schema** 成立的理由，但那一格装的是**我们的**执行编号，它的
`runId` 是"可能跨轮共享的关联"，我们没有那一层。名字留在边界上，读报文的人就会以为我们也有一层
叫 run 的东西，正是本文背景里那个"同名不同物"。所以 `AuditEventView` 的组件与 `AuditController`
的参数都叫 `executionId`（与 `/api/chat` 的 `AskResponse`、`/api/approvals` 的 `ApprovalView` 一致）。
代价：报文不再是它 `audit.activity` 的实例，要按它那份 schema 解析的客户端得自己映射一次。

**三、撤除 `audit.run.inspect` 那一套**（原票 14）。它是 run 级的语义：传 `runId` 是"发现这次 run
下的多个执行"，一个 run 对应多条时只回 `ambiguous`、要求选 `executionId`
（`docs/gateway/protocol/ledgers.md:124-127`）。我们没有"一次 run 下多个执行"这回事——
一个执行编号就是一次执行——所以照抄出来的端点只能覆盖它的 `executionId` 分支，
名字却叫 run。

## 代价

- **一次 schema 改动**（并入基线 `V5__run.sql`）：三张表的那一列直接叫 `execution_id`，索引叫
  `ix_audit_event_execution`，约束叫 `ck_audit_event_execution_required`。外部手写 SQL 若引用了
  `run_id` 要跟着改。
- **工具上下文里的键名**：`claw4j.runId` → `claw4j.executionId`（`ContextKeys.EXECUTION_ID`）。
  自定义工具若直接读这个字符串，要跟着改；读常量（`ContextKeys`）的不用。
- **观测层同样只有这一个词**（票 17）：根 span 名 `run` → `execution`，属性 `claw4j.run.id` →
  `claw4j.execution.id`，投递指标 `claw4j.run.delivery` → `claw4j.execution.delivery`。
  它同样是"一轮的编号只有一个名字"的必要条件——同一个 id 在 trace、审计、工具参数三处出现却有两个
  名字，读的人会以为它们指两件事。代价：**看板与告警里按旧名写的查询会查不到数据**（不报错，只是
  静默变空），上线前要一起改。
- **来源键前缀 `run:` → `exec:`**（`source_id`）：旧行保持旧前缀。它是内部去重键，不进报文，
  但两份前缀会同时存在——查历史时按值与时间，不按前缀。
- **公开契约**：审计接口的参数名与报文那一格都改成了 `executionId`（决定二）；`/api/chat` 的
  同步/流式回执、审批卡片载荷里的同名字段更早一步跟着 Java 记录一起改了（`runId` → `executionId`）。
  四处都是"一轮的编号"，只有一个名字。
- **不再是 OpenClaw 的实例**：`audit.activity` 的报文与参数表里那一格叫 `runId`，我们不再照抄。
  要按它那份 schema 解析的客户端得自己映射一次。
- **少了一个能力**：`GET /api/audit/runs/{runId}` 撤除。单次取证的替代是
  `GET /api/audit?executionId=<执行编号>`（时间线里筛这一轮），以及库里的 `approval_request` /
  `execution_decision_fact` 两张表。要"按决策类型合并 + 覆盖度"这一层，得先有执行身份与
  决策回执的属主，不是读侧能补的。

## 退场条件

如果将来引入"跨轮共享"的那一层（例如网关侧的路由/恢复生命周期），**把那个概念叫 `run`**，
一轮继续叫 `execution`。那时 `run.inspect` 才有它原本的含义，届时再按本 ADR 的决定三恢复，
并且必须带 `ambiguous` 分支。

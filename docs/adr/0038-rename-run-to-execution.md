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

**二、线格式保持 `runId`**。`audit.activity` 的 `agent_run` / `tool_action` 变体里 `runId` 是
必填字段（`packages/gateway-protocol/src/schema/audit-activity.ts:112-120`），而四个变体都是闭对象
（`:135-142`）——把报文改叫 `executionId`，要么少一个必填格、要么多一个非法格，两种都解析不了。
查询参数同理（它的参数表里只有 `runId`，`:410-420`）。翻译只在一处：`AuditEventView` 与
`AuditController` 把 `row.executionId()` 放到报文/参数的 `runId` 上，和 `seq → sequence`、
`at → occurredAt` 是同一类转换。

**三、撤除 `audit.run.inspect` 那一套**（原票 14）。它是 run 级的语义：传 `runId` 是"发现这次 run
下的多个执行"，一个 run 对应多条时只回 `ambiguous`、要求选 `executionId`
（`docs/gateway/protocol/ledgers.md:124-127`）。我们没有"一次 run 下多个执行"这回事——
一个执行编号就是一次执行——所以照抄出来的端点只能覆盖它的 `executionId` 分支，
名字却叫 run。

## 代价

- **一次 schema 迁移**（V16）：三张表 `rename column run_id → execution_id`，索引
  `ix_audit_event_run → ix_audit_event_execution`，约束
  `ck_audit_event_run_required → ck_audit_event_execution_required`。历史行不迁移（值一直是对的）。
  外部手写 SQL 若引用了 `run_id` 要跟着改。
- **工具上下文里的键名**：`claw4j.runId` → `claw4j.executionId`（`ContextKeys.EXECUTION_ID`）。
  自定义工具若直接读这个字符串，要跟着改；读常量（`ContextKeys`）的不用。
- **来源键前缀 `run:` → `exec:`**（`source_id`）：旧行保持旧前缀。它是内部去重键，不进报文，
  但两份前缀会同时存在——查历史时按值与时间，不按前缀。
- **公开契约**：审计接口的参数名与报文那一格**不变**（见决定二）；但 `/api/chat` 的同步/流式
  回执里那个字段跟着 Java 记录一起改名（`runId` → `executionId`），以及审批卡片载荷里的同名字段。
  这两处是我们自己的契约，不是 OpenClaw 的线格式，改名是为了"一轮的编号"只有一个名字。
- **少了一个能力**：`GET /api/audit/runs/{runId}` 撤除。单次取证的替代是
  `GET /api/audit?runId=<执行编号>`（时间线里筛这一轮），以及库里的 `approval_request` /
  `execution_decision_fact` 两张表。要"按决策类型合并 + 覆盖度"这一层，得先有执行身份与
  决策回执的属主，不是读侧能补的。

## 退场条件

如果将来引入"跨轮共享"的那一层（例如网关侧的路由/恢复生命周期），**把那个概念叫 `run`**，
一轮继续叫 `execution`。那时 `run.inspect` 才有它原本的含义，届时再按本 ADR 的决定三恢复，
并且必须带 `ambiguous` 分支。

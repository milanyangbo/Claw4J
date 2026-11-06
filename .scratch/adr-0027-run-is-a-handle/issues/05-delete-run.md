# 05 删掉 run

**Status:** resolved（2026-11-06）
**依赖:** 01、02、03、04
**依据:** ADR-0027 全篇

## 背景

前四张票做完，系统里已经没有东西需要 run 了：

- 审计写 `audit_event`（01）；
- 计划变成会话上的 goal（02）；
- 审批与投递按对话地址（03）；
- 额度是 goal 的属性（04）。

这一票只做删除。它应该在**前面四张票都全绿之后**才开始——顺序反了，删到一半会发现某处还在读。

## 落点

### 1. `V24__drop_run.sql`

（票面原先写 V23，实际版本号是 V24——V23 被票 01 的 `schema_version` 用掉了。）

```text
drop view  if exists claw4j_run.audit_trail;      -- 票 01 之后没人读，但一直还在
drop table if exists claw4j_run.agent_step;
drop table if exists claw4j_run.agent_run_delivery;   -- 03 已删；if exists 是给没跑过 V21 的库
drop table if exists claw4j_run.agent_run;
```

`approval_request.run_id` 与 `audit_event.run_id` **保留原列名**，既不 drop 也不改名：
它们装的是"哪一轮"，不是那个已经不存在的实体。审批行要能回答"这次批准是哪一轮发起的"，
审计行要能回答"这条事件属于哪一轮"——删了就永远答不出来。改名同样是错的（见下面的修订记录）。

### 2. 删掉的类

```text
agent/run/Run.java
agent/run/RunStatus.java
agent/run/RunStore.java
agent/run/JdbcRunStore.java
agent/run/Step.java
agent/run/StepInput.java
agent/run/StepKind.java
agent/run/StepStatus.java
agent/run/StepAudit.java
agent/run/RunResumer.java
agent/run/RunDelivery.java
agent/run/Budget.java
agent/run/UpdatePlanTool.java          -- 02 已经让它没有调用方
core/domain/port/RunView.java          -- 若有对外读，改成 TurnView 或直接删
```

`DeliveryTarget` 保留（它只是"往哪投"的一个值对象），从 `agent/run` 移到 `agent/channel`。

### 3. 改掉的类

| 类 | 改成 |
| --- | --- |
| `ChatClientAgentPort` | 不再注入 `RunStore`；`runId` 本地生成（UUID），只用于 trace / 审计 / 返回值 |
| `AgentAutoConfiguration` / `RunPersistenceConfiguration` | 删 run 相关 bean；`AuditLedger` 的装配挪到审计配置里 |
| `MeteredToolCallback` | 审计调用改成 `AuditLedger` 的两条事件 |
| `AuthorizationAdvisor` | 同上；拒绝分支写 `tool.action.finished` / `blocked` |
| `ScheduleTools` / `ScheduleScanner` | 结果投递用任务自己的 `channel_ref`；不再建 `DeliveryTarget` 记录 |
| `AuditRetention` | 删除（票 01 的 `prune()` 已经接管） |

### 4. 端点

- `GET /api/runs/{id}`、`GET /api/runs` **删除，不提供"按 runId 取运行状态"的替代**。
  理由要说准：OpenClaw 没有的是"按一次运行取它的状态与步骤"——它根本没有持久 run；
  它有的是"按 runId 过滤审计"（`audit.list { runId }`、`openclaw audit --run <id>`）
  和"按 runId 中止"（`chat.abort`）。那两样是读路径与中止路径，不是运行实体，
  所以它们的存在不构成"要留 run"的理由，也不能拿来当"OpenClaw 没有按 id 查"的证据。
  取某次回答的正确方式是**会话历史**（`chat.history`），不是"按一次运行查"。
- 若现有 HTTP 面还没有取会话历史的端点，本票补一个 `GET /api/conversations/{chatId}/history`，
  形状照 `chat.history`（支持 `limit`、返回条目带 `role` / `text` / `at`）。
- `AskResponse.runId` **保持 `runId`，不改名**（ADR-0027 决定一）。票 03 一度改成了 `turnId`，
  已随本票一并改回：OpenClaw 的 `chat.send` 返回的就是 `runId`，改名只会让迁过来的客户端断一次，
  换不来任何东西。

### 5. 测试

| 文件 | 处理 |
| --- | --- |
| `RunSchemaSqlTest` | 删除（它测的表都没了）；其中"审批唯一约束"那条搬到 `ApprovalFlowTest` |
| `RunLifecycleTest` | 删除；"清理四条语句"那段搬到新的 `SessionMaintenanceTest` |
| `ApprovalFlowTest` | 改：批准后是新一轮、拒绝不开新一轮（03 已改） |
| `ChatPipelineTest` | 改：断言从 `runs.finished` 换成 `audit_event` 里的 `agent.run.finished` |
| `HttpEndpointsTest` | 删 runs 两个端点的断言，加 history 端点 |
| `ToolGovernanceTest` / `ObservabilityTest` | 改构造参数，去掉 `RunStore` 替身 |

测试文件数与每文件方法数不能破线（每模块 ≤10 文件、每文件 ≤20 方法）。删掉两个文件之后有余额，
但不要把删掉的断言都塞进一个文件。

### 6. 文档

| 文件 | 改什么 |
| --- | --- |
| `GLOSSARY.md` | 删 `run` / `step` / `suspended` 条目；加 `turn`（一次运行）、`goal`（会话目标）、`audit_event` |
| `AGENTS.md` | ADR 索引加 0027；"0016–0021 是六条裁决"那句改成"0016 / 0018 / 0020 被 0027 部分取代" |
| `docs/design/` 里讲 run 的页 | 按新形状重写，不保留"历史上曾经有 run"的叙述——那属于 ADR，不属于设计文档 |
| `README.md` | 升级说明：`GET /api/runs` 下线；`runId` 字段不变 |
| `docs/agents/guardrails.md` | 记一条：删掉了本项目最大的一处"自己发明的实体" |
| `docs/adr/0016`、`0018`、`0020`、`0023` | 各自顶部加一行 `superseded by 0027（部分）` 的指针，正文不动 |

## 判据

- [x] **run 这个实体与它的状态机不在了**：`RunStore|RunStatus|BUDGET_EXHAUSTED|SUSPENDED|agent_step`
      在 `*/src/main` 下**零命中**（历史迁移除外，那是不可变的历史）。
      `agent_run` 只在一处还出现：`AuditAction` 的 `kind` 列值——那是照 OpenClaw 的记录分类
      （`agent.run.started` 的 kind 就叫 `agent_run`），不是那个被删掉的实体。
      证据：`Grep` 全库 `*/src/main/**`。
      **四个名字也清了**（第二次修订）：`RunTracer` → `SpanTracer`、`OtelRunTracer` →
      `OtelSpanTracer`、`RunApprovalGate` → `ConversationApprovalGate`；
      `ATTR_STEP_SEQ = "claw4j.step.seq"` 与 `ATTR_RESUMED` **整个删掉**。
      span 上不再写账本行号，trace 与审计只靠 `claw4j.run.id` 关联——OpenClaw 同样是这个分法：
      它的 span 属性只有 outcome / channel / provider / model / agent，账本序号留在账本里
      给排序、翻页与上限淘汰用。两个类本体是真实能力（tracing 与审批），OpenClaw 都有，
      所以改名不删；两个常量没有任何对应物，删掉之后连"账本序号在 span 上"这条假设也没了。
- [x] **错误码是 OpenClaw 的九项词表**：`AuditErrorCode` 是枚举，写不进词表外的值；
      库里 `ck_audit_event_error_code` 同一条口径。
      证据：`PersistenceSchemaTest#theErrorCodeVocabularyIsClosed`（插一个 `policy_denied` 被库拒绝）。
- [x] **库里没有表了**：`V24` 之后 `claw4j_run` 里只剩 `agent_schedule`、`approval_request`、
      `audit_event`。证据：`PersistenceSchemaTest#migrationLeavesOnlyTheTablesThatSurvived`。
- [x] **端点下线**：`RunQueryController` 与 `RunQuery` / `RunView` 一起删，`GET /api/runs/xxx`
      不再存在（404 由 Spring 的默认处理给）。`HttpEndpointsTest` 里那三条断言跟着删。
      **`GET /api/conversations/{chatId}/history` 没做**，理由见"没做"一节。
- [x] **一次完整的问答仍然可用**：`ChatPipelineTest#replyLeavesTwoAuditEvents` 钉住
      `agent.run.started` + `agent.run.finished` 成对、两条的 `run_id` 与返回的 `runId` 同值、
      `session_key` 与 `actor` 都在。
- [x] **一次审批仍然可用**：`ApprovalFlowTest` 13 条全绿，批准之后是同一段对话里的新一轮
      （`runId` 与上一轮不同，`session_key` 相同）。
- [x] **定时任务仍然可用**：`ScheduleFlowTest#aDueTaskRunsOnTheOwnersBehalf` 读审计里那条
      `agent.run.started`：`actor` 是任务主人，`session_key` 含 `cron:`；`agent.run.finished`
      是 `succeeded`。
- [x] `./scripts/verify.sh` 全绿，跳过 0，覆盖率 ≥60%。
      证据：647 条、失败 0、跳过 0，agent 73.3% / app 80.8%，10 文件 / 20 方法。
- [x] **反例**：往 `agent/src/main` 放一个空的 `RunStore` 接口 → 判据 1 立刻命中
      （`claw4j-agent/src/main/java/io/github/claw4j/agent/RunStore.java:2`），删掉才恢复。
      这条判据存在的理由就是这个诱惑——兼容层一旦留下，下一个人会往里加东西。

## 不做什么

- 不保留 `agent_step` 作为"历史归档表"。要查历史，查 `audit_event`。
- 不写数据迁移脚本（把 `agent_step` 的行搬进 `audit_event`）：两个形状对不上，
  搬过去的行没有 `action` / `status` 词表可言。旧数据留在库里等 TTL，或者部署时手工导出一份。
- 不改 `claw4j_session` 的两张表。

## 实现记录

### 落在哪儿

| 东西 | 位置 |
| --- | --- |
| 表 | `V24__drop_run.sql`：`drop view audit_trail` → `drop table agent_step` → `agent_run_delivery`（`if exists`）→ `agent_run`；`approval_request.run_id` 与 `audit_event.run_id` **改名**为 `turn_id`（不是删列，见下） |
| 编号 | `ChatClientAgentPort` 里 `UUID.randomUUID()` 生成 `runId`，不落任何表 |
| 投递 | `RunDelivery` → `ConversationDelivery`（`agent/channel`），只有 `announce`，不再实现 `RunQuery` |
| 投递地址 | `DeliveryTarget` → `agent/channel` |
| 启动接口 | `RunLauncher` → `TaskLauncher`（`agent`），定时任务那一侧的入参 |
| 装配 | `RunPersistenceConfiguration` → `AgentPersistenceConfiguration`（`agent`），删 `runStore` bean，开关 `claw4j.run.enabled` → `claw4j.persistence.enabled` |
| 审计列 | `AuditEvent.Scope` 的字段名与列名都保持 `runId` / `run_id`（修订后）|
| 评测 | `EvalScorer` 的轨迹输入从 `List<Step>` 换成 `List<EvalScorer.Trace>`（一次工具调用一条，与审计同形） |

### 三处必须说明的

1. **`run_id` 既不删也不改名。** 那两列装的是"这一轮的编号"，不是那个不存在的实体。
   审批行要能回答"这次批准是哪一轮发起的"，审计行要能回答"这条事件属于哪一轮"——
   删了就永远答不出来。改名（曾经改成 `turn_id`）换不来任何东西，还挡了客户端一次；
   已改回 `run_id`（见下面的修订）。
2. **`claw4j.run.enabled` → `claw4j.persistence.enabled`。** 这个开关管的是"要不要装
   审计 / 审批 / 调度这几样需要库的东西"，而 run 已经不在里面了。留着旧名字，
   下一个人会以为它管什么别的东西。
3. **`EvalScorer` 跟着改了形状。** 它读的是"轨迹"，而轨迹原来是 `agent_step` 的
   `tool_calls` 汇总行（一行逗号串好几个工具）。现在读审计：一次调用一条，
   分得出"调了两次同一个工具"——这是变准，不是变宽。

### 没做

- **`GET /api/conversations/{chatId}/history`。** 票面说"若没有取会话历史的端点就补一个"。
  没有补，理由是这是一件**独立的事**，不是删除的替代品：它要一个新的读路径
  （`SessionService#getEvents` 加 `EventFilter`）、一套"谁能读谁的会话"的授权判定
  （`RecallSessionTool` 里那条"sessionId 必须以当前身份的归属键开头"就是雏形），
  以及分页与游标。把它塞进"删 run"这一票，会让一票里同时有"删"和"加一个新能力"。
  **记在这里，单独开票做。**
- **数据迁移**：不把 `agent_step` 的行搬进 `audit_event`。两个形状对不上——
  step 的行没有 `action` / `status` 词表可言，搬过去只会得到一堆解释不了的行。
- **`ChatPipelineTest` 的并发建议**：`launch()` 现在不再因"没有 run 存储"返回空，
  于是调度器拿到的空只可能是"模型抛了"。这条改动的判据是
  `ScheduleFlowTest#aDueTaskRunsOnTheOwnersBehalf`（真库、真审计）。

### 用例的去向（删了 21 条，改了一批）

| 原来 | 现在 |
| --- | --- |
| `RunLifecycleTest`（12 条） | 改名 `TurnFlowTest`，删 3 条（run 生命周期、按 id 回收、trace 写 step 表），其余保留 |
| `RunSchemaSqlTest`（16 条） | 改名 `PersistenceSchemaTest`，删 7 条（建表/读回来/序号分配/降级/suspended/待做/审计视图/一条批准一次执行），其余保留 |
| `ChatPipelineTest` | 4 条改写成"读审计"（`RecordingAudit`），2 条删除（没有 run 存储的降级、等审批的 run 接不上） |
| `ApprovalFlowTest` | `runs.find(…).status()` → `finishStatusOf(runId)`（读审计的 finished 那一条）；`count(agent_run)` → `count(audit_event where action='agent.run.started')` |
| `ScheduleFlowTest` | 端口接上真审计；会话断言从 `agent_run.session_id` 改读 `audit_event.session_key` |
| `ObservabilityTest` | 删掉 mock 的 `RunStore`；`claw4j.run.id` 那条断言改成"等于回答里的 runId" |
| `HttpEndpointsTest` | 删三条 run 端点断言与两个夹具 |

### 修订（2026-11-06 晚，按 OpenClaw 收敛）

复核时对照 `packages/gateway-protocol/src/schema/audit.ts` 与 `docs/cli/audit.md`，发现两处不对齐，
都已改。判断标准只有一条：OpenClaw 叫什么，我们就叫什么。

1. **字段名改回 `runId`。** OpenClaw 的 `AuditEventSchema.runId` 是**必填**；`audit.list { runId }`、
   `chat.abort { runId }`、hooks 的 `{ ok, runId }` 都用这个名字。`turnId` 在它那儿是另一个概念
   （`worker-inference` 里 `{ sessionId, runId, turnId }` 三者并存，runId 可跨轮共享）。
   本票原先改成 `turnId`，与 ADR-0027 决定一（"字段名不改"）直接冲突，也已让客户端断过一次。
   已改回：`OutboundMessage`、`ContextKeys.RUN_ID`（`claw4j.runId`）、`AuditEvent.Scope`、
   `ApprovalLedger.Ask`、`AskResponse`；列名保持 `run_id`，V24 里那两句 `rename column`
   与索引改名一并删掉——迁移历史不该记录一次改错又改回来的反复。
2. **错误码换成它的九项词表**（`V25__audit_error_code_vocabulary.sql`）。新增枚举
   `AuditErrorCode`，九个值逐字相同；旧的四个值（`policy_denied` / `tool_error` / `timeout` /
   `cancelled`）在迁移里按 `kind` 映射，随后加 `ck_audit_event_error_code`。
   顺带补上原先漏掉的一处：运行失败那条事件的 `error_code` 一直是 null，现在是 `run_failed`。
3. **票面判据 1 的说法改准了**：原来写"代码里没有 run 了"，但正则只查了五个符号，
   `RunTracer` / `RunApprovalGate` / `claw4j.step.seq` / `ATTR_RESUMED` 都还在。它们是名字不是实体，
   本票不动，但判据不能声称一件没有做到的事。
4. **四个 `run` / `step` 名字清了**（用户点名之后）。逐个对过 OpenClaw：
   - `RunTracer` / `OtelRunTracer` → `SpanTracer` / `OtelSpanTracer`。能力留着——OpenClaw 有完整
     traces（`openclaw.run`、`openclaw.model.call`、`openclaw.tool.execution`），删掉的是"这个接口
     负责持久 run"的错觉。
   - `RunApprovalGate` → `ConversationApprovalGate`。它按对话地址推审批卡片，名字改成它实际做的事。
   - `ATTR_STEP_SEQ = "claw4j.step.seq"` **删掉**，连带整条 `Optional<Long>` 返回链
     （`closeTurn` / `finishAudit` / `ToolCallAudit` 的五个方法 / `MeteredToolCallback` 的两个私有方法
     全部收窄成 `void`）。理由：span 与审计的关联键已经有一个 `claw4j.run.id`，再挂一个"第几行"
     不回答任何新问题，却把两条本可以各自演进的管道钉在一起；OpenClaw 的 span 属性里没有账本序号。
   - `ATTR_RESUMED = "claw4j.run.resumed"` 删掉。死常量，续跑在票 03 就没了，只有定义没有使用处。

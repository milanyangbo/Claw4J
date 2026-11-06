# 05 删掉 run

**Status:** ready-for-agent
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

### 1. `V23__drop_run.sql`

```text
drop view  if exists claw4j_run.audit_trail;      -- 票 01 之后没人读，但一直还在
drop table if exists claw4j_run.agent_step;
drop table if exists claw4j_run.agent_run_delivery;   -- 03 若已删则 no-op
drop table if exists claw4j_run.agent_run;
```

`approval_request.run_id` 一列同时 drop（它没有外键，删起来没有级联问题）。

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
| `ChatClientAgentPort` | 不再注入 `RunStore`；`turnId` 本地生成（UUID），只用于 trace / 审计 / 返回值 |
| `AgentAutoConfiguration` / `RunPersistenceConfiguration` | 删 run 相关 bean；`AuditLedger` 的装配挪到审计配置里 |
| `MeteredToolCallback` | 审计调用改成 `AuditLedger` 的两条事件 |
| `AuthorizationAdvisor` | 同上；拒绝分支写 `tool.action.finished` / `blocked` |
| `ScheduleTools` / `ScheduleScanner` | 结果投递用任务自己的 `channel_ref`；不再建 `DeliveryTarget` 记录 |
| `AuditRetention` | 删除（票 01 的 `prune()` 已经接管） |

### 4. 端点

- `GET /api/runs/{id}`、`GET /api/runs` **删除，不提供"按 turnId 查"的替代**。
  理由：OpenClaw 没有这个能力，它有 `chat.history`（按会话取历史）。
  取某次回答的正确方式是**会话历史**，不是"按一次运行查"——后者只有在"一件事能跨请求活着"时才有意义。
- 若现有 HTTP 面还没有取会话历史的端点，本票补一个 `GET /api/conversations/{chatId}/history`，
  形状照 `chat.history`（支持 `limit`、返回条目带 `role` / `text` / `at`）。
- `AskResponse.runId` 改名 `turnId`（票 03 已经改 Java 侧，这里落实 JSON 字段）。
  前端如果还在读 `runId`，这是**破坏性变更**——要在 `README` 的升级说明里写一行。

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
| `README.md` | 升级说明：`runId` → `turnId`；`GET /api/runs` 下线 |
| `docs/agents/guardrails.md` | 记一条：删掉了本项目最大的一处"自己发明的实体" |
| `docs/adr/0016`、`0018`、`0020`、`0023` | 各自顶部加一行 `superseded by 0027（部分）` 的指针，正文不动 |

## 判据

- [ ] **代码里没有 run 了**：`grep -rn "RunStore\|RunStatus\|agent_run\|BUDGET_EXHAUSTED\|SUSPENDED"`
      在 `src/main` 下零命中（`db/migration` 的历史迁移除外，那是不可变的历史）。
- [ ] **库里没有表了**：`V23` 之后 `claw4j_run` 里只剩 `approval_request`、`audit_event`、
      `agent_schedule`、`agent_session_budget`（若 04 已删则无）。
- [ ] **端点下线**：`GET /api/runs/xxx` 返回 404；`GET /api/conversations/{chatId}/history` 能取到最近若干条。
- [ ] **一次完整的问答仍然可用**：HTTP 问一句 → 拿到 `turnId` 与回答 → `audit_event` 里有
      `agent.run.started` / `agent.run.finished`，`approval_request` 为空。
- [ ] **一次审批仍然可用**：问一句触发工具 → 停在审批 → 批准 → 同一会话里拿到结果，
      且 `turnId` 与上一轮不同。
- [ ] **定时任务仍然可用**：到点跑一次 → 结果投递到任务的 `channel_ref`，
      `audit_event` 的 `run_id` 与 `session_key` 都非空。
- [ ] `./scripts/verify.sh` 全绿，跳过 0，覆盖率 ≥60%。
- [ ] **反例**：如果留一个 `RunStore` 的空实现"以防万一"，判据 1 直接红。
      这条判据存在的理由就是这个诱惑——兼容层一旦留下，下一个人会往里加东西。

## 不做什么

- 不保留 `agent_step` 作为"历史归档表"。要查历史，查 `audit_event`。
- 不写数据迁移脚本（把 `agent_step` 的行搬进 `audit_event`）：两个形状对不上，
  搬过去的行没有 `action` / `status` 词表可言。旧数据留在库里等 TTL，或者部署时手工导出一份。
- 不改 `claw4j_session` 的两张表。

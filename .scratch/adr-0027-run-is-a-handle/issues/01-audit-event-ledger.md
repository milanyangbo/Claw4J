# 01 审计改成事件流水

**Status:** ready-for-agent
**依赖:** 无
**依据:** ADR-0027 决定二

## 背景

现在的审计是"给 `agent_step` 加七列 + 一个视图"（ADR-0023）。OpenClaw 不是这个形状：

> The ledger is separate from conversation transcripts: it records **identity, ordering, provenance,
> action, status, and normalized error codes**, but **never stores prompts, messages, tool arguments,
> tool results, command output, or raw error text**.
> 四类 action：`agent.run.started`、`agent.run.finished`、`tool.action.started`、`tool.action.finished`。
> Records **expire after 30 days**，ledger **capped at 100,000 rows**。Tool call ids 只存 **stable one-way fingerprints**。

差别不只是列多列少：它的审计是**事件**，没有父亲；我们的审计是"某个 run 的第几步"，
父亲没了（票 05）它就没处挂。

## 落点

### 1. 新表 `V19__audit_event.sql`

```text
claw4j_run.audit_event
  id            uuid pk
  seq           bigserial          -- 单调递增的账本序号（OpenClaw 的 ledger sequence）
  event_id      varchar(64)        -- 稳定事件 id，重复投递时去重
  kind          varchar(16)        -- agent_run | tool_action
  action        varchar(32)        -- 四类，见下
  status        varchar(16)        -- started/succeeded/failed/cancelled/timed_out/blocked/unknown
  error_code    varchar(48)        -- 归一化错误码，不存原始异常文本
  run_id        varchar(64)        -- 关联用，不设外键（run 表在票 05 才删）
  session_key   varchar(255)       -- 与 SessionKeys 同口径
  agent_id      varchar(64)
  actor         varchar(128)
  actor_role    varchar(64)
  channel       varchar(32)
  tool_name     varchar(128)       -- 必须是模型可见的紧凑名，否则记 unknown
  call_fingerprint varchar(64)     -- 工具调用 id 的单向指纹
  approval_id   varchar(64)
  redaction     varchar(24)        -- 恒为 metadata_only
  at            timestamptz
```

约束与索引：

- `status` 与 `action` 都写成 check 约束的**闭合词表**（OpenClaw 同）；
- `(actor, at desc)`、`(session_key, at desc)`、`(run_id, at desc)`、`(status, at desc)` 四条索引——
  这正好是 `openclaw audit` 的四个过滤维度（`--agent` / `--session` / `--run` / `--status`）；
- 唯一键 `event_id`。

### 2. `AuditLedger`（新类）

```text
record(String runId, String sessionKey, String agentId, Event event)
Event: kind / action / status / errorCode / actor / actorRole / channel / toolName / fingerprint / approvalId
prune()            -- 30 天 + 10 万行
```

- `prune()` 三个时机各调一次：启动时、每小时、每次写之后（OpenClaw 同："deleted during Gateway
  startup, hourly maintenance, and later writes"）；
- 写失败只计数（`claw4j.audit.write_errors`）+ 第一次 WARN，**不让审计把一轮回答打成失败**——
  与现在的 `ToolCallAudit` 同一个口径。

### 3. 改写 `ToolCallAudit`

现在它的四个方法都经 `RunStore.append` 写 `agent_step`。改成写事件，且**方法数减少**：

| 现在 | 改成 |
| --- | --- |
| `executed(runId, principal, tool, argsJson, result)` | `toolStarted(...)` + `toolFinished(status, errorCode)` 两条 |
| `rejected(...)` | **只写** `tool.action.finished`，status=`blocked`，error_code=`policy_denied` |
| `awaitingApproval(...)` | **一条都不写**。等审批的留痕在 `approval_request` 行上（谁提、何时、批没批），审计不复制 |
| `granted(...)` | 删除。批准后的执行会走 `toolStarted` + `toolFinished`，带 `approvalId` 关联 |

**这三行删掉的是一条重复**：同一个事实（有人要跑这个工具、还没批）现在被写在两张表里，
而 `approval_request` 那张才是真相。审计留下来只会让"今天有几条被拒"要 union 两张表。

### 4. 调用点

| 位置 | 改什么 |
| --- | --- |
| `ChatClientAgentPort` 开 run 之后 / 收尾之前 | `agent.run.started` / `agent.run.finished` |
| `MeteredToolCallback` 的 `call()` | 前后各一条 `tool.action.*`，`call_fingerprint` 用 `ToolCallAudit.digestOf` |
| `AuthorizationAdvisor` 的拒绝分支 | `tool.action.finished` / `blocked` |
| `RunPersistenceConfiguration` | `ToolCallAudit` 的构造参数从 `RunStore` 换成 `AuditLedger` |
| `AuditRetention` | 换成 `AuditLedger.prune()` 的 `@Scheduled` 壳；删 `AuditProperties#retention` |
| `AuditProperties` | 只剩 `enabled`（默认 true，与 `logging.audit.enabled` 同默认值） |

`V14` 的视图 `audit_trail` 在票 05 随 `agent_step` 一起删；本票先保留（还有人读）。

## 判据

- [x] **四类 action 齐全**：一轮"开始 → 工具 → 收尾"各写一对事件。
      证据：`ToolGovernanceTest#anExecutedCallLandsAsTwoEvents`（工具开始 + 结束）。
- [x] **同一次工具调用的两条事件指纹相同**：`toolStarted` 与 `toolFinished` 各算一次，
      用的是同一个 `CallFingerprint`。
      证据：同上，`callFingerprint` 相等那两条断言。
- [x] **被策略挡住只有一条**：status=`blocked`、`error_code=policy_denied`，**没有** started。
      反例：把 `rejected` 改成先写 started → `#aRejectedCallIsOneBlockedEvent` 与
      `#aHalfReceivedArgumentLeavesNoFingerprint` 同时红。
      证据：`ToolGovernanceTest#aRejectedCallIsOneBlockedEvent`。
- [x] **等审批不写审计**：`AuthorizationAdvisor#onDenial` 在等审批那条分支直接 return；
      审批的留痕只在 `approval_request`。
      证据：`ApprovalFlowTest#theExecutedCallCarriesTheApprovalItWasLetThroughBy` 断言该 run 的
      `tool_action` 事件正好 2 条（开始 + 成功），没有第三条"停在谁身上"。
- [x] **不存原文**：`AuditEvent` 里没有装参数、摘要、异常消息的字段——不是"没写进去"，
      是"没有地方装"。
      证据：`#theRawArgumentsNeverLand`；参数里带 `13800138000`，事件 `toString()` 里不含它。
- [x] **30 天**：`AuditLedger.prune()` 用库的时钟判，不用应用时间。
      证据：`RunSchemaSqlTest#theAuditLedgerPrunesByAgeAndSize` 前半段。
- [x] **10 万行**：按 `seq` 删最旧的。
      反例：把上限改成按 `at` 删 → 同一秒写进来的 100001 条被一起删掉，
      `overLimit` 变成 100001≠1，红。
      证据：同一方法后半段，断言 `min(seq) = max(seq) - 99999`。
- [x] **审计写失败不炸一轮**：`AuditLedger.record` catch、计数、第一次 WARN。
      证据：`ToolGovernanceTest#aBrokenLedgerNeverBreaksTheCall`。
- [x] `./scripts/verify.sh` 全绿，跳过 0，覆盖率 ≥60%。
      证据：673 条、失败 0、跳过 0，agent 64.5% / app 80.8%，10 文件 / 20 方法。

## 落地时注意

- `agent_step` 里那七列（actor / actor_role / outcome / args_digest / args_summary / approval_id）在票 05
  随表消失。本票**不要**把它们从 `agent_step` 删掉——`audit_trail` 视图还依赖它们。
- `V14` 已发布，**不能改**它的内容，只能新增迁移。
- `AuditProperties#retention` 删掉之后，`application.yml` 里的 `claw4j.audit.retention` 也要删；
  留着不报错，但会变成"配了不生效"——那正是这个仓库第一大类的缺陷。

## 不做什么

- 不做 `messages`（消息生命周期元数据）与 `executionIdentity`：OpenClaw 两个默认都是 off。
- 不做 `openclaw audit` 那样的 CLI；查现有的 HTTP 端点或直接 SQL。
- 不改会话表，不给 `ai_session` 加列。
- 不把 `args_summary`（参数摘要 200 字符）带进新审计——OpenClaw 明确不存参数。
  审批卡片上那份摘要在 `approval_request`，那条线不动。

## 实现记录

### 落在哪儿

| 东西 | 位置 |
| --- | --- |
| 表 | `V19__audit_event.sql`：`claw4j_run.audit_event` + 四条索引 + 三条 check（kind / action / status，以及 action 与 status 的配对） |
| 事件 | `AuditEvent`（含 `Scope`）+ `AuditAction` + `AuditStatus` |
| 账本 | `AuditLedger implements AuditSink`：写一条、清一次、`@PostConstruct` + `@Scheduled` + 每 500 次写 |
| 规则 | `ToolCallAudit`：四类事件、指纹、审批与执行的配对；`awaitingApproval` 删除 |
| 运行事件 | `ChatClientAgentPort`：`answer` / `streamInLane` / `doResume` 三处各一对 |
| 工具事件 | `MeteredToolCallback`：`delegate.call` 前后各一条；`scope` 从 `ToolContext` 取 `ChatMemory.CONVERSATION_ID` |
| 拒绝 | `AuthorizationAdvisor#onDenial`：等审批直接 return，其余写一条 `blocked` |
| 装配 | `RunPersistenceConfiguration`：`auditLedger` + `toolCallAudit`；删 `auditRetention` |
| 配置 | `AuditProperties` 只剩 `enabled`；`application.yml` 删 `retention`、`purge-interval` 改 `prune-interval` |

### 三处与票面不一致（都是"同一策略换调度/换形态"，不是改口径）

1. **清理不是在每次写之后，而是每 500 次写之后。** OpenClaw 的写入走有界后台批处理，
   它"每次写之后清"是廉价的；我们的写入是同步的，每条 INSERT 后面跟两条 DELETE
   （其中一条要按 seq 偏移 10 万行）会让每次工具调用多两次往返。
   启动一次、每小时一次、每 500 次写一次，上限仍然成立——最多多留 500 行。
2. **`event_id` 用随机 UUID。** OpenClaw 用它去重（同一条事件重复投递只留一行）。
   我们的写入是同步单次的，没有重放路径，所以它现在只是一个唯一值；
   字段与唯一约束保留，将来真有重放时，调用方带一个稳定 id 进来就能直接对上，不用改表。
3. **`granted` 留着，但不是事件。** 它记的是"那条批准刚被兑现"，等执行那一下取走，
   用来给结束事件补 `approval_id`。它不是审计事实，所以不落库。

另外票面里说的"`AuditRetention` 换成 `AuditLedger.prune()` 的 `@Scheduled` 壳"，
最后是**直接删掉 `AuditRetention`**：`AuditLedger` 自己带 `@Scheduled` 与 `@PostConstruct`，
再加一个壳只是多一个要维护的转发。

### 顺带修的一处不一致

`ATTR_STEP_SEQ` 这一格以前装 step 序号，而工具与模型轮分属两个序号空间——同一个属性名
在两个地方指向不同的表。现在两边都装审计账本序号，于是"哪一段 span 对应账本哪一行"
只有一个答案。`ObservabilityTest#everyActionSpanCarriesTheSeqOfTheStepItWrote` 跟着改了断言。

### 没做

- `logging.audit.messages` 与 `executionIdentity`：OpenClaw 两个默认都是 off，我们不做。
- 没有 CLI：查现有 HTTP 端点或直接 SQL。

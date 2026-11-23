# ADR-0037 审计对齐 OpenClaw：补齐读侧、终态、幂等与消息层

状态：accepted（2026-11-22）
取代：无
关联：ADR-0027（审计挂载点、只记元数据）、ADR-0017（幂等与重放）

修订 2026-11-23：`tool_call_id` 原先判为 blocked（"Spring AI 的 `ToolContext` 里没有调用 id"），
那句话仍然成立，但 id 就在同一条调用链的观测上下文里——`DefaultToolCallingManager` 调工具之前
把 `toolCall.id()` 放进了 `ToolCallingObservationContext` 并在它的 scope 里执行回调
（2.0.2 `:313-328`）。包装层因此取得到，不需要包装 `ToolCallingManager`、也不需要自己生成 id。
落地：`audit_event.tool_call_id`（V13，列名与"只有工具行有值"都照 OpenClaw 的
`audit-event-store.ts:100` / `audit-activity.ts:463`）＋读接口输出该字段；`args_digest` **不退**，
它承担审批绑定（`approval_request.args_digest`）与来源键，而 OpenClaw 没有这一列。见票 06。

修订 2026-11-23（第二批）：补两处读/写面的对齐，都不动 schema。
① **读侧 `eventType`**（票 07）：四个变体 `agent_run` / `tool_action` / `inbound_message` /
`outbound_message` 各对应一份 schema（`audit-activity.ts:183,215,249,323`）。它由 `kind` 与消息
方向推出、不落库——与它"持久层只存 kind/direction"一致；`kind` 与 `direction` 仍留给过滤。
② **子 Agent 的审计**（票 08）：每次委派开自己的 `runId`，`session_key = subagent:<定义名>`、
`agent_id = 定义名`。依据是 `docs/gateway/audit.md`："the child owns a new context; it never
reuses or mutates the parent context"。**不造 lineage**：那条投影要精确父令牌，拿不到就明说缺失，
我们也一样——没有 `parent_run_id` 这一列，就不从父 `runId` 编一个。

修订 2026-11-23（第三批）：读侧的**报文**与写侧的**归属**按它的字段逐项对完，改三处。
① **写侧归属**（票 11，V14）：`actor_type` / `actor` 记**执行者**——它的
`projectExplicitAttribution` 是 `actorType = agentId ? "agent" : "system"`、`actorId = agentId`
（`agent-event-audit.ts`）。所以我们把人从 `actor` 挪到扩展列 `requested_by`（不进读接口），
运行/工具行写 `agent` + agentId，入站消息写 `channel_sender` + 发送者假名。
同时补 `session_id`（那一段），`session_key` 改成不含段的会话键（`audit-event-store.ts:97-98`）；
`channel` 只在消息行落值，非消息行一律 null 并加 CHECK（`audit-event-store.ts:103`）。
② **读侧报文**（票 09）：补齐它每个变体必填的信封五格 `schemaVersion` / `sequence` /
`sourceSequence` / `occurredAt`（**Unix 毫秒整数**）/ `redaction`；`actor` 从字符串改成
`{type,id}` 对象；消息字段从嵌套摊平；响应从"多带字段"改成**闭对象**（`additionalProperties:
false`，`audit-activity.ts:135-142`），空字段整格省掉。为此**扩展列不再进报文**：
`args_digest` / `approval_id` 留在库里供审批绑定与 SQL 查询。
③ **读接口参数**（票 10）：补 `after` / `before`（Unix 毫秒闭区间）；参数自相矛盾时回 400
而不是空页（`findAuditActivityFilterConflict` 的三条）；`cursor` 改成不透明字符串。

**仍然没对齐的两处，都是缺一整层、不是缺字段**：`operator.read` 授权（我们整个 http 层没有鉴权）；
`run.inspect` / decision receipts（要 execution identity 那一套）。

## 背景

审计现在能写不能查，且七个终态只有三个有生产者。对照 OpenClaw `main`
（2026-11-22 现读：`packages/gateway-protocol/src/schema/audit.ts`、
`src/audit/audit-event-types.ts`、`src/audit/audit-event-store.ts`、
`docs/gateway/protocol/ledgers.md`），差距不在词表——词表是逐字相同的——而在**覆盖面**：

| 它的机制（出处） | 我们现在 | 差在哪 |
| --- | --- | --- |
| 三类 kind：`agent_run` / `tool_action` / `message`（`audit-event-types.ts:6`） | 两类 | 消息收发没有审计行 |
| 七终态全有生产者（`audit.ts:17-25`；取消/超时/拦截/结果不明各有码，`audit-event-types.ts:75-110`） | 只写 started / succeeded / failed | 取消、超时、被拦、结果不明查不到 |
| `agentId` 必填（`audit.ts:53`） | 恒 null（`AuditEvent.java:52`） | 多 agent / 子代理归不了属 |
| `source_id` + `source_sequence` 幂等去重（`audit-event-store.ts:81-116,244`） | 随机 `event_id`，无来源序号 | 重放产生重复行 |
| `audit.list` / `audit.activity.list`：seq 游标、`limit≤500`、五个过滤（`ledgers.md:11-30`） | 无读接口，只有手写 SQL | "事后查得到"依赖会写 SQL 的人 |
| 30 天 / 10 万行（`ledgers.md:16-18`） | 相同 | 已对齐 |
| 关采集不关留存（`ledgers.md:117-119`） | 相同 | 已对齐 |
| `redaction: metadata_only` 字面量（`audit.ts:59`） | 相同 | 已对齐 |

**"自己实现的要去除"的具体清单**（本次一并处理）：

1. `actor_role` 死列（`V5__run.sql:334-337` 的注释仍声称"必须带上角色"，实际恒 NULL）。
2. 内存里的审批—执行交接（`ToolCallAudit.granted`：256 条上限、整份 clear、只在单进程成立）。
3. `args_digest` 冒充 `tool_call_id`（`V5:344-347`）。**这一条不照抄**：Spring AI 的
   `ToolContext` 是自由 Map，给不出框架级调用 id。按"上游没有接缝 → 标 blocked"处理，
   见票 05 的缺口，不在这里硬造一个。

## 决定

1. **补齐终态**，四个新生产者：`blocked`（工具被策略拦下时由拦截点写）、`timed_out`
   （HTTP 流式超时）、`cancelled`（客户端断开 / 调用方取消）、`unknown`（工具 started 后
   进程内已无收尾，启动扫尾把它补成 `tool_outcome_unknown`）。
2. **`agentId` 必填**：`Scope` 增加 agent 归属，运行与工具两侧都写入；取不到时写
   `unknown`（字符串），**不允许为 null**——`null` 让"谁干的"变成不可查，`unknown` 至少可数。
3. **Schema V9**：加 `source_id / source_sequence / session_id / tool_call_id / actor_type /
   outcome / reason_code / delivery_kind / failure_stage / duration_ms / result_count /
   account_ref / conversation_ref / message_ref / target_ref`，`kind` 允许 `message`；
   删 `actor_role`；`(source_id, source_sequence)` 唯一。
4. **终态配对进库**：`error_code` 与 `kind`/`status` 的配对由 CHECK 表达（OpenClaw 用
   TypeScript 联合类型，见 `audit-event-types.ts:75-110`；库是最后一道）。
5. **读侧**：一个只读端口 `AuditQuery` + HTTP 端点呈 newest-first、seq 游标、
   `limit` 1–500、过滤 `agentId/sessionKey/runId/kind/status/direction/channel`，形状照
   `AuditListParamsSchema`（`audit.ts:63-73`）与 `ledgers.md:11-30`。只读，不提供导出、不提供
   `--explain`（`audit.run.inspect` 依赖 decision receipts，见票 05 缺口）。
6. **幂等**：`source_id + source_sequence` 唯一，重复投递 `on conflict do nothing`；与
   ADR-0017 同一个思路——重放不产生第二行。
7. **消息审计**：`kind=message`，`direction`/`outcome`/`reasonCode`/`failureStage`/
   `deliveryKind`/`duration_ms`/`result_count` 全按 `audit-event-types.ts:120-252`；
   身份引用用 HMAC 假名（`ledgers.md:41-48` 的 `hmac-sha256:v1:<keyId>:<digest>` 形状），
   密钥存一张只有它自己的表，**不入 RPC/导出**。默认 `claw4j.audit.messages=false`
   （同 `logging.audit.messages` 默认 off，`ledgers.md:117-118`）。

## 代价

- 审计写入路径从"一行 INSERT"变成"一行 INSERT + 条件 UPSERT"，每事件多一次唯一键判定；
  上限仍是每轮 2–4 行，量级不变。
- `scope` 多一个必填参数，五处构造点都要改；取不到 agent 时写 `unknown` 会把"没接线"
  暴露在数据里（这正是目的）。
- 消息审计打开后行数上升一个量级（每条消息 1–3 行）；靠 30 天 / 10 万行硬上限兜住，
  与 `ledgers.md:16-18` 同一口径。
- 读接口是新的公开契约，形状一旦出去就不好改；因此只做 OpenClaw 已有的过滤与游标，
  不自创排序参数。

## 退场条件

- OpenClaw 若把 `audit.list` 与 `audit.activity.list` 合并（当前是并存，见 `ledgers.md:130-140`），
  我们跟着只留一个。
- 若 Spring AI 将来在 `ToolContext` 里给出稳定的调用 id（`tool_call_id`），票 05 的 blocked
  项解除，`args_digest` 退为辅助列。

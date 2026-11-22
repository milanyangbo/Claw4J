# ADR-0037 审计对齐 OpenClaw：补齐读侧、终态、幂等与消息层

状态：accepted（2026-11-22）
取代：无
关联：ADR-0027（审计挂载点、只记元数据）、ADR-0017（幂等与重放）

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

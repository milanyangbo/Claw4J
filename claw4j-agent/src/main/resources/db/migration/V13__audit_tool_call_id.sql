-- 票 06：工具调用编号，列名与语义照 OpenClaw。
--
-- 那一列在 OpenClaw 是 `audit_events.tool_call_id`，而且只在 tool_action 行上有值：
--   tool_call_id: input.kind === "tool_action" ? (input.toolCallId ?? null) : null
--   （src/audit/audit-event-store.ts:100）
-- 读侧同样是这个形状：tool_action 变体里 `toolCallId: Type.Optional(NonEmptyString)`，
-- 而消息变体是 `toolCallId?: never`（packages/gateway-protocol/src/schema/audit-activity.ts:219,463）。
-- 下面的 CHECK 就是后那条"消息行不许有它"在写侧的落点。
--
-- 与既有的 `args_digest` 并存，不替换：`args_digest` 是审批链的键
-- （`approval_request.args_digest`）与来源键 `tool:<runId>:<工具>:<指纹>` 的一部分，
-- OpenClaw 没有这一列；`tool_call_id` 才是它标识"这一次调用"的那一列。同参数连调两次，
-- 指纹相同、这个编号不同——那正是加它的理由。
--
-- 可空、不建索引：OpenClaw 的查询维度是 run / session / agent / kind / status / channel，
-- 副本里没有按 tool_call_id 查的入口；给一张 30 天、上限 10 万行的表加一条谁都不过滤的索引，
-- 只是多一份写放大。
alter table claw4j_run.audit_event add column tool_call_id varchar(128);

alter table claw4j_run.audit_event
    add constraint ck_audit_event_tool_call_id
    check (tool_call_id is null or kind = 'tool_action');

comment on column claw4j_run.audit_event.tool_call_id is
    '这一次工具调用在模型侧的编号（OpenClaw 同名同义，audit-event-store.ts:100）。只有 tool_action 行有值：消息行带它是读侧 schema 明确禁止的形状（toolCallId?: never，audit-activity.ts:463）。取不到时留空，不编 UUID——编出来的会让"同参数两次调用"看起来可分、实际每次都不同，按它做的统计全是假数';

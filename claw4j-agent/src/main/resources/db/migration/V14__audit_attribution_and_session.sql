-- 归属、两把会话键、channel 的归属，三样都照 OpenClaw 的形状（ADR-0037 修订 2026-11-23 第三批）。
--
-- 1) actor / actor_type 记的是**执行者**：`agent`（有 agentId 时）或 `system`；消息行是
--    `channel_sender` 或 `agent`（`src/audit/agent-event-audit.ts` 的 `projectExplicitAttribution`：
--    `actorType = eventAgentId ? "agent" : "system"`，`actorId = agentId`）。
--    **人不在事件里**——OpenClaw 把它放在 execution decision 那一层，我们放在本表新增的扩展列
--    `requested_by`：那一列不进读接口，只为"消息审计默认关"时仍答得出"这一轮是谁触发的"。
-- 2) session_key 是会话键（不含段），session_id 是那一段。原来我们把含段的 id 写进了 session_key，
--    于是同一段对话的两把键被合成了一把（`audit-event-store.ts:97-98` 两列都在）。
-- 3) channel 只属于消息行：它按 kind 强制 null（`audit-event-store.ts:103`），
--    所以按 channel 过滤时不该带出工具行。
alter table claw4j_run.audit_event
    add column session_id   varchar(255),
    add column requested_by varchar(128);

-- 历史行：先把人从 actor 挪到 requested_by（只有非消息行的 actor 是人）
update claw4j_run.audit_event
   set requested_by = actor
 where kind <> 'message' and actor is not null;

-- 非消息行：actor_type / actor 换成执行者
update claw4j_run.audit_event
   set actor_type = case when agent_id is null or agent_id = '' then 'system' else 'agent' end,
       actor = coalesce(nullif(agent_id, ''), 'unknown')
 where kind <> 'message';

-- 消息行：actor 补齐成 OpenClaw 的 actor_id（入站是发送者假名，出站是 agent）
update claw4j_run.audit_event
   set actor = coalesce(account_ref, nullif(agent_id, ''), 'unknown')
 where kind = 'message' and (actor is null or actor = '');

-- 历史行在非消息行上的 channel 没有意义（OpenClaw 从不写）：清掉，再用约束钉住
update claw4j_run.audit_event
   set channel = null
 where kind <> 'message' and channel is not null;

alter table claw4j_run.audit_event
    add constraint ck_audit_event_channel_message_only check (kind = 'message' or channel is null);

-- 三个值逐字照 OpenClaw 的两种 actor 变体：agent / system / channel_sender
alter table claw4j_run.audit_event
    add constraint ck_audit_event_actor_type check (
        actor_type is null or actor_type in ('agent', 'system', 'channel_sender'));

-- 非消息行的归属必须落下来：读侧四个变体里 actor 是必填（`audit-activity.ts:105-112` 的
-- commonProperties + agentProperties），空着的话客户端解析不了。消息行同样的理由。
alter table claw4j_run.audit_event
    add constraint ck_audit_event_actor_present check (actor is not null);

create index ix_audit_event_session_id on claw4j_run.audit_event (session_id, seq desc);

comment on column claw4j_run.audit_event.session_id is
    '会话的那一段（含段的 id）；session_key 是不含段的会话键（ADR-0037 第三批）';
comment on column claw4j_run.audit_event.requested_by is
    '触发这一轮的人（扩展列，OpenClaw 没有）：不进读接口，只供 SQL 追"谁让这件事发生"';
comment on column claw4j_run.audit_event.actor is
    '执行者的 id：agent 行是 agentId，channel_sender 行是发送者假名（与 account_ref 同值）';

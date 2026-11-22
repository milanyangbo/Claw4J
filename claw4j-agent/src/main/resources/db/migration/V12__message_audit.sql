-- 票 04（ADR-0037 决定 3、7）：消息审计
--
-- 第三类 kind：`message`。形状逐列照 OpenClaw 的持久投影（src/audit/audit-event-store.ts:94-114）：
-- 除了 action/status 之外再多一组消息专有的列（方向、结果、原因、阶段、时长、假名引用）。
-- 为什么单开一类而不是塞进 tool_action：一次投递不是一次工具调用，塞进去之后
-- 「这个月工具被调了多少次」会把消息也算进去，而那种偏差不报错。
--
-- 这一层的铁律没变：只记元数据。消息正文、附件、收件地址原文都不进来——
-- 引用一律是 HMAC 假名（见下面 audit_identity_key），形状照 OpenClaw 的
-- `hmac-sha256:v1:<keyId>:<digest>`（docs/gateway/protocol/ledgers.md:41-48）。

alter table claw4j_run.audit_event
    -- inbound | outbound。只有 message 行有。
    add column direction         varchar(16),
    -- direct | group | channel | unknown：同一段会话是私聊还是群，决定"泄露半径"。
    add column conversation_kind varchar(16),
    -- completed | skipped | sent | suppressed | failed | unknown。与 status 分开：
    -- status 是通用生命周期，outcome 是这一层的具体结果（OpenClaw 同样两列）。
    add column message_outcome   varchar(24),
    -- 为什么是这个结果：duplicate / no_visible_payload / …（闭集见下面的 check）。
    add column reason_code       varchar(48),
    -- text | media | other。
    add column delivery_kind     varchar(16),
    -- queue | platform_send | unknown：失败发生在排队还是在平台发送。
    add column failure_stage     varchar(24),
    add column duration_ms       integer,
    add column result_count      integer,
    -- channel_sender | agent | system：谁在说这句话。
    add column actor_type        varchar(24),
    -- 四个假名引用，不含原文。
    add column account_ref       varchar(96),
    add column conversation_ref  varchar(96),
    add column message_ref       varchar(96),
    add column target_ref        varchar(96);

-- 消息行可以没有 runId：入站消息在开跑之前就到了（它可能被跳过、可能没认出身分）。
-- OpenClaw 的 MessageAuditEventInputBase.runId 同样是可选。运行/工具事件仍然必填——
-- 那一格空着的行按 runId 永远查不到，留着只会让计数虚高（V5 原注释）。
alter table claw4j_run.audit_event alter column run_id drop not null;

alter table claw4j_run.audit_event
    add constraint ck_audit_event_run_required
        check (kind = 'message' or run_id is not null);

alter table claw4j_run.audit_event drop constraint ck_audit_event_kind;

alter table claw4j_run.audit_event
    add constraint ck_audit_event_kind check (kind in ('agent_run', 'tool_action', 'message'));

alter table claw4j_run.audit_event drop constraint ck_audit_event_action;

alter table claw4j_run.audit_event
    add constraint ck_audit_event_action check (action in (
        'agent.run.started', 'agent.run.finished',
        'tool.action.started', 'tool.action.finished',
        -- 只做终态：queued / platform-started 那种进度行在 OpenClaw 里也不进
        -- audit.activity.list（docs/gateway/protocol/ledgers.md:132-136），票 04 的缺口里记了。
        'message.inbound.processed', 'message.outbound.finished'));

-- 生命周期那条只对 run / tool 成立：消息行是"一次处理完就一条终态"，没有 started 那一半。
alter table claw4j_run.audit_event drop constraint ck_audit_event_action_status;

alter table claw4j_run.audit_event
    add constraint ck_audit_event_action_status check (
        (action like '%.started' and status = 'started')
        or (action like '%.finished' and status <> 'started')
        or (action = 'message.inbound.processed'
            and status in ('succeeded', 'blocked', 'failed'))
        or (action = 'message.outbound.finished'
            and status in ('succeeded', 'blocked', 'failed', 'unknown')));

-- 错误码从九个扩到十二个：OpenClaw 的入站处理失败与两种投递失败各有自己的码
-- （src/audit/audit-event-types.ts:157-215）。合成一个 message_failed 的话，
-- 「是消息没处理成，还是没送出去」在审计里分不开——而这两个问题的处置完全不同。
alter table claw4j_run.audit_event drop constraint ck_audit_event_error_code;

alter table claw4j_run.audit_event
    add constraint ck_audit_event_error_code check (
        error_code is null or error_code in (
            'run_failed', 'run_cancelled', 'run_timed_out', 'run_blocked',
            'tool_failed', 'tool_cancelled', 'tool_timed_out', 'tool_blocked',
            'tool_outcome_unknown',
            'message_processing_failed', 'message_delivery_failed',
            'message_delivery_partial_failure'));

-- 终态配对（V11）扩到消息行：消息的 status 与 outcome 又是一组关系。
alter table claw4j_run.audit_event drop constraint ck_audit_event_terminal_pairing;

alter table claw4j_run.audit_event
    add constraint ck_audit_event_terminal_pairing check (
        (status = 'started' and error_code is null)
        or (status = 'succeeded' and error_code is null)
        or (kind = 'agent_run' and status = 'failed' and coalesce(error_code, '') = 'run_failed')
        or (kind = 'agent_run' and status = 'cancelled' and coalesce(error_code, '') = 'run_cancelled')
        or (kind = 'agent_run' and status = 'timed_out' and coalesce(error_code, '') = 'run_timed_out')
        or (kind = 'agent_run' and status = 'blocked' and coalesce(error_code, '') = 'run_blocked')
        or (kind = 'agent_run' and status = 'unknown' and error_code is null)
        or (kind = 'tool_action' and status = 'failed' and coalesce(error_code, '') = 'tool_failed')
        or (kind = 'tool_action' and status = 'cancelled' and coalesce(error_code, '') = 'tool_cancelled')
        or (kind = 'tool_action' and status = 'timed_out' and coalesce(error_code, '') = 'tool_timed_out')
        or (kind = 'tool_action' and status = 'blocked' and coalesce(error_code, '') = 'tool_blocked')
        or (kind = 'tool_action' and status = 'unknown'
            and coalesce(error_code, '') = 'tool_outcome_unknown')
        -- 消息行：成功了没有错误码；被跳过（blocked）不是错误；
        -- 失败必带这三个码之一；不知道结果时那一格空着（OpenClaw 同）。
        or (kind = 'message' and status = 'succeeded' and error_code is null)
        or (kind = 'message' and status = 'blocked' and error_code is null)
        or (kind = 'message' and status = 'failed' and coalesce(error_code, '') in (
                'message_processing_failed', 'message_delivery_failed',
                'message_delivery_partial_failure'))
        or (kind = 'message' and status = 'unknown' and error_code is null));

-- 消息行必须交代方向与结果：两列都空着的话，这一行回答不了"这条是进来的还是出去的"。
alter table claw4j_run.audit_event
    add constraint ck_audit_event_message_shape check (
        (kind <> 'message')
        or (direction is not null and message_outcome is not null));

-- 原因码是闭集：照抄 OpenClaw 的四组（src/audit/audit-event-types.ts:22-57）。
-- 自由文本在这里的代价是"按原因统计"永远统计不全，而那种漏不报错。
alter table claw4j_run.audit_event
    add constraint ck_audit_event_reason_code check (
        reason_code is null or reason_code in (
            -- 入站：处理完成
            'fast_abort', 'plugin_bound_handled', 'plugin_bound_unavailable',
            'plugin_bound_declined', 'before_dispatch_handled', 'acp_dispatch_completed',
            'acp_dispatch_empty', 'active_run_injected',
            -- 入站：跳过
            'duplicate', 'reply_operation_active', 'reply_operation_aborted', 'acp_dispatch_aborted',
            -- 入站：失败
            'acp_dispatch_failed', 'plugin_bound_error',
            -- 出站：被抑制
            'cancelled_by_message_sending_hook', 'cancelled_by_reply_payload_sending_hook',
            'empty_after_message_sending_hook', 'empty_after_reply_payload_sending_hook',
            'no_visible_payload'));

alter table claw4j_run.audit_event
    add constraint ck_audit_event_delivery_kind check (
        delivery_kind is null or delivery_kind in ('text', 'media', 'other'));

alter table claw4j_run.audit_event
    add constraint ck_audit_event_failure_stage check (
        failure_stage is null or failure_stage in ('queue', 'platform_send', 'unknown'));

alter table claw4j_run.audit_event
    add constraint ck_audit_event_conversation_kind check (
        conversation_kind is null or conversation_kind in ('direct', 'group', 'channel', 'unknown'));

alter table claw4j_run.audit_event
    add constraint ck_audit_event_direction check (
        direction is null or direction in ('inbound', 'outbound'));

-- 消息的读法与 run / tool 不同：按方向 + 时间翻页（"今天进来的都送到了吗"）。
create index ix_audit_event_message on claw4j_run.audit_event (direction, seq desc);

comment on column claw4j_run.audit_event.direction is
    'inbound | outbound：只有 kind=message 的行有（ADR-0037 票 04）';
comment on column claw4j_run.audit_event.message_outcome is
    'completed / skipped / sent / suppressed / failed / unknown：与 status 分开的"具体结果"';
comment on column claw4j_run.audit_event.account_ref is
    'HMAC 假名引用，不含原文：hmac-sha256:v1:<keyId>:<digest>';

-- 假名密钥：单行表。只在首次写消息审计时生成，读接口永远不返回它。
-- 为什么落库而不是读配置：多实例要算出同一个假名，密钥必须在共享存储里；
-- 放配置里则换一次部署就换一套假名，历史行再也对不上同一个人。
create table claw4j_run.audit_identity_key (
    -- 单行：id 恒为 1，多一行就说明有人塞了第二个密钥，而那时假名会分裂成两套。
    id         smallint    not null primary key check (id = 1),
    -- 32 字节随机。keyId 取它的前 8 字节 hex，写在假名前缀里——换密钥时旧行还认得出是哪一把。
    secret     bytea       not null,
    created_at timestamptz not null default now(),
    constraint ck_audit_identity_key_length check (octet_length(secret) = 32)
);

comment on table claw4j_run.audit_identity_key is
    '消息审计的假名密钥（ADR-0037 票 04）：单行，只在首次使用时生成，读接口不返回';

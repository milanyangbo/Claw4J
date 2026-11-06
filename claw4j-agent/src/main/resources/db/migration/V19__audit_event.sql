-- 审计是一个事件流水，不是"某个 run 的第几步"（ADR-0027 决定二）。
--
-- 形状照 OpenClaw 的 metadata-only ledger（commit f533469 与 docs/cli/audit.md）：
--   · 四类 action：agent.run.started / agent.run.finished / tool.action.started / tool.action.finished
--   · 记 identity、ordering、provenance、action、status、归一化错误码
--   · **不记**提示词、消息体、工具参数、工具结果、命令输出、原始错误文本
--   · 工具调用 id 只存单向指纹
--   · 30 天 + 10 万行，启动时 / 每小时 / 每次写之后各清一次
--
-- **为什么不再长在 agent_step 上**：step 的语义是"某个 run 的第几步"，
-- 而 run 只是一轮的句柄（ADR-0027），它连跨请求都不活。审计要回答的是
-- "谁在什么时候让谁做了什么、成没成"，那件事不需要一个父亲。
--
-- **event_id 与 seq 是两个东西**：event_id 用来去重（同一条事件重复投递只留一行），
-- seq 是账本序号（单调递增，翻页与"上限淘汰"都按它）。两者合成一个的话，
-- 去重成功就必须重排序号，而那等于把已经发出去的分页游标全部作废。
create table if not exists claw4j_run.audit_event (
    id               uuid         not null primary key,
    -- 账本序号。OpenClaw 的 ledger sequence 同名同义：单调递增，只用于排序、翻页与上限淘汰。
    seq              bigserial    not null,
    event_id         varchar(64)  not null,
    -- agent_run | tool_action，与四类 action 一一对应（见下面的 check）。
    kind             varchar(16)  not null,
    action           varchar(32)  not null,
    -- 闭合词表：多一个拼错的拼法，按 status 过滤时会被安静地漏掉，而漏掉的那些看起来像"没发生过"。
    status           varchar(16)  not null,
    -- 归一化错误码（policy_denied / tool_error / budget_exhausted …），**不是**异常消息原文。
    -- 原文里常有数据：一条贴了 SQL 的报错就够搬走一张表。
    error_code       varchar(48),
    -- 关联用。不设外键：run 表在 ADR-0027 的票 05 会被删掉，而审计比它活得久。
    run_id           varchar(64),
    session_key      varchar(255),
    agent_id         varchar(64),
    actor            varchar(128),
    actor_role       varchar(64),
    channel          varchar(32),
    -- 工具名必须是模型可见的紧凑名；认不出来的写 unknown，不写原始调用名。
    tool_name        varchar(128),
    -- 工具调用 id 的单向指纹。与审批那条用的是同一个函数（CallFingerprint），
    -- 于是"这次执行是哪次批准的"能直接对起来，而参数原文不进库。
    call_fingerprint varchar(64),
    approval_id      varchar(64),
    -- 恒为 metadata_only：这一列存在的理由不是分类，是让读的人一眼看见"这行没有内容"。
    redaction        varchar(24)  not null default 'metadata_only',
    at               timestamptz  not null default now(),
    constraint uq_audit_event_event_id unique (event_id),
    constraint ck_audit_event_kind check (kind in ('agent_run', 'tool_action')),
    constraint ck_audit_event_action check (action in (
        'agent.run.started', 'agent.run.finished',
        'tool.action.started', 'tool.action.finished')),
    constraint ck_audit_event_status check (status in (
        'started', 'succeeded', 'failed', 'cancelled', 'timed_out', 'blocked', 'unknown')),
    -- 动作与状态不能随便配：`agent.run.started` 只可能是 started，
    -- 而 `tool.action.started` 也不该带着 succeeded 出现。这条挡的是"字段填错但没人发现"。
    constraint ck_audit_event_action_status check (
        (action like '%.started' and status = 'started')
        or (action like '%.finished' and status <> 'started'))
);

-- 四条索引，正好是查询的四个维度（`openclaw audit` 的 --agent / --session / --run / --status）。
-- 时间都倒序带上：这类问题总是与"最近"一起问。
create index if not exists ix_audit_event_actor_at
    on claw4j_run.audit_event (actor, at desc);

create index if not exists ix_audit_event_session_at
    on claw4j_run.audit_event (session_key, at desc);

create index if not exists ix_audit_event_run_at
    on claw4j_run.audit_event (run_id, at desc);

create index if not exists ix_audit_event_status_at
    on claw4j_run.audit_event (status, at desc);

-- 上限淘汰要按 seq 走，单列索引让它是一次索引扫描而不是整表排序。
create index if not exists ix_audit_event_seq
    on claw4j_run.audit_event (seq desc);

comment on table claw4j_run.audit_event is
    '审计事件流水（ADR-0027）：一行 = 一次动作的开始或结束。只记元数据，不记内容';
comment on column claw4j_run.audit_event.seq is
    '账本序号，单调递增。排序、翻页与 10 万行上限淘汰都按它；与 event_id（去重用）分开';
comment on column claw4j_run.audit_event.error_code is
    '归一化错误码。不存异常消息原文：原文里常有数据，一条贴了 SQL 的报错就够搬走一张表';
comment on column claw4j_run.audit_event.call_fingerprint is
    '工具调用 id 的单向指纹，与审批 args_digest 同一个函数。参数原文不进库';
comment on column claw4j_run.audit_event.redaction is
    '恒为 metadata_only。存在的理由不是分类，是让读的人一眼看见这一行没有内容';

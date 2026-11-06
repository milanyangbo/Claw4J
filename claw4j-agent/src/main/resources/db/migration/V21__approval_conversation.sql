-- 审批不再"唤醒一件挂着的事"，而是"回到那段对话开新一轮"（ADR-0027 决定一、四）。
--
-- **改的是什么**：以前批准之后要拿 runId 把 run 从 suspended 拉起来、读 run 上的
-- principal_snapshot 与 session_id、再把结果推到 agent_run_delivery 登记的那几个地址。
-- 那条路要求"一件事"能跨请求活着，而 OpenClaw 没有这种东西——它的审批是
-- 运行内等待，或 approval-pending + 系统事件唤醒**会话**。
--
-- **审批行才是这份记录真正的主人**：谁提的请求、在哪一段对话里提的、发起人是谁，
-- 这些都是"这一条请求"的属性。以前寄存在 run 上，因为只有 run 能跨请求活；
-- 现在不需要那个前提了，就该放回它自己的行上。
--
-- **run_id 本票不删**：它仍然回答"这次审批发生在哪一轮"，票 05 随 run 一起清。
-- 只是不再有人拿它去唤醒什么。
alter table claw4j_run.approval_request
    -- 回到哪一段对话（含段）。批准之后用它在原会话里开新一轮：
    -- 上下文与 goal 都在那段对话里，不用我们从 step 拼一段 recap。
    add column if not exists session_key        varchar(255),
    -- 卡片往哪个通道发、通道内的哪个会话。与 OutboundDelivery#channel() 同口径。
    -- 以前这两个值来自 agent_run_delivery（一对多，还要求先用 runId 查回地址）；
    -- 现在它们就写在请求自己身上——一条请求只可能来自一段对话。
    add column if not exists channel            varchar(32),
    add column if not exists chat_ref           varchar(255),
    -- 发起人的身份快照（JSON），从 agent_run.principal_snapshot 迁来（V11 那一列）。
    -- 必须过来：批准之后要调模型，调模型要 Principal；用审批人的身份等于提权，
    -- 而只存 owner 反推不出角色与可访问表。
    add column if not exists principal_snapshot text;

comment on column claw4j_run.approval_request.session_key is
    '这条请求来自哪一段对话。批准后在这段对话里开新一轮，不换段（ADR-0027）';
comment on column claw4j_run.approval_request.channel is
    '审批卡片与结果的投递通道。落在请求上而不是从 run 反查：一条请求只来自一段对话';
comment on column claw4j_run.approval_request.chat_ref is
    '通道内的会话地址。审批卡片落回这里，绝不回退到「最近用过的通道」';
comment on column claw4j_run.approval_request.principal_snapshot is
    '发起人的身份快照（JSON）。批准后用它开新一轮：接着做的是当初那个人，不是批它的人';

-- run 不再有"挂起"这个状态（ADR-0027 决定一）。
--
-- **先迁数据再改约束**：删掉 suspended 之后读回一行旧 suspended 会让 RunStatus.fromColumn
-- 直接抛，于是一条历史记录能让整个列表页 500。迁成 done 不是"抹掉事实"——那一轮确实结束了，
-- 它回答的是"我需要你批准"；真正没做完的是那件事，而"那件事"这个概念本票之后不存在了。
update claw4j_run.agent_run set status = 'done' where status = 'suspended';

alter table claw4j_run.agent_run drop constraint if exists ck_agent_run_status;

alter table claw4j_run.agent_run
    add constraint ck_agent_run_status check (status in ('running', 'done', 'failed', 'budget_exhausted'));

comment on column claw4j_run.agent_run.status is
    '这一轮的结局：running / done / failed / budget_exhausted。没有 suspended：run 的寿命是一轮（ADR-0027）';

-- 收件地址表下线：结果只能回到发起它的那段对话，而那段对话在审批行/调度行/请求头上都有。
-- 留着它就得继续回答"这个 runId 登记过几个地址"，而 runId 恰恰是这一版要去掉的东西。
drop table if exists claw4j_run.agent_run_delivery;

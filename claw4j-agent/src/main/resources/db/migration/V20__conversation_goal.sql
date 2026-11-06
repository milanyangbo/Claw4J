-- 会话上的目标（ADR-0027 决定三）。
--
-- 为什么挂在 claw4j_session 而不是 claw4j_run：OpenClaw 的原话是
-- "A goal is one durable objective attached to the current OpenClaw session. Goals are
-- session state: they move with the session key, survive process restarts."
-- 挂在 run 上是原来那张表的设计，而 run 的寿命只有一轮——那正是"跨轮盯着一个目标"
-- 在那个设计里根本表达不出来的原因。
--
-- 主键是 session_key（含段）：一个会话一行，这就是"同时只能有一个目标"。
-- 段跟着 /new 换掉之后这一行读不到了，新段天然没有目标——不需要谁去删。
create table if not exists claw4j_session.conversation_goal
(
    -- 与 SessionKeys.sessionIdOf 同形状（claw4j:user:<owner>:<通道>:<chatId>:<段>）。
    session_key  varchar(255) primary key,
    -- 六态照 OpenClaw 的词表。usage_limited 我们现在进不去，但词表少一个状态，
    -- 将来加回来时要同时改约束、命令与注入逻辑三处。
    status       varchar(24)  not null,
    objective    text         not null,
    -- 最新一条状态说明：/goal pause waiting for CI 里那一句。
    status_note  varchar(1024),
    -- null 表示不限额。有值时必须是正数（OpenClaw："可选的正数 Token 预算"）。
    token_budget bigint,
    tokens_used  bigint       not null default 0,
    -- 每次改写加一。edit 与 setStatus 都加，于是"这一版目标什么时候变的"查得到。
    revision     integer      not null default 1,
    created_at   timestamptz  not null default now(),
    updated_at   timestamptz  not null default now(),
    -- 六态是闭合词表：写成 running 这类值会被库拒绝，而不是存下一个没人认得的状态。
    constraint conversation_goal_status_allowed check (status in (
        'active', 'paused', 'blocked', 'budget_limited', 'usage_limited', 'complete')),
    constraint conversation_goal_objective_present check (length(btrim(objective)) > 0),
    constraint conversation_goal_budget_positive check (token_budget is null or token_budget > 0),
    constraint conversation_goal_used_non_negative check (tokens_used >= 0)
);

-- "这个会话有没有活跃目标"是每回合都要问一次的那一句，走主键就够；
-- 但按状态筛（"现在有几个目标在推进"）也要能走索引，所以补一条。
create index if not exists conversation_goal_active_idx
    on claw4j_session.conversation_goal (status)
    where status = 'active';

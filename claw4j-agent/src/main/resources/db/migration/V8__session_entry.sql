-- 票 06：会话状态并成一张 session_entry（形状照 OpenClaw 的 SessionEntry）。
--
-- 旧的 conversation_pointer + conversation_goal 两张表在这里合成一张：
-- 它的 entry 就是"一条记录装着 sessionId、时间和 goal"，goal 换段时跟着那一条一起清掉。
-- 拆成两张表的代价已经发生过：`/new` 只换了段、没清目标，旧段的 goal 行永久留着，
-- 而 `where status = 'active'` 的部分索引把它们算进"现在有几个目标在推进"。
--
-- 本文件做三件事：建表 → 从两张旧表回填（只回填当前段的目标）→ 删旧表。
-- **回填是保数据的迁移，不是基线重来**：中间态（新旧都有）可以跑，回填完才 drop。
create table claw4j_session.session_entry (
    -- 会话节点的键（不含段）：'claw4j:user:' || owner || ':' || channel || ':' || chat_id。
    -- 与 OpenClaw 的 sessionKey 同义；段是下面的一列，不是主键的一部分。
    session_key            text        primary key,
    owner                  text        not null,
    channel                text        not null,
    chat_id                text        not null,

    -- = 它的 sessionId。换段 = 改这一列 + 清空下面的 goal_* 列（同一条 update）。
    segment                text        not null,
    -- = sessionStartedAt。存**时刻**而不是"算好的逻辑日"：daily 边界在读时算，
    -- 改 at-hour 或改时区之后判定立刻跟着变。旧表存 day_started 是这次的偏离之一。
    session_started_at     timestamptz not null,
    -- = lastInteractionAt。每轮入站刷新，idle 判定用它。
    last_interaction_at    timestamptz not null,
    -- 节点创建时间，跨段不变（它的 createdAt 就是这个语义）。
    created_at             timestamptz not null,
    -- = previousSessionId。换段时写旧段，回答"上一段是什么"。
    previous_segment       text,
    -- = totalTokens。**段级累计**，换段清零；目标的预算按 "total_tokens - goal_token_start" 算。
    total_tokens           bigint      not null default 0,

    -- goal 平铺（关系库没有嵌套对象）。goal_id is null 表示这一段没有目标。
    goal_schema_version    smallint,
    -- 它的 id：所有变更请求都带它，防"旧按钮改到替换后的目标"。
    goal_id                uuid,
    goal_objective         text,
    goal_status            varchar(24),
    -- 预算窗口的起点（创建目标时会话的累计用量）。
    goal_token_start       bigint,
    -- 起点是不是真实快照。false = 创建那轮拿不到用量，等下一次真实用量到来才采纳。
    goal_token_start_fresh boolean,
    -- 从起点算起的用量（存下的"最后一次已知值"，account 用 max 自愈）。
    goal_tokens_used       bigint,
    goal_token_budget      bigint,
    goal_continuation_turns integer,
    goal_last_status_note  text,
    -- 五个时刻分开存：合用一个 updated_at 就答不了"首次完成时间"。
    goal_paused_at         timestamptz,
    goal_blocked_at        timestamptz,
    goal_completed_at      timestamptz,
    goal_usage_limited_at  timestamptz,
    goal_budget_limited_at timestamptz,
    goal_created_at        timestamptz,
    goal_updated_at        timestamptz,

    -- 目标要么整份在、要么整份不在：三列里有一列非空就都得非空，且内容不能是空白。
    constraint session_entry_goal_all_or_nothing check (
        (goal_id is null and goal_objective is null and goal_status is null)
        or (goal_id is not null and goal_objective is not null and goal_status is not null
            and length(btrim(goal_objective)) > 0)
    ),
    -- 六态逐字照 SessionGoalStatus。
    constraint session_entry_goal_status_allowed check (
        goal_status is null or goal_status in
        ('active', 'paused', 'blocked', 'budget_limited', 'usage_limited', 'complete')
    ),
    constraint session_entry_goal_budget_positive check (
        goal_token_budget is null or goal_token_budget > 0
    ),
    constraint session_entry_goal_window_non_negative check (
        (goal_token_start is null or goal_token_start >= 0)
        and (goal_tokens_used is null or goal_tokens_used >= 0)
    )
);

-- 回填：一行 = 一个会话节点 + 它当前那一段（含目标）。
--
-- 只回填**当前段**的目标：join 条件带着 segment，旧段的 goal 行 join 不上，直接丢弃。
-- 那些正是这次要消灭的孤儿——它们今天还挂在 active 的部分索引上。
--
-- 两处只能取近似值，都写在列上：
-- 1. session_started_at 取段 id 里的毫秒前缀（`<epochMilli>-<4 位 hex>`），
--    这是准确的"段开始时刻"；形状对不上的历史行退回 day_started 的零点。
-- 2. 五个 goal 时刻：旧表只有一个 updated_at，只能把它当作"进入当前状态的时间"。
--    goal_token_start 回填 0 且 fresh = false：下一次真实用量到来时会被采纳成当时的累计，
--    旧用量（tokens_used）保持——与 account 里 shouldAdoptFreshStart 的行为一致。
with source as (
    select
        'claw4j:user:' || p.owner || ':' || p.channel || ':' || p.chat_id as session_key,
        p.owner,
        p.channel,
        p.chat_id,
        p.segment,
        p.touched_at,
        case
            when p.segment ~ '^[0-9]+-'
                then to_timestamp(substring(p.segment from '^([0-9]+)-')::bigint / 1000.0)
            else p.day_started::timestamptz
        end as started_at,
        g.status,
        g.objective,
        g.status_note,
        g.token_budget,
        g.tokens_used,
        g.created_at as goal_created_at,
        g.updated_at as goal_updated_at
    from claw4j_session.conversation_pointer p
    left join claw4j_session.conversation_goal g
        on g.session_key = 'claw4j:user:' || p.owner || ':' || p.channel || ':' || p.chat_id
                           || ':' || p.segment
)
insert into claw4j_session.session_entry (
    session_key, owner, channel, chat_id,
    segment, session_started_at, last_interaction_at, created_at, previous_segment, total_tokens,
    goal_schema_version, goal_id, goal_objective, goal_status,
    goal_token_start, goal_token_start_fresh, goal_tokens_used, goal_token_budget,
    goal_continuation_turns, goal_last_status_note,
    goal_paused_at, goal_blocked_at, goal_completed_at, goal_usage_limited_at,
    goal_budget_limited_at, goal_created_at, goal_updated_at
)
select
    session_key, owner, channel, chat_id,
    segment, started_at, touched_at, started_at, null, 0,
    case when status is null then null else 1 end,
    case when status is null then null else gen_random_uuid() end,
    objective,
    status,
    case when status is null then null else 0 end,
    case when status is null then null else false end,
    case when status is null then null else coalesce(tokens_used, 0) end,
    token_budget,
    case when status is null then null else 0 end,
    status_note,
    case when status = 'paused' then goal_updated_at end,
    case when status = 'blocked' then goal_updated_at end,
    case when status = 'complete' then goal_updated_at end,
    null,
    case when status = 'budget_limited' then goal_updated_at end,
    goal_created_at,
    goal_updated_at
from source;

drop table claw4j_session.conversation_goal;
drop table claw4j_session.conversation_pointer;

-- "这个会话有没有活跃目标"每回合都问一次，走主键就够；
-- 按状态筛（"现在有几个目标在推进"）要能走索引。合并之后孤儿没了，这个数才会降。
create index session_entry_active_goal_idx
    on claw4j_session.session_entry (goal_status)
    where goal_status = 'active';

-- 清理按最后交互时间扫。
create index session_entry_last_interaction_idx
    on claw4j_session.session_entry (last_interaction_at);

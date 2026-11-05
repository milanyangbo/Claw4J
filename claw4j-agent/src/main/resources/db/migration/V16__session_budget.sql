-- 预算从"一次 run"改挂"一段会话"（A04 的修正，见 ADR-0025）。
--
-- 为什么改：run 的寿命是一轮——`ChatClientAgentPort` 每轮收尾都调 `finish(runId, DONE)`，
-- 下一轮开的是新 run。所以 V8 那句"按 run 累计，跨轮不重置"从来没有发生过：
-- 三轮各调 6 次、一共 18 次，每一次都落在"新 run 的 0/6"之内。
-- A04 的验收框"跨 3 轮的第 7 次被拒"在当时的实现里不可能成立。
--
-- 挂到会话上，"同一段对话一共烧多少"才有地方记。会话的定义照 OpenClaw：
--   1. 键按 owner + 通道 + chatId 分（SessionKeys），群聊与私聊天然是两段；
--   2. 一段会话靠**空闲过期**结束，不靠"任务完成"——行业里没有谁来判定用户这件事做完了，
--      判错的代价是把人卡住。OpenClaw 的 idleMinutes 就是这件事，默认 120 分钟。
--
-- **不按 run 行求和**：那样子在连续对话里会"回血"——每轮都写新的 updated_at，
-- 而两小时前那几轮掉出时间窗，累计值就从 5 掉回 3。额度只会变少，不会变多，
-- 那是预算最要紧的性质，所以窗口内必须有一个只增不减的计数器。
create table if not exists claw4j_run.agent_session_budget (
    session_id        text primary key,
    -- 窗口内的累计量。超过 idle 没有动静就整行清零，见 JdbcRunStore#spentOfSession
    tool_calls        integer not null default 0,
    tokens            bigint  not null default 0,
    millis            bigint  not null default 0,
    -- 这条会话最后一次动过的时刻：判断"还算不算同一段对话"用的就是它
    touched_at        timestamptz not null,
    -- 本窗口从什么时候开始。只用于排查与审计（"这个 6 是几点开始算的"）
    window_started_at timestamptz not null
);

comment on table claw4j_run.agent_session_budget is
    '一段会话的预算累计（ADR-0025）。超过 claw4j.run.budget.idle-minutes 没动静即清零：空闲重置与 OpenClaw 的 idle reset 同构';

-- run 记下自己属于哪一段会话。默认空 = 加列之前的老 run，它们不参与会话累计
-- （宁可少算一段历史，也不要拿猜出来的归属去拦人）。
alter table claw4j_run.agent_run
    add column if not exists session_id text;

create index if not exists idx_agent_run_session
    on claw4j_run.agent_run (session_id, created_at);

comment on column claw4j_run.agent_run.session_id is
    '这段 run 属于哪一段会话（SessionKeys：owner + 通道 + chatId）。空 = 加列之前的老数据';

-- V8 的描述已经被上面的设计取代，就地更正：留着旧说法会让下一个人以为账是按 run 记的。
comment on column claw4j_run.agent_run.budget_used is
    '这一条 run 里执行过的工具调用次数（审计用）。"这段对话一共烧多少"在 agent_session_budget 上';
comment on column claw4j_run.agent_run.budget_tokens is
    '模型报上来的 token 累计；拿不到就记 0，不记估算值';
comment on column claw4j_run.agent_run.budget_ms is
    '各轮实际耗时的累计毫秒数。不含等待：等审批那一段没有任何一轮在跑';

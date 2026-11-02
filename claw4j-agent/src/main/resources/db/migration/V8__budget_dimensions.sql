-- 预算从"一轮"提到"一次 run"（A04）。V6 只留了 budget_used 一列，而"这一次 run
-- 一共烧了多少"要三项才说得清：工具调用次数、token、耗时。
--
-- **不另建一张用量表**：用量是 run 的一个属性，不是另一件事。
-- 分开存的话"这件事为什么停"要多一次 join，而它恰恰是出事之后才会被问的那个问题——
-- 那种时刻最不该依赖"记得去 join"。
--
-- **不改 budget_used 的类型**：工具调用次数用 integer 足够（上限是个位数到几十），
-- 而 token 与毫秒要 bigint——一轮带长上下文的提问就能上万，
-- 一件跑一刻钟的事是九十万毫秒。
alter table claw4j_run.agent_run
    add column if not exists budget_tokens bigint not null default 0,
    add column if not exists budget_ms     bigint not null default 0;

comment on column claw4j_run.agent_run.budget_used is
    '已执行的工具调用次数（按 run 累计，跨轮不重置，A04）';
comment on column claw4j_run.agent_run.budget_tokens is
    '模型报上来的 token 总数累计；拿不到就记 0，不记估算值';
comment on column claw4j_run.agent_run.budget_ms is
    '各轮实际耗时的累计毫秒数。不含等待：等审批那一段没有任何一轮在跑';

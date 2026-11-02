-- Run 与 step 的表（A01）。ADR-0005：一个 Postgres 多种存储，靠 schema 分开。
-- 业务在 business、会话在 claw4j_session、缓存在 claw4j_cache，run 在 claw4j_run。
--
-- 形状来自 ADR-0016：两张表，step 的粒度是"模型的一次完整动作"（一轮工具循环），
-- 不是"一次工具调用"。工具循环的内部（检索 → 调用 → 自纠错）不落库——
-- 那 75% 的行在任何场景下都没人读，还会把第三方 MCP 的工具参数原文复制一份进库。
--
-- 建表写全限定名、运行时查询也写全限定名（与 claw4j_session 那条不同：
-- 会话表是框架生成的 SQL，只能靠连接的 currentSchema 指定落点；
-- 这两张表的 SQL 全在我们自己手里，没有非限定名，也就不需要独立连接池）。
-- 少一个连接池的额外好处：容器里不会出现第二个 DataSource，
-- @ConditionalOnSingleCandidate(DataSource.class) 那条限制不会被顶掉
-- （会话那边为此专门不把池注册成 bean，见 SessionMemoryConfiguration）。
create schema if not exists claw4j_run;

create table if not exists claw4j_run.agent_run (
    -- run id 由应用生成（UUID），不是数据库序列：run 要在模型还没被调用之前就存在，
    -- 而它的 id 要能立刻回给调用方（OutboundMessage.runId），事后查序列多一次往返。
    id               varchar(64)  not null primary key,
    -- 归属键沿用会话 id（SessionKeys.ownerOf）：userId@权限指纹。
    -- 不改会话表，也不存裸 userId——一个人可以同时有多件事，session 与 run 是一对多。
    owner            varchar(255) not null,
    -- 权限指纹单独一列：owner 里已经含了它，但"这个 run 当时是按哪份权限跑的"
    -- 要能被单独查出来（审计是 step 的只读投影，P07 要用）。
    principal_digest varchar(64)  not null,
    goal             text         not null,
    status           varchar(32)  not null,
    plan_ref         varchar(64),
    budget_used      integer      not null default 0,
    created_at       timestamptz  not null default now(),
    updated_at       timestamptz  not null default now(),
    constraint ck_agent_run_status check (
        status in ('running', 'suspended', 'done', 'failed', 'budget_exhausted'))
);

-- 查"这个人有哪些事"是最高频的读（A05 的 GET /api/runs 与"做到第几步"都走它）。
create index if not exists idx_agent_run_owner_created
    on claw4j_run.agent_run (owner, created_at desc);

create table if not exists claw4j_run.agent_step (
    id             varchar(64) not null primary key,
    run_id         varchar(64) not null,
    seq            integer     not null,
    -- kind 而不是"类型字符串随便写"：回读进度时要按 kind 分组（计划 step 与前两类
    -- 在 A03 里走的是不同的读法），放任自由文本会让"还剩几步没做"变成猜。
    kind           varchar(32) not null,
    input          text,
    -- 只存工具名与摘要，不存参数原文（ADR-0016）：第三方 MCP 的参数里可能有凭据。
    tool_names     text,
    result_summary text,
    artifact_ref   varchar(255),
    status         varchar(32) not null,
    created_at     timestamptz not null default now(),
    constraint fk_agent_step_run
        foreign key (run_id) references claw4j_run.agent_run (id) on delete cascade,
    constraint ck_agent_step_status check (status in ('done', 'failed')),
    -- 同一 run 内 seq 唯一：这是"第几步"这件事唯一的来源，
    -- 没有它，并发追加会写出两步同号，而"做到第几步"会变成一个看运气的答案。
    constraint uq_agent_step_run_seq unique (run_id, seq)
);

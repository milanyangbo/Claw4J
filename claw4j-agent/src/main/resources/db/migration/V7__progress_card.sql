-- 进度卡：每会话一行，整卡替换。
--
-- 形状逐字对齐 OpenClaw 的 session_progress_cards（src/state/openclaw-agent-schema.sql），
-- 只换方言。它那张表：
--   session_key PK / markdown / steps_json / revision / created_at / updated_at
-- 两处有意偏离：
--   1) 不加外键（它 FK 到 session_nodes；我们的会话行由框架的 ai_session 管，schema 不由我们定）；
--   2) 时间列用 timestamptz（它用 epoch 整数，SQLite 没有时间类型）。
--
-- 为什么需要它：TodoWrite 只把计划交给模型自己的上下文，进程一退就没了；而"做到第几步"
-- 的读者是人（通道预览、UI），不是模型。ADR-0024 实测过"把它钉进上下文"找不到生效场景，
-- 卡也不进上下文——它是一份投影。
create schema if not exists claw4j_session;

create table if not exists claw4j_session.session_progress_cards (
    -- 会话 id（含段）。换段即换卡，旧卡随旧段一起过期。
    session_key  varchar(255) primary key,
    -- 备注。建但不写：TodoWrite 的入参里没有它，留着是为了形状对齐。
    markdown     text,
    -- 整份计划 [{"step": "...", "status": "pending|in_progress|completed"}]。
    -- 不拆成列：这里从不按步骤内容查询，只按会话读回整份。
    steps_json   text,
    -- 每次写 +1。清空也 +1（墓碑行保留），这样"曾经有过卡"这件事仍然可查，
    -- 也让写入可以带期望版本做乐观并发。
    revision     bigint      not null,
    created_at   timestamptz not null,
    updated_at   timestamptz not null
);

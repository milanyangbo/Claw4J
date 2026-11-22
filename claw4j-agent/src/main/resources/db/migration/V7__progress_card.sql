-- 进度卡：每会话一份，整卡替换。
--
-- 形状照 OpenClaw 的 progress_card：**不是消息，是一份可被覆盖的状态**。
-- 每次调用替换整张卡（"replacement, not a patch"），所以这里只有一行，没有历史版本。
--
-- 为什么需要它：TodoWrite 只把计划交给模型自己的上下文，进程一退就没了；而"做到第几步"
-- 这件事的读者是人（通道预览、UI），不是模型。ADR-0024 已实测过"把它钉进上下文"
-- 找不到生效场景，卡也不进上下文——它是一份投影，不是提示词的一部分。
create schema if not exists claw4j_session;

create table if not exists claw4j_session.progress_card (
    -- 会话 id（含段），与 ai_session 的键同源。换段即换卡，旧卡随旧段一起过期。
    session_id   varchar(255) primary key,
    -- 整份计划，不拆成列：这里从不按步骤内容查询，只按会话读回整份。
    plan_json    text         not null,
    -- 冗余两个数是为了排查时不用解析 JSON：一眼看出"几步、几件在做"。
    step_count   int          not null,
    in_progress  int          not null,
    updated_at   timestamptz  not null
);

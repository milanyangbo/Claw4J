-- 会话段的指针（ADR-0026）：这段对话现在叫什么。
--
-- 会话 id 原来是"人 + 权限 + 通道 + chatId"，永远不会变。于是"这段对话什么时候结束"
-- 这件事没有落点：预算按 120 分钟空闲自己猜一套、TTL 按 30 天删行、记忆永远不换。
-- 这张表给"一段对话"一个显式的生命周期：换段 = 换对话，旧段只读不写。
--
-- 与 OpenClaw 的 sessions.json 是同一个东西：它也是"每个 sessionKey 一条指针"，
-- 记录这段会话的元数据，重启之后靠它接回原处。
--
-- 放在 claw4j_session 而不是 claw4j_run：它回答的是"这是哪一段话"，
-- 不是"这件事做完了没有"。run 那一侧的清理、留存、审计口径都不该把它带走。
create table if not exists claw4j_session.conversation_pointer (
    -- SessionKeys.ownerOf 的结果（userId@权限指纹）。不拆成两列：
    -- 权限变了就是另一段对话，拆开反而会让人以为可以按 userId 聚合。
    owner       text        not null,
    -- 缺省落 '-'（SessionKeys.NO_CHAT），与 sessionId 里的占位保持同一口径。
    channel     text        not null,
    chat_id     text        not null,
    -- 当前段。形状是 <epochMilli>-<4 位随机>：带时间前缀是为了排查时能一眼看出
    -- 这段对话是什么时候开始的，04 的清理也能按它排序。
    segment     text        not null,
    -- 空闲判定的依据。**每次入站消息都刷新**——不刷新的话，120 分钟是从
    -- "这一段的第一次说话"算起的，之后每句话都会换段。
    touched_at  timestamptz not null,
    -- 跨天判定的依据。存的是"逻辑日"：按 claw4j.agent.zone 的日期，
    -- 且从 claw4j.session.reset.at-hour 那一小时起算新的一天（默认 4 点）。
    -- 存成 date 而不是 timestamp，是因为判定只问"是不是同一天"。
    day_started date        not null,
    primary key (owner, channel, chat_id)
);

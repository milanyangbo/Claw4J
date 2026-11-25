-- 会话身份层下线（ADR-0026 修订、ADR-0029 修订，2026-11-25 票 01）。
--
-- V3 里建了四张表，实践下来只用到一小半：session_windows 全仓没有一条 select、
-- conversations 只有 insert、session_nodes 的读者只剩"目标 / 清理 / 判换段"三处，而后两者
-- 已经随各自的功能一起下线。会话身份回到框架那两张：ai_session / ai_session_event。
--
-- 顺序不能换：先删连接表（它引用 windows 与 conversations），再删 windows（它引用 nodes），
-- 最后才是 nodes 与 conversations。反过来会撞外键。
--
-- 保留的两张：
--   session_progress_cards —— 按会话键存，没有外键，进度卡还在用
--   standing_intents       —— 意图，与会话身份无关
--
-- 代价写在 ADR 修订里：换段没了（一个会话永远一段）、目标与预算一起下线、
-- "这一段收过哪几条通道"不再可查。老数据随表一起没了，不做迁移——那份数据没有第二处可放。
drop table if exists claw4j_session.session_conversations;
drop table if exists claw4j_session.session_windows;
drop table if exists claw4j_session.session_nodes;
drop table if exists claw4j_session.conversations;

-- 05：run 不在了（ADR-0027）。
--
-- 前四张票做完之后，系统里没有东西需要 run：
--   01 审计写自己的事件流水、02 计划变成会话上的 goal、
--   03 审批与投递按对话地址、04 额度是 goal 的属性。
-- 这一支迁移把最后两张表删掉，并把剩下的两处"哪一轮"的列名改对。
--
-- **不保留 agent_step 当历史归档表**：要查历史查 audit_event。两个形状对不上——
-- step 的行没有 action / status 词表可言，搬过去只会得到一堆解释不了的行。
-- 也不写数据迁移：旧数据留在库里等 TTL，或者部署时手工导出一份。

-- 审计视图长在 step 上（V14），它是 step 的投影，step 没了它也就不存在。
-- 先删视图再删表：视图依赖表，反过来删不掉。
drop view if exists claw4j_run.audit_trail;

-- 一对一，且 agent_step 有指向 agent_run 的外键：先删子表。
drop table if exists claw4j_run.agent_step;

-- V21 已经删过一次（票 03 把投递改成"跟着对话走"）。
-- 这句留着是为了那些没跑过 V21 的部署——`if exists` 让它在跑过的库上是空操作。
drop table if exists claw4j_run.agent_run_delivery;

drop table if exists claw4j_run.agent_run;

-- 剩下两处仍叫 run_id 的列：它们装的是"这一轮的编号"（turnId），不是那个已经不存在的实体。
-- 改名而不是删列——审批行要能回答"这次批准是哪一轮发起的"，审计行要能回答"这条事件属于哪一轮"。
alter table claw4j_run.approval_request
    rename column run_id to turn_id;

alter table claw4j_run.audit_event
    rename column run_id to turn_id;

-- 索引跟着列改名一起指到新列上（PostgreSQL 会更新索引的列引用），
-- 所以这里只改名字，不重建——重建会白白扫一遍表。
alter index if exists claw4j_run.ix_audit_event_run_at
    rename to ix_audit_event_turn_at;

comment on column claw4j_run.approval_request.turn_id is
    '发起这一轮时的编号（turnId）。它不是外键、也不指向任何表：一轮答完就作废';
comment on column claw4j_run.audit_event.turn_id is
    '这一轮的编号（turnId），与 span 上的 claw4j.run.id 同值。一轮答完就作废';

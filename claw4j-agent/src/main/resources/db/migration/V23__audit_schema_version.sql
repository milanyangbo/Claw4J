-- 补齐 OpenClaw 记录的两个字段（ADR-0027 决定二）。
--
-- docs/gateway/audit.md：'Every record carries a stable event id, a monotonic owner
-- sequence, a lifecycle timestamp, actor, action, status, `schemaVersion: 1`, and
-- `redaction: "metadata_only"`.' 此前只做了后面两样里的 redaction，schemaVersion 漏了。
--
-- **为什么一个只有一种取值的列还要存在**：它是一条读侧的承诺——"这一行按第 1 版字段解释"。
-- 将来加字段（比如把 message 族补上）时，旧的读代码靠它区分"老行没有这个字段"与
-- "这个字段是空的"。没有它，加列之后所有历史行都被当成新格式解释，
-- 而那种错不会报错，只会让统计数字悄悄偏掉。
--
-- 与 goal 的 schemaVersion 是同一条约定的两处：那个在协议 schema 里
-- （packages/gateway-protocol/src/schema/sessions-goal.js），这个在库里。

alter table claw4j_run.audit_event
    add column if not exists schema_version smallint not null default 1;

alter table claw4j_run.audit_event
    add constraint ck_audit_event_schema_version check (schema_version = 1);

comment on column claw4j_run.audit_event.schema_version is
    '记录格式版本，恒为 1。存在的理由不是分类，是让将来加字段时旧行仍能按第 1 版解释';

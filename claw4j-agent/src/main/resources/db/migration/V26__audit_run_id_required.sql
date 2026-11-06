-- run_id 必填（OpenClaw 的 AuditEventSchema.runId 是 NonEmptyString）。
--
-- 事件必须属于一次已准入的运行：一行查不到轮次的审计，按 runId 查永远命中不了，
-- 留着只会让"这个月跑了多少次"的数字比能查到的多。写入侧同时收紧——
-- AuditLedger 在没有 runId 时直接跳过，计入 claw4j.audit.skipped（不是 write_errors：
-- 那不是写失败，是"没有归属"）。
--
-- **先清掉没有归属的行、再改约束**：反过来约束会先把现存的行判红，而那时迁移已经跑了一半。
-- 这些行本来就在 30 天保留期内，清掉等于让它们提前到期。
delete from claw4j_run.audit_event
where run_id is null;

alter table claw4j_run.audit_event
    alter column run_id set not null;

comment on column claw4j_run.audit_event.run_id is
    '这一轮的编号（runId，与 OpenClaw 同名，必填）。事件必须属于一次已准入的运行；'
    '与 span 上的 claw4j.run.id 同值，一轮答完即作废';

-- 票 02 / 票 01（ADR-0037 决定 3）：审计的来源键 + 删掉死列
--
-- 来源键照 OpenClaw 的 AuditEventSchema.sourceSequence 与 audit-event-store.ts 的
-- (source_id, source_sequence) 写法：同一件事重复投递只留第一行，重放不再产生第二行。
-- 与 ADR-0017 是同一个口径——幂等靠唯一键，不靠调用方记得别重复。
--
-- 一行的来源键怎么算（见 AuditEvent 的四个工厂）：
--   运行    source_id = 'run:<runId>'                             序号 1 = started，2 = finished
--   工具    source_id = 'tool:<runId>:<工具名>:<参数指纹>'        序号 1 = started，2 = finished
-- 被拦下的工具只有 finished 一条（它没有开始），序号用 1。

alter table claw4j_run.audit_event
    add column source_id       varchar(255),
    add column source_sequence smallint;

-- 历史行没有来源：用 event_id 兜底。历史里成对的 started/finished 本来就无法还原它们
-- 属于哪一次调用——当时的表里没有这个信息，编一个比留空更糟。
update claw4j_run.audit_event
   set source_id = 'legacy:' || event_id,
       source_sequence = 1
 where source_id is null;

alter table claw4j_run.audit_event
    alter column source_id set not null,
    alter column source_sequence set not null;

-- 唯一键就是幂等本身。写侧的 on conflict 靠它，读侧若要按来源翻也只靠它。
alter table claw4j_run.audit_event
    add constraint uq_audit_event_source unique (source_id, source_sequence);

comment on column claw4j_run.audit_event.source_id is
    '来源键：同一件事的多次投递共用一个值（run:<runId> 或 tool:<runId>:<工具>:<指纹>）';
comment on column claw4j_run.audit_event.source_sequence is
    '来源内序号：1 = started，2 = finished。唯一键 (source_id, source_sequence) 是幂等的落点';

-- actor_role 从来没被写过（V5 的注释承诺"必须带上角色"，代码里一个写入点都没有）；
-- OpenClaw 那边人的身份走假名引用，没有这个列。删列而不是留着，避免下一个人拿它做过滤。
alter table claw4j_run.audit_event drop column actor_role;

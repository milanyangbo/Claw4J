-- 票 15（ADR-0038）：我们没有 run 这一层，这一格是"哪一次执行"，不是"哪一个 run"
--
-- OpenClaw 把两者分开：每一轮外层请求是 executionId，而 runId 是"可能跨轮共享的会话/路由/恢复关联"
-- （docs/cli/audit.md:102-106）。我们只有前者，却一直叫它 runId。这张迁移把库里的名字改过来。
--
-- 只改名字，不改值、不改语义：
--   - 列改名后，引用它的索引与约束在 Postgres 里自动跟着改，名字里的 run 是历史遗留，一并改掉；
--   - 历史行不迁移（值本来就对，它一直是执行编号）；
--   - 旧迁移文件不动——新库从头跑出来的列名最终也是 execution_id。

alter table claw4j_run.audit_event rename column run_id to execution_id;

alter table claw4j_run.approval_request rename column run_id to execution_id;

alter table claw4j_run.execution_decision_fact rename column run_id to execution_id;

alter index claw4j_run.ix_audit_event_run rename to ix_audit_event_execution;

alter table claw4j_run.audit_event
    rename constraint ck_audit_event_run_required to ck_audit_event_execution_required;

comment on column claw4j_run.audit_event.execution_id is
    '哪一次执行（一轮一条消息）：OpenClaw 的 executionId。它的 runId 是可能跨轮共享的关联，我们没有那一层（ADR-0038）';
comment on column claw4j_run.approval_request.execution_id is
    '发起这次审批的执行编号；一次"被拦 → 批准 → 执行"会跨两次执行，执行那一侧靠审计行的 approval_id 接回来';
comment on column claw4j_run.execution_decision_fact.execution_id is
    '这次执行依据了哪条批准：与审批、审计同一个编号';

-- 票 05（ADR-0037）：审批与执行的交接从进程内 Map 换成一张表
--
-- 原来那份在 ToolCallAudit 里：256 条上限、10 分钟 TTL、超了整份 clear，只在单进程成立。
-- 后果不是"少一条线索"，是审计里的 approval_id 会成批、静默地消失——丢的时候不报错。
--
-- 形状照 OpenClaw 的 execution_decision_facts（docs/gateway/protocol/ledgers.md:123-131）：
-- 一条事实 = 某次执行依据了哪一条批准。主键就是"这一次调用"，所以同一件事只留一行。
-- 取走用 delete ... returning：同一批准不为第二次执行放行。

create table claw4j_run.execution_decision_fact (
    -- 这一轮的编号。一轮之内才有"刚批的这次执行"这回事。
    run_id      varchar(64)  not null,
    tool_name   varchar(128) not null,
    -- 参数指纹 sha256(工具名 + 规范化参数)，与审批、审计同一个函数。
    args_digest varchar(64)  not null,
    approval_id varchar(64)  not null,
    -- 只用来清过期行：审批过期之后这条线索也没有意义了。
    created_at  timestamptz  not null default now(),
    constraint pk_execution_decision_fact primary key (run_id, tool_name, args_digest)
);

create index ix_execution_decision_fact_age
    on claw4j_run.execution_decision_fact (created_at);

comment on table claw4j_run.execution_decision_fact is
    '审批与执行的交接（ADR-0037 票 05）：一次执行依据了哪条批准。取走即删，不为第二次执行放行';
comment on column claw4j_run.execution_decision_fact.args_digest is
    '与 approval_request.args_digest、audit_event.args_digest 同一个函数算出来';

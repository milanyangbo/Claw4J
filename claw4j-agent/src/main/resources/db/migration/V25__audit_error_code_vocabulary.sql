-- 错误码换成 OpenClaw 的闭合词表（ADR-0027 决定二）。
--
-- OpenClaw 的 `AuditEventErrorCodeSchema`（packages/gateway-protocol/src/schema/audit.ts）
-- 是九个值：run_failed / run_cancelled / run_timed_out / run_blocked /
-- tool_failed / tool_cancelled / tool_timed_out / tool_blocked / tool_outcome_unknown。
-- 我们原来用的是自己编的四个（policy_denied / tool_error / timeout / cancelled）——
-- 词表不是"顺便写上的几个字符串"，它是按 error_code 过滤时能被命中的全部：
-- 换个词表，同一个问题问出来的答案就不一样。
--
-- **先映射旧值、再加约束**：顺序反过来，约束会先把现存的行判红，
-- 而那时候迁移已经跑了一半。
--
-- 映射的判据是"这一行属于谁"（kind）而不是猜语义：
--   · policy_denied → tool_blocked   被策略挡住的工具调用（没有 started 那条）
--   · tool_error    → tool_failed    工具抛了异常
--   · timeout       → run_/tool_timed_out   按 kind 分
--   · cancelled     → run_/tool_cancelled   按 kind 分
--   · budget_exhausted → run_blocked 额度撞线是"这一轮被挡住"，不是工具失败
update claw4j_run.audit_event
set error_code = case
        when error_code = 'policy_denied' then 'tool_blocked'
        when error_code = 'tool_error' then 'tool_failed'
        when error_code = 'budget_exhausted' and kind = 'agent_run' then 'run_blocked'
        when error_code = 'budget_exhausted' then 'tool_blocked'
        when error_code = 'timeout' and kind = 'agent_run' then 'run_timed_out'
        when error_code = 'timeout' then 'tool_timed_out'
        when error_code = 'cancelled' and kind = 'agent_run' then 'run_cancelled'
        when error_code = 'cancelled' then 'tool_cancelled'
        else error_code
    end
where error_code is not null;

-- 闭合：写一个词表外的值会在 INSERT 时就失败，而不是等到某天按它过滤时
-- 发现"这一批怎么少了"。与 V19 给 status / action 的那两条 check 同一个理由。
alter table claw4j_run.audit_event
    add constraint ck_audit_event_error_code check (
        error_code is null or error_code in (
            'run_failed', 'run_cancelled', 'run_timed_out', 'run_blocked',
            'tool_failed', 'tool_cancelled', 'tool_timed_out', 'tool_blocked',
            'tool_outcome_unknown'));

comment on column claw4j_run.audit_event.error_code is
    '归一化错误码，闭合词表，与 OpenClaw 的 AuditEventErrorCodeSchema 逐字相同。'
    '不存异常消息原文：原文里常有数据，一条贴了 SQL 的报错就够搬走一张表';

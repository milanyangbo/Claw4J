-- 票 01（ADR-0037 决定 1/2）：终态配对落成库里的约束
--
-- OpenClaw 把「哪种终态配哪个错误码」做成联合类型（src/audit/audit-event-types.ts:75-110），
-- 写错的组合在编译期就不成立。我们这边 status / error_code 各是一个闭合词表，但两张词表之间
-- 没有关系——`agent_run` 配上 `tool_failed` 能进库（V5 只挡了 action × status）。
-- 这条 check 把关系补上，于是「按 error_code 统计」不会把两层的数混在一起。
--
-- 先把历史行归一到配对形状，再加约束：老数据里 `failed` 可能没带错误码，也可能带的是另一层的码。
-- 不改语义，只把错误码改成它该有的那个（status 与 kind 都没动）。
update claw4j_run.audit_event
   set error_code = null
 where status in ('started', 'succeeded')
   and error_code is not null;

update claw4j_run.audit_event
   set error_code = case
           when kind = 'agent_run' then case status
                   when 'failed'    then 'run_failed'
                   when 'cancelled' then 'run_cancelled'
                   when 'timed_out' then 'run_timed_out'
                   when 'blocked'   then 'run_blocked'
                   else null end
           else case status
                   when 'failed'    then 'tool_failed'
                   when 'cancelled' then 'tool_cancelled'
                   when 'timed_out' then 'tool_timed_out'
                   when 'blocked'   then 'tool_blocked'
                   when 'unknown'   then 'tool_outcome_unknown'
                   else null end
       end
 where status not in ('started', 'succeeded');

alter table claw4j_run.audit_event
    add constraint ck_audit_event_terminal_pairing check (
        -- 开始与成功都没有错误码：成功了还带一个错误码，读的人会以为这轮出过事。
        (status = 'started' and error_code is null)
        or (status = 'succeeded' and error_code is null)
        -- 运行级：五个终态各配自己的码；unknown 没有对应的运行级码（OpenClaw 也没有），留空。
        or (kind = 'agent_run' and status = 'failed' and coalesce(error_code, '') = 'run_failed')
        or (kind = 'agent_run' and status = 'cancelled' and coalesce(error_code, '') = 'run_cancelled')
        or (kind = 'agent_run' and status = 'timed_out' and coalesce(error_code, '') = 'run_timed_out')
        or (kind = 'agent_run' and status = 'blocked' and coalesce(error_code, '') = 'run_blocked')
        or (kind = 'agent_run' and status = 'unknown' and error_code is null)
        -- 工具级：同上，unknown 配 tool_outcome_unknown（工具跑了但结果不可知，这件事本身有码）。
        or (kind = 'tool_action' and status = 'failed' and coalesce(error_code, '') = 'tool_failed')
        or (kind = 'tool_action' and status = 'cancelled' and coalesce(error_code, '') = 'tool_cancelled')
        or (kind = 'tool_action' and status = 'timed_out' and coalesce(error_code, '') = 'tool_timed_out')
        or (kind = 'tool_action' and status = 'blocked' and coalesce(error_code, '') = 'tool_blocked')
        or (kind = 'tool_action' and status = 'unknown'
            and coalesce(error_code, '') = 'tool_outcome_unknown')
    );

comment on constraint ck_audit_event_terminal_pairing on claw4j_run.audit_event is
    '终态与错误码必须配对（ADR-0037 票 01）：agent_run 不会带 tool_* 的码，反之亦然';

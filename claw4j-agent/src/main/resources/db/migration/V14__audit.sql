-- 审计长在 step 上（P07，ADR-0023）：加列，不建执行表。
--
-- 决策只有一句：**执行真相只有一份**。step 已经记着"谁在第几步、调了什么、成没成"，
-- 审计另外要的五样（发起人、批准人、决定、参数摘要、留存）都补在它身上。
-- 另建一张 "audit_tool_call" 也能跑，但那时同一件事有两个答案——
-- 一张说"跑了两次"、另一张说"三次"，而对不上的那天没人知道该信哪张。
--
-- 所以这一版只做三件事：给 step 加六列、给"批准 ↔ 执行"加一条唯一约束、建一个只读视图。
--
-- **不存参数原文，也不存产出原文**：审计要回答的是"谁在什么时候让谁做了什么、谁批的"，
-- 不需要成为业务数据的第二份副本（ADR-0016 同一条纪律）。参数摘要 200 字符，
-- 够审批人与审计人认出"这是一次查表"，不够把一张表搬进审计库。
alter table claw4j_run.agent_step
    -- 发起这次调用的人与**当时的角色**。run 的 owner 里也含人，但它是归属键
    -- （userId@权限指纹），审计要的是"谁"和"以什么角色"——角色是收权时最常被问的那一格。
    add column if not exists actor        varchar(128),
    add column if not exists actor_role   varchar(64),
    -- 这一次的决定。与 status 并存不是重复：status 说的是"这一步成了没有"，
    -- outcome 说的是"为什么没成"——被拒、还在等人点头、还是真跑了。
    -- 合成一个值的话，"今天拒了几次"要按中文话术去匹配，而那正是数字最难看的来源。
    add column if not exists outcome      varchar(24),
    -- 参数指纹，与审批那一列是同一个函数（CallFingerprint）：同一个值能直接对起来，
    -- "这次调用是哪次批准的"才有得查。指纹不含原文，反推不回参数。
    add column if not exists args_digest  varchar(64),
    -- 给人看的参数摘要（已脱敏、已截断）。它是摘要，不是原文——
    -- 一个连"批的是什么"都看不见的审计记录，只能证明有人点过按钮。
    add column if not exists args_summary varchar(256),
    -- 这次调用是哪一条批准放行的。为空表示不需要审批（或者这一轮根本没有审批设施）。
    add column if not exists approval_id  varchar(64);

-- 三种决定写死在约束里：多一个拼错的拼法，按 outcome 过滤时会被安静地漏掉，
-- 而漏掉的那些看起来像是"没有发生过"。
alter table claw4j_run.agent_step
    drop constraint if exists ck_agent_step_outcome;

alter table claw4j_run.agent_step
    add constraint ck_agent_step_outcome check (
        outcome is null or outcome in ('executed', 'rejected', 'awaiting_approval'));

-- 一条批准只对应**一条执行**。兑现那一步本来是一次性的（V11 的 consumed_at），
-- 这条把同一句话落到"执行记录也只有一条"上：万一哪天有人在别处补写了一条
-- 同样 approval_id 的执行行，库会直接拒绝，而不是让审计里出现两个答案。
--
-- **谓词里的 outcome = 'executed' 不是随手加的**：停在等审批的那一行也带着同一个编号
-- （"这条停在谁身上"要能查），而它不是一次执行。把谓词写成只按 approval_id 判的话，
-- 一条"先等批、后执行"的正常流程会撞唯一约束——那等于惩罚正确的用法。
create unique index if not exists ux_agent_step_approval
    on claw4j_run.agent_step (approval_id)
    where approval_id is not null and outcome = 'executed';

-- 两个查询形状各一条：按人（"这个人在什么时候让谁做了什么"）与按结局
-- （"这个月拒了几次、卡在哪个工具上"）。时间都倒序带上，因为它们总是与"最近"一起问。
create index if not exists ix_agent_step_actor_at
    on claw4j_run.agent_step (actor, created_at desc)
    where actor is not null;

create index if not exists ix_agent_step_outcome_at
    on claw4j_run.agent_step (outcome, created_at desc)
    where outcome is not null;

-- 只读投影：这个视图就是"审计"这件事在库里的形状。
--
-- 一行 = 一次工具调用（真跑了、被拒了、或停在等审批）。step 与 approval 都不复制，
-- 视图里除了 step 自己的列，多出来的只有 approval 那一侧的"谁批的、什么时候、什么结论"——
-- 那些本来就在审批表里，投影只是把它们接到同一行上。
--
-- **为什么不把审计写成一张表**：视图没有写路径，也就不可能与执行真相不一致。
-- 这也是这一票唯一的"架构"结论（ADR-0023）。
--
-- 留存：随 run 走。run 留 90 天（`claw4j.audit.retention`），到点由
-- `AuditRetention` 删掉整件旧事——step 级联一起走，于是审计不会活得比它的执行记录更久，
-- 也不会出现"有一条审计行，但它指向的执行记录已经没了"。
create or replace view claw4j_run.audit_trail as
select s.run_id                              as run_id,
       s.seq                                 as seq,
       r.owner                               as owner,
       r.goal                                as goal,
       s.actor                               as actor,
       s.actor_role                          as actor_role,
       s.tool_names                          as tool,
       s.outcome                             as outcome,
       s.args_digest                         as args_digest,
       s.args_summary                        as args_summary,
       s.result_summary                      as reason,
       s.approval_id                         as approval_id,
       a.status                              as approval_status,
       a.decided_by                          as approved_by,
       a.decided_at                          as approved_at,
       a.reason                              as approval_reason,
       s.created_at                          as at
  from claw4j_run.agent_step s
  join claw4j_run.agent_run r on r.id = s.run_id
  left join claw4j_run.approval_request a on a.id = s.approval_id
 where s.kind = 'tool_calls';

comment on view claw4j_run.audit_trail is
    '审计投影（P07）：一行 = 一次工具调用。只读：真相在 agent_step 与 approval_request，这里不复制';
comment on column claw4j_run.agent_step.actor is
    '发起这次调用的人（userId）。与 run.owner 不同：owner 是归属键（userId@权限指纹）';
comment on column claw4j_run.agent_step.actor_role is
    '发起时的角色。收权之后先问的就是这一格：他当时是什么身份';
comment on column claw4j_run.agent_step.outcome is
    '这一次的决定：executed / rejected / awaiting_approval。与 status 分工：status 说成没成，这里说为什么没成';
comment on column claw4j_run.agent_step.args_digest is
    '参数指纹，与 approval_request.args_digest 同一个函数。不存原文：审计不是业务数据的第二份副本';
comment on column claw4j_run.agent_step.args_summary is
    '参数摘要（脱敏 + 前 200 字符）。够认出"这是一次查表"，不够搬走一张表';
comment on column claw4j_run.agent_step.approval_id is
    '这次调用是哪条批准放行的。唯一（只看 executed）：一条批准只放行一次执行';

-- 审批维度（P01，ADR-0020）：权限从"能不能"变成"能不能 + 要不要批"。
-- 今天能表达的只有"这个身份能不能执行这个动作"，于是"能执行，但太重，要有人点头"
-- 这句话说不出来——`run_script` 要么全放、要么全不给。
--
-- **审批不替代鉴权**：这张表里的 granted 只回答"有人同意过"。同一次调用仍要先过
-- 动作与表那两道鉴权（ToolPermission#grantedTo），两条都满足才执行。把两者合成一条，
-- 拒绝话术就再也说不清"是没权限还是等你批"。
--
-- **run_id 可以为空，且不设外键**：这条记录的寿命与 run 不一样——run 的留存策略比审批短，
-- 级联删掉等于抹掉一段"谁批过"的留痕，而 P07 的审计恰恰要按它回答"这次调用是哪次批准的"。
-- 空表示这次调用不在一个 run 里（子 Agent 那一层还没有 runId，A10）。
--
-- **过期不是一种状态，是时间**：有效 = status = 'granted' 且 expires_at 还没到。
-- 另立一个 'expired' 状态的话，要么有个谁都没写的状态，要么得有个定时器去改它，
-- 而"现在还有效吗"这个问题本来一行比较就能答。
create table if not exists claw4j_run.approval_request (
    id           varchar(64)  not null,
    run_id       varchar(64),
    -- 提请求的人。批准是批给某个人的，不是批给某个动作：
    -- 同一句话换一个没被批过的人来问，仍然要重新批一遍。
    owner        varchar(128) not null,
    tool_name    varchar(128) not null,
    status       varchar(16)  not null,
    -- 拒绝的理由要回灌模型（P02），它得能存住一句话。
    reason       varchar(1024),
    requested_at timestamptz  not null default now(),
    -- 没给默认值是有意的："默认十分钟"是策略，写在代码里（P02），不写在表上——
    -- 表上一个默认值会让"改了配置但库里还是十分钟"这种偏差看不见。
    expires_at   timestamptz  not null,
    decided_at   timestamptz,
    -- 谁批的由 P03 填（审批人名单）。这一列先留着：没有"谁"的记录事后没法追，
    -- 而补一列比补一整段留痕便宜得多。
    decided_by   varchar(128),
    constraint pk_approval_request primary key (id),
    -- 三种状态写死在约束里：多一个拼错的拼法，查询按 status = 'granted' 过滤时
    -- 会安静地把它当"没批准"，而那条记录看起来是批过的。
    constraint ck_approval_request_status check (status in ('pending', 'granted', 'denied')),
    -- 挂了结论就必须说清什么时候定的；还挂着的就不该有决定时间。
    -- 这条挡的是"批了但没批"：没有时间的 granted 在审计里是一句无法追溯的断言。
    constraint ck_approval_request_decided check (
        (status = 'pending' and decided_at is null)
        or (status <> 'pending' and decided_at is not null)
    )
);

-- 判定只按"这个人 + 这个工具"查当前有效的那条，索引跟着这个形状走。
create index if not exists ix_approval_request_lookup
    on claw4j_run.approval_request (owner, tool_name, status);

comment on table claw4j_run.approval_request is
    '审批请求（P01）：一条请求 = 某人要执行某个工具。有效 = status granted 且未过期；批准记录不替代鉴权';
comment on column claw4j_run.approval_request.tool_name is
    '要执行的工具名（不是动作名）：动作是权限的口径，批准的对象是这一次会跑起来的那个工具';
comment on column claw4j_run.approval_request.run_id is
    '这次调用属于哪件事；为空表示不在一个 run 里。不设外键：run 的留存比审批短，级联删掉等于抹掉留痕';
comment on column claw4j_run.approval_request.expires_at is
    '批到什么时候。过期不是一种状态，是时间：有效 = granted 且 expires_at > now()';

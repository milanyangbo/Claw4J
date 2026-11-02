-- 定时任务（P04）：助手自己到点醒来。
--
-- **为什么是一张表 + 每 30 秒扫一次，而不是引一个调度器**：Quartz 要一整套自己的表与
-- 线程池，Redis 要多一个组件，外部调度器要多一个部署单元。这个项目要的只是
-- "到点开一个 run"，而 run 那边已经有入口、鉴权、投递、审批。多引一个组件，
-- 换来的是"两套状态各自认为自己在跑"——那类事故排查起来要先对齐两个时钟。
-- 依据是 ADR-0020 里"不引新组件"的裁决，不是图省事。
--
-- **同一时刻两个实例不许跑同一条**：认领是一条带 `for update skip locked` 的 update
-- （见 ScheduleStore#claim）。第二个实例撞上被锁的行时**跳过**而不是等——
-- 等会让它的整轮扫描卡在第一个实例的一次模型调用上。
--
-- **next_run_at 存在库里，不留在内存**：这是"实例重启不丢已定好的时间点"的全部实现。
-- 进程里只留"下一次扫描是什么时候"，那是 @Scheduled 的事，丢了也无所谓。
--
-- **不补跑**：错过的时间点（实例停了一夜）只跑一次，下一次从"现在"往后算。
-- 补跑三次等于在重启后补三份账单，而人要看的是现在这一份。
create table if not exists claw4j_run.agent_schedule (
    id                 varchar(64)  not null primary key,
    -- 归属键沿用 run 的口径（SessionKeys.ownerOf：userId@权限指纹）。
    -- "我的定时任务"与"我的 run"是同一个人的东西，两处用不同的键会让它们对不上。
    owner              varchar(255) not null,
    -- 结果送回哪儿。与 agent_run_delivery 的两列同口径；为空表示这个任务不推结果，
    -- 只能靠 GET /api/runs/{id} 取。
    channel            varchar(32),
    channel_ref        varchar(255),
    -- 每次到点要问的那句话，原样交给模型。它不是"计划"——计划属于某一次 run，
    -- 而这句话是每一次都一样的起点。
    goal               text         not null,
    -- 6 段 cron（秒 分 时 日 月 周），由 Spring 的 CronExpression 解析——它不接受 5 段，
    -- 少写一段不会被"猜一个秒"，而是当场报错（P05 把这句话原样告诉模型）。
    -- 存原文而不是存"每隔多少秒"：人说的是"每天早上九点"，那是日历上的一个点，
    -- 不是一段间隔。
    cron               varchar(128) not null,
    -- 时区。为空表示用部署所在时区（JVM 的 systemDefault）——"九点"是哪个九点，
    -- 只能由部署环境回答。写进来是为了让跨时区（或测试里）有一个确定答案。
    zone               varchar(64),
    -- 默认 silent：定时任务默认静默完成，结果随时能查到（spec 第 16 条）。
    -- 说"发我"的那一类由创建者改成 done_only。
    notify_policy      varchar(24)  not null default 'silent',
    -- 任务主人的身份快照（JSON），与 agent_run.principal_snapshot 同一个形状。
    -- 到点那一刻没有人发消息，鉴权要一份角色与可访问表——猜一个身份去跑，
    -- 比停在那儿更糟（P02 定的口径）。
    principal_snapshot text,
    -- 下一次该跑的时刻。它就是"已定好的时间点"，也是扫描的唯一入口条件。
    next_run_at        timestamptz  not null,
    last_fired_at      timestamptz,
    -- 租约：认领时写"我占到什么时候"，跑完清空。
    -- 没有它，认领之后进程崩了的那一条会一直躺在"已认领"里——行锁随连接消失，
    -- 但没有任何人知道该不该再跑它。租约过期即视为没人占。
    claimed_until      timestamptz,
    -- 连续失败次数。成功一次清零：这里问的是"还连着坏吗"，不是"一共坏过几次"。
    failures           integer      not null default 0,
    last_error         varchar(1024),
    enabled            boolean      not null default true,
    -- 停用原因。手动暂停也停用，那时它为空——"为什么没有下一次"有两种答案，
    -- 混成一句"已停用"的话，接到通知的人也判断不了要不要处理。
    disabled_reason    varchar(1024),
    created_at         timestamptz  not null default now(),
    updated_at         timestamptz  not null default now(),
    -- 三档写死在约束里，与 V9 的 ck_agent_run_notify_policy 逐字对应：
    -- 通知策略拼错的表现是"该说的一次没说"，只在事后被发现。
    constraint ck_agent_schedule_notify_policy check (
        notify_policy in ('done_only', 'silent', 'state_changes')),
    constraint ck_agent_schedule_failures check (failures >= 0),
    -- 停用的才允许有原因；在跑的任务带着一句停用原因是自相矛盾的。
    -- 写成 `not enabled or ...`：`enabled = false` 时无条件放行，
    -- `enabled = true` 时要求原因为空。反过来写成 `enabled or ...` 会把
    -- "停用并记下原因"整条打死——而那正是这个功能唯一的正常出口。
    constraint ck_agent_schedule_disabled check (not enabled or disabled_reason is null)
);

-- 扫描只问"到点且还启用"，索引跟着这个形状走，并且做成部分索引：
-- 停用的任务永远不进这个索引，它们只会越来越多。
create index if not exists ix_agent_schedule_due
    on claw4j_run.agent_schedule (next_run_at)
    where enabled;

-- "这个人有哪些定时任务"是接下来最高频的读（P05 列出任务、P07 审计）。
create index if not exists ix_agent_schedule_owner
    on claw4j_run.agent_schedule (owner, created_at desc);

comment on table claw4j_run.agent_schedule is
    '定时任务（P04）：一条 = 某人每隔一段时间让助手做同一件事。到点创建一个 run，其余全部复用现有路径';
comment on column claw4j_run.agent_schedule.goal is
    '每次到点要问模型的那句话。与 agent_run.goal 不是同一个东西：那条属于某一次运行，这条属于任务本身';
comment on column claw4j_run.agent_schedule.cron is
    'cron 原文（6 段：秒 分 时 日 月 周）。存日历上的点而不是间隔：人说"每天早上九点"';
comment on column claw4j_run.agent_schedule.zone is
    '时区；为空表示用部署所在时区。写进来是为了让"九点"有确定答案';
comment on column claw4j_run.agent_schedule.next_run_at is
    '下一次该跑的时刻。实例重启不丢时间点靠的就是这一列';
comment on column claw4j_run.agent_schedule.claimed_until is
    '认领租约：占着的那一段不算别人的。没有它，认领后崩溃的那条没人敢再跑';
comment on column claw4j_run.agent_schedule.failures is
    '连续失败次数，成功一次清零。到了阈值自动停用，并推一条带最后错误的通知';
comment on column claw4j_run.agent_schedule.principal_snapshot is
    '任务主人的身份快照（JSON）：到点那一刻没有人发消息，鉴权要靠它';

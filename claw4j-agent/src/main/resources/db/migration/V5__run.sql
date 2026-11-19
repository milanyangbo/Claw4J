-- 审批、定时任务、审计流水与 exec 白名单（四张表，都在 claw4j_run）。
--
-- 一句话说清这几张表的共同前提：run 不是实体，是一轮的句柄——它答完就作废，跨请求不活。
-- 于是"某事做完了没有"这个概念不存在，审计是**自己的事件流水**，目标挂到会话上，
-- 审批与投递按对话地址定位。
create schema if not exists claw4j_run;

-- 审批（P01，ADR-0020）：权限从"能不能"变成"能不能 + 要不要批"。
--
-- **审批不替代鉴权**：allowed 只回答"有人同意过"。同一次调用仍要先过动作与表那两道鉴权，
-- 两条都满足才执行。把两者合成一条，拒绝话术就再也说不清"是没权限还是等你批"。
--
-- 生命周期五态、结论单列 decision、终态原因单列 terminal_reason。三列分开是因为它们答三个
-- 不同问题："这条走到哪一步了"、"人给的是什么"、"为什么结束"。合成一列时，"过期没批"与
-- "人拒了"都会写成 denied，事后分不出是没人看还是有人不同意。
--
-- 过期要**落终态**，不能只靠 expires_at 比较：那样过期的行永远停在 pending，答不了
-- "这条最后怎么了"。ApprovalService 的清扫按分钟判一次，把过期写成
-- expired / deny / timeout / resolver_kind = system。
create table claw4j_run.approval_request (
    id           varchar(64)  not null,
    -- 发起这一轮时的编号（runId，与 OpenClaw 同名）。**不是外键、不指向任何表**：
    -- 一轮答完就作废，而审批的留存比它长。为空表示这次调用不在一个 run 里。
    run_id       varchar(64),
    -- 提请求的人。批准是批给某个人的，不是批给某个动作：
    -- 同一句话换一个没被批过的人来问，仍然要重新批一遍。
    owner        varchar(128) not null,
    -- 要执行的工具名（不是动作名）：动作是权限的口径，批准的对象是那一次会跑起来的工具。
    tool_name    varchar(128) not null,
    -- 生命周期五态：
    -- pending 挂着 / allowed 批了 / denied 拒了 / expired 超时 / cancelled 运行时中断。
    -- 后两个不是"另一种拒绝"：它们记的是"没人做决定"这件事本身，而 denied 是有人做了决定。
    status       varchar(16)  not null,
    -- 人给的结论：allow-once 一次性 / allow-always 长期（同时按命令段落 exec_allowlist）/ deny。
    decision     varchar(16),
    -- 为什么结束。**当前只写 user（人点的）与 timeout（清扫判超时）**：其余五档（回执解析失败、
    -- 找不到投递路由、运行时中止、网关重启、存储损坏）对应的入口还没有。词表照抄是为了
    -- 将来接上时不用改表，不是为了现在能写出这些值。
    terminal_reason varchar(32),
    -- 拒绝的理由要回灌模型（P02），它得能存住一句话。
    reason       varchar(1024),
    requested_at timestamptz  not null default now(),
    -- 没给默认值是有意的："默认十分钟"是策略，写在代码里（P02），不写在表上——
    -- 表上一个默认值会让"改了配置但库里还是十分钟"这种偏差看不见。
    expires_at   timestamptz  not null,
    -- 定下结论的时刻。它、decision、status 三列合起来回答"谁在什么时候、把这条推到了哪个终态"。
    resolved_at  timestamptz,
    -- 谁把结论送进来的：channel（人在卡片上点）/ system（清扫判定超时）。
    -- device 与 runtime 我们还没有入口。
    resolver_kind varchar(16),
    -- 谁批的（人名/通道侧 id）。没有"谁"的记录事后没法追。
    resolver_id  varchar(128),
    -- 参数指纹 sha256(工具名 + 规范化参数)，对象 key 递归排序、数字归一（见 CallFingerprint）。
    -- 参数变了就是另一次，要重新批。
    --
    -- **不含 runId、不含序号**：这与幂等键（ADR-0017）刚好相反，且是最容易被"顺手改对"的一处。
    -- 幂等键要区分每一次执行（否则重复发一封邮件），审批指纹要跨执行复用一次批准
    -- （否则每件新事都要重批，人会开始闭眼点）。两个哈希形状相似、口径相反。
    --
    -- **这一列是 OpenClaw 没有的**（它按 tool_call_id 绑定一次调用，用 standing grants 表达"长期"）。
    -- 我们拿不到 tool_call_id（框架的 ToolContext 里没有），于是用"同一工具同一参数"近似
    -- "同一次调用"。代价写在语义上：同参数连调两次，一次批准能兑现两次——所以兑现是
    -- 一次性的（consumed_at），不是"批准了就永久放行"。
    args_digest  varchar(64)  not null default '',
    -- 给人看的参数摘要（已脱敏、已截断）。审批人看不到自己批的是什么时，点下去等于盖章。
    -- 存的是**摘要**不是原文。
    args_summary varchar(512),
    -- 一次性：兑现过的批准不再有效。列一个 consumed_at 而不是删行，
    -- 因为"这条批准被谁用过、什么时候用掉的"正是事后要追的东西（P07）。
    consumed_at  timestamptz,
    -- 谁兑现的（同 owner 的哪一轮）。与 consumed_at 成对：只知道时间不知道人，
    -- 出事时还是要翻日志。
    consumed_by  varchar(128),
    -- 回到哪一段对话（含段）。批准之后用它在原会话里开新一轮：
    -- 上下文与 goal 都在那段对话里，不用我们从 step 拼一段 recap。
    session_key  varchar(255),
    -- 卡片往哪个通道发、通道内的哪个会话。与 OutboundDelivery#channel() 同口径。
    -- 这两个值写在请求自己身上，不从别处反查：一条请求只可能来自一段对话。
    channel      varchar(32),
    chat_ref     varchar(255),
    -- 发起人的身份快照（JSON）。批准之后要调模型，调模型要 Principal；用审批人的身份等于提权
    -- （审批人权限通常比提问人大），而只存 owner 又反推不出角色与可访问表。
    -- 存 JSON 而不是拆成三列：它是快照，不是一个要 join 的实体；拆成列之后每加一个身份维度
    -- 就要加一列迁移，而它作为快照本来就不该参与查询。
    principal_snapshot text,
    -- 批准那一刻冻结的执行计划（exec：command + cwd）。**一份 JSON 而不是拆成列**：
    -- 它是一份快照，不参与查询、不 join、不需要索引；拆成 command / cwd 之后，每次计划里
    -- 多一样（脚本类还要加被调文件的哈希）就要一次迁移，而这一列的读法只有"按 id 取出来整份比"。
    --
    -- 为什么要有它：批准的对象是**那一条具体命令**，不是"这个工具"。只存参数指纹时，
    -- 改动之后命不中，表现出来的只是一句"没有批准"——人能做的只有再批一次，而看不出差别在哪。
    -- 存下批准那一刻的原文，才答得出"批的是 `./run.sh a`，现在要跑的是 `./run.sh b`"。
    --
    -- 可以为空：除 exec 之外的审批（工具名 + 参数指纹就够定位"批的是什么"）不写它。
    -- 为空时按"这条批准说不清批的是哪条命令"处理——不放行，要重批一次。
    frozen_plan  jsonb,
    constraint pk_approval_request primary key (id),
    -- 五态写死在约束里：多一个拼错的拼法，查询按 status = 'allowed' 过滤时
    -- 会安静地把它当"没批准"，而那条记录看起来是批过的。
    constraint ck_approval_request_status check (
        status in ('pending', 'allowed', 'denied', 'expired', 'cancelled')),
    constraint ck_approval_request_decision check (
        decision is null or decision in ('allow-once', 'allow-always', 'deny')),
    constraint ck_approval_request_resolver_kind check (
        resolver_kind is null or resolver_kind in ('device', 'channel', 'runtime', 'system')),
    constraint ck_approval_request_terminal_reason check (
        terminal_reason is null or terminal_reason in (
            'user', 'timeout', 'malformed-verdict', 'no-route',
            'run-aborted', 'gateway-restart', 'storage-corrupt')),
    -- 五态与其余列的一致性，逐条照 OpenClaw 的 operator_approvals 的 CHECK 搬过来。
    -- 它挡的是"批了但没批"这类自相矛盾：allowed 却没有结论时间、denied 却带着兑现时间、
    -- expired 却说得出是谁点的。
    constraint ck_approval_request_state check (
        (status = 'pending'
            and decision is null and terminal_reason is null
            and resolved_at is null and resolver_kind is null and resolver_id is null
            and consumed_at is null and consumed_by is null)
        or (status = 'allowed' and decision in ('allow-once', 'allow-always')
            and terminal_reason = 'user'
            and resolved_at is not null and resolver_kind is not null)
        or (status = 'denied' and decision = 'deny'
            and terminal_reason in ('user', 'malformed-verdict', 'no-route', 'storage-corrupt')
            and resolved_at is not null and resolver_kind is not null
            and consumed_at is null and consumed_by is null)
        or (status = 'expired' and decision = 'deny' and terminal_reason = 'timeout'
            and resolved_at is not null and resolver_kind is not null
            and consumed_at is null and consumed_by is null)
        or (status = 'cancelled' and decision = 'deny'
            and terminal_reason in ('run-aborted', 'gateway-restart')
            and resolved_at is not null and resolver_kind is not null
            and consumed_at is null and consumed_by is null)
    ),
    -- 时间单调。没有 updated_at 列，把它写成 resolved_at <= now() 又不行（CHECK 里不能有 now()），
    -- 所以只留能表达的三条。
    constraint ck_approval_request_times check (
        expires_at >= requested_at
        and (resolved_at is null or resolved_at >= requested_at)
        and (consumed_at is null or (resolved_at is not null and consumed_at >= resolved_at))
    ),
    -- 兑现只属于 allow-once：allow-always 不该被"用掉"，denied/expired/cancelled 更不该有兑现时间。
    constraint ck_approval_request_consumed check (
        (consumed_at is null and consumed_by is null)
        or (status = 'allowed' and decision = 'allow-once'
            and consumed_at is not null and consumed_by is not null)
    )
);

-- 判定只按（人 + 工具 + 指纹）查当前有效的那条，索引跟着这个形状走；
-- 部分索引带上 consumed_at is null：已兑现的行不会再被读到，不该占索引。
create index ix_approval_request_lookup
    on claw4j_run.approval_request (owner, tool_name, args_digest, status)
    where consumed_at is null;

-- 清扫要找的是"还挂着且已过点"的那些，索引跟着这个形状走。没有它，每分钟一次的清扫是全表扫。
create index ix_approval_request_due
    on claw4j_run.approval_request (status, expires_at)
    where status = 'pending';

comment on table claw4j_run.approval_request is
    '审批请求（P01）：一条请求 = 某人要执行某个工具。allowed 只代表有人同意过，不替代鉴权；过期与中断由清扫落成终态';
comment on column claw4j_run.approval_request.tool_name is
    '要执行的工具名（不是动作名）：动作是权限的口径，批准的对象是这一次会跑起来的那个工具';
comment on column claw4j_run.approval_request.run_id is
    '发起这一轮时的编号（runId，与 OpenClaw 同名）。不是外键、不指向任何表：一轮答完就作废';
comment on column claw4j_run.approval_request.status is
    '生命周期五态（照 operator_approvals）：pending/allowed/denied/expired/cancelled。后两个记的是「没人做决定」，denied 是「有人做了决定」';
comment on column claw4j_run.approval_request.decision is
    '人给的是什么结论：allow-once 一次性 / allow-always 长期（同时落 exec_allowlist）/ deny 不许';
comment on column claw4j_run.approval_request.terminal_reason is
    '为什么结束。当前只写 user（人点的）与 timeout（清扫判超时）；词表照抄 openclaw，其余五档对应的入口还没有';
comment on column claw4j_run.approval_request.expires_at is
    '批到什么时候。过期也能被当成时间读（expires_at > now()），但清扫会把它落成 expired 终态：审计要答「最后怎么了」，不能永远停在 pending';
comment on column claw4j_run.approval_request.resolved_at is
    '定下结论的时刻。挂在终态上就必须有（约束保证）：没有时间的 allowed 在审计里是一句无法追溯的断言';
comment on column claw4j_run.approval_request.resolver_kind is
    '谁把结论送进来的：channel（人在卡片上点）/ system（清扫判超时）。device 与 runtime 我们还没有入口';
comment on column claw4j_run.approval_request.args_digest is
    '参数指纹 sha256(工具名 + 规范化参数)。不含 runId 与序号：批准要跨执行复用（与幂等键口径相反）。openclaw 没有这一列，它按 tool_call_id 绑定单次调用——我们拿不到 tool_call_id';
comment on column claw4j_run.approval_request.args_summary is
    '给人看的参数摘要（脱敏 + 截断）。不存原文，但也不该让人盲签';
comment on column claw4j_run.approval_request.consumed_at is
    '被兑现的时间。一次性：兑现过的批准不再有效，但这一行留着，审计要问"谁批的、谁用掉的"';
comment on column claw4j_run.approval_request.consumed_by is
    '谁兑现的。与 consumed_at 成对：只知道时间不知道人，出事时还是要翻日志';
comment on column claw4j_run.approval_request.session_key is
    '这条请求来自哪一段对话。批准后在这段对话里开新一轮，不换段（ADR-0027）';
comment on column claw4j_run.approval_request.channel is
    '审批卡片与结果的投递通道。落在请求上而不是从别处反查：一条请求只来自一段对话';
comment on column claw4j_run.approval_request.chat_ref is
    '通道内的会话地址。审批卡片落回这里，绝不回退到「最近用过的通道」';
comment on column claw4j_run.approval_request.principal_snapshot is
    '发起人的身份快照（JSON）。批准后用它开新一轮：接着做的是当初那个人，不是批它的人';
comment on column claw4j_run.approval_request.frozen_plan is
    '批准那一刻冻结的执行计划（exec：command + cwd）。执行前与它逐字符比，不一致按 approval mismatch 拒；为空表示这条批准说不清批的是哪条命令';

-- 定时任务（P04/P05）：助手自己到点醒来。
--
-- **为什么是一张表 + 每 30 秒扫一次，而不是引一个调度器**：Quartz 要一整套自己的表与线程池，
-- Redis 要多一个组件，外部调度器要多一个部署单元。这个项目要的只是"到点开一次"，
-- 而那条路已经有入口、鉴权、投递、审批。多引一个组件，换来的是"两套状态各自认为自己在跑"
-- ——那类事故排查起来要先对齐两个时钟。
--
-- **同一时刻两个实例不许跑同一条**：认领是一条带 `for update skip locked` 的 update。
-- 第二个实例撞上被锁的行时**跳过**而不是等——等会让它的整轮扫描卡在第一个实例的一次模型调用上。
--
-- **不补跑**：错过的时间点（实例停了一夜）只跑一次，下一次从"现在"往后算。
-- 补跑三次等于在重启后补三份账单，而人要看的是现在这一份。
create table claw4j_run.agent_schedule (
    id                 varchar(64)  not null primary key,
    -- 归属键沿用会话/run 的口径（SessionKeys.ownerOf：userId@权限指纹）。
    -- "我的定时任务"与"我说的那段话"是同一个人的东西，两处用不同的键会让它们对不上。
    owner              varchar(255) not null,
    -- 结果送回哪儿。与审批行的 channel / chat_ref 同口径；为空表示这个任务不推结果，
    -- 只能靠 GET /api/runs/{id} 取。
    channel            varchar(32),
    channel_ref        varchar(255),
    -- 每次到点要问的那句话，原样交给模型。它不是"计划"——计划属于某一次执行，
    -- 而这句话是每一次都一样的起点。
    goal               text         not null,
    -- 6 段 cron（秒 分 时 日 月 周），由 Spring 的 CronExpression 解析——它不接受 5 段，
    -- 少写一段不会被"猜一个秒"，而是当场报错。
    -- 存原文而不是存"每隔多少秒"：人说的是"每天早上九点"，那是日历上的一个点，不是一段间隔。
    cron               varchar(128) not null,
    -- 时区。为空表示用部署所在时区（JVM 的 systemDefault）——"九点"是哪个九点，
    -- 只能由部署环境回答。写进来是为了让跨时区（或测试里）有一个确定答案。
    zone               varchar(64),
    -- 默认 silent：定时任务默认静默完成，结果随时能查到。说"发我"的那一类由创建者改成 done_only。
    notify_policy      varchar(24)  not null default 'silent',
    -- 下一次该跑的时刻。它就是"已定好的时间点"，也是扫描的唯一入口条件；
    -- 存在库里而不是留在内存，这是"实例重启不丢已定好的时间点"的全部实现。
    next_run_at        timestamptz  not null,
    last_fired_at      timestamptz,
    -- 租约：认领时写"我占到什么时候"，跑完清空。没有它，认领之后进程崩了的那一条会一直
    -- 躺在"已认领"里——行锁随连接消失，但没有任何人知道该不该再跑它。租约过期即视为没人占。
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
    -- 任务主人在通道侧的标识（飞书是 open_id，HTTP 是 X-Claw4j-User）。到点只用它起跑。
    --
    -- **为什么不像早期那样存整份 Principal**：那实际上是一份角色快照，而快照不会跟着角色
    -- 配置变——一个人的权限昨天被收回，今天那条任务照样按昨天的权限跑完，"收权"对定时任务
    -- 等于没有发生。现在只留"这是谁"，到点那一刻按当前身份重算；读不出主人就停用并说明原因。
    owner_user_id      varchar(255),
    -- 这条任务预计要用到的工具名。建的时候核一遍（跑不了当场拒，不用等到明天九点），
    -- 到点之前再核一遍（权限被收回时，别开到一半才发现每个工具都被拦）。
    -- 空表示纯模型回答、不碰工具。
    required_tools     text[]       not null default '{}',
    -- 三档写死在约束里，与审批那一侧的路径同口径：
    -- 通知策略拼错的表现是"该说的一次没说"，只在事后被发现。
    constraint ck_agent_schedule_notify_policy check (
        notify_policy in ('done_only', 'silent', 'state_changes')),
    constraint ck_agent_schedule_failures check (failures >= 0),
    -- 停用的才允许有原因；在跑的任务带着一句停用原因是自相矛盾的。
    -- 写成 `not enabled or ...`：enabled = false 时无条件放行，enabled = true 时要求原因为空。
    -- 反过来写成 `enabled or ...` 会把"停用并记下原因"整条打死——而那正是这个功能唯一的正常出口。
    constraint ck_agent_schedule_disabled check (not enabled or disabled_reason is null)
);

-- 扫描只问"到点且还启用"，索引跟着这个形状走，并且做成部分索引：
-- 停用的任务永远不进这个索引，它们只会越来越多。
create index ix_agent_schedule_due
    on claw4j_run.agent_schedule (next_run_at)
    where enabled;

-- "这个人有哪些定时任务"是接下来最高频的读。
create index ix_agent_schedule_owner
    on claw4j_run.agent_schedule (owner, created_at desc);

comment on table claw4j_run.agent_schedule is
    '定时任务（P04）：一条 = 某人每隔一段时间让助手做同一件事。到点创建一次运行，其余全部复用现有路径';
comment on column claw4j_run.agent_schedule.goal is
    '每次到点要问模型的那句话。与一次运行的 goal 不是同一个东西：那条属于某一次运行，这条属于任务本身';
comment on column claw4j_run.agent_schedule.cron is
    'cron 原文（6 段：秒 分 时 日 月 周）。存日历上的点而不是间隔：人说"每天早上九点"';
comment on column claw4j_run.agent_schedule.zone is
    '时区；为空表示用部署所在时区。写进来是为了让"九点"有确定答案';
comment on column claw4j_run.agent_schedule.owner_user_id is
    '任务主人在通道侧的标识（飞书是 open_id，HTTP 是 X-Claw4j-User）。到点只用它起跑——角色那一层在 ADR-0030 里已下线';
comment on column claw4j_run.agent_schedule.required_tools is
    '这条任务预计要用到的工具名。建的时候与每次到点之前都核一遍；空表示纯模型回答、不碰工具';
comment on column claw4j_run.agent_schedule.next_run_at is
    '下一次该跑的时刻。实例重启不丢时间点靠的就是这一列';
comment on column claw4j_run.agent_schedule.claimed_until is
    '认领租约：占着的那一段不算别人的。没有它，认领后崩溃的那条没人敢再跑';
comment on column claw4j_run.agent_schedule.failures is
    '连续失败次数，成功一次清零。到了阈值自动停用，并推一条带最后错误的通知';

-- 审计是一个事件流水，不是"某件事的第几步"（P07，ADR-0027 决定二）。
--
-- 形状照 OpenClaw 的 metadata-only ledger：
--   · 四类 action：agent.run.started / agent.run.finished / tool.action.started / tool.action.finished
--   · 记 identity、ordering、provenance、action、status、归一化错误码
--   · **不记**提示词、消息体、工具参数、工具结果、命令输出、原始错误文本
--   · 30 天 + 10 万行，启动时 / 每小时 / 每次写之后各清一次
--
-- **event_id 与 seq 是两个东西**：event_id 用来去重（同一条事件重复投递只留一行），
-- seq 是账本序号（单调递增，翻页与"上限淘汰"都按它）。两者合成一个的话，
-- 去重成功就必须重排序号，而那等于把已经发出去的分页游标全部作废。
--
-- **主键就是 seq**：翻页与淘汰都按它走，另起一个没人读的主键只是每行多 16 字节和一个索引；
-- 而"稳定 id"这件事 event_id 已经做了。
create table claw4j_run.audit_event (
    -- 账本序号，同时是主键。单调递增，只用于排序、翻页与上限淘汰。
    seq              bigserial    not null primary key,
    event_id         varchar(64)  not null,
    -- agent_run | tool_action，与四类 action 一一对应（见下面的 check）。
    kind             varchar(16)  not null,
    action           varchar(32)  not null,
    -- 闭合词表：多一个拼错的拼法，按 status 过滤时会被安静地漏掉，
    -- 而漏掉的那些看起来像"没发生过"。
    status           varchar(16)  not null,
    -- 归一化错误码（闭合词表，见下面的 check），**不是**异常消息原文。
    -- 原文里常有数据：一条贴了 SQL 的报错就够搬走一张表。
    error_code       varchar(48),
    -- 这一轮的编号（runId，与 OpenClaw 同名，必填）。事件必须属于一次已准入的运行：
    -- 一行查不到轮次的审计，按 runId 查永远命中不了，留着只会让"这个月跑了多少次"的数字
    -- 比能查到的多。写入侧同时收紧——AuditLedger 在没有 runId 时直接跳过，
    -- 计入 claw4j.audit.skipped（不是 write_errors：那不是写失败，是"没有归属"）。
    run_id           varchar(64)  not null,
    session_key      varchar(255),
    agent_id         varchar(64),
    -- 谁让这件事发生的（提问的人）。**与 openclaw 的 actor 不是同一个东西**：
    -- 它的 actor 是执行者（agent / system），人的身份走 account_ref（HMAC 假名）。
    -- 我们只做了一侧——记人，执行者就是本进程。要照抄它那一套，得先有假名化密钥表，
    -- 那是另一件事，记在 docs/design/pitfalls 的偏差里。
    actor            varchar(128),
    -- 这个人当时的角色。openclaw 没有这一列（它的人身份在 account_ref 里）。
    -- 保留的理由：它的 actor 记执行者，我们的 actor 记人，而"谁"在人这一侧必须带上角色
    -- 才答得出"是不是越权"。它是当时的快照，只用于事后追溯，绝不回头拿去授权。
    actor_role       varchar(64),
    channel          varchar(32),
    -- 工具名必须是模型可见的紧凑名；认不出来的写 unknown，不写原始调用名。
    tool_name        varchar(128),
    -- 参数指纹 sha256(工具名 + 规范化参数)，与审批的 args_digest 同一个函数——
    -- 于是"这次执行是哪次批准的"能直接对上，而参数原文不进库。
    --
    -- **openclaw 这一列是 tool_call_id**（标识"这一次调用"）。我们拿不到：框架的 ToolContext
    -- 只是一个自由 Map，没有调用 id。所以这里只能放"这组参数"的指纹，代价是
    -- 同参数连调两次在审计里长得一样，数不出"调了几次"——要数就按 seq 数行。
    args_digest      varchar(64),
    -- 放行这次调用的审批编号。openclaw 没有这一列（它用 execution_decision_facts 关联），
    -- 我们没做那张表，所以留在这里——它是"这次执行是哪次批准的"唯一的落点。
    approval_id      varchar(64),
    -- 恒为 metadata_only：这一列存在的理由不是分类，是让读的人一眼看见"这行没有内容"。
    redaction        varchar(24)  not null default 'metadata_only',
    at               timestamptz  not null default now(),
    -- 记录格式版本，恒为 1。存在的理由不是分类，是让将来加字段时旧行仍能按第 1 版解释——
    -- 没有它，加列之后所有历史行都被当成新格式解释，而那种错不会报错，只会让统计数字悄悄偏掉。
    schema_version   smallint     not null default 1,
    constraint uq_audit_event_event_id unique (event_id),
    constraint ck_audit_event_kind check (kind in ('agent_run', 'tool_action')),
    constraint ck_audit_event_action check (action in (
        'agent.run.started', 'agent.run.finished',
        'tool.action.started', 'tool.action.finished')),
    constraint ck_audit_event_status check (status in (
        'started', 'succeeded', 'failed', 'cancelled', 'timed_out', 'blocked', 'unknown')),
    -- 动作与状态不能随便配：agent.run.started 只可能是 started，
    -- 而 tool.action.started 也不该带着 succeeded 出现。这条挡的是"字段填错但没人发现"。
    constraint ck_audit_event_action_status check (
        (action like '%.started' and status = 'started')
        or (action like '%.finished' and status <> 'started')),
    constraint ck_audit_event_schema_version check (schema_version = 1),
    -- 九个值与 OpenClaw 的 AuditEventErrorCodeSchema 逐字相同。词表不是"顺便写上的几个字符串"，
    -- 它是按 error_code 过滤时能被命中的全部：换个词表，同一个问题问出来的答案就不一样。
    constraint ck_audit_event_error_code check (
        error_code is null or error_code in (
            'run_failed', 'run_cancelled', 'run_timed_out', 'run_blocked',
            'tool_failed', 'tool_cancelled', 'tool_timed_out', 'tool_blocked',
            'tool_outcome_unknown'))
);

-- 索引照 openclaw 的形状：每个查询维度上都跟 **seq**，不是跟 at。
-- 翻页游标是 seq，按 at 排序在"同一毫秒写了多条"时不稳定——两条事件的 at 相同，
-- 页码就会重复或漏行。时间维度单独一条（(at desc, seq desc)）。
create index ix_audit_event_time on claw4j_run.audit_event (at desc, seq desc);

create index ix_audit_event_actor on claw4j_run.audit_event (actor, seq desc);

create index ix_audit_event_session on claw4j_run.audit_event (session_key, seq desc);

create index ix_audit_event_run on claw4j_run.audit_event (run_id, seq desc);

-- kind 与 channel 是排障时最先问的两句："只看工具动作""只看某个通道来的"。
create index ix_audit_event_kind on claw4j_run.audit_event (kind, seq desc);

create index ix_audit_event_channel on claw4j_run.audit_event (channel, seq desc);

create index ix_audit_event_status on claw4j_run.audit_event (status, seq desc);

comment on table claw4j_run.audit_event is
    '审计事件流水（ADR-0027）：一行 = 一次动作的开始或结束。只记元数据，不记内容';
comment on column claw4j_run.audit_event.seq is
    '账本序号，单调递增。排序、翻页与 10 万行上限淘汰都按它；与 event_id（去重用）分开';
comment on column claw4j_run.audit_event.error_code is
    '归一化错误码，闭合词表，与 OpenClaw 的 AuditEventErrorCodeSchema 逐字相同。不存异常消息原文：原文里常有数据，一条贴了 SQL 的报错就够搬走一张表';
comment on column claw4j_run.audit_event.args_digest is
    '参数指纹 sha256(工具名 + 规范化参数)，与审批 args_digest 同一个函数。openclaw 这一列是 tool_call_id（标识单次调用），我们拿不到，只能放参数指纹——同参数连调两次分不开';
comment on column claw4j_run.audit_event.actor is
    '谁让这件事发生（提问的人）。与 openclaw 的 actor（执行者 agent/system）不是同一个语义：我们只记人这一侧';
comment on column claw4j_run.audit_event.approval_id is
    '放行这次调用的审批编号。openclaw 用 execution_decision_facts 关联，我们没做那张表，所以留在这里';
comment on column claw4j_run.audit_event.redaction is
    '恒为 metadata_only。存在的理由不是分类，是让读的人一眼看见这一行没有内容';
comment on column claw4j_run.audit_event.schema_version is
    '记录格式版本，恒为 1。存在的理由不是分类，是让将来加字段时旧行仍能按第 1 版解释';
comment on column claw4j_run.audit_event.run_id is
    '这一轮的编号（runId，与 OpenClaw 同名，必填）。事件必须属于一次已准入的运行；与 span 上的 claw4j.run.id 同值，一轮答完即作废';

-- exec 的"以后这个可执行文件都放行"（ADR-0030 裁决四的三态之一：allow-always）。
--
-- 一条记录对**一段**命令，不是对整条命令行：
--     rg -n TODO            → pattern = 'rg'
--     rg -n TODO && ls      → 两条记录：'rg' 与 'ls'
-- 批一条复合命令等于把它的每一段都放开——这正是"按段落"的理由：只落整条命令的哈希，
-- 下一次 `rg -n FIXME` 还要再问一遍，人会开始无脑点批准。
--
-- **没有 command_text 列**。OpenClaw 的记录里有 `commandText`，但那是旧字段、加载时会被
-- stripAllowlistCommandText 剥掉：留着它等于把"用户跑过的一条完整命令"永久写进库，
-- 而匹配一份都不用它。要看人话有 last_used_command，那是一次性的便于排查的记录。
--
-- `last_used_at` / `last_used_command` / `last_resolved_path` 都是**命中之后才补**的：
-- 刚写进去时它们全是 null。不给默认值的理由：默认值会让"从没命中过"与"命中过但没记下来"
-- 看起来一样。
--
-- 唯一键用 coalesce(arg_pattern, '') 而不是 (pattern, arg_pattern)：Postgres 里 NULL 互不相等，
-- 直接建唯一索引时同一个 pattern 能插进无数条 arg_pattern 为 null 的记录，于是每次启动都多一批。
create table claw4j_run.exec_allowlist
(
    id                 uuid        not null primary key,
    pattern            text        not null,
    arg_pattern        text,
    source             text        not null,
    last_used_at       timestamptz,
    last_used_command  text,
    last_resolved_path text,
    created_at         timestamptz not null default now()
);

comment on table claw4j_run.exec_allowlist is
    'exec 的 allow-always 条目：一条对一段命令（ADR-0030）。pattern 是可执行名，arg_pattern 预留参数级收窄';
comment on column claw4j_run.exec_allowlist.pattern is
    '可执行名（如 rg）。路径形态与赋值形态不落库——取 basename 会让 /tmp/evil/rg 与 rg 共用一条';
comment on column claw4j_run.exec_allowlist.arg_pattern is
    '参数级收窄，暂时恒为 null；有它时 pattern + arg_pattern 一起匹配';
comment on column claw4j_run.exec_allowlist.source is
    '谁写的这一条。目前只有 allow-always；留字面量是为了将来区分配置文件导入与人工批准';
comment on column claw4j_run.exec_allowlist.last_resolved_path is
    '上次命中时解析出的可执行文件绝对路径，事后排查用，不参与匹配';

create unique index uq_exec_allowlist_pattern
    on claw4j_run.exec_allowlist (pattern, coalesce(arg_pattern, ''));

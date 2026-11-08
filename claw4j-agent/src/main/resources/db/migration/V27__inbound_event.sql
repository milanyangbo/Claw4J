-- 入站事件先落库、再 ACK（票 04）。
--
-- 形状照 OpenClaw 的 channel_ingress_events（src/state/postgres-schema.generated/
-- state.postgres.sql:189-211）：**一个状态机 + 一个 tombstone，不是一卷消息日志**。
--
-- 为什么需要它：平台收到我们的 ACK 之后就不会重投，而那条消息原来只在线程池的队列里——
-- 进程一退就永久消失，日志里只留下"已提交到执行线程池"。留一份去重表在内存里解决的是
-- "多实例之间不重复"，那件事在单实例形态下（ADR-0009 的长连接不支持广播）本来就不成立。
--
-- 四条形状上的判断（每一条都对应一个容易写错的版本）：
--   · 主键是 (queue_name, event_id)，**不是 event_id**：同一个平台的两个 bot 各自收到
--     同一个事件时 event_id 会撞，而它们该各跑一次。
--   · 完成 = tombstone，不是删除：status 置 completed、payload_json 置 'null'、清认领三列、
--     行留着。这行就是去重记录——重投时撞主键，于是"这条处理过"。删掉行等于重投会再跑一遍。
--     顺带的好处：消息内容不留长期副本。
--   · **没有序号列**：顺序由 (received_at, event_id) 给，串行由 lane_key 给
--     （claimNext 把"正在跑的车道"整片排除）。取号要么带锁、要么允许空洞，而这两件事
--     入站都不需要——它要的是"按到达顺序"，那正是 received_at 本身。
--   · attempts 只由 release 涨，**不由孤儿回收涨**（丢了一个认领人不算一次投递失败；
--     涨的话"进程重启三次"就能把一条正常消息的额度耗光）。
--
-- 两处与它的形态不同，都因为我们的库是 PG 而不是 SQLite：
--   · 时间列用 timestamptz（它的 bigint 来自 SQLite 只有整数；本仓 audit_event 也是 timestamptz）。
--   · 只留 account_id，不留 channel_id（它的 channel_id 服务于"按通道列账号"那个查询，
--     而我们 queue_name 本身就是通道名，多一列同值）。
create schema if not exists claw4j_channel;

create table if not exists claw4j_channel.inbound_event (
    -- 队列名。我们一个通道一份配置，今天只有四个值（feishu / slack / wecom / dingtalk）。
    -- 留着这一列当主键的一半，是因为同一个通道将来接第二个账号时不用改主键。
    queue_name              varchar(96)  not null,
    event_id                varchar(128) not null,
    account_id              varchar(64)  not null default 'default',
    -- 四值闭合词表，见下面的 check。**不是** pending/running/done：claimed 与"正在执行"
    -- 是两件事——claimed 表达的是"有人认领了、还在等租约"，它能被回收，completed 不能。
    status                  varchar(16)  not null,
    -- 串行车道（它的 lane_key）。我们是 conversationId：同一条会话上同时只有一条在跑。
    lane_key                varchar(255),
    -- 整条信封的 JSON（InboundEvent 的五个字段），**不拆成列**：拆了之后每加一个字段就是
    -- 一次迁移，而这里从来不按内容查询——只按 id、状态、车道、时间。
    payload_json            text         not null,
    metadata_json           text,
    received_at             timestamptz  not null,
    updated_at              timestamptz  not null,
    -- 认领：谁在跑、什么时候认领的、拿什么令牌去结算。令牌挡住"两个执行者都以为自己持有这一行"。
    claim_token             uuid,
    claim_owner             varchar(96),
    claimed_at              timestamptz,
    attempts                int          not null default 0,
    last_attempt_at         timestamptz,
    last_error              text,
    failed_reason           varchar(64),
    failed_at               timestamptz,
    completed_at            timestamptz,
    completed_metadata_json text,
    constraint pk_inbound_event primary key (queue_name, event_id),
    constraint ck_inbound_event_status check (status in ('pending', 'claimed', 'completed', 'failed'))
);

-- 三条索引与它的三个同名同形（idx_channel_ingress_*）：认领扫描、车道判定、按到达顺序取。
create index if not exists ix_inbound_event_claims
    on claw4j_channel.inbound_event (queue_name, status, claimed_at);

create index if not exists ix_inbound_event_lane
    on claw4j_channel.inbound_event (queue_name, status, lane_key);

create index if not exists ix_inbound_event_pending
    on claw4j_channel.inbound_event (queue_name, status, received_at, event_id);

-- 结果投递与回收（A05，ADR-0018）：一件事的结果不再跟着"那一次请求"回去。
-- 今天谁问的就回给谁，于是"在飞书提问、在别处收结果"与"定时跑完没人在线"
-- 这两种场景都做不到——不是缺推的能力（飞书的回推本来就与当前请求无关），
-- 缺的是一个与通道无关的收件地址。
--
-- **notify_policy 与 result 落在 run 行上，不另建表**：两者都是这件事的属性，
-- 不是另一件事。分开存的话"做完了没有、做出来是什么"要多一次 join，
-- 而它恰恰是出事之后才会被问的那个问题——那种时刻最不该依赖"记得去 join"。
--
-- **投递目标单独一张表**：它是"结果送到哪儿"，与"这件事是什么"是一对多——
-- 同一件事可以 HTTP 发起、飞书批准、企微收结果。塞进 run 行里就得写成
-- channel_1 / ref_1 这种带编号的列，加第三个地址要改表。
alter table claw4j_run.agent_run
    add column if not exists notify_policy varchar(24) not null default 'done_only',
    add column if not exists result text;

-- 三档写死在约束里：通知策略的失败姿势是"吵到不该吵的人"或者"该说的一次没说"，
-- 两种都只在事后被发现。让库先挡住拼错的那一次。
alter table claw4j_run.agent_run
    add constraint ck_agent_run_notify_policy check (
        notify_policy in ('done_only', 'silent', 'state_changes'));

comment on column claw4j_run.agent_run.notify_policy is
    '通知策略（A05）：done_only 做完了说一次（默认）/ silent 一次都不说，等人来查 / state_changes 状态变了就说。定时任务用 silent';
comment on column claw4j_run.agent_run.result is
    '这件事到目前为止的回答全文，每轮覆盖。与 step 上的 result_summary 不是一个东西：那个是截断摘要，回答"这一步得到了什么"';

create table if not exists claw4j_run.agent_run_delivery (
    run_id      varchar(64)  not null,
    -- 通道标识，与 OutboundDelivery#channel() 同口径。没有实现推的通道（HTTP）
    -- 也会登记进来——它靠轮询取结果，登记是为了"换通道继续"时认得出同一个地址。
    channel     varchar(32)  not null,
    -- 通道内的收件地址（飞书是 chat_id）。它不是回调地址：
    -- 回调是单点且要能被外网访问，这里记的是"回到那个会话去"。
    channel_ref varchar(255) not null,
    created_at  timestamptz  not null default now(),
    constraint fk_agent_run_delivery_run
        foreign key (run_id) references claw4j_run.agent_run (id) on delete cascade,
    -- 主键就是"同一个地址不登记第二次"：一件事在飞书里连着问了三轮，
    -- 收件地址还是那一个。靠 insert 时判重去兜的话，判重逻辑要在每个写的地方各写一遍。
    constraint pk_agent_run_delivery primary key (run_id, channel, channel_ref)
);

comment on table claw4j_run.agent_run_delivery is
    '一件事的收件地址（A05）：与 run 一对多。同一件事可以在多个通道的多个会话里被找到';

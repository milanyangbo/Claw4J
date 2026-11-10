-- 会话身份、段与通道绑定（照 OpenClaw 的会话持久层形状重做）。
--
-- 它那份 schema 的开头有一句 doctrine，指明了为什么不是一个单表：
--
--   session_nodes.entry_json 拥有热的逻辑会话事实；session_entry_snapshots 拥有按 key 的冷值；
--   节点上被"提升"出来的列只是查询索引；session_windows 及其子表拥有 transcript 的世代。
--
-- 这五句话对应四张表，职责不重叠。本文件照着这个分层建：
--
--   session_nodes             一个 session_key 一行。热事实在 entry_json，其余列是索引
--   session_entry_snapshots   按 (session_key, field) 存的冷值，三档固定
--   conversations             通道侧的会话身份：谁、在哪、怎么回
--   session_windows           一段对话的"一世"，session_id 一词一代
--   session_conversations     段与通道会话的多对多，role 分 primary / participant / related
--   session_key_contract      单行，记 main 会话的键
--
-- 这五张表取代 V3 里的 session_entry。旧表本版不删：代码还在按它读写，
-- 删了会让现有用例全红。切换完成之后由下一个版本 drop（届时 goal 的平铺列一起走）。
--
-- **transcript 不在这里**。Spring AI 的 ai_session / ai_session_event 是框架契约
-- （JdbcChatMemoryRepository 的 SQL 写死了这两张表），消息由它持有；session_windows
-- 只持有"段"这个身份，session_id 与 ai_session.id 同值，两边靠这个 id 对齐。
-- 所以它的 transcript_events 我们不建——那会是第二份真相。
--
-- **与 OpenClaw 的偏差**（逐列核对之后知道的，不是漏的）：
--   · 不建 transcript_events / session_transcript_archives / session_transcript_cold_archives：
--     理由见上，消息归 Spring AI。它那套归档是给"把旧代 transcript 冻成文件"用的，
--     我们换段是把 ai_session 的行留着只读，没有冻结这个动作。
--   · 不建 session_participants / session_members / session_upstream_links：那是"谁参与过"
--     与"上游会话链接"，本仓库只有一个身份维度，没有多参与者与上游。
--   · 去掉 legacy_acp_migration_json / acp_owned：我们不做 ACP。
--   · 去掉 plugin_owner_id / hook_external_content_source / agent_harness_id：没有插件侧写入，
--     也没有可切换的 harness。
--   · 去掉 session_nodes.has_optional_references：它是"这条 entry 引用了可选的冷值"的标记，
--     用来决定要不要去查 snapshots 表。我们的冷值查询按 (session_key, field) 主键走，
--     没有这个判断也不多一次扫描。
--   · 时间列用 timestamptz，不用它的 INTEGER 毫秒：SQLite 没有原生时间类型，
--     那是存储限制不是设计选择。判定（跨天、空闲）在两边算出的结果一样。
--   · session_key_contract 去掉 canonical_ready：那是它"整库键规范校验"的回执列，
--     我们的库由 Flyway 建，不存在"导入了一堆非规范键"这个入口。
--
-- 后续改表结构请加新版本，不要动这里。

create table claw4j_session.session_key_contract (
    id         smallint    primary key,
    -- main 会话的键。它存库里而不是每次从配置读：改配置会让已有的 main 会话变孤儿，
    -- 而"这个库的 main 叫什么"是数据，不是配置。
    main_key   text        not null,
    updated_at timestamptz not null,
    constraint session_key_contract_single_row check (id = 1)
);

insert into claw4j_session.session_key_contract (id, main_key, updated_at)
values (1, 'main', now());

create table claw4j_session.session_nodes (
    -- 会话节点的键。段不在键里——一个节点恒有且只有一个"当前段"。
    session_key             text        primary key,

    -- 指向当前段（session_windows.session_id）。换段改这一列，不改键。
    -- entry_json 里的 sessionId 必须与它同值：列是索引，JSON 是真相。
    current_session_id      text        not null,

    -- 热的逻辑会话事实。形状由 SessionEntry 定义，至少含 sessionId / updatedAt /
    -- totalTokens / goal。它的价值在于"加一个会话字段不用改表"——它的 entry 有一百多列
    -- 的量级，绝大多数只被整份读写，摊成列会让每次取会话都扫一大片。
    entry_json              jsonb       not null,

    -- 当前段的开始时刻。判定跨天要用它，所以提升成列（JSON 里也有一份）。
    session_started_at      timestamptz,

    -- 冷值的版本号：snapshots 表每次增删改由触发器 +1。读侧靠它判断"要不要重读冷值"，
    -- 没有它就只能每轮去查 snapshots。
    snapshot_revision       integer     not null default 0,

    -- entry_json 是否已通过结构校验：1 已校验，0 待校验，-1 不该存在。
    -- 它的用途是让"导入的脏行"不必阻塞整库启动，只标记这一行待修。
    entry_valid             smallint    not null default 0
        check (entry_valid in (-1, 0, 1)),

    -- 节点最后更新时间（跨段不变）。清理与列表排序按它走。
    updated_at              timestamptz not null,

    -- 整段 run 的结果。/new 之后清空——它描述的是"当前段跑完没有"，不是节点属性。
    status                  text check (status is null or status in
        ('running', 'done', 'failed', 'killed', 'timeout')),

    created_at              timestamptz,
    -- 谁建的。channel 是"外部消息触发的"，spawn 是"子代理"，cron 是定时任务，
    -- 这些区别在排查"这个会话怎么冒出来的"时是唯一线索。
    created_via             text check (created_via is null or created_via in
        ('operator', 'spawn', 'channel', 'cron', 'talk', 'run', 'plugin', 'internal')),
    created_actor_type      text check (created_actor_type is null or created_actor_type in
        ('human', 'agent', 'system')),
    created_actor_id        text,

    -- 归属与转让。分开存"谁现在拥有"与"谁转的、什么时候转的"：
    -- 合用一个 updated_at 答不了"这次转让发生在什么时候"。
    owner_actor_type        text,
    owner_actor_id          text,
    owner_assigned_by_type  text,
    owner_assigned_by_id    text,
    owner_assigned_at       timestamptz,

    project_id              text,

    -- 谱系。parent 是"我是谁起的"，fork_source 是"我从谁的哪一段分叉来的"。
    -- 两者不是一件事：子代理有 parent 但没有 fork source，分叉会话反过来。
    parent_session_key      text,
    spawned_by              text,
    fork_source_session_key text,
    fork_source_session_id  text,
    fork_source_entry_id    text,

    label                   text,
    display_name            text,
    category                text,
    icon                    text,
    pinned_at               timestamptz,
    archived_at             timestamptz,
    -- 三个"什么时候"分开：读过 ≠ 交互过 ≠ 有活动。
    -- 合成一个的话，"未读"这个状态就表达不出来。
    last_read_at            timestamptz,
    last_interaction_at     timestamptz,
    last_activity_at        timestamptz
);

-- 列表按更新时间倒序翻页。
create index session_nodes_updated_at_idx
    on claw4j_session.session_nodes (updated_at desc, session_key);

create index session_nodes_label_idx
    on claw4j_session.session_nodes (label, session_key)
    where label is not null;

create index session_nodes_parent_idx
    on claw4j_session.session_nodes (parent_session_key, session_key);

create index session_nodes_spawned_by_idx
    on claw4j_session.session_nodes (spawned_by, session_key);

create index session_nodes_status_idx
    on claw4j_session.session_nodes (status, session_key)
    where status is not null;

create index session_nodes_archived_idx
    on claw4j_session.session_nodes (archived_at, session_key)
    where archived_at is not null;

-- 活跃节点：清理与列表的默认范围。部分索引让"未归档"这半边不进索引。
create index session_nodes_active_idx
    on claw4j_session.session_nodes (session_key)
    where archived_at is null;

create index session_nodes_current_session_idx
    on claw4j_session.session_nodes (current_session_id);

-- 待校验的行：doctor 与修复工具只扫这一小片。
create index session_nodes_entry_valid_idx
    on claw4j_session.session_nodes (session_key)
    where entry_valid != 1;

create table claw4j_session.session_entry_snapshots (
    session_key text        not null
        references claw4j_session.session_nodes (session_key) on delete cascade,
    -- 三档固定，不开放自定义：它只装"大、冷、整份读写"的三样，
    -- 装别的东西就等于把 entry_json 拆散了。
    field       text        not null
        check (field in ('sessionDiffBaseline', 'skillsSnapshot', 'systemPromptReport')),
    value_json  jsonb       not null,
    primary key (session_key, field)
);

-- 冷值变了就让版本号往前走。放在库里而不是应用层，是因为应用有多个写入点
-- （会话访问器、修复工具、导入），漏一处就会读到旧快照。
create or replace function claw4j_session.bump_snapshot_revision()
returns trigger
language plpgsql
as $$
begin
    if tg_op = 'DELETE' then
        update claw4j_session.session_nodes
           set snapshot_revision = snapshot_revision + 1
         where session_key = old.session_key;
        return old;
    end if;
    update claw4j_session.session_nodes
       set snapshot_revision = snapshot_revision + 1
     where session_key in (new.session_key, old.session_key);
    return new;
end;
$$;

create trigger session_entry_snapshots_after_insert
    after insert on claw4j_session.session_entry_snapshots
    for each row execute function claw4j_session.bump_snapshot_revision();

create trigger session_entry_snapshots_after_update
    after update on claw4j_session.session_entry_snapshots
    for each row execute function claw4j_session.bump_snapshot_revision();

create trigger session_entry_snapshots_after_delete
    after delete on claw4j_session.session_entry_snapshots
    for each row execute function claw4j_session.bump_snapshot_revision();

create table claw4j_session.conversations (
    conversation_id        text        primary key,
    -- 通道名（slack / feishu / wecom / http）。
    channel                text        not null,
    -- 同一种通道可以配多个机器人。没有这一维的话，两个机器人收到的私聊会算同一条对话。
    account_id             text        not null,
    -- 三档：私聊、群、频道。它是 dmScope 的判据——main 只作用于 direct。
    kind                   text        not null check (kind in ('direct', 'group', 'channel')),
    -- 对端身份：私聊是用户，群聊是群。
    peer_id                text        not null,
    -- 回消息往哪投。与 peer_id 分开：群里回消息要带 thread，而 peer 是群本身。
    delivery_target        text        not null,
    parent_conversation_id text,
    thread_id              text,
    -- 通道自己的 id。排查时拿它去平台上对照，比拿我们的内部 id 有用。
    native_channel_id      text,
    native_direct_user_id  text,
    label                  text,
    metadata_json          jsonb,
    created_at             timestamptz not null,
    updated_at             timestamptz not null
);

create index conversations_lookup_idx
    on claw4j_session.conversations (channel, account_id, kind, peer_id, thread_id);

-- 同一条通道会话只能有一行。空值在唯一索引里互不相等，所以显式消掉 null：
-- 不处理的话，没有 thread 的私聊每轮都能插一行新的。
create unique index conversations_identity_idx
    on claw4j_session.conversations (
        channel, account_id, kind, peer_id,
        coalesce(parent_conversation_id, ''), coalesce(thread_id, ''));

create index conversations_updated_idx
    on claw4j_session.conversations (updated_at desc, conversation_id);

create table claw4j_session.session_windows (
    -- 一"世"对话的 id。等于 ai_session.id——消息在那边，身份在这边。
    session_id              text        primary key,
    session_key             text        not null
        references claw4j_session.session_nodes (session_key) on delete cascade,

    -- 上一世的 id。只记一个，不是链——顺着它能走回去，但不需要在库里维护整条链。
    previous_session_id     text,

    -- 为什么换段。七档缺一不可：reset 是用户按 /new，rollover 是策略自动换，
    -- compaction 是压缩触发的重写，recovery 是崩了之后接上。
    -- 只记"换过段"的话，"为什么记忆没了"就只能靠猜。
    reason                  text check (reason is null or reason in
        ('initial', 'reset', 'rollover', 'fork', 'rewind', 'switch', 'recovery', 'compaction')),

    -- 这一段被谁共享。shared-main 就是 dmScope: main 的落点（所有私聊共用主会话），
    -- group 是按群隔离，channel 是整条通道一个段。它记的是"这一段的边界是什么"，
    -- 不是"键长什么样"——键是节点的事。
    session_scope           text        not null default 'conversation'
        check (session_scope in ('conversation', 'shared-main', 'group', 'channel')),

    created_at              timestamptz not null,
    updated_at              timestamptz not null,
    started_at              timestamptz,
    ended_at                timestamptz,

    status                  text check (status is null or status in
        ('running', 'done', 'failed', 'killed', 'timeout')),

    -- 这一段的源头是哪类会话。与 conversations.kind 同义，但留一份在这里：
    -- 查"这个段的私聊还是群聊"不该再走一次 join。
    chat_type               text check (chat_type is null or chat_type in
        ('direct', 'group', 'channel')),
    channel                 text,
    account_id              text,
    primary_conversation_id text
        references claw4j_session.conversations (conversation_id) on delete set null,

    -- 这一段用的模型。换模型不换段，所以它是"这段开始时的模型"，
    -- 中途切模型会改它——它的语义是当前值，不是历史。
    model_provider          text,
    model                   text,

    display_name            text
);

create index session_windows_updated_idx
    on claw4j_session.session_windows (updated_at desc, session_id);

-- 一个节点的段按时间倒序：取"当前段之前那一世"走这条。
create index session_windows_session_key_idx
    on claw4j_session.session_windows (session_key, updated_at desc, session_id);

create index session_windows_created_idx
    on claw4j_session.session_windows (created_at desc, session_id);

create index session_windows_conversation_idx
    on claw4j_session.session_windows (primary_conversation_id)
    where primary_conversation_id is not null;

create table claw4j_session.session_conversations (
    session_id      text        not null
        references claw4j_session.session_windows (session_id) on delete cascade,
    conversation_id text        not null
        references claw4j_session.conversations (conversation_id) on delete cascade,

    -- 一个段可以有多个会话，但只有一个 primary。这就是"所有私聊共用主会话"的实现：
    -- 不是把键拼成一个字符串，而是让多个 direct 会话都挂到同一个 session_id 上。
    -- participant 是群里的其他人，related 是"顺带提过一句"的会话。
    role            text        not null default 'primary'
        check (role in ('primary', 'participant', 'related')),

    -- 这条关联的路由现场（谁、从哪来、回哪去）。留 JSON 是因为它随通道变，
    -- 摊成列等于把每个通道的字段都塞进这张表。
    route_context_json jsonb,
    first_seen_at   timestamptz not null,
    last_seen_at    timestamptz not null,
    primary key (session_id, conversation_id, role)
);

create index session_conversations_conversation_idx
    on claw4j_session.session_conversations (conversation_id, last_seen_at desc, session_id);

-- 一个段只能有一个 primary。挂第二个就是"这一段的主通道是哪条"没有答案。
create unique index session_conversations_primary_idx
    on claw4j_session.session_conversations (session_id)
    where role = 'primary';

package io.github.claw4j.agent.session;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 会话节点与段的 JDBC 实现（ADR-0029）。
 *
 * <p>一次访问要落三件事：锁住判定、可能插一行 window、更新节点的当前段。它们在一个事务里，
 * 判定在锁内做——两条消息同时进门时，后到的那个看到的是前一个写完的状态，不会各自判成
 * "该换"、把同一次轮换做两遍。
 *
 * <p>锁用 {@code pg_advisory_xact_lock(hashtextextended(key, 0))} 而不是 {@code select ... for
 * update}：节点行可能还不存在（第一次访问），而 {@code for update} 锁不住一行还没有的行。
 * 先插占位再锁的话，占位那一行的 {@code current_session_id} 得先编一个，而它是非空列。
 * 咨询锁按字符串取，第一次访问也能挡住并发。散列撞车的代价只是两条不相干的会话排队，
 * 不影响正确性。
 *
 * <p>锁与读分成两条语句，不合并：同一个 {@code select} 里，join 与 select 列表的求值顺序
 * 没有保证，合起来写会出现"先读到旧值、后拿到锁"，而那正是要防的窗口。
 *
 * <p>为什么不是一条语句：V8 里 window 的 {@code session_key} 是延迟外键，本可以塞进一个
 * CTE，但"先插节点还是先插 window"在 CTE 里没有保证（{@code WITH} 的子语句并发执行），
 * 而分语句换来的可读性更值。会话状态行每轮只走一次，不在热循环里。
 *
 * <p>表名写全限定，schema 段来自配置：本类走主连接池，它的 {@code search_path} 里没有会话
 * schema。会话那一路另开池是因为框架生成的 SQL 不带前缀；这里 SQL 全在自己手里，多开一个池
 * 不划算，而且容器里出现第二个 {@code DataSource} 会打掉一整套自动配置。schema 名必须与
 * 框架那一路同源，两边都读 {@code claw4j.session.schema}。
 */
public final class JdbcSessionEntryStore implements SessionEntryStore {

    private static final String LOCK = "select pg_advisory_xact_lock(hashtextextended(?, 0))";

    private static final String READ = """
            select current_session_id, session_started_at, last_interaction_at
            from {{schema}}session_nodes where session_key = ?
            """;

    private static final String INSERT_WINDOW = """
            insert into {{schema}}session_windows
                   (session_id, session_key, previous_session_id, reason, session_scope,
                    created_at, updated_at, started_at, chat_type, channel, account_id,
                    primary_conversation_id)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    /**
     * 登记一条通道会话（幂等）。
     *
     * <p>id 由通道身份派生（{@link SessionKeys#conversationRefOf}），所以这里不需要"先查有没有"：
     * 同一条会话算出来的 id 每次都一样，重复登记只是刷新 {@code delivery_target} 与时间。
     * 形状照 OpenClaw 的 {@code registerConversationAddresses}——它把这件事叫"cataloguing
     * routable addresses ... without creating sessions"（{@code src/config/sessions/conversation-registry.ts}）。
     */
    private static final String UPSERT_CONVERSATION = """
            insert into {{schema}}conversations
                   (conversation_id, channel, account_id, kind, peer_id, delivery_target,
                    created_at, updated_at)
            values (?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (conversation_id) do update
               set delivery_target = excluded.delivery_target,
                   updated_at = excluded.updated_at
            """;

    /**
     * 这条会话已经挂在这一段上了：只刷新 {@code last_seen_at}。
     *
     * <p>为什么"先刷新再插"而不是一条 {@code insert ... on conflict}：主键是
     * {@code (session_id, conversation_id, role)}，同一对 (段, 会话) 换个 role 会被当成新行——
     * 于是"先在飞书建的段、又在网页说了一句"会让同一条会话在这一段里有两行
     * （primary 与 participant），而 {@code currentSessionKey} 那边只认其中一行。
     * 一行一对才是这张表的意思。
     */
    private static final String TOUCH_BINDING = """
            update {{schema}}session_conversations set last_seen_at = ?
             where session_id = ? and conversation_id = ?
            """;

    /**
     * 把这条会话挂到这一段上：这一段还没有 primary 就它当，否则是 participant。
     *
     * <p>判定写在语句里而不是先查一次：两条消息同时进门时，"都查到没有 primary"会让第二句插
     * 一个 primary 撞上唯一索引。整条语句在咨询锁内执行（锁键就是这个节点），所以这里的
     * {@code exists} 与插入之间没有窗口。
     */
    private static final String BIND_CONVERSATION = """
            insert into {{schema}}session_conversations
                   (session_id, conversation_id, role, first_seen_at, last_seen_at)
            select ?, ?, case when exists (
                       select 1 from {{schema}}session_conversations
                        where session_id = ? and role = 'primary')
                   then 'participant' else 'primary' end, ?, ?
            on conflict do nothing
            """;

    /**
     * 第一次访问：建节点。
     *
     * <p>{@code entry_valid = 1} 表示这一行是我们自己写的，不是导入来的待修行。
     * {@code entry_json} 装 {@code sessionId} 与 {@code updatedAt}（毫秒）——与它的 entry 同形，
     * 而 {@code updated_at} 列是索引；两者在同一条写里同步，不会分叉。
     */
    private static final String INSERT_NODE = """
            insert into {{schema}}session_nodes
                   (session_key, current_session_id, entry_json, session_started_at,
                    updated_at, last_interaction_at, created_at, entry_valid)
            values (?, ?, jsonb_build_object('sessionId', ?, 'updatedAt', ?, 'totalTokens', 0,
                                             'totalTokensVersion', 2),
                    ?, ?, ?, ?, 1)
            """;

    /**
     * 换段：改当前段、重置段的起点，并把 {@code entry_json} 换成一份干净的。
     *
     * <p>整份换而不是逐个删键：目标与用量是这一段的东西，段换了它们就该一起走。留一条
     * {@code jsonb_set} 链去清的话，将来加一个字段就得多改一处，漏了就是"新段还带着旧段的目标"。
     */
    private static final String UPDATE_NODE_ROTATED = """
            update {{schema}}session_nodes set
                current_session_id = ?,
                session_started_at = ?,
                entry_json = jsonb_build_object('sessionId', ?, 'updatedAt', ?, 'totalTokens', 0,
                                                'totalTokensVersion', 2),
                updated_at = ?,
                last_interaction_at = ?
            where session_key = ?
            """;

    /**
     * 不换段：只刷新两个时间，{@code entry_json} 原样保留。
     *
     * <p>但 JSON 里的 {@code updatedAt} 要跟着列走，否则"索引与真相不一致"从第一天就成立。
     */
    private static final String UPDATE_NODE_TOUCHED = """
            update {{schema}}session_nodes set
                updated_at = ?,
                last_interaction_at = ?,
                entry_json = jsonb_set(entry_json, '{updatedAt}', to_jsonb(?::bigint))
            where session_key = ?
            """;

    private final JdbcTemplate jdbc;

    private final TransactionTemplate transaction;

    private final String lock;
    private final String read;
    private final String insertWindow;
    private final String upsertConversation;
    private final String touchBinding;
    private final String bindConversation;
    private final String insertNode;
    private final String updateNodeRotated;
    private final String updateNodeTouched;

    /**
     * @param jdbc   JDBC 句柄，走主连接池
     * @param schema 会话表所在的 schema，与框架那一路同源（{@code claw4j.session.schema}）。
     *               本类在主连接池上跑，只能写全限定名，所以它必须由参数决定
     */
    public JdbcSessionEntryStore(JdbcTemplate jdbc, String schema) {
        this.jdbc = jdbc;
        // 单测里的 JdbcTemplate 是 mock，没有 DataSource：那时不开事务，语句照发（用例只查顺序）。
        // 生产里没有 DataSource 的 JdbcTemplate 根本装配不起来，所以这一支不会走到。
        this.transaction = jdbc.getDataSource() == null
                ? null
                : new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        String qualifier = SessionSchema.qualifier(schema);
        this.lock = LOCK;
        this.read = READ.replace("{{schema}}", qualifier);
        this.insertWindow = INSERT_WINDOW.replace("{{schema}}", qualifier);
        this.upsertConversation = UPSERT_CONVERSATION.replace("{{schema}}", qualifier);
        this.touchBinding = TOUCH_BINDING.replace("{{schema}}", qualifier);
        this.bindConversation = BIND_CONVERSATION.replace("{{schema}}", qualifier);
        this.insertNode = INSERT_NODE.replace("{{schema}}", qualifier);
        this.updateNodeRotated = UPDATE_NODE_ROTATED.replace("{{schema}}", qualifier);
        this.updateNodeTouched = UPDATE_NODE_TOUCHED.replace("{{schema}}", qualifier);
    }

    /** 有事务就开，没有就直接跑（只在单测的 mock 上发生）。 */
    private void inTransaction(Runnable work) {
        if (transaction == null) {
            work.run();
            return;
        }
        transaction.executeWithoutResult(status -> work.run());
    }

    @Override
    public Optional<String> segmentOf(Key key, Rotation rotation) {
        String fresh = rotation.freshSegment();
        String sessionId = SessionKeys.sessionIdOf(key.sessionKey(), fresh);
        String conversationRef = SessionKeys.conversationRefOf(
                key.channel(), key.direct() ? "direct" : "group", key.chatId());
        String[] answer = new String[1];
        inTransaction(() -> {
            lock(key);
            Previous before = read(key);
            boolean rotate = before == null || rotation.forced() || expired(before, rotation);
            // 先登记通道会话：window 的 primary_conversation_id 外键指着它。
            jdbc.update(upsertConversation, conversationRef, SessionKeys.placeholder(key.channel()),
                    SessionKeys.DEFAULT_ACCOUNT, key.direct() ? "direct" : "group",
                    SessionKeys.placeholder(key.chatId()), SessionKeys.placeholder(key.chatId()),
                    millis(rotation.now()), millis(rotation.now()));
            if (rotate) {
                insertWindow(key, before, sessionId, rotateReason(before, rotation), rotation,
                        conversationRef);
            }
            // 这一轮实际落在那一段上：换段是新的，不换段是旧的。
            String current = before == null || rotate ? sessionId : before.sessionId();
            if (before == null) {
                insertNode(key, sessionId, rotation);
                answer[0] = fresh;
            }
            else if (rotate) {
                rotateNode(key, sessionId, rotation);
                answer[0] = fresh;
            }
            else {
                touchNode(key, rotation);
                // 没换段就要回**旧的**那一个：回新的等于告诉调用方"换过了"，
                // 而下一轮它会拿一个库里不存在的段去查记忆与目标。
                answer[0] = segmentOf(before.sessionId(), key.sessionKey());
            }
            bind(key, current, conversationRef, rotation);
        });
        return Optional.ofNullable(answer[0]);
    }

    /**
     * 把这条通道会话挂到这一段上。
     *
     * <p>它与换段无关：不换段时也要走，因为 {@code last_seen_at} 是"这条会话现在落在哪一段"
     * 的判据（OpenClaw 的 {@code currentSessionKey} 就按它取最大的一条）。
     *
     * @param key 这一行的定位键
     * @param sessionId 这一轮实际的那一段
     * @param conversationRef 通道会话 id
     * @param rotation 这一次访问的时刻
     */
    private void bind(Key key, String sessionId, String conversationRef, Rotation rotation) {
        Timestamp now = millis(rotation.now());
        if (jdbc.update(touchBinding, now, sessionId, conversationRef) == 0) {
            jdbc.update(bindConversation, sessionId, conversationRef, sessionId, now, now);
        }
    }

    /**
     * 从完整对话键里取出段名：{@code <节点键>:<段>} → {@code <段>}。
     *
     * <p>形状对不上时原样返回：那种情况只可能出现在手工改过的行上，把整串当段名
     * 至少能让调用方继续跑，而不是抛一个没人看得懂的越界。
     */
    private static String segmentOf(String sessionId, String sessionKey) {
        String prefix = sessionKey + SessionKeys.SESSION_SEPARATOR;
        return sessionId.startsWith(prefix) ? sessionId.substring(prefix.length()) : sessionId;
    }

    /**
     * 这一段过没过期。
     *
     * <p>跨天比的是时刻而不是"算好的逻辑日"：改 at-hour 或改时区之后，旧行会按新边界重判；
     * 存结论的话，该换的段不换。
     */
    private static boolean expired(Previous before, Rotation rotation) {
        if (rotation.dailyGate() && before.sessionStartedAt().isBefore(rotation.dailyResetAt())) {
            return true;
        }
        return before.lastInteractionAt().isBefore(rotation.idleBefore());
    }

    /**
     * 为什么换段。
     *
     * <p>第一次不算"换"，是 {@code initial}；用户按 {@code /new} 是 {@code reset}；其余
     * （跨天、空闲）都是 {@code rollover}。三者在库里分开记，"为什么记忆没了"才答得出来。
     */
    private static String rotateReason(Previous before, Rotation rotation) {
        if (before == null) {
            return "initial";
        }
        return rotation.forced() ? "reset" : "rollover";
    }

    /**
     * 这一段的共享范围。
     *
     * <p>键落到 {@code main} 的私聊是 {@code shared-main}——一个人的所有私聊共用这一段；
     * 群聊是 {@code group}；其余私聊（{@code per-channel-peer} 等）是 {@code conversation}。
     * 这个值回答"这一段的边界是什么"，查的时候不用再把键拆开。
     *
     * <p>判据照抄 OpenClaw 的 {@code resolveSqliteSessionScope}：{@code chatType === "direct"}
     * 且键是 {@code main} 或以 {@code :main} 结尾
     * （{@code src/config/sessions/session-accessor.sqlite-session-row.ts:143}）。
     * 包括它那处粗糙也一并照抄：{@code dm-scope: per-peer} 下 peer 段就是 chatId，
     * 一个恰好叫 {@code main} 的 chatId 会被算成共享主会话。改判据要两边一起改，
     * 否则同一条会话在两边拿到两个 scope，而对账的人是看不出来的。
     */
    private static String scopeOf(Key key) {
        if (!key.direct()) {
            return "group";
        }
        return key.sessionKey().endsWith(":" + SessionKeys.MAIN_PEER) ? "shared-main" : "conversation";
    }

    /** 拿不到结果就抛：锁没拿到之前读到的值不能用。 */
    private void lock(Key key) {
        jdbc.queryForList(lock, key.sessionKey());
    }

    /**
     * 写进列的时刻：截到毫秒，与 {@code entry_json.updatedAt} 同源同精度。
     *
     * <p>{@code Timestamp.from(Instant.now())} 留到纳秒（列上截成微秒），而 JSON 里存的是整毫秒，
     * 于是"列是 JSON 的投影"这句话只对到 0.999 毫秒——拿 {@code extract(epoch)*1000} 去比对账，
     * 差的那一毫秒还会被 {@code ::bigint} 的四舍五入放大成 1。它的存储本来就是整毫秒
     * （SQLite 那套 INTEGER），我们跟着它，投影才是真的。
     */
    private static Timestamp millis(Instant value) {
        return Timestamp.from(Instant.ofEpochMilli(value.toEpochMilli()));
    }

    private Previous read(Key key) {
        List<Previous> rows = jdbc.query(read, (rs, rowNum) -> new Previous(
                rs.getString("current_session_id"),
                instantOf(rs.getTimestamp("session_started_at")),
                instantOf(rs.getTimestamp("last_interaction_at"))), key.sessionKey());
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 可空的时间列，空就是"很久没动"。
     *
     * <p>V8 允许这两列为空：{@code entry_valid = -1} 那一档（逻辑 entry 被清空、行还在）的语义
     * 就是"把 entry_json 置成空、把提升列置空"。直接 {@code toInstant()} 的话这里是一个 NPE，
     * 它会被 {@code SessionEntries} 的兜底吞成"这段没有段"——而这个人的会话从此再也不换段，
     * 日志只有第一条。读成纪元之后，判定把它当陈旧，下一轮换段时时间被写回去，那一行自己就好了。
     *
     * <p>这与 OpenClaw 读侧的取舍一致：{@code normalizeSessionEntryTimestamp} 在
     * {@code updatedAt} 缺失时拿 {@code sessionStartedAt} 或当前时刻兜底
     * （{@code src/config/sessions/session-entry-json.ts:57}），不抛。
     */
    private static Instant instantOf(Timestamp value) {
        return value == null ? Instant.EPOCH : value.toInstant();
    }

    /**
     * 插一行"这一世"。
     *
     * <p>{@code account_id} 恒为 {@code null}：本仓库没有 account 这一维（一个通道实例只配一台
     * 机器人），而 V8 那列本来就是可空的——它记的是"这段来自哪台通道账号"，与
     * {@code channel} 是两件事。曾经这里两个参数都传了 {@code key.channel()}，
     * 于是库里每一段都声称自己属于一台名叫 {@code feishu} 的账号。
     */
    private void insertWindow(Key key, Previous before, String sessionId, String reason,
                              Rotation rotation, String conversationRef) {
        Timestamp now = millis(rotation.now());
        jdbc.update(insertWindow,
                sessionId, key.sessionKey(), before == null ? null : before.sessionId(),
                reason, scopeOf(key),
                now, now, now,
                key.direct() ? "direct" : "group",
                key.channel(), null,
                // 这一段由这条对话开启，它就是 primary。判据与 OpenClaw 的 currentSessionKey
                // 一致：一组 association 里，只有 role=primary 且这一列指向它的才有效。
                conversationRef);
    }

    private void insertNode(Key key, String sessionId, Rotation rotation) {
        Timestamp now = millis(rotation.now());
        jdbc.update(insertNode,
                key.sessionKey(), sessionId, sessionId, rotation.now().toEpochMilli(),
                now, now, now, now);
    }

    private void rotateNode(Key key, String sessionId, Rotation rotation) {
        Timestamp now = millis(rotation.now());
        jdbc.update(updateNodeRotated,
                sessionId, now, sessionId, rotation.now().toEpochMilli(),
                now, now, key.sessionKey());
    }

    private void touchNode(Key key, Rotation rotation) {
        Timestamp now = millis(rotation.now());
        jdbc.update(updateNodeTouched, now, now, rotation.now().toEpochMilli(), key.sessionKey());
    }

    /** 锁内读到的旧状态。字段名与列同义，避免与 {@link Key} 的位置参数混起来。 */
    private record Previous(String sessionId, Instant sessionStartedAt, Instant lastInteractionAt) {
    }
}

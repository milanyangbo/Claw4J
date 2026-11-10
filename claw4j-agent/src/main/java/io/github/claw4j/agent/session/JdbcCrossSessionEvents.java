package io.github.claw4j.agent.session;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.support.SessionEventCodec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.util.Assert;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 跨会话检索的 JDBC 实现：一条 SQL 查 {@code ai_session_event}（票 05）。
 *
 * <p>表名写全限定、走主连接池。会话仓储那一路不同：框架生成的 SQL 不带 schema，
 * 必须另开一个池把 {@code currentSchema} 指过去（见 {@link SessionSchema}）。这里的 SQL
 * 全在本类手里，加上前缀之后不依赖连接的 {@code search_path}，也就不需要第二个
 * {@code DataSource}——容器里出现两个会让 {@code @ConditionalOnSingleCandidate(DataSource.class)}
 * 那套自动配置整套不装配。
 *
 * <p>排序与分页照框架的 {@code SELECT_EVENTS_BASE}：{@code ORDER BY e.seq ASC} 再
 * {@code LIMIT ? OFFSET ?}。{@code EventFilter#applyWindow} 是前向切片
 * （{@code subList(page*size, min(…))}），所以 {@code page=0} 拿到的是最早几条，不是最近几条。
 * 两侧顺序必须一致：同一个 filter 在两个范围下给出不同结果，那种差异只在翻旧账时才会被发现。
 *
 * <p>关键词一段照方言的 {@code getKeywordFilterFragment}：
 * {@code LOWER(COALESCE(e.message_content, '')) LIKE ? ESCAPE '!'}，参数是
 * {@code "%" + 转义过的 lower(词) + "%"}。不自己写大小写敏感的子串匹配——
 * 模型给的关键词是随手写的，正文里的写法不一定一样。
 */
public final class JdbcCrossSessionEvents implements CrossSessionEvents {

    /**
     * 列清单与框架 {@code SELECT_EVENTS_BASE} 一字不差：行映射靠这些名字取值。
     *
     * <p>表名是拼出来的（{@code %s} 来自 {@link SessionSchema#qualifier}）：schema 可配
     * （{@code claw4j.session.schema}），写死 {@code claw4j_session} 的话，改配之后这条 SQL
     * 会报 {@code BadSqlGrammarException}，而那一档的异常被降级成"没找到"。
     */
    private static final String SELECT = """
            SELECT e.id, e.session_id, e.timestamp, e.message_type, e.message_content, \
            e.message_data, e.synthetic, e.archived, e.metadata
            FROM %sai_session_event e WHERE 1 = 1""";

    /** 与 {@code JdbcSessionRepositoryDialect#getKeywordFilterFragment} 同形，但不带前缀的 {@code AND}。 */
    private static final String KEYWORD = "LOWER(COALESCE(e.message_content, '')) LIKE ? ESCAPE '!'";

    /**
     * 行 → 事件。
     *
     * <p>解码交给框架的 {@link SessionEventCodec}，不自己拼 {@code Message}：事件表里存的是
     * 编码过的消息（{@code message_content} + {@code message_data}），工具调用、附件、思考块
     * 各有各的装法。自己解码只会解对文本那一类，其余的在召回里静默变成空正文。
     *
     * <p>{@code synthetic} 照框架那样再写进 metadata 一份：{@code SessionEvent#isSynthetic}
     * 读的就是这个键，不写的话整条消息都会被当成"用户说的"。
     */
    private static final SessionEventCodec CODEC = new SessionEventCodec();

    private static final RowMapper<SessionEvent> ROWS = (rs, rowNum) -> {
        MessageType type = MessageType.valueOf(rs.getString("message_type"));
        Message message = CODEC.decode(new SessionEventCodec.EncodedMessage(
                type, rs.getString("message_content"), rs.getString("message_data")));
        Map<String, Object> decoded = CODEC.fromJsonMap(rs.getString("metadata"));
        Map<String, Object> metadata = decoded == null ? new HashMap<>() : new HashMap<>(decoded);
        metadata.put(SessionEvent.METADATA_SYNTHETIC, rs.getBoolean("synthetic"));
        LocalDateTime at = rs.getObject("timestamp", LocalDateTime.class);
        return SessionEvent.builder()
                .id(rs.getString("id"))
                .sessionId(rs.getString("session_id"))
                .timestamp(at.toInstant(ZoneOffset.UTC))
                .message(message)
                .archived(rs.getBoolean("archived"))
                .metadata(metadata)
                .build();
    };

    private final JdbcTemplate jdbc;

    private final String select;

    /**
     * public 是为了让真库那条用例能直接构造它（{@code SearchSqlTest} 在 app 模块）。
     *
     * <p>这条 SQL 里 {@code ESCAPE '!'}、{@code LIMIT ? OFFSET ?}、{@code e.timestamp} 三件事
     * 只有真 Postgres 能判，替身判不了。
     *
     * @param jdbc   JDBC 句柄，走主连接池
     * @param schema 会话表所在的 schema（{@code claw4j.session.schema}）。非法标识符在构造时
     *               就抛，不留到第一条 SQL——那时候它会被降级成"没找到"
     */
    public JdbcCrossSessionEvents(JdbcTemplate jdbc, String schema) {
        this.jdbc = jdbc;
        this.select = SELECT.formatted(SessionSchema.qualifier(schema));
    }

    @Override
    public List<SessionEvent> find(EventFilter filter) {
        Assert.notNull(filter, "filter");
        // 用 UnsupportedOperationException 而不是 IllegalArgumentException：后者会被
        // RecallSessionTool 的 catch 接住，变成"召回参数不合法"回给模型——那是编程错误，
        // 不是模型能改的参数，回一句"换个词试试"只会让它换个词再撞一次。
        if (filter.pattern() != null) {
            throw new UnsupportedOperationException(
                    "跨会话检索不支持正则条件：SQL 里那是另一套语义（方言正则），召回也不用它");
        }
        if (filter.lastN() != null) {
            throw new UnsupportedOperationException(
                    "跨会话检索不支持 lastN：它是倒序窗口，与本类的升序分页不是一回事");
        }
        if (filter.keyword() != null) {
            throw new UnsupportedOperationException(
                    "跨会话检索只认复数 keywords：单数 keyword 在这里没有对应的 SQL 分支，"
                            + "而忽略它的表现是「结果多了」，与「这段历史确实说过」分不开");
        }

        StringBuilder sql = new StringBuilder(select);
        List<Object> args = new ArrayList<>();
        if (filter.excludeSynthetic()) {
            sql.append(" AND e.synthetic = false");
        }
        if (filter.excludeArchived()) {
            sql.append(" AND e.archived = false");
        }
        if (filter.from() != null) {
            sql.append(" AND e.timestamp >= ?");
            args.add(utc(filter.from()));
        }
        if (filter.to() != null) {
            sql.append(" AND e.timestamp <= ?");
            args.add(utc(filter.to()));
        }
        if (filter.messageTypes() != null && !filter.messageTypes().isEmpty()) {
            sql.append(" AND e.message_type IN (");
            List<String> marks = new ArrayList<>();
            filter.messageTypes().forEach(type -> {
                marks.add("?");
                args.add(type.name());
            });
            sql.append(String.join(", ", marks)).append(')');
        }
        appendKeywords(sql, args, filter);

        sql.append(" ORDER BY e.seq ASC");
        sql.append(" LIMIT ? OFFSET ?");
        int pageSize = filter.pageSize() == null ? EventFilter.DEFAULT_PAGE_SIZE : filter.pageSize();
        int page = filter.page() == null ? 0 : filter.page();
        args.add(pageSize);
        args.add((long) page * pageSize);

        return jdbc.query(sql.toString(), args.toArray(), ROWS);
    }

    /**
     * 关键词：{@code any} 用 OR 连、{@code all} 用 AND 连，整体加一对括号。
     *
     * <p>只认复数 {@code keywords}，单数 {@code keyword} 在 {@link #find} 里直接拒：
     * {@code EventFilter} 上两个字段都在，照着"支持"会多一条从不走、也从不被验的分支；
     * 默默忽略更糟——表现是"结果多了"，与"这段历史本来就说过"分不开。
     *
     * <p>不加括号会跟前后的 {@code AND} 粘错：{@code any} 模式下 {@code AND a OR b} 变成
     * "（全部条件 AND a）OR b"，b 一命中就无视 synthetic 与时间范围。
     */
    private static void appendKeywords(StringBuilder sql, List<Object> args, EventFilter filter) {
        List<String> keywords = filter.keywords();
        if (keywords == null || keywords.isEmpty()) {
            return;
        }
        String glue = filter.matchMode() == EventFilter.MatchMode.ALL ? " AND " : " OR ";
        sql.append(" AND (");
        for (int i = 0; i < keywords.size(); i++) {
            if (i > 0) {
                sql.append(glue);
            }
            sql.append(KEYWORD);
            args.add(contains(keywords.get(i)));
        }
        sql.append(')');
    }

    /**
     * 一个词变成 LIKE 的模式：先转义 {@code !}（它是转义符本身），再转 {@code %} 与 {@code _}，
     * 两端各补一个通配符。顺序不能反——先转 {@code %} 的话，{@code !} 变 {@code !!} 时会把
     * 已补上的转义符再转一遍。
     */
    static String contains(String term) {
        String escaped = term.replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return "%" + escaped.toLowerCase(Locale.ROOT) + "%";
    }

    /** 库里存的是 UTC 的 {@code LocalDateTime}，按 UTC 解释回去。 */
    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}

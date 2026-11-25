package io.github.claw4j.agent.session;

import io.github.claw4j.domain.port.SessionQuery;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 侧边栏读侧的 JDBC 实现（票 02）：只读框架那两张表，不新增存储。
 *
 * <p>两个方法各走框架的一个读入口，见 {@link SessionQuery}：{@link #list} 走
 * {@code findByUserId}（有哪些会话），{@link #conversation} 走 {@code getEvents}（这条会话里
 * 每一轮）。第三个入口 {@code findEventsByUserId} 是召回那条线，这里不用。
 *
 * <p>归属判定在 {@link #conversation} 里做，不在 SQL 里：框架的 {@code findById} 不带 owner，
 * 于是"这个 id 存不存在"与"是不是你的"必须在这一层合成一个答案——不属于就当作不存在，
 * 不泄露"存在但不是你的"。
 *
 * <p>{@link #list} 只收 {@code claw4j:user:} 开头的 id：定时任务（{@code cron:<taskId>}）与子 Agent
 * 也算会话，但它们不是"这个人在跟谁说话"，混进侧边栏之后，一个人的列表里会出现几条他从未
 * 打开过的对话，而且标题是从定时任务的提示词里截的。
 */
public final class JdbcSessionQuery implements SessionQuery {

    /** 侧边栏一行只看两格：`#` 之类开头的记号，与压平之后的空白。 */
    private static final Pattern MARKDOWN_MARKERS = Pattern.compile("(?m)^\\s{0,3}(?:```[\\w-]*|[#>\\-*+]{1,3})\\s*");

    /** 标题最长多少个字符（按码点算，不切坏表情）。 */
    private static final int TITLE_LIMIT = 40;

    /** 逐轮只回这两类：{@code system}、工具请求与工具结果不是"说过的话"。 */
    private static final Set<MessageType> TURNS = Set.of(MessageType.USER, MessageType.ASSISTANT);

    /** 列表排序：最后活动倒序，同一时刻按 id 倒序——游标要在同一时刻上仍能定序。 */
    private static final Comparator<SessionQuery.Summary> NEWEST_FIRST =
            Comparator.comparing(SessionQuery.Summary::lastActivityAt)
                    .thenComparing(SessionQuery.Summary::id)
                    .reversed();

    private final SessionService sessions;

    /**
     * @param sessions 会话服务，读的是它背后那两张表
     */
    public JdbcSessionQuery(SessionService sessions) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    @Override
    public SessionQuery.Page list(String owner, int limit, String cursor) {
        Objects.requireNonNull(owner, "owner");
        int size = clamp(limit);
        Cursor after = Cursor.parse(cursor);

        List<SessionQuery.Summary> rows = new ArrayList<>(sessions.findByUserId(owner).stream()
                .filter(session -> session.id().startsWith(SessionKeys.PREFIX))
                .map(this::summarize)
                .toList());
        rows.sort(NEWEST_FIRST);

        int from = 0;
        while (after != null && from < rows.size() && after.covers(rows.get(from))) {
            from++;
        }
        int to = Math.min(from + size, rows.size());
        List<SessionQuery.Summary> page = List.copyOf(rows.subList(from, to));
        String next = (to < rows.size() && to > from) ? Cursor.keyOf(rows.get(to - 1)) : null;
        return new SessionQuery.Page(page, next);
    }

    @Override
    public Optional<SessionQuery.Conversation> conversation(String owner, String sessionId, int limit) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(sessionId, "sessionId");
        Session session = sessions.findById(sessionId);
        if (session == null || !owner.equals(session.userId())) {
            return Optional.empty();
        }
        int size = clamp(limit);
        // lastN 而不是分页：打开会话要看的是"最近说了什么"，而分页从最早一条开始——
        // 一段聊了三个月的会话，第一页永远是三个月前那几句。窗口会按轮补齐（框架契约），
        // 所以回条数可能比 limit 多，这是有意的：半轮比少一条更难解释。
        List<SessionEvent> window = sessions.getEvents(sessionId, EventFilter.builder()
                .messageTypes(TURNS)
                .excludeSynthetic(true)
                .lastN(size)
                .build());
        boolean hasMore = !window.isEmpty() && !window.getFirst().getId().equals(firstEventId(sessionId));
        List<SessionQuery.Turn> turns = window.stream().map(JdbcSessionQuery::turnOf).toList();
        return Optional.of(new SessionQuery.Conversation(sessionId, session.createdAt(), turns, hasMore));
    }

    /**
     * 侧边栏一行：id、建的时候、最后活动、标题。
     *
     * @param session 框架那一行
     * @return 一行
     */
    private SessionQuery.Summary summarize(Session session) {
        List<SessionEvent> tail = sessions.getEvents(session.id(), EventFilter.lastN(1));
        Instant lastActivityAt = tail.isEmpty() ? session.createdAt() : tail.getLast().getTimestamp();
        return new SessionQuery.Summary(session.id(), session.createdAt(), lastActivityAt,
                titleOf(firstUserText(session.id())));
    }

    /**
     * 首条用户消息：{@code page(0)} + {@code pageSize(1)}，分页按时间正序，所以第 0 页第一条
     * 就是最早的那条。
     *
     * <p>不看 {@code archived}（默认 false）：压缩归档的照样读得到，否则聊长了之后侧边栏的标题
     * 会突然变成"新对话"——归档的是上下文窗口，不是这段对话叫什么。
     *
     * @param sessionId 会话 id
     * @return 正文；没有用户消息时为 null
     */
    private String firstUserText(String sessionId) {
        List<SessionEvent> first = sessions.getEvents(sessionId, EventFilter.builder()
                .messageTypes(Set.of(MessageType.USER))
                .excludeSynthetic(true)
                .page(0)
                .pageSize(1)
                .build());
        return first.isEmpty() ? null : first.getFirst().getMessage().getText();
    }

    /**
     * 这条会话最早一条事件的编号，用来判"后面还有没有更早的"。
     *
     * <p>判据只能是"窗口的第一条是不是全量里那条最早的"：{@code lastN} 会按轮补齐，
     * 回条数与 limit 不等，靠数数会误报。
     *
     * @param sessionId 会话 id
     * @return 编号；一条事件都没有时为 null
     */
    private String firstEventId(String sessionId) {
        List<SessionEvent> first = sessions.getEvents(sessionId, EventFilter.builder()
                .page(0)
                .pageSize(1)
                .build());
        return first.isEmpty() ? null : first.getFirst().getId();
    }

    /**
     * 一条事件 → 一轮。
     *
     * @param event 事件
     * @return 一轮
     */
    private static SessionQuery.Turn turnOf(SessionEvent event) {
        return new SessionQuery.Turn(event.getId(), event.getTimestamp(),
                event.getMessageType().name().toLowerCase(Locale.ROOT), event.getMessage().getText());
    }

    /**
     * 标题：压平空白、去掉开头的 Markdown 记号、截断 40 字。
     *
     * @param raw 首条用户消息的正文，可以为 null
     * @return 显示名；没有正文时是 {@link SessionQuery#UNTITLED}
     */
    static String titleOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return SessionQuery.UNTITLED;
        }
        String text = MARKDOWN_MARKERS.matcher(raw).replaceAll("").replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) {
            return SessionQuery.UNTITLED;
        }
        if (text.codePointCount(0, text.length()) <= TITLE_LIMIT) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, TITLE_LIMIT)) + "…";
    }

    /**
     * 每页条数的上限与下限。
     *
     * @param limit 调用方给的数
     * @return 1..{@link SessionQuery#MAX_LIMIT}
     */
    private static int clamp(int limit) {
        return Math.min(Math.max(limit, 1), SessionQuery.MAX_LIMIT);
    }

    /**
     * 翻页游标：{@code <最后活动毫秒>:<会话 id>}。
     *
     * <p>不用下标：列表按最后活动排序，聊一句就会重排，下标会把上一页的尾巴带到下一页。
     * 不用"只给 id"：那要去查 id 才能知道从哪开始，而这一页的排序键本来就在手里。
     *
     * <p>会话 id 里本来就有冒号（{@code claw4j:user:...}），所以按**第一个**冒号切：
     * 毫秒数里没有冒号，这样切永远不含糊。
     *
     * @param at 最后活动时刻
     * @param id 会话 id
     */
    private record Cursor(Instant at, String id) {

        static String keyOf(SessionQuery.Summary summary) {
            return summary.lastActivityAt().toEpochMilli() + ":" + summary.id();
        }

        /**
         * @param cursor 上一页的 {@code nextCursor}，可以为 null（第一页）
         * @return 游标；null 表示第一页
         * @throws IllegalArgumentException 形状不认识——那是调用方把别的东西传进来了
         */
        static Cursor parse(String cursor) {
            if (cursor == null || cursor.isBlank()) {
                return null;
            }
            int split = cursor.indexOf(':');
            if (split <= 0 || split == cursor.length() - 1) {
                throw new IllegalArgumentException("cursor 必须是上一页返回的 nextCursor：" + cursor);
            }
            try {
                return new Cursor(Instant.ofEpochMilli(Long.parseLong(cursor.substring(0, split))),
                        cursor.substring(split + 1));
            }
            catch (NumberFormatException notACursor) {
                throw new IllegalArgumentException("cursor 必须是上一页返回的 nextCursor：" + cursor, notACursor);
            }
        }

        /**
         * 这一行是否已经翻过去了（在游标那一行之前或就是它）。
         *
         * @param row 一行
         * @return 是则真
         */
        boolean covers(SessionQuery.Summary row) {
            int byTime = row.lastActivityAt().compareTo(at);
            return byTime > 0 || (byTime == 0 && row.id().compareTo(id) >= 0);
        }
    }
}

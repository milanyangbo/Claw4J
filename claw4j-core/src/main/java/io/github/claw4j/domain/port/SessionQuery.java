package io.github.claw4j.domain.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 会话的只读入口（ADR-0029 修订）：侧边栏列会话，点进去看逐轮。
 *
 * <p>两个方法回答两个不同的问题，不能互相替代：{@link #list} 说"这个人有哪几条会话"，
 * {@link #conversation} 说"这一条会话里每一轮说了什么"。前者不知道"哪一条"，后者需要一个 id——
 * 而列表的意义正是先把它拿到。
 *
 * <p>第三个容易混的是"跨这个人的所有会话捞消息"（召回用的那一路）：拿它做列表要先按会话分组，
 * 拿它做单会话历史是先捞全量再筛。两个方向都多绕一步，所以不在这里。
 *
 * <p>读的是框架那两张表（{@code ai_session} / {@code ai_session_event}），本仓库不再有第二份
 * 会话身份。归属判定在实现里做：{@code owner} 与 {@code ai_session.user_id} 不等就当作不存在，
 * 不泄露"这个 id 存在但不是你的"。
 */
public interface SessionQuery {

    /** 一页多少条：与 OpenClaw 的 {@code sessions.list} 默认值一致。 */
    int DEFAULT_LIMIT = 20;

    /** 打开一条会话时默认回多少轮。比列表大一档：一轮对话本来就长过一行标题。 */
    int DEFAULT_TURNS = 50;

    /**
     * 一条消息都没有时的显示名。
     *
     * <p>空标题会让侧边栏出现一行空白，读的人以为那里有东西没加载出来。
     */
    String UNTITLED = "新对话";

    /** 上限。再大就不是"翻页"而是"导出"，那是另一个接口的事。 */
    int MAX_LIMIT = 100;

    /**
     * 这个人的会话列表，按最后活动时间倒序（最近聊过的排前面）。
     *
     * <p>为什么是最后活动而不是创建时间：聊了三天的会话一直排在下面，用户就找不到它了。
     * 框架的 {@code Session} 只有 {@code createdAt}，所以每条会话要再读一次最后一条事件
     * ——一页 N 条就是 2N 次轻查询（都有 {@code session_id} 索引），这是这一版的代价，
     * 框架哪天给 {@code Session} 加 {@code updatedAt} 就删掉它。
     *
     * @param owner 归属键（{@code SessionKeys.ownerOf} 的输出）
     * @param limit 每页条数，实现里钳到 1..{@link #MAX_LIMIT}
     * @param cursor 上一页的 {@link Page#nextCursor()}；null 表示第一页
     * @return 一页
     * @throws IllegalArgumentException 游标不是本接口发出来的形状
     */
    Page list(String owner, int limit, String cursor);

    /**
     * 一条会话的逐轮。
     *
     * @param owner 归属键
     * @param sessionId 会话 id（就是会话键，ADR-0026 修订）
     * @param limit 最多回几条，实现里钳到 1..{@link #MAX_LIMIT}
     * @return 会话；不属于这个人或不存在时为空
     */
    Optional<Conversation> conversation(String owner, String sessionId, int limit);

    /**
     * 一页会话。
     *
     * @param sessions 这一页，按最后活动时间倒序
     * @param nextCursor 下一页游标；没有更多时为空
     */
    record Page(List<Summary> sessions, String nextCursor) {
    }

    /**
     * 侧边栏的一行。
     *
     * @param id 会话 id
     * @param createdAt 建的时候
     * @param lastActivityAt 最后一条事件的时刻；一条事件都没有时等于 {@code createdAt}
     * @param title 显示名：首条用户消息截断，没有消息时是"新对话"
     */
    record Summary(String id, Instant createdAt, Instant lastActivityAt, String title) {
    }

    /**
     * 一条会话的逐轮。
     *
     * @param id 会话 id
     * @param createdAt 建的时候
     * @param turns 从最早开始的一页
     * @param hasMore 后面还有没有
     */
    record Conversation(String id, Instant createdAt, List<Turn> turns, boolean hasMore) {
    }

    /**
     * 一轮。
     *
     * @param id 事件编号
     * @param at 说的时刻
     * @param role {@code user} / {@code assistant} / {@code tool} / {@code system}
     * @param text 正文；工具轮可能为空（那是"调了什么"，不在这一列）
     */
    record Turn(String id, Instant at, String role, String text) {
    }
}

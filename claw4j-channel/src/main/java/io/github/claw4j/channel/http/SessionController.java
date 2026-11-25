package io.github.claw4j.channel.http;

import io.github.claw4j.channel.spi.PrincipalRejectedException;
import io.github.claw4j.domain.port.SessionQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * 会话的两层读入口（票 02）：{@code GET /api/sessions} 列会话，
 * {@code GET /api/sessions/{id}/messages} 看某一条里每一轮。
 *
 * <p>两个端点不能合成一个"历史"接口：前者一个会话一行，后者一条消息一行，粒度不同。
 * 合起来之后返回体里一定长出两种粒度混排的数组，客户端要靠"这一条有没有 turns 字段"
 * 猜自己拿到的是什么。设计与"为什么是两个读方法"见 {@code docs/design/session-memory.md}。
 *
 * <p>认人与 {@code /api/chat} 同一条路（{@link HttpPrincipalResolver}，{@code X-Claw4j-User}）。
 * 这里只把"这个人是谁"传下去，"这些会话是不是他的"由查询那一层判——两个地方各判一次，
 * 第二个必然漏。
 *
 * <p>没有装配查询时回 404 而不是 500：能力不在场，与 {@code /api/audit} 同一个判断。
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private static final Logger log = LoggerFactory.getLogger(SessionController.class);

    private final ObjectProvider<SessionQuery> queries;

    private final HttpPrincipalResolver principalResolver;

    public SessionController(ObjectProvider<SessionQuery> queries, HttpPrincipalResolver principalResolver) {
        this.queries = queries;
        this.principalResolver = principalResolver;
    }

    /**
     * 这个人的会话列表，最近聊过的在前。
     *
     * @param user 请求头里的用户标识，允许缺失（缺失即 401）
     * @param cursor 上一页的 {@code nextCursor}；不给就是第一页
     * @param limit 每页条数，1–100（越界在查询那一层钳位）
     * @return 200 加一页；游标不认识时 400；没有装配查询时 404
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> list(@RequestHeader(value = HttpPrincipalResolver.USER_HEADER,
                                          required = false) String user,
                                  @RequestParam(value = "cursor", required = false) String cursor,
                                  @RequestParam(value = "limit",
                                          defaultValue = "" + SessionQuery.DEFAULT_LIMIT) int limit) {
        String owner = principalResolver.resolve(user).userId();
        SessionQuery query = queries.getIfAvailable();
        if (query == null) {
            log.debug("没有装配会话查询，/api/sessions 一律 404");
            return ResponseEntity.notFound().build();
        }
        SessionQuery.Page page;
        try {
            page = query.list(owner, limit, cursor);
        }
        catch (IllegalArgumentException notACursor) {
            return ResponseEntity.badRequest().body(new Problem(notACursor.getMessage()));
        }
        List<SessionView> sessions = page.sessions().stream().map(SessionView::of).toList();
        return ResponseEntity.ok(new SessionListView(sessions, page.nextCursor()));
    }

    /**
     * 一条会话里最近的那些轮，按时间正序。
     *
     * <p>会话 id 里带冒号（{@code claw4j:user:<owner>:<peer>}），路径段里原样带着就行：
     * 冒号在路径里不是保留字符。
     *
     * @param id 会话 id
     * @param user 请求头里的用户标识
     * @param limit 最多回多少轮，1–100
     * @return 200 加这些轮；会话不存在或不属于这个人时 404
     */
    @GetMapping(path = "/{id}/messages", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> messages(@PathVariable("id") String id,
                                      @RequestHeader(value = HttpPrincipalResolver.USER_HEADER,
                                              required = false) String user,
                                      @RequestParam(value = "limit",
                                              defaultValue = "" + SessionQuery.DEFAULT_TURNS) int limit) {
        String owner = principalResolver.resolve(user).userId();
        SessionQuery query = queries.getIfAvailable();
        if (query == null) {
            log.debug("没有装配会话查询，/api/sessions 一律 404");
            return ResponseEntity.notFound().build();
        }
        // 拿别人的 id 与拿一个不存在的 id 在这里是同一个答案：判定在查询那一层合并，
        // 分成两种情况回（403 / 404）等于告诉调用方"这个 id 存在"。
        return query.conversation(owner, id, limit)
                .<ResponseEntity<?>>map(conversation -> ResponseEntity.ok(ConversationView.of(conversation)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 认人失败是"不该进来"，回 401；与聊天、审批同一口径。 */
    @ExceptionHandler(PrincipalRejectedException.class)
    public ResponseEntity<ErrorResponse> onPrincipalRejected(PrincipalRejectedException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse(exception.reason()));
    }

    /**
     * 一页会话。
     *
     * @param sessions 这一页，最近聊过的在前
     * @param nextCursor 下一页游标；没有更多时不存在
     */
    public record SessionListView(List<SessionView> sessions, String nextCursor) {
    }

    /**
     * 侧边栏一行。
     *
     * @param id 会话 id（打开逐轮时原样带回路径里）
     * @param createdAt 建的时候
     * @param lastActivityAt 最后一条消息的时刻；一条消息都没有时等于 {@code createdAt}
     * @param title 显示名：首条用户消息截断 40 字，没有消息时是"新对话"
     */
    public record SessionView(String id, Instant createdAt, Instant lastActivityAt, String title) {

        static SessionView of(SessionQuery.Summary summary) {
            return new SessionView(summary.id(), summary.createdAt(), summary.lastActivityAt(),
                    summary.title());
        }
    }

    /**
     * 一条会话里的这些轮。
     *
     * @param id 会话 id
     * @param createdAt 建的时候
     * @param hasMore 更早的还有没有（这一版不给"往前翻"的参数，先让人知道有）
     * @param turns 从早到晚；窗口按轮补齐，所以条数可能比 {@code limit} 多
     */
    public record ConversationView(String id, Instant createdAt, boolean hasMore, List<TurnView> turns) {

        static ConversationView of(SessionQuery.Conversation conversation) {
            return new ConversationView(conversation.id(), conversation.createdAt(), conversation.hasMore(),
                    conversation.turns().stream().map(TurnView::of).toList());
        }
    }

    /**
     * 一轮。
     *
     * @param id 事件编号
     * @param at 说的时刻
     * @param role {@code user} / {@code assistant}
     * @param text 正文；带工具调用的那一轮可能只有工具名，正文为空
     */
    public record TurnView(String id, Instant at, String role, String text) {

        static TurnView of(SessionQuery.Turn turn) {
            return new TurnView(turn.id(), turn.at(), turn.role(), turn.text());
        }
    }

    /**
     * 参数错误。
     *
     * @param error 人话说明哪个参数不对
     */
    public record Problem(String error) {
    }

    /**
     * 认人失败时的响应体。
     *
     * @param error 拒绝原因
     */
    public record ErrorResponse(String error) {
    }
}

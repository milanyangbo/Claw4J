package io.github.claw4j.agent.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.compaction.CompactionResult;
import org.springframework.ai.session.compaction.CompactionStrategy;
import org.springframework.ai.session.compaction.CompactionTrigger;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 会话存储不可用时<b>退化成"这一轮没有记忆"</b>，而不是把整条链拖垮。
 *
 * <p><b>为什么记忆可以降级，鉴权与脱敏不行</b>：三个 Advisor 的失败姿势不一样。
 * 鉴权失败如果放行，越权就发生了；脱敏失败如果放行，原始号码就出去了——
 * 它们失败时只能拒绝。记忆失败的唯一后果是"这一轮接不上上一轮"，
 * 用户会立刻发现（"我刚说过"），而让整条链 500 连**鉴权路径**都一起答不了话，
 * 是拿一个可用性问题换一个安全问题：库挂了的时候，"没权限"这句话也说不出来。
 *
 * <p><b>降级不是静默</b>：每次降级都打一条 WARN，带上动作名。
 * "悄悄没记忆"和"库挂了"在用户看来一模一样，只能靠日志分开。
 *
 * <p>这里包的是 {@link SessionService} 而不是 {@code SessionRepository}：
 * 存储层的返回值是有语义的（{@code saveIfAbsent} 返回 false 表示"已存在"，
 * 上层会据此抛"会话已存在"），在那一层编一个假返回值，
 * 等于把"存储不可用"伪装成"存储说了话"。服务层的返回值语义简单得多。
 */
public final class FaultTolerantSessionService implements SessionService {

    private static final Logger log = LoggerFactory.getLogger(FaultTolerantSessionService.class);

    private final SessionService delegate;

    public FaultTolerantSessionService(SessionService delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /**
     * 创建不出会话时返回一个<b>没有落库</b的 Session，而不是 null。
     * 返回 null 会让 {@code SessionMemoryAdvisor} 抛"会话不存在"，
     * 而它要的只是一个能往下走的对象：历史是空的，这次对话照常进行。
     */
    @Override
    public Session create(CreateSessionRequest request) {
        Objects.requireNonNull(request, "request");
        return fallback(() -> delegate.create(request),
                () -> Session.builder()
                        .id(request.id())
                        .userId(request.userId())
                        .createdAt(Instant.now())
                        .build(),
                "创建会话 " + request.id());
    }

    /** 读不到就是没有：不要区分"库里没这个人"和"库连不上"，对调用方都是没有。 */
    @Override
    public Session findById(String id) {
        return fallback(() -> delegate.findById(id), () -> null, "读取会话 " + id);
    }

    @Override
    public List<Session> findByUserId(String userId) {
        return fallback(() -> delegate.findByUserId(userId), List::of, "按用户查会话 " + userId);
    }

    @Override
    public void delete(String id) {
        swallow(() -> delegate.delete(id), "删除会话 " + id);
    }

    @Override
    public int deleteExpiredSessions(Instant now) {
        return fallback(() -> delegate.deleteExpiredSessions(now), () -> 0, "清理过期会话");
    }

    /** 追加失败只意味着这一轮不会被记住，不影响这一轮已经算出来的答案。 */
    @Override
    public void appendEvent(SessionEvent event) {
        // 不 dereference event：这一层是以"绝不把存储问题变成整链失败"为职责的，
        // 为了拼一句日志先 NPE 一次，等于自己破了自己的职责。
        String what = "追加会话事件" + (event == null ? "" : " " + event.getSessionId());
        swallow(() -> delegate.appendEvent(event), what);
    }

    @Override
    public List<SessionEvent> getEvents(String sessionId, EventFilter filter) {
        return fallback(() -> delegate.getEvents(sessionId, filter), List::of, "读取会话事件 " + sessionId);
    }

    @Override
    public List<SessionEvent> findEventsByUserId(String userId, EventFilter filter) {
        return fallback(() -> delegate.findEventsByUserId(userId, filter), List::of,
                "按用户查事件 " + userId);
    }

    /**
     * 压缩失败返回 null：M1 没有启用压缩（{@code SessionMemoryConfiguration} 不设
     * compactionTrigger），所以这条路径不会被走到；留着是为了配置一旦打开，
     * 库不可用时不会因为压缩把整轮对话搞崩。
     */
    @Override
    public CompactionResult compact(String sessionId, CompactionTrigger trigger, CompactionStrategy strategy) {
        return fallback(() -> delegate.compact(sessionId, trigger, strategy), () -> null,
                "压缩会话 " + sessionId);
    }

    /**
     * 认两类异常，不是一个父类下的两种：
     *
     * <ul>
     *   <li>{@link DataAccessException} —— JDBC 那一支（表没了、SQL 错了、连不上）。
     *   <li>{@link TransactionException} —— 事务那一支。{@code JdbcSessionRepository} 把写操作
     *       包在事务里，事务开不了时抛的是 {@code CannotCreateTransactionException}，
     *       它继承的是 {@code TransactionException} 而<b>不是</b> {@code DataAccessException}。
     *       只认前者的话，"连不上库"这一种恰恰漏在外面——正是最该降级的那一种。
     * </ul>
     */
    private <T> T fallback(Supplier<T> action, Supplier<T> degraded, String what) {
        try {
            return action.get();
        }
        catch (DataAccessException | TransactionException unavailable) {
            log.warn("会话存储不可用，本次降级为没有记忆（{}）：{}", what, unavailable.getMessage());
            return degraded.get();
        }
    }

    private void swallow(Runnable action, String what) {
        fallback(() -> {
            action.run();
            return null;
        }, () -> null, what);
    }
}

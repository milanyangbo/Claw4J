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
 * 会话存储不可用时退化成"这一轮没有记忆"，而不让整条链失败。
 *
 * <p>记忆可以降级，鉴权与脱敏不行：后两者失败时放行就是越权与明文外泄，只能拒绝；
 * 记忆失败只是接不上上一轮，用户下一句就会发现。库挂时若整条链 500，
 * 连鉴权路径也一起答不了话，等于拿可用性问题换安全问题。
 *
 * <p>降级不静默：每次打一条 WARN 并带上动作名。"悄悄没记忆"与"库挂了"在用户看来一样，
 * 只能靠日志分开。
 *
 * <p>包在 bean 上而不是在调用点 try/catch：{@code SessionMemoryAdvisor} 直接持有
 * {@link SessionService} 读写历史，那些调用点改不了，bean 是唯一能拦到的位置。
 *
 * <p>包的是 {@link SessionService} 而不是 {@code SessionRepository}：存储层的返回值有语义
 * （{@code saveIfAbsent} 返回 false 表示"已存在"，上层据此抛"会话已存在"），
 * 在那一层编一个假返回值，等于把"存储不可用"伪装成"存储说了话"。
 */
public final class FaultTolerantSessionService implements SessionService {

    private static final Logger log = LoggerFactory.getLogger(FaultTolerantSessionService.class);

    private final SessionService delegate;

    public FaultTolerantSessionService(SessionService delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /**
     * 创建会话；存储不可用时返回一个没有落库的 Session，而不是 null。
     *
     * <p>返回 null 会让 {@code SessionMemoryAdvisor} 抛"会话不存在"，而它要的只是一个
     * 能往下走的对象：历史为空，这一轮照常进行。
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

    /**
     * 读不到就是没有：不区分"库里没这个人"与"库连不上"，对调用方都是没有。
     */
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

    /**
     * 清理过期会话；本仓库不会调到这里。
     *
     * <p>过期判据是"多久没动"，在 {@link SessionMaintenance} 里。框架的 TTL 只写在建行那一刻，
     * 不随活动刷新，拿它删会把活跃会话删掉。
     *
     * <p>仍然要实现，因为这一层是整个接口的装饰器：只覆盖一部分的话，未覆盖的方法在存储
     * 故障时会绕过降级直接抛给调用方，同一个 bean 一半降级一半不降级，更难查。
     */
    @Override
    public int deleteExpiredSessions(Instant now) {
        return fallback(() -> delegate.deleteExpiredSessions(now), () -> 0, "清理过期会话");
    }

    /**
     * 追加事件；失败只意味着这一轮不会被记住，不影响这一轮已经算出来的答案。
     */
    @Override
    public void appendEvent(SessionEvent event) {
        // 不 dereference event：这一层以"绝不把存储问题变成整链失败"为职责，
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
     * 压缩会话；失败返回 null。
     *
     * <p>{@code SessionMemoryConfiguration} 不向 Advisor 传 compactionTrigger，压缩由
     * {@link SessionCompactor} 在进模型之前驱动，这条路径目前不会被走到。留着是为了配置一旦
     * 打开，库不可用时不会因为压缩把整轮对话搞崩。
     */
    @Override
    public CompactionResult compact(String sessionId, CompactionTrigger trigger, CompactionStrategy strategy) {
        return fallback(() -> delegate.compact(sessionId, trigger, strategy), () -> null,
                "压缩会话 " + sessionId);
    }

    /**
     * 认两类异常，它们不是同一父类下的两种。
     *
     * <p>{@link DataAccessException} 覆盖 JDBC 那一支（表没了、SQL 错了、连不上）；
     * {@link TransactionException} 覆盖事务那一支——{@code JdbcSessionRepository} 把写操作包在
     * 事务里，事务开不了时抛 {@code CannotCreateTransactionException}，它继承的是
     * {@code TransactionException} 而不是 {@code DataAccessException}。只认前者的话，
     * "连不上库"这一种恰好漏在外面，而它正是最该降级的那一种。
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

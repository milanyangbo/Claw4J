package io.github.claw4j.agent.session;

import io.github.claw4j.domain.principal.Principal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.compaction.CompactionResult;
import org.springframework.ai.session.compaction.CompactionStrategy;
import org.springframework.ai.session.compaction.CompactionTrigger;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 记忆这一块的三件事：<b>会话 id 怎么来</b>、<b>会话表落在哪个 schema</b>、
 * <b>存储不可用时怎么办</b>。
 *
 * <p>三件事放在一起，是因为它们各自单独看都"没有返回值可以断言"：
 * 前两件的错误表现都是<b>不报错、但结果是错的</b>——所有人共用一个会话、
 * 会话表悄悄落到 public。这种错误正是最需要测试钉住的一类（见 docs/agents/testing.md 判据一）。
 *
 * <p>这里的存储替身直接抛异常，不依赖真数据库：降级是"库里出事时"的行为，
 * 用真库反而没法稳定复现。
 */
class SessionMemoryTest {

    private static final String USER = "ethan";

    // ---------------------------------------------------------------- 会话 id

    /**
     * 会话属于人，不属于通道：{@link SessionKeys#sessionIdOf} 只吃 {@code userId}。
     * 换通道（HTTP 请求头 → 飞书 chat_id）时这一段代码根本没被问到 chatId，
     * 所以上下文自然接得上。同一句话的另一面是：<b>换个用户必须是另一个会话</b>。
     */
    @Test
    @DisplayName("会话 id 只由人派生：同一个人反复问是同一个会话，换个人就换会话")
    void 会话id只由人派生() {
        String ethan = SessionKeys.sessionIdOf(Principal.readOnly(USER));
        String again = SessionKeys.sessionIdOf(Principal.readOnly(USER));
        String lisa = SessionKeys.sessionIdOf(Principal.readOnly("lisa"));

        assertThat(ethan).isEqualTo(SessionKeys.PREFIX + USER);
        assertThat(again).isEqualTo(ethan);
        assertThat(lisa).isNotEqualTo(ethan);
    }

    /**
     * 空的 {@code userId} 必须拒绝：它不会导致"存不下"，而是让<b>所有人共用一个会话</b>。
     * 那比没有记忆更糟——它看起来记得住，只是记得的是别人的。
     */
    @Test
    @DisplayName("用户 id 为空时直接拒绝，而不是让所有人共用一个会话")
    void 用户id为空时拒绝() {
        assertThatThrownBy(() -> SessionKeys.sessionIdOf(Principal.readOnly("")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SessionKeys.sessionIdOf(Principal.readOnly("   ")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------- 会话 schema

    /**
     * 表名不带 schema 前缀，落点全由连接的 {@code search_path} 决定，
     * 而 {@code currentSchema} 是唯一跟着连接池走的那个开关（见 {@link SessionSchema}）。
     */
    @Test
    @DisplayName("schema 拼进 JDBC URL：原本没有参数用 ?，已有参数用 &")
    void schema拼进JDBC_URL() {
        assertThat(SessionSchema.urlWithSchema("jdbc:postgresql://db:5432/claw4j", "claw4j_session"))
                .isEqualTo("jdbc:postgresql://db:5432/claw4j?currentSchema=claw4j_session");
        assertThat(SessionSchema.urlWithSchema("jdbc:postgresql://db:5432/claw4j?sslmode=disable",
                "claw4j_session"))
                .isEqualTo("jdbc:postgresql://db:5432/claw4j?sslmode=disable&currentSchema=claw4j_session");
    }

    /**
     * 空 schema 拼进去不报错，它只是让会话表悄悄落到 public——正是 ADR-0005 要避免的。
     * 带空白或 {@code ?} 的 schema 会把后面那截 URL 弄坏，同样得在拼之前拦下。
     */
    @Test
    @DisplayName("schema 为空或脏时拒绝，而不是让会话表落到 public")
    void schema为空或脏时拒绝() {
        assertThatThrownBy(() -> SessionSchema.urlWithSchema("jdbc:postgresql://db/x", ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SessionSchema.urlWithSchema("jdbc:postgresql://db/x", "   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SessionSchema.urlWithSchema("jdbc:postgresql://db/x", "a b"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ 降级

    /**
     * 读不到就当作没有，不抛。不要区分"库里没这个人"和"库连不上"：
     * 对调用方来说两者都是"没有历史"，而抛出去会把这句话变成 500。
     */
    @Test
    @DisplayName("读不到会话时当作没有：返回空值而不是把异常扔给上游")
    void 读取降级() {
        SessionService service = new FaultTolerantSessionService(
                FakeSessionService.down(STORAGE_DOWN));

        assertThat(service.findById("claw4j:user:ethan")).isNull();
        assertThat(service.getEvents("claw4j:user:ethan", null)).isEmpty();
        assertThat(service.findEventsByUserId(USER, null)).isEmpty();
        assertThat(service.findByUserId(USER)).isEmpty();
        assertThat(service.compact("claw4j:user:ethan", null, null)).isNull();
    }

    /**
     * 创建必须返回一个 Session 而不是 null：{@code SessionMemoryAdvisor} 拿到 null
     * 会抛"会话不存在"，于是"库挂了"变成"这条消息 500"。
     *
     * <p>返回的这个会话<b>没有落库</b>，本来也不必——这一轮要的只是一个能往下走的对象，
     * 历史是空的，答案照常算。
     */
    @Test
    @DisplayName("创建不出会话时给一个没落库的会话，好让这一轮照常进行")
    void 创建降级() {
        SessionService service = new FaultTolerantSessionService(
                FakeSessionService.down(STORAGE_DOWN));

        Session session = service.create(CreateSessionRequest.builder()
                .id("claw4j:user:ethan")
                .userId(USER)
                .build());

        assertThat(session).isNotNull();
        assertThat(session.id()).isEqualTo("claw4j:user:ethan");
        assertThat(session.userId()).isEqualTo(USER);
    }

    /**
     * 写不进去只意味着这一轮记不住，<b>不影响这一轮已经算出来的答案</b>——
     * 所以这些调用一个都不能抛。{@code appendEvent(null)} 也照样要安全：
     * 这一层的职责是"绝不把存储问题变成整链失败"，为了拼日志先 NPE 一次就自己破了规矩。
     */
    @Test
    @DisplayName("写不进去时静默丢弃：不影响这一轮已经算出来的答案")
    void 写入降级() {
        SessionService service = new FaultTolerantSessionService(
                FakeSessionService.down(STORAGE_DOWN));

        service.appendEvent(null);
        service.delete("claw4j:user:ethan");

        assertThat(service.deleteExpiredSessions(Instant.now())).isZero();
    }

    /**
     * 降级认的是"存储不可用"这一类，不是一个父类下的两种：
     * {@link org.springframework.dao.DataAccessException}（JDBC 那一支）与
     * {@link org.springframework.transaction.TransactionException}（事务开不了）。
     * 后者不继承前者，只认前者的话"连不上库"这一种恰恰漏在外面。
     *
     * <p>别的错误照常抛出去：一个把所有异常都吞掉的包装，会把真正的 bug
     * 变成"好像只是没记住"，而这两种情况的处理方式完全相反。
     */
    @Test
    @DisplayName("只降级存储故障（含事务开不了），代码写错照常抛")
    void 只降级存储故障() {
        SessionService transactionDown = new FaultTolerantSessionService(
                FakeSessionService.down(new CannotCreateTransactionException("事务开不了")));
        assertThat(transactionDown.findById("claw4j:user:ethan")).isNull();

        SessionService buggy = new FaultTolerantSessionService(
                FakeSessionService.down(new IllegalStateException("代码写错了")));
        assertThatThrownBy(() -> buggy.findById("claw4j:user:ethan"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> buggy.appendEvent(null))
                .isInstanceOf(IllegalStateException.class);
    }

    /** 存储正常时原样透传：降级不是常驻行为，别把正常的会话也降级掉。 */
    @Test
    @DisplayName("存储正常时原样透传")
    void 正常时透传() {
        Session persisted = Session.builder()
                .id("claw4j:user:ethan").userId(USER).createdAt(Instant.now()).build();
        SessionService service = new FaultTolerantSessionService(FakeSessionService.up(persisted));

        assertThat(service.findById("claw4j:user:ethan")).isSameAs(persisted);
        assertThat(service.findByUserId(USER)).containsExactly(persisted);
    }

    // ---------------------------------------------------------------- 测试替身

    /** 存储故障：连不上、表没了、连接池耗尽，落到调用方都是这一种。 */
    private static final DataAccessResourceFailureException STORAGE_DOWN =
            new DataAccessResourceFailureException("会话库连不上");

    /**
     * 一种行为两个开关：{@code failure} 非空时每个方法都抛它，为空时正常返回那个会话。
     * 这个接口有九个方法，写三个替身类只会让文件变成替身的展览。
     */
    private static final class FakeSessionService implements SessionService {

        private final RuntimeException failure;

        private final Session session;

        private FakeSessionService(RuntimeException failure, Session session) {
            this.failure = failure;
            this.session = session;
        }

        static FakeSessionService down(RuntimeException failure) {
            return new FakeSessionService(failure, null);
        }

        static FakeSessionService up(Session session) {
            return new FakeSessionService(null, session);
        }

        private void failIfDown() {
            if (failure != null) {
                throw failure;
            }
        }

        @Override
        public Session create(CreateSessionRequest request) {
            failIfDown();
            return session;
        }

        @Override
        public Session findById(String id) {
            failIfDown();
            return session;
        }

        @Override
        public List<Session> findByUserId(String userId) {
            failIfDown();
            return session == null ? List.of() : List.of(session);
        }

        @Override
        public void delete(String id) {
            failIfDown();
        }

        @Override
        public int deleteExpiredSessions(Instant now) {
            failIfDown();
            return 0;
        }

        @Override
        public void appendEvent(SessionEvent event) {
            failIfDown();
        }

        @Override
        public List<SessionEvent> getEvents(String sessionId, EventFilter filter) {
            failIfDown();
            return List.of();
        }

        @Override
        public List<SessionEvent> findEventsByUserId(String userId, EventFilter filter) {
            failIfDown();
            return List.of();
        }

        @Override
        public CompactionResult compact(String sessionId, CompactionTrigger trigger,
                                        CompactionStrategy strategy) {
            failIfDown();
            return null;
        }
    }
}

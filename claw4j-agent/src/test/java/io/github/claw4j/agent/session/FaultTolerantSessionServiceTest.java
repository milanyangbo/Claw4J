package io.github.claw4j.agent.session;

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

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 会话存储不可用时的降级（{@link FaultTolerantSessionService}）。
 *
 * <p>降级这件事必须被钉住，否则它就是"悄悄失忆"——而悄悄失忆和"记忆真的在工作"
 * 在用户看来完全一样。这里用替身直接抛存储异常，不依赖真的数据库。
 */
class FaultTolerantSessionServiceTest {

    /** 存储故障：连不上、表没了、连接池耗尽，落到调用方都是这一种。 */
    static final class StorageDown implements SessionService {

        static final DataAccessResourceFailureException DOWN =
                new DataAccessResourceFailureException("会话库连不上");

        @Override
        public Session create(CreateSessionRequest request) {
            throw DOWN;
        }

        @Override
        public Session findById(String id) {
            throw DOWN;
        }

        @Override
        public List<Session> findByUserId(String userId) {
            throw DOWN;
        }

        @Override
        public void delete(String id) {
            throw DOWN;
        }

        @Override
        public int deleteExpiredSessions(Instant now) {
            throw DOWN;
        }

        @Override
        public void appendEvent(SessionEvent event) {
            throw DOWN;
        }

        @Override
        public List<SessionEvent> getEvents(String sessionId, EventFilter filter) {
            throw DOWN;
        }

        @Override
        public List<SessionEvent> findEventsByUserId(String userId, EventFilter filter) {
            throw DOWN;
        }

        @Override
        public CompactionResult compact(String sessionId, CompactionTrigger trigger,
                                        CompactionStrategy strategy) {
            throw DOWN;
        }
    }

    /** 正常存储：只回答"有没有这个会话"，其余一概用不上。 */
    static final class Stub implements SessionService {

        private final Session persisted;

        Stub(Session persisted) {
            this.persisted = persisted;
        }

        @Override
        public Session create(CreateSessionRequest request) {
            return persisted;
        }

        @Override
        public Session findById(String id) {
            return persisted;
        }

        @Override
        public List<Session> findByUserId(String userId) {
            return List.of(persisted);
        }

        @Override
        public void delete(String id) {
        }

        @Override
        public int deleteExpiredSessions(Instant now) {
            return 0;
        }

        @Override
        public void appendEvent(SessionEvent event) {
        }

        @Override
        public List<SessionEvent> getEvents(String sessionId, EventFilter filter) {
            return List.of();
        }

        @Override
        public List<SessionEvent> findEventsByUserId(String userId, EventFilter filter) {
            return List.of();
        }

        @Override
        public CompactionResult compact(String sessionId, CompactionTrigger trigger,
                                        CompactionStrategy strategy) {
            return null;
        }
    }

    @Test
    @DisplayName("读不到会话时当作没有，不抛")
    void 读取降级() {
        SessionService service = new FaultTolerantSessionService(new StorageDown());

        assertThat(service.findById("claw4j:user:ethan")).isNull();
        assertThat(service.getEvents("claw4j:user:ethan")).isEmpty();
        assertThat(service.findByUserId("ethan")).isEmpty();
    }

    /**
     * 创建必须返回一个 Session 而不是 null：{@code SessionMemoryAdvisor} 拿到 null
     * 会抛"会话不存在"，于是"库挂了"变成"这条消息 500"，连鉴权路径都一起答不了话。
     */
    @Test
    @DisplayName("创建不出会话时返回一个没落库的会话，好让这一轮照常进行")
    void 创建降级() {
        SessionService service = new FaultTolerantSessionService(new StorageDown());

        Session session = service.create(CreateSessionRequest.builder()
                .id("claw4j:user:ethan")
                .userId("ethan")
                .build());

        assertThat(session).isNotNull();
        assertThat(session.id()).isEqualTo("claw4j:user:ethan");
        assertThat(session.userId()).isEqualTo("ethan");
    }

    @Test
    @DisplayName("写不进去时这一轮记不住，但不影响这一轮已经算出来的答案")
    void 写入降级() {
        SessionService service = new FaultTolerantSessionService(new StorageDown());

        service.appendEvent(null);
        service.delete("claw4j:user:ethan");

        assertThat(service.deleteExpiredSessions(Instant.now())).isZero();
    }

    /**
     * 降级只认存储异常。别的错误（参数非法、代码写错）照常抛出去——
     * 一个把所有异常都吞掉的包装，会把真正的 bug 变成"好像只是没记住"。
     */
    @Test
    @DisplayName("只吞存储故障，不吞别的错误")
    void 只吞存储故障() {
        SessionService service = new FaultTolerantSessionService(new SessionService() {
            @Override
            public Session create(CreateSessionRequest request) {
                throw new IllegalArgumentException("参数不对");
            }

            @Override
            public Session findById(String id) {
                throw new IllegalStateException("代码写错了");
            }

            @Override
            public List<Session> findByUserId(String userId) {
                return List.of();
            }

            @Override
            public void delete(String id) {
            }

            @Override
            public int deleteExpiredSessions(Instant now) {
                return 0;
            }

            @Override
            public void appendEvent(SessionEvent event) {
            }

            @Override
            public List<SessionEvent> getEvents(String sessionId, EventFilter filter) {
                return List.of();
            }

            @Override
            public List<SessionEvent> findEventsByUserId(String userId, EventFilter filter) {
                return List.of();
            }

            @Override
            public CompactionResult compact(String sessionId, CompactionTrigger trigger,
                                            CompactionStrategy strategy) {
                return null;
            }
        });

        assertThatThrownBy(() -> service.findById("x")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.create(CreateSessionRequest.builder().id("x").build()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 存储正常时原样透传：降级不是常驻行为，别把正常的会话也降级掉。 */
    @Test
    @DisplayName("存储正常时原样透传")
    void 正常时透传() {
        Session persisted = Session.builder()
                .id("claw4j:user:ethan").userId("ethan").createdAt(Instant.now()).build();
        SessionService service = new FaultTolerantSessionService(new Stub(persisted));

        assertThat(service.findById("claw4j:user:ethan")).isSameAs(persisted);
        assertThat(service.findByUserId("ethan")).containsExactly(persisted);
    }
}

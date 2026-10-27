package io.github.claw4j.agent;

import io.github.claw4j.agent.session.FaultTolerantSessionService;
import io.github.claw4j.agent.session.SessionCleanup;
import io.github.claw4j.agent.session.SessionCompactor;
import io.github.claw4j.agent.session.SessionKeys;
import io.github.claw4j.agent.session.SessionSchema;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.principal.Principal;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import org.springframework.ai.session.compaction.CompactionRequest;
import org.springframework.ai.session.compaction.CompactionResult;
import org.springframework.ai.session.compaction.CompactionStrategy;
import org.springframework.ai.session.compaction.CompactionTrigger;
import org.springframework.ai.session.compaction.RecursiveSummarizationCompactionStrategy;
import org.springframework.ai.session.compaction.TokenCountTrigger;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 会话记忆：第二轮请求里到底有没有上一轮，以及存储挂掉时是降级还是把整条链拖垮。
 *
 * <p>会话 id 的派生、schema 的落点这类"错了不报错、只是记到别处/别人头上"的地方
 * 单独钉住；记忆有没有真的进提示词则从 AgentPort 走两轮。
 */
class SessionMemoryTest {

    @Test
    @DisplayName("存储连不上时读取返回「没有」，而不是把异常抛给对话链")
    void readFailureDegradesToNoMemory() {
        SessionService delegate = mock(SessionService.class);
        when(delegate.findById("s-1")).thenThrow(new DataAccessResourceFailureException("连不上库"));
        when(delegate.getEvents("s-1", EventFilter.active())).thenThrow(new DataAccessResourceFailureException("连不上库"));
        when(delegate.findByUserId("u-1")).thenThrow(new DataAccessResourceFailureException("连不上库"));
        FaultTolerantSessionService service = new FaultTolerantSessionService(delegate);

        assertThat(service.findById("s-1")).isNull();
        assertThat(service.getEvents("s-1", EventFilter.active())).isEmpty();
        assertThat(service.findEventsByUserId("u-1", EventFilter.active())).isEmpty();
        assertThat(service.findByUserId("u-1")).isEmpty();
    }

    @Test
    @DisplayName("事务开不起来也算存储不可用：它不继承 DataAccessException，漏认就整链 500")
    void transactionFailureIsAlsoDegraded() {
        SessionService delegate = mock(SessionService.class);
        when(delegate.getEvents("s-1", EventFilter.active()))
                .thenThrow(new CannotCreateTransactionException("事务管理器连不上库"));
        FaultTolerantSessionService service = new FaultTolerantSessionService(delegate);

        assertThat(service.getEvents("s-1", EventFilter.active())).isEmpty();
    }

    @Test
    @DisplayName("创建不出会话时返回一个能继续走的空会话，不是 null")
    void createFailureReturnsUsableSession() {
        SessionService delegate = mock(SessionService.class);
        when(delegate.create(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new DataAccessResourceFailureException("连不上库"));
        FaultTolerantSessionService service = new FaultTolerantSessionService(delegate);

        Session session = service.create(CreateSessionRequest.builder().id("s-1").userId("u-1").build());

        assertThat(session).isNotNull();
        assertThat(session.id()).isEqualTo("s-1");
        assertThat(session.userId()).isEqualTo("u-1");
    }

    @Test
    @DisplayName("写不进事件只丢这一轮的记忆，不把已经算出来的回答搞崩")
    void appendFailureIsSwallowed() {
        SessionService delegate = mock(SessionService.class);
        org.mockito.Mockito.doThrow(new DataAccessResourceFailureException("连不上库"))
                .when(delegate).appendEvent(org.mockito.ArgumentMatchers.any());
        FaultTolerantSessionService service = new FaultTolerantSessionService(delegate);

        SessionEvent event = SessionEvent.builder()
                .id("e-1")
                .sessionId("s-1")
                .timestamp(Instant.now())
                .message(new org.springframework.ai.chat.messages.UserMessage("在吗"))
                .build();

        assertThatCode(() -> service.appendEvent(event)).doesNotThrowAnyException();
        assertThatCode(() -> service.delete("s-1")).doesNotThrowAnyException();
        assertThat(service.deleteExpiredSessions(Instant.now())).isZero();
    }

    @Test
    @DisplayName("不是存储问题的异常照样抛：别把编程错误伪装成「这一轮没记忆」")
    void nonStorageFailurePropagates() {
        SessionService delegate = mock(SessionService.class);
        when(delegate.findById("s-1")).thenThrow(new IllegalStateException("代码写错了"));
        FaultTolerantSessionService service = new FaultTolerantSessionService(delegate);

        assertThatThrownBy(() -> service.findById("s-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("代码写错了");
    }

    @Test
    @DisplayName("schema 拼进 URL 时按已有查询参数选 ? 还是 &")
    void schemaIsAppendedToTheJdbcUrl() {
        assertThat(SessionSchema.urlWithSchema("jdbc:postgresql://db:5432/claw4j", "claw4j_session"))
                .isEqualTo("jdbc:postgresql://db:5432/claw4j?currentSchema=claw4j_session");
        assertThat(SessionSchema.urlWithSchema("jdbc:postgresql://db:5432/claw4j?sslmode=require", "claw4j_session"))
                .isEqualTo("jdbc:postgresql://db:5432/claw4j?sslmode=require&currentSchema=claw4j_session");
    }

    @Test
    @DisplayName("schema 为空或带空白直接拒绝：空参数会让会话表悄悄落到 public")
    void blankOrUnsafeSchemaIsRejected() {
        assertThatThrownBy(() -> SessionSchema.urlWithSchema("jdbc:postgresql://db/claw4j", "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空");
        assertThatThrownBy(() -> SessionSchema.urlWithSchema("jdbc:postgresql://db/claw4j", "claw4j session"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("空白");
        assertThatThrownBy(() -> SessionSchema.urlWithSchema("jdbc:postgresql://db/claw4j", "x?y"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("第二轮请求真的带上了第一轮的问题与回答")
    void secondTurnCarriesTheFirstTurnToTheModel() {
        MapSessionService sessions = new MapSessionService();
        FixedChatModel model = new FixedChatModel("华东上周成交 128400");
        ChatClientAgentPort port = port(model, sessions);

        port.reply(message("chat-a", "上周华东成交额"));
        port.reply(message("chat-b", "那华南呢"));

        List<Message> secondTurn = model.prompts().getLast().getInstructions();
        String sent = secondTurn.stream().map(Message::getText).collect(Collectors.joining("\n"));
        assertThat(sent).contains("上周华东成交额").contains("华东上周成交 128400");
        assertThat(secondTurn).hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("会话属于人而不是通道：换 chatId 还是同一段记忆")
    void memoryFollowsThePrincipalNotTheChatId() {
        MapSessionService sessions = new MapSessionService();
        FixedChatModel model = new FixedChatModel("记住了");
        ChatClientAgentPort port = port(model, sessions);
        Principal principal = Principal.readOnly("u-1");

        port.reply(new InboundMessage(principal, "web-chat", "我叫 Ethan"));
        port.reply(new InboundMessage(principal, "feishu-chat", "我叫什么"));

        String secondTurn = model.prompts().getLast().getInstructions().stream()
                .map(Message::getText)
                .collect(Collectors.joining("\n"));
        assertThat(sessions.createdIds())
                .as("同一段对话从网页切到飞书，只应有一个会话")
                .hasSize(1);
        assertThat(secondTurn).contains("我叫 Ethan");
    }

    @Test
    @DisplayName("两个用户不会共用会话：userId 变了就是另一个人")
    void differentUsersGetDifferentSessions() {
        MapSessionService sessions = new MapSessionService();
        ChatClientAgentPort port = port(new FixedChatModel("好"), sessions);

        port.reply(new InboundMessage(Principal.readOnly("u-1"), "chat", "甲说的话"));
        port.reply(new InboundMessage(Principal.readOnly("u-2"), "chat", "乙说的话"));

        assertThat(sessions.createdIds()).hasSize(2).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("userId 为空的身份不许开会话：否则所有人共用一个，还记得是别人的")
    void blankUserIdIsRejected() {
        assertThatThrownBy(() -> SessionKeys.sessionIdOf(Principal.readOnly(" ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId");
    }

    @Test
    @DisplayName("聊得久了历史被压成摘要：事件不再线性增长，省下的 token 记在指标里")
    void longConversationGetsCompacted() {
        MapSessionService sessions = new MapSessionService();
        String sessionId = SessionKeys.sessionIdOf(Principal.readOnly("u-1"));
        sessions.create(CreateSessionRequest.builder().id(sessionId).userId("u-1").build());
        for (int i = 0; i < 200; i++) {
            sessions.appendEvent(event(sessionId, i));
        }
        int before = sessions.getEvents(sessionId, EventFilter.active()).size();

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        compactor(sessions, registry).compactIfNeeded(sessionId);

        assertThat(sessions.getEvents(sessionId, EventFilter.active()))
                .as("压完事件条数必须下降，否则等于什么都没做")
                .hasSizeLessThan(before);
        assertThat(registry.get("claw4j.session.compaction.archived").counter().count())
                .as("压掉几条要能看见")
                .isGreaterThan(0);
    }

    @Test
    @DisplayName("会话还没建出来时不记失败：第一轮没有历史可压，那不是故障")
    void missingSessionIsNotAFailure() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        compactor(new MapSessionService(), registry).compactIfNeeded("http:u-never-asked");

        assertThat(registry.find("claw4j.session.compaction.failures").counter())
                .as("把时序问题记成故障，这个数从第一天起就不是 0，从此没人看它")
                .isNull();
    }

    @Test
    @DisplayName("摘要模型挂了只留痕：压缩失败不能把这一轮回答拖垮")
    void compactionFailureDoesNotBreakTheTurn() {
        SessionService broken = mock(SessionService.class);
        when(broken.compact(anyString(), any(CompactionTrigger.class), any(CompactionStrategy.class)))
                .thenThrow(new IllegalStateException("摘要模型 503"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        assertThatCode(() -> compactor(broken, registry).compactIfNeeded("s-1"))
                .as("压不成是成本问题，不是可用性问题")
                .doesNotThrowAnyException();
        assertThat(registry.get("claw4j.session.compaction.failures").counter().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("过期会话真的被删掉、没过期的还在：TTL 只是一个时间戳，删要靠清理任务")
    void expiredSessionsAreDeleted() {
        MapSessionService sessions = new MapSessionService();
        sessions.create(CreateSessionRequest.builder()
                .id("old").userId("u-1").timeToLive(Duration.ofSeconds(-1)).build());
        sessions.create(CreateSessionRequest.builder()
                .id("fresh").userId("u-1").timeToLive(Duration.ofDays(1)).build());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new SessionCleanup(sessions, registry).deleteExpired();

        assertThat(sessions.findById("old")).as("过期的那条该没了").isNull();
        assertThat(sessions.findById("fresh")).as("没过期的一条都不该动").isNotNull();
        assertThat(registry.get("claw4j.session.expired").counter().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("没设 TTL 的会话永不过期：这是框架默认值，也正是必须显式配 30 天的理由")
    void sessionWithoutTtlNeverExpires() {
        MapSessionService sessions = new MapSessionService();
        sessions.create(CreateSessionRequest.builder().id("forever").userId("u-1").build());

        new SessionCleanup(sessions, new SimpleMeterRegistry()).deleteExpired();

        assertThat(sessions.findById("forever")).isNotNull();
    }

    private static SessionCompactor compactor(SessionService sessions, MeterRegistry registry) {
        return new SessionCompactor(sessions,
                TokenCountTrigger.builder().threshold(100).build(),
                RecursiveSummarizationCompactionStrategy.builder(
                                ChatClient.builder(new FixedChatModel("摘要：用户连问了多轮成交额")).build())
                        .maxEventsToKeep(6)
                        .build(),
                registry);
    }

    private static SessionEvent event(String sessionId, int index) {
        return SessionEvent.builder()
                .id("e-" + index)
                .sessionId(sessionId)
                .timestamp(Instant.now().plusSeconds(index))
                .message(new UserMessage("第 " + index + " 轮：" + "成交额是多少。".repeat(40)))
                .build();
    }

    private static ChatClientAgentPort port(ChatModel model, SessionService sessions) {
        ChatClient chat = ChatClient.builder(model, ObservationRegistry.NOOP, null, null,
                        ToolCallingAdvisor.builder()
                                .toolCallingManager(ToolCallBudget.toolCallingManager(ObservationRegistry.NOOP)))
                .defaultAdvisors(SessionMemoryAdvisor.builder(sessions)
                        .order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
                        .build())
                .build();
        return new ChatClientAgentPort(chat);
    }

    private static InboundMessage message(String chatId, String text) {
        return new InboundMessage(Principal.readOnly("u-1"), chatId, text);
    }

    /** 只活在内存里的会话存储：验的是记忆有没有进提示词，不是 Postgres 的 SQL。 */
    private static final class MapSessionService implements SessionService {

        private final Map<String, Session> sessions = new LinkedHashMap<>();
        private final Map<String, List<SessionEvent>> events = new LinkedHashMap<>();
        private final List<String> createdIds = new CopyOnWriteArrayList<>();

        @Override
        public Session create(CreateSessionRequest request) {
            Instant now = Instant.now();
            Session session = Session.builder()
                    .id(request.id())
                    .userId(request.userId())
                    .createdAt(now)
                    // 与 JdbcSessionRepository 同一个口径：TTL 为空就是永不过期。
                    .expiresAt(request.timeToLive() == null ? null : now.plus(request.timeToLive()))
                    .build();
            sessions.put(session.id(), session);
            createdIds.add(session.id());
            return session;
        }

        @Override
        public Session findById(String id) {
            return sessions.get(id);
        }

        @Override
        public List<Session> findByUserId(String userId) {
            return sessions.values().stream().filter(s -> s.userId().equals(userId)).toList();
        }

        @Override
        public void delete(String id) {
            sessions.remove(id);
            events.remove(id);
        }

        @Override
        public int deleteExpiredSessions(Instant now) {
            List<String> expired = sessions.values().stream()
                    .filter(s -> s.expiresAt() != null && !s.expiresAt().isAfter(now))
                    .map(Session::id)
                    .toList();
            expired.forEach(this::delete);
            return expired.size();
        }

        @Override
        public void appendEvent(SessionEvent event) {
            events.computeIfAbsent(event.getSessionId(), key -> new ArrayList<>()).add(event);
        }

        @Override
        public List<SessionEvent> getEvents(String sessionId, EventFilter filter) {
            return List.copyOf(events.getOrDefault(sessionId, List.of()));
        }

        @Override
        public List<SessionEvent> findEventsByUserId(String userId, EventFilter filter) {
            return events.values().stream().flatMap(List::stream).toList();
        }

        /**
         * 与 {@code DefaultSessionService} 同形状：会话不存在就抛、先问 trigger 再压、
         * 压完用新的事件集替换旧的。照抄这一段的理由是——压缩测试要验的正是"这套流程跑完
         * 事件真的变少了"，如果这里返回 null 或者不替换，测的就是一个空壳。
         */
        @Override
        public CompactionResult compact(String sessionId, CompactionTrigger trigger, CompactionStrategy strategy) {
            Session session = sessions.get(sessionId);
            if (session == null) {
                throw new IllegalArgumentException("会话不存在：" + sessionId);
            }
            List<SessionEvent> current = events.getOrDefault(sessionId, List.of());
            CompactionRequest request = CompactionRequest.of(session, current);
            if (!trigger.shouldCompact(request)) {
                return null;
            }
            CompactionResult result = strategy.compact(request);
            events.put(sessionId, new ArrayList<>(result.compactedEvents()));
            return result;
        }

        List<String> createdIds() {
            return List.copyOf(createdIds);
        }
    }
}

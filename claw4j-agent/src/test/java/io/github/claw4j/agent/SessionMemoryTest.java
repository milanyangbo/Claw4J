package io.github.claw4j.agent;

import io.github.claw4j.agent.session.FaultTolerantSessionService;
import io.github.claw4j.agent.session.SessionMemoryConfiguration;
import io.github.claw4j.agent.session.SessionMemoryProperties;
import io.github.claw4j.agent.session.SessionSchema;
import io.github.claw4j.domain.port.AgentPort;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.port.OutboundMessage;
import io.github.claw4j.domain.principal.Principal;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springaicommunity.session.autoconfigure.SessionServiceProperties;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionRepository;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import org.springframework.ai.session.compaction.CompactionPlan;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 一个完整功能：会话记忆。第一轮说的话，第二轮还能不能用上；存储挂了会不会把整条链拖垮。
 *
 * <p>这里没有"很明显"的失败：记忆没带上时回答仍然通顺，只是接不上上一轮；
 * 会话 id 算错时也不会报错，只是所有人共用一份记忆——看起来记得住，记得是别人的。
 */
class SessionMemoryTest {

    @Test
    @DisplayName("第二轮请求真的带上了第一轮的问与答，而且换一条通道仍接得上")
    void secondTurnCarriesFirstTurnAcrossChannels() {
        Fixture fixture = fixture(InMemorySessionRepository.builder().build());
        fixture.reply("feishu:chat-a", "我叫张三");
        fixture.reply("http:chat-b", "我刚才说了什么");

        assertThat(fixture.model.prompts()).hasSize(2);
        assertThat(fixture.model.prompts().get(1).getContents())
                .as("换 chatId 不该换会话：会话属于人，不属于通道")
                .contains("我叫张三")
                .contains("你好，张三");
    }

    @Test
    @DisplayName("记忆按人隔离：换一个用户问同一句话，看不到上一个人的内容")
    void memoryDoesNotLeakAcrossUsers() {
        Fixture fixture = fixture(InMemorySessionRepository.builder().build());
        fixture.reply(Principal.readOnly("alice"), "feishu:same-chat", "我叫张三");
        fixture.reply(Principal.readOnly("bob"), "feishu:same-chat", "刚才聊了什么");

        assertThat(fixture.model.prompts()).hasSize(2);
        assertThat(fixture.model.prompts().get(1).getContents())
                .as("用户 id 进了会话主键；派生错就会串号")
                .doesNotContain("我叫张三")
                .doesNotContain("你好，张三");
    }

    @Test
    @DisplayName("存储整个不可用时第二轮照常回答，只是接不上上一轮")
    void answersWithoutHistoryWhenStorageIsDown() {
        Fixture fixture = fixture(new BrokenRepository());
        fixture.reply("feishu:chat-c", "我叫张三");

        OutboundMessage second = fixture.reply("feishu:chat-c", "我刚才说了什么");

        assertThat(second.text()).isEqualTo("第二轮的回答");
        assertThat(fixture.model.prompts().get(1).getContents()).doesNotContain("我叫张三");
    }

    @Test
    @DisplayName("写会话事件失败只丢这一轮的记忆，这一轮的答案照常交回")
    void appendFailureDoesNotBreakTheCurrentAnswer() {
        Fixture fixture = fixture(new AppendFailingRepository());

        OutboundMessage first = fixture.reply("feishu:chat-d", "我叫张三");
        OutboundMessage second = fixture.reply("feishu:chat-d", "我刚才说了什么");

        assertThat(first.text()).isEqualTo("你好，张三");
        assertThat(second.text()).isEqualTo("第二轮的回答");
    }

    @Test
    @DisplayName("创建会话失败时给一个没落库的 Session，链路继续；失败只尝试一次，不重试")
    void createDegradesToAnUnsavedSessionWithoutRetrying() {
        BrokenSessionService broken = new BrokenSessionService();
        FaultTolerantSessionService service = new FaultTolerantSessionService(broken);

        Session created = service.create(CreateSessionRequest.builder()
                .id("claw4j:user:u-1")
                .userId("u-1")
                .build());

        assertThat(created).isNotNull();
        assertThat(created.id()).isEqualTo("claw4j:user:u-1");
        assertThat(created.userId()).isEqualTo("u-1");
        assertThat(broken.creates.get()).as("存储不可用时是降级，不是重试").isEqualTo(1);
    }

    @Test
    @DisplayName("每一种存储故障都降级成“没有”，包括事务开不了与 null 事件")
    void everyStorageFailureDegradesInsteadOfThrowing() {
        FaultTolerantSessionService dataAccess = new FaultTolerantSessionService(new BrokenSessionService());
        FaultTolerantSessionService transaction = new FaultTolerantSessionService(new TransactionBrokenService());

        assertThat(dataAccess.findById("s")).isNull();
        assertThat(dataAccess.findByUserId("u")).isEmpty();
        assertThat(dataAccess.getEvents("s", EventFilter.all())).isEmpty();
        assertThat(dataAccess.findEventsByUserId("u", EventFilter.all())).isEmpty();
        assertThat(dataAccess.deleteExpiredSessions(Instant.now())).isZero();
        assertThat(dataAccess.compact("s", null, null)).isNull();
        assertThatCode(() -> dataAccess.delete("s")).doesNotThrowAnyException();
        assertThatCode(() -> dataAccess.appendEvent(null)).doesNotThrowAnyException();

        assertThat(transaction.findById("s"))
                .as("事务开不了抛的是 TransactionException，不是 DataAccessException")
                .isNull();
    }

    @Test
    @DisplayName("会话 schema 拼进 JDBC URL：已有参数时用 & 接上，不是覆盖")
    void appendsSchemaToJdbcUrl() {
        String plain = SessionSchema.urlWithSchema("jdbc:postgresql://db:5432/claw4j", "claw4j_session");
        String withQuery = SessionSchema.urlWithSchema(
                "jdbc:postgresql://db:5432/claw4j?sslmode=disable", "claw4j_session");

        assertThat(plain).isEqualTo("jdbc:postgresql://db:5432/claw4j?currentSchema=claw4j_session");
        assertThat(withQuery).isEqualTo(
                "jdbc:postgresql://db:5432/claw4j?sslmode=disable&currentSchema=claw4j_session");
    }

    @Test
    @DisplayName("空 schema 或带空白/问号的 schema 直接拒绝：空参数会让会话表悄悄落到 public")
    void rejectsSchemaThatWouldSilentlyLandInPublic() {
        assertThatThrownBy(() -> SessionSchema.urlWithSchema("jdbc:postgresql://db/claw4j", "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("public");
        assertThatThrownBy(() -> SessionSchema.urlWithSchema("jdbc:postgresql://db/claw4j", "claw4j session"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("含空白或");
        assertThatThrownBy(() -> SessionSchema.urlWithSchema("jdbc:postgresql://db/claw4j", "claw4j?x=1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("含空白或");
    }

    @Test
    @DisplayName("空 schema 配置落回默认 schema，非正连接池落回默认大小")
    void blankSchemaFallsBackToTheSessionSchemaNotPublic() {
        SessionMemoryProperties blank = new SessionMemoryProperties("   ", 0);
        SessionMemoryProperties given = new SessionMemoryProperties(" claw4j_session_other ", 5);

        assertThat(blank.schema()).isEqualTo(SessionSchema.DEFAULT_SCHEMA);
        assertThat(blank.poolSize()).isEqualTo(SessionMemoryProperties.DEFAULT_POOL_SIZE);
        assertThat(given.schema()).isEqualTo("claw4j_session_other");
        assertThat(given.poolSize()).isEqualTo(5);
    }

    private static Fixture fixture(SessionRepository repository) {
        SessionMemoryConfiguration configuration = new SessionMemoryConfiguration();
        SessionService sessions = configuration.sessionService(repository, sessionProperties());
        SessionMemoryAdvisor memory = configuration.sessionMemoryAdvisor(sessions);
        FixedChatModel model = new FixedChatModel();
        return new Fixture(model, port(model, memory));
    }

    private static SessionServiceProperties sessionProperties() {
        SessionServiceProperties properties = new SessionServiceProperties();
        properties.setTimeToLive(Duration.ofHours(1));
        properties.setAllowSystemMessages(true);
        return properties;
    }

    private static AgentPort port(ChatModel model, Advisor... advisors) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        for (int i = 0; i < advisors.length; i++) {
            beans.registerSingleton("advisor-" + i, advisors[i]);
        }
        AgentAutoConfiguration configuration = new AgentAutoConfiguration();
        ChatClient client = configuration.chatClient(model,
                beans.getBeanProvider(Advisor.class),
                beans.getBeanProvider(ToolCallback.class),
                beans.getBeanProvider(ToolCallbackProvider.class),
                beans.getBeanProvider(ObservationRegistry.class));
        return configuration.agentPort(client);
    }

    private record Fixture(FixedChatModel model, AgentPort port) {

        OutboundMessage reply(String chatId, String text) {
            return reply(Principal.readOnly("u-1"), chatId, text);
        }

        OutboundMessage reply(Principal principal, String chatId, String text) {
            return port.reply(new InboundMessage(principal, chatId, text));
        }
    }

    /** 第一轮答“你好，张三”，第二轮答固定文本；记录每次真正发给模型的 prompt。 */
    private static final class FixedChatModel implements ChatModel {

        private final ChatOptions options = ToolCallingChatOptions.builder().build();
        private final List<Prompt> prompts = new CopyOnWriteArrayList<>();
        private final AtomicInteger rounds = new AtomicInteger();

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            String answer = rounds.getAndIncrement() == 0 ? "你好，张三" : "第二轮的回答";
            return ChatResponse.builder()
                    .generations(List.of(new Generation(new AssistantMessage(answer))))
                    .build();
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            prompts.add(prompt);
            return Flux.just(call(prompt));
        }

        @Override
        public ChatOptions getOptions() {
            return options;
        }

        List<Prompt> prompts() {
            return prompts;
        }
    }

    /** 存储整个不可用：每一层调用都抛 DataAccessException。 */
    private static class BrokenRepository implements SessionRepository {

        protected final DataAccessResourceFailureException down() {
            return new DataAccessResourceFailureException("数据库连不上");
        }

        @Override
        public Session save(Session session) {
            throw down();
        }

        @Override
        public boolean saveIfAbsent(Session session) {
            throw down();
        }

        @Override
        public Session findById(String id) {
            throw down();
        }

        @Override
        public List<Session> findByUserId(String userId) {
            throw down();
        }

        @Override
        public void delete(String id) {
            throw down();
        }

        @Override
        public int deleteExpiredSessions(Instant now) {
            throw down();
        }

        @Override
        public void appendEvent(SessionEvent event) {
            throw down();
        }

        @Override
        public boolean applyCompaction(String sessionId, CompactionPlan plan, long expectedVersion) {
            throw down();
        }

        @Override
        public long getEventVersion(String sessionId) {
            throw down();
        }

        @Override
        public List<SessionEvent> findEvents(String sessionId, EventFilter filter) {
            throw down();
        }
    }

    /** 能读能建会话，唯独写事件失败：这一轮的答案不该跟着一起失败。 */
    private static final class AppendFailingRepository extends BrokenRepository {

        private final InMemorySessionRepository delegate = InMemorySessionRepository.builder().build();

        @Override
        public Session save(Session session) {
            return delegate.save(session);
        }

        @Override
        public boolean saveIfAbsent(Session session) {
            return delegate.saveIfAbsent(session);
        }

        @Override
        public Session findById(String id) {
            return delegate.findById(id);
        }

        @Override
        public List<Session> findByUserId(String userId) {
            return delegate.findByUserId(userId);
        }

        @Override
        public List<SessionEvent> findEvents(String sessionId, EventFilter filter) {
            return delegate.findEvents(sessionId, filter);
        }
    }

    /** 直接替身：断言的是 FaultTolerantSessionService 的降级契约，不碰存储。 */
    private static class BrokenSessionService implements SessionService {

        private final AtomicInteger creates = new AtomicInteger();

        @Override
        public Session create(CreateSessionRequest request) {
            creates.incrementAndGet();
            throw new DataAccessResourceFailureException("数据库连不上");
        }

        @Override
        public Session findById(String id) {
            throw new DataAccessResourceFailureException("数据库连不上");
        }

        @Override
        public List<Session> findByUserId(String userId) {
            throw new DataAccessResourceFailureException("数据库连不上");
        }

        @Override
        public void delete(String id) {
            throw new DataAccessResourceFailureException("数据库连不上");
        }

        @Override
        public int deleteExpiredSessions(Instant now) {
            throw new DataAccessResourceFailureException("数据库连不上");
        }

        @Override
        public void appendEvent(SessionEvent event) {
            throw new DataAccessResourceFailureException("数据库连不上");
        }

        @Override
        public List<SessionEvent> getEvents(String sessionId, EventFilter filter) {
            throw new DataAccessResourceFailureException("数据库连不上");
        }

        @Override
        public List<SessionEvent> findEventsByUserId(String userId, EventFilter filter) {
            throw new DataAccessResourceFailureException("数据库连不上");
        }

        @Override
        public org.springframework.ai.session.compaction.CompactionResult compact(
                String sessionId,
                org.springframework.ai.session.compaction.CompactionTrigger trigger,
                org.springframework.ai.session.compaction.CompactionStrategy strategy) {
            throw new DataAccessResourceFailureException("数据库连不上");
        }
    }

    /** 事务那一支：继承的是 TransactionException，不是 DataAccessException。 */
    private static final class TransactionBrokenService extends BrokenSessionService {

        @Override
        public Session findById(String id) {
            throw new CannotCreateTransactionException("事务开不了");
        }
    }
}

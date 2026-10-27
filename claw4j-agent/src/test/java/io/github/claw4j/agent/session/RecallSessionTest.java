package io.github.claw4j.agent.session;

import io.github.claw4j.domain.ContextKeys;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.principal.ToolPermission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.compaction.CompactionResult;
import org.springframework.ai.session.compaction.CompactionStrategy;
import org.springframework.ai.session.compaction.CompactionTrigger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨会话召回：这一轮能不能看见"别处说过的话"，以及**是不是只有本人能看见**。
 *
 * <p>第二条比第一条重要。第一条失效的表现是"想不起来"，用户会再说一遍；
 * 第二条失效的表现是"一切正常"——召回成功、有结果、有内容，只是内容是别人的。
 * 所以这里用 {@link EventFilter#matches} 让假仓库按框架真实语义过滤，
 * 而不是自己写一个 contains 假装在匹配。
 */
class RecallSessionTest {

    private static final String OLD_SESSION = "claw4j:user:u-1";

    private static final String OTHER_SESSION = "claw4j:user:u-1:topic-2";

    @Test
    @DisplayName("另一个会话里说过的口径，这一轮问起来能召回到")
    void recallsWhatWasSaidInAnotherSession() {
        FakeSessions sessions = new FakeSessions();
        sessions.create(CreateSessionRequest.builder().id(OLD_SESSION).userId("u-1").build());
        sessions.create(CreateSessionRequest.builder().id(OTHER_SESSION).userId("u-1").build());
        sessions.append(OLD_SESSION, "华东区的成交口径按签约主体算，不按开票主体");
        sessions.append(OTHER_SESSION, "华南那边的口径还是按开票主体");

        String recalled = tool(sessions).call("{\"query\":\"华东,口径\"}",
                toolContext(Principal.readOnly("u-1")));

        assertThat(recalled)
                .as("跨会话召回没把另一个会话的内容带回来，等于这个工具什么都没做")
                .contains(OLD_SESSION)
                .contains("按签约主体算");
        assertThat(recalled)
                .as("默认是任意命中：只讲了「口径」的那段也会被带回来")
                .contains(OTHER_SESSION);
    }

    @Test
    @DisplayName("match_mode 真的传下去了：all 只留同时命中全部关键词的那段")
    void matchModeNarrowsTheResults() {
        FakeSessions sessions = new FakeSessions();
        sessions.create(CreateSessionRequest.builder().id(OLD_SESSION).userId("u-1").build());
        sessions.append(OLD_SESSION, "华东区的成交口径按签约主体算");
        sessions.append(OLD_SESSION, "华南那边的口径还是按开票主体");

        String onlyBoth = tool(sessions).call("{\"query\":\"华东,口径\",\"match_mode\":\"all\"}",
                toolContext(Principal.readOnly("u-1")));

        assertThat(onlyBoth).contains("按签约主体算").doesNotContain("华南");
    }

    @Test
    @DisplayName("换一个 userId 就看不到：召回按当前身份查，不是按工具实例上写死的那个人")
    void anotherUserSeesNothing() {
        FakeSessions sessions = new FakeSessions();
        sessions.create(CreateSessionRequest.builder().id(OLD_SESSION).userId("u-1").build());
        sessions.append(OLD_SESSION, "华东区的成交口径按签约主体算");

        String recalled = tool(sessions).call("{\"query\":\"华东\"}",
                toolContext(Principal.readOnly("u-2")));

        assertThat(recalled)
                .as("u-2 看到了 u-1 的历史——这是这张票唯一真正的安全边界")
                .doesNotContain("签约主体")
                .contains("没有找到");
    }

    @Test
    @DisplayName("没有身份就不查：宁可不回答，也不能退化成查某个人")
    void refusesWithoutPrincipal() {
        FakeSessions sessions = new FakeSessions();
        sessions.create(CreateSessionRequest.builder().id(OLD_SESSION).userId("u-1").build());
        sessions.append(OLD_SESSION, "华东区的成交口径按签约主体算");

        assertThat(tool(sessions).call("{\"query\":\"华东\"}", null))
                .contains("没有携带身份")
                .doesNotContain("签约主体");
        assertThat(tool(sessions).call("{\"query\":\"华东\"}"))
                .as("单参入口没有 ToolContext，同样按没身份处理")
                .contains("没有携带身份");
    }

    @Test
    @DisplayName("一条长对话不会把上下文吃满：条数与总字数都封顶")
    void truncatesWhatItBringsBack() {
        FakeSessions sessions = new FakeSessions();
        sessions.create(CreateSessionRequest.builder().id(OLD_SESSION).userId("u-1").build());
        for (int i = 0; i < 40; i++) {
            sessions.append(OLD_SESSION, "华东口径第 " + i + " 段：" + "按签约主体算。".repeat(60));
        }

        String recalled = tool(sessions).call("{\"query\":\"华东口径\"}",
                toolContext(Principal.readOnly("u-1")));

        assertThat(recalled.length())
                .as("条数封顶后仍可能是一条一万字，整段必须再按字符裁一次")
                .isLessThan(RecallSessionTool.MAX_RESULT_CHARS + 200);
        assertThat(recalled).contains("已截断");
    }

    @Test
    @DisplayName("参数写错了回一句话，不是抛出去变成整轮 500")
    void badArgumentsComeBackAsAdvice() {
        FakeSessions sessions = new FakeSessions();
        RecallSessionTool tool = tool(sessions);
        ToolContext context = toolContext(Principal.readOnly("u-1"));

        assertThat(tool.call("{\"match_mode\":\"all\"}", context))
                .as("query 必填，框架抛的是 IllegalArgumentException")
                .contains("召回参数不合法");
        assertThat(tool.call("{\"query\":\"华东\",\"since\":\"上周\"}", context))
                .contains("召回参数不合法");
        assertThat(tool.call("{\"query\":\"华东\",\"page\":\"第一页\"}", context))
                .contains("召回参数不合法");
    }

    @Test
    @DisplayName("权限是 recall_session，不带表；只读能用、guest 不能用")
    void permissionIsItsOwnAction() {
        ToolPermission permission = RecallSessionTool.permission();

        assertThat(permission.action()).isEqualTo("recall_session");
        assertThat(permission.tables()).as("它读的是会话，不是业务表").isEmpty();
        assertThat(readOnlyWithRecall("u-1").canUse(permission))
                .as("read-only 不给就等于只有管理员能回忆，这个能力等于没上")
                .isTrue();
        assertThat(Principal.readOnly("u-1").canUse(permission))
                .as("内置的只读身份只有 read，它本来就不含这个能力——放行与否由角色配置里那一行决定")
                .isFalse();
        assertThat(Principal.of("u-3", "guest", java.util.Set.of("region"),
                        java.util.Set.of(ToolPermission.READ)).canUse(permission))
                .as("guest 的 actions 里没有 recall_session：能读 region 与能回忆自己说过什么是两件事")
                .isFalse();
    }

    /** 与 application.yml 里 read-only 那一行同形状：read 之外还给了 recall_session。 */
    private static Principal readOnlyWithRecall(String userId) {
        return Principal.of(userId, "read-only",
                java.util.Set.of(Principal.WILDCARD),
                java.util.Set.of(ToolPermission.READ, RecallSessionTool.ACTION));
    }

    private static RecallSessionTool tool(SessionService sessions) {
        return new RecallSessionTool(sessions);
    }

    private static ToolContext toolContext(Principal principal) {
        return new ToolContext(Map.of(ContextKeys.PRINCIPAL, principal));
    }

    /**
     * 假会话仓库：只做两件事——按 userId 找事件、按框架的 {@link EventFilter} 过滤。
     *
     * <p>过滤交给框架自己的 {@code matches}，不自己写字符串包含：
     * 自己实现一遍的话，测的就是"我以为的关键词匹配"，而真跑起来语义可能完全不同
     * （大小写、多词组合、归档事件剔不剔），这种测试在换实现时一声不响地继续绿。
     */
    private static final class FakeSessions implements SessionService {

        private final Map<String, Session> sessions = new LinkedHashMap<>();

        private final Map<String, List<SessionEvent>> events = new LinkedHashMap<>();

        private int eventSequence;

        @Override
        public Session create(CreateSessionRequest request) {
            Session session = Session.builder()
                    .id(request.id())
                    .userId(request.userId())
                    .createdAt(Instant.now())
                    .build();
            sessions.put(session.id(), session);
            return session;
        }

        void append(String sessionId, String text) {
            events.computeIfAbsent(sessionId, key -> new ArrayList<>())
                    .add(SessionEvent.builder()
                            .id("e-" + (++eventSequence))
                            .sessionId(sessionId)
                            .timestamp(Instant.now().plusSeconds(eventSequence))
                            .message(new UserMessage(text))
                            .build());
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
            return 0;
        }

        @Override
        public void appendEvent(SessionEvent event) {
            events.computeIfAbsent(event.getSessionId(), key -> new ArrayList<>()).add(event);
        }

        @Override
        public List<SessionEvent> getEvents(String sessionId, EventFilter filter) {
            return filter.apply(events.getOrDefault(sessionId, List.of()));
        }

        @Override
        public List<SessionEvent> findEventsByUserId(String userId, EventFilter filter) {
            List<SessionEvent> mine = sessions.values().stream()
                    .filter(session -> userId.equals(session.userId()))
                    .flatMap(session -> events.getOrDefault(session.id(), List.of()).stream())
                    .toList();
            return filter.apply(mine);
        }

        /** 压缩与本票无关：测试不压，但接口要求有实现。 */
        @Override
        public CompactionResult compact(String sessionId, CompactionTrigger trigger, CompactionStrategy strategy) {
            return null;
        }
    }
}

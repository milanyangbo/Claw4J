package io.github.claw4j.agent;

import io.github.claw4j.agent.session.ConversationPointerStore;
import io.github.claw4j.agent.session.ConversationPointers;
import io.github.claw4j.agent.session.FaultTolerantSessionService;
import io.github.claw4j.agent.session.JdbcConversationPointerStore;
import io.github.claw4j.agent.session.SessionCleanup;
import io.github.claw4j.agent.session.SessionCompactor;
import io.github.claw4j.agent.session.SessionKeys;
import io.github.claw4j.agent.session.SessionMemoryProperties;
import io.github.claw4j.agent.session.SessionResetProperties;
import io.github.claw4j.agent.session.SessionSchema;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.principal.ToolPermission;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.CannotCreateTransactionException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
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

        // 同一段对话里的两轮：chatId 相同，才该带上上一句（ADR-0025）
        port.reply(message("chat-a", "上周华东成交额"));
        port.reply(message("chat-a", "那华南呢"));

        List<Message> secondTurn = model.prompts().getLast().getInstructions();
        String sent = secondTurn.stream().map(Message::getText).collect(Collectors.joining("\n"));
        assertThat(sent).contains("上周华东成交额").contains("华东上周成交 128400");
        assertThat(secondTurn).hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("会话跟着「这一段对话」走：同一段的两轮共享，换一段就断（ADR-0025）")
    void memoryFollowsTheConversationNotJustThePrincipal() {
        MapSessionService sessions = new MapSessionService();
        FixedChatModel model = new FixedChatModel("记住了");
        ChatClientAgentPort port = port(model, sessions);
        Principal principal = Principal.readOnly("u-1");

        port.reply(new InboundMessage(principal, "web-chat", "我叫 Ethan"));
        port.reply(new InboundMessage(principal, "web-chat", "我叫什么"));

        String sameConversation = model.prompts().getLast().getInstructions().stream()
                .map(Message::getText)
                .collect(Collectors.joining("\n"));
        assertThat(sessions.createdIds())
                .as("同一段对话的两轮落在同一个会话里，否则它连上一句都记不住")
                .hasSize(1);
        assertThat(sameConversation).contains("我叫 Ethan");

        port.reply(new InboundMessage(principal, "feishu-chat", "我叫什么"));

        String otherConversation = model.prompts().getLast().getInstructions().stream()
                .map(Message::getText)
                .collect(Collectors.joining("\n"));
        assertThat(sessions.createdIds())
                .as("换一段对话就是另一段记忆")
                .hasSize(2);
        assertThat(otherConversation)
                .as("两段串了的话，群里就能看见私聊里说过的内容——那是没人复核的泄露")
                .doesNotContain("我叫 Ethan");
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
    @DisplayName("会话表的 user_id 存归属键而非裸 userId：召回按它精确匹配，裸的会让降权失效")
    void sessionUserIdCarriesThePermissionFingerprint() {
        MapSessionService sessions = new MapSessionService();
        ChatClientAgentPort port = port(new FixedChatModel("好"), sessions);

        Principal who = Principal.readOnly("u-1");
        port.reply(new InboundMessage(who, "chat", "问一句"));

        assertThat(sessions.createdUserIds())
                .as("写裸 userId 的话，换角色之后 recall_session 照样能查回他高权限时的会话")
                .containsExactly(SessionKeys.ownerOf(who));
    }

    @Test
    @DisplayName("userId 为空的身份不许开会话：否则所有人共用一个，还记得是别人的")
    void blankUserIdIsRejected() {
        assertThatThrownBy(() -> SessionKeys.sessionIdOf(Principal.readOnly(" "), "web", "c-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId");
    }

    @Test
    @DisplayName("会话 id 跟着「人 + 权限 + 这一段对话 + 段」走：前四样稳定，段按时间换")
    void sessionIdFollowsIdentityAndPermissions() {
        Principal who = Principal.readOnly("u-1");

        assertThat(SessionKeys.sessionIdOf(who, "web", "c-1"))
                .as("每次都不一样的话，这个人永远记不住上一轮，而且没有任何报错")
                .isEqualTo(SessionKeys.sessionIdOf(Principal.readOnly("u-1"), "web", "c-1"))
                .startsWith(SessionKeys.PREFIX + "u-1@");

        Principal downgraded = Principal.of("u-1", "guest",
                Set.of("region"), Set.of(ToolPermission.READ));
        assertThat(SessionKeys.sessionIdOf(downgraded, "web", "c-1"))
                .as("权限变了却还是同一段会话，历史里那些高权限数字会被原样念出来")
                .isNotEqualTo(SessionKeys.sessionIdOf(who, "web", "c-1"));

        assertThat(SessionKeys.sessionIdOf(who, "feishu", "oc_group"))
                .as("私聊与群聊必须是两段：同一份权限下听众可以完全不同，"
                        + "群里有二十个人，而私聊只有他自己")
                .isNotEqualTo(SessionKeys.sessionIdOf(who, "web", "c-1"));

        assertThat(SessionKeys.sessionIdOf(who, "web", null))
                .as("没有 chatId 的消息落在占位段，同通道共享——但不会串到别的通道去")
                .isNotEqualTo(SessionKeys.sessionIdOf(who, "feishu", null));

        // ---- 段：形状与回落（ADR-0026）。
        String segmented = SessionKeys.sessionIdOf(who, "web", "c-1", "1762300000000-abcd");
        assertThat(segmented)
                .as("段拼在最后。拼在 owner 前面会让召回的身份校验（startsWith）对别人成立")
                .startsWith(SessionKeys.PREFIX + "u-1@")
                .endsWith(":web:c-1:1762300000000-abcd")
                .isNotEqualTo(SessionKeys.sessionIdOf(who, "web", "c-1"));
        assertThat(SessionKeys.sessionIdOf(who, "web", "c-1", ""))
                .as("空段 = 加段之前的形状：审批续跑读不到 run 上的 session_id 时走这条")
                .isEqualTo(SessionKeys.sessionIdOf(who, "web", "c-1"));

        // ---- 段：什么时候换。判定在内存实现里，与生产 SQL 同语义；
        // 真正的 SQL 语义（一条语句、每次都刷新 touched_at）由 RunSchemaSqlTest 用真库验。
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        MemoryPointers store = new MemoryPointers();
        Instant first = Instant.parse("2026-11-05T02:00:00Z");   // 东八区 11-05 10:00
        String today = pointersAt(store, first, zone).segmentOf("u-1@fp", "web", "c-1");
        assertThat(pointersAt(store, first.plusSeconds(3600), zone)
                .segmentOf("u-1@fp", "web", "c-1"))
                .as("同一天连着说两句就换段，等于每一句都是新对话：记忆与额度都不累积")
                .isEqualTo(today);
        assertThat(pointersAt(store, first.plusSeconds(3600 + 121 * 60), zone)
                .segmentOf("u-1@fp", "web", "c-1"))
                .as("空闲超过阈值必须换：不换的话这一段永远不会结束")
                .isNotEqualTo(today);

        // 东八区 03:59 与 04:00 只差一分钟，idle 不可能触发，能触发的只有日历。
        MemoryPointers crossing = new MemoryPointers();
        String beforeBreak = pointersAt(crossing, Instant.parse("2026-11-05T19:59:00Z"), zone)
                .segmentOf("u-1@fp", "web", "c-1");
        assertThat(pointersAt(crossing, Instant.parse("2026-11-05T20:00:00Z"), zone)
                .segmentOf("u-1@fp", "web", "c-1"))
                .as("04:00 是逻辑日的分界点。用 UTC 的日期来判断的话，东八区凌晨 0~8 点全错，"
                        + "而 CI 跑在 UTC 上是绿的")
                .isNotEqualTo(beforeBreak);

        // ---- 一次访问只发一条 SQL。写成"先 select 再 insert/update"的话这里是 2~3 条，
        // 而那个形状的代价不只是往返：两次调用之间的窗口正是并发下同一次轮换被做两遍的地方。
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        doReturn("seg-1").when(jdbc)
                .queryForObject(anyString(), eq(String.class), any(Object[].class));
        assertThat(new JdbcConversationPointerStore(jdbc).segmentOf(
                new ConversationPointerStore.Key("u-1@fp", "web", "c-1"),
                new ConversationPointerStore.Rotation("fresh", first, first.minusSeconds(7200),
                        LocalDate.of(2026, 11, 5), true)))
                .contains("seg-1");
        verify(jdbc, times(1)).queryForObject(anyString(), eq(String.class), any(Object[].class));
        verifyNoMoreInteractions(jdbc);

        // ---- 指针挂了不拦话：退回加段之前的形状，并计数。
        // 抛出去会把每一句话都打成 500，而根因只是少了一张表——两件事的代价差得太远。
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        ConversationPointers broken = new ConversationPointers(
                (key, rotation) -> {
                    throw new IllegalStateException("表还没迁");
                },
                SessionResetProperties.defaults(), zone, Clock.fixed(first, zone), metrics);
        assertThat(broken.segmentOf("u-1@fp", "web", "c-1"))
                .as("降级之后所有对话退到同一个永不过期的 id，也就是回到改造之前的行为")
                .isEmpty();
        assertThat(metrics.get(ConversationPointers.FAILURE_METRIC).counter().count())
                .as("这个计数不为 0 就说明段当前没生效；少记一笔可以，不让它变成静默")
                .isEqualTo(1.0);
    }

    private static ConversationPointers pointersAt(MemoryPointers store, Instant now, ZoneId zone) {
        return new ConversationPointers(store, SessionResetProperties.defaults(), zone,
                Clock.fixed(now, zone), null);
    }

    /**
     * 指针的内存实现：语义与 {@code JdbcConversationPointerStore} 的两条 SQL 对齐——
     * 过期就换段、没过期就沿用并刷新 touched_at。
     *
     * <p>它不替代 SQL 那条用例，只让"时间怎么驱动段"能单独验：那三段的输入是时刻，
     * 不是数据库。反过来说，<b>它红了不代表生产也红</b>——两边是同一套语义的两个实现，
     * 所以真正的 SQL 仍然由 {@code RunSchemaSqlTest} 在真 Postgres 上验。
     */
    private static final class MemoryPointers implements ConversationPointerStore {

        private final Map<Key, Row> rows = new LinkedHashMap<>();

        @Override
        public Optional<String> segmentOf(Key key, Rotation rotation) {
            Row old = rows.get(key);
            boolean stale = old == null
                    || old.touched().isBefore(rotation.idleBefore())
                    || (rotation.dailyGate() && !old.day().equals(rotation.today()));
            Row next = stale
                    ? new Row(rotation.freshSegment(), rotation.now(), rotation.today())
                    : new Row(old.segment(), rotation.now(), old.day());
            rows.put(key, next);
            return Optional.of(next.segment());
        }

        private record Row(String segment, Instant touched, LocalDate day) {
        }
    }

    @Test
    @DisplayName("权限集合的迭代顺序不影响指纹")
    void fingerprintIgnoresSetOrder() {
        Principal a = Principal.of("u-1", "read-only",
                new LinkedHashSet<>(Set.of("sales_order", "order_item", "region")),
                new LinkedHashSet<>(Set.of("read", "recall_session")));
        Principal b = Principal.of("u-1", "read-only",
                new LinkedHashSet<>(Set.of("region", "sales_order", "order_item")),
                new LinkedHashSet<>(Set.of("recall_session", "read")));

        assertThat(PermissionFingerprint.of(a))
                .as("不排序的话同一份权限能算出两个指纹：缓存永不命中，会话每轮都是新的")
                .isEqualTo(PermissionFingerprint.of(b));
    }

    @Test
    @DisplayName("聊得久了历史被压成摘要：事件不再线性增长，省下的 token 记在指标里")
    void longConversationGetsCompacted() {
        MapSessionService sessions = new MapSessionService();
        String sessionId = SessionKeys.sessionIdOf(Principal.readOnly("u-1"), "web", "c-1");
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
    @DisplayName("清理间隔配成 0 或负数直接拒绝启动：调度器读的是占位符原文，归一不会改它拿到的值")
    void cleanupIntervalMustBePositive() {
        assertThatThrownBy(() -> new SessionMemoryProperties(null, 0, 0, 0, 0, Duration.ZERO))
                .as("fixedDelay=0 是「上一轮结束立刻下一轮」，一个热循环在打库；"
                        + "而这里悄悄改成 1 小时，调度器照样拿到 0")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cleanup-interval");
        assertThatThrownBy(() -> new SessionMemoryProperties(null, 0, 0, 0, 0, Duration.ofMinutes(-5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(SessionMemoryProperties.defaults().cleanupInterval())
                .as("键缺失才落默认值——「没配」有合理默认，「写 0」是写错了，两件事不能混")
                .isEqualTo(SessionMemoryProperties.DEFAULT_CLEANUP_INTERVAL);
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
        ChatClient chat = ChatPipelineTest.clientBuilder(model)
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
        private final List<String> createdUserIds = new CopyOnWriteArrayList<>();

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
            createdUserIds.add(session.userId());
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

        List<String> createdUserIds() {
            return List.copyOf(createdUserIds);
        }
    }
}

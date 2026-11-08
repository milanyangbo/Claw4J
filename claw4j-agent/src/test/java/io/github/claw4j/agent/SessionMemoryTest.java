package io.github.claw4j.agent;

import io.github.claw4j.agent.session.ConversationPointerStore;
import io.github.claw4j.agent.session.ConversationPointers;
import io.github.claw4j.agent.session.FaultTolerantSessionService;
import io.github.claw4j.agent.session.JdbcConversationPointerStore;
import io.github.claw4j.agent.session.SessionCompactor;
import io.github.claw4j.agent.schedule.ScheduleProperties;
import io.github.claw4j.agent.session.SessionKeys;
import io.github.claw4j.agent.session.SessionMaintenance;
import io.github.claw4j.agent.goal.GoalCommand;
import io.github.claw4j.agent.session.SessionCommand;
import io.github.claw4j.agent.session.SessionMaintenanceProperties;
import io.github.claw4j.agent.session.SessionMemoryProperties;
import io.github.claw4j.agent.session.SessionResetProperties;
import io.github.claw4j.agent.session.SessionSchema;
import io.github.claw4j.agent.session.SessionVisibility;
import io.github.claw4j.agent.session.SessionVisibilityProperties;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.port.OutboundMessage;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.domain.principal.ToolTier;
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
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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

        // 拼进 SQL 的那一路（跨会话召回）口径更严：标识符的位置没法用占位符，
        // 只能拼字符串，所以这里必须挡住"能改写语句"的输入，而不是等第一条 SQL 报语法错。
        assertThat(SessionSchema.qualifier("claw4j_session")).isEqualTo("claw4j_session.");
        assertThatThrownBy(() -> SessionSchema.qualifier("claw4j_session; drop table ai_session"))
                .as("分号与空格都不是标识符的一部分：这条要是放过去，拼出来的 SQL 是两条语句")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("claw4j.session.schema");
        assertThatThrownBy(() -> SessionSchema.qualifier("会话"))
                .as("非 ASCII 标识符在 Postgres 里要加引号才能用，本仓库不给这个口子")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("并发两条要排队：第二条等第一条说完，才看得见它的答案（ADR-0026）")
    void concurrentMessagesAreSerialized() throws Exception {
        MapSessionService sessions = new MapSessionService();
        CountDownLatch firstInside = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        FixedChatModel model = new FixedChatModel(prompt -> {
            // 看最后一条（这一轮的提问），不看整段：第二轮的提示词里带着第一轮的原话，
            // 按整段判断的话，重问的那句会被认成第一轮。
            String asked = prompt.getInstructions().getLast().getText();
            if (!asked.contains("上周华东成交额")) {
                return ChatPipelineTest.answer("华南上周成交 66400");
            }
            firstInside.countDown();
            try {
                releaseFirst.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return ChatPipelineTest.answer("华东上周成交 128400");
        });
        ConversationLane lane = new ConversationLane(Duration.ZERO);
        ChatClientAgentPort port = port(model, sessions, lane);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<OutboundMessage> first =
                    pool.submit(() -> port.reply(message("chat-a", "上周华东成交额")));
            assertThat(firstInside.await(5, TimeUnit.SECONDS))
                    .as("第一条没进模型，后面的断言都无从谈起").isTrue();

            // 同一段对话的第二条，此刻第一条还占着锁。等的上限是 0，
            // 所以它立刻拿到那句话，而不是排到第一条后面去。
            Future<OutboundMessage> second =
                    pool.submit(() -> port.reply(message("chat-a", "那华南呢")));
            assertThat(second.get(5, TimeUnit.SECONDS).text())
                    .as("等不到就抛 500 或干脆沉默，用户只会以为机器人坏了")
                    .isEqualTo(ConversationLane.BUSY_REPLY);
            assertThat(model.prompts())
                    .as("第二条在第一条说完之前就调了模型：它拼提示词时 128400 还没落库，"
                            + "而这个失败长得像「模型偶尔会忘事」")
                    .hasSize(1);

            releaseFirst.countDown();
            first.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        // 锁空出来之后，她重问一次。这一轮必须看得见上一轮的数字。
        assertThat(port.reply(message("chat-a", "那华南呢")).text()).contains("66400");
        String secondTurn = model.prompts().get(1).getInstructions().stream()
                .map(Message::getText).collect(Collectors.joining("\n"));
        assertThat(secondTurn)
                .as("第二条的提示词里必须有第一条的答案，否则它只能重查一遍或者反问")
                .contains("128400");
        assertThat(sessions.createdIds())
                .as("两条 run 落在同一段会话里；分成两段的话，记忆根本无处共享")
                .hasSize(1);
        assertThat(lane.size())
                .as("用完不回收，这张锁表会随对话数一直长——一个跑半年的进程里没人清理它")
                .isZero();
    }

    @Test
    @DisplayName("会话跟着「这一段对话」走：同一段的两轮共享，换一段就断（ADR-0025）")
    void memoryFollowsTheConversationNotJustThePrincipal() {
        MapSessionService sessions = new MapSessionService();
        FixedChatModel model = new FixedChatModel("记住了");
        // 时钟钉在同一天：跨天与空闲都不会触发，所以下面每一段的"换没换"只由 /new 决定。
        ChatClientAgentPort port = port(model, sessions, null, pointersAt(new MemoryPointers(),
                Instant.parse("2026-11-05T02:00:00Z"), ZoneId.of("Asia/Shanghai")));
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

        // ---- 第三条换段途径：用户自己说"换一段"（ADR-0026）。
        String acknowledged = port.reply(new InboundMessage(principal, "web-chat", "/new")).text();
        port.reply(new InboundMessage(principal, "web-chat", "我叫什么"));

        String afterCommand = model.prompts().getLast().getInstructions().stream()
                .map(Message::getText)
                .collect(Collectors.joining("\n"));
        assertThat(acknowledged)
                .as("单独 /new 回一句确认，而不是把命令本身送进模型")
                .isEqualTo(SessionCommand.ACK);
        assertThat(sessions.createdIds())
                .as("换段之后是另一行会话：同上一个 id 的话，新段根本不存在")
                .hasSize(3);
        assertThat(afterCommand)
                .as("这就是用户按 /new 的理由：上下文污染之后，唯一有效的动作是换一段重说")
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
        assertThat(new JdbcConversationPointerStore(jdbc, SessionSchema.DEFAULT_SCHEMA).segmentOf(
                new ConversationPointerStore.Key("u-1@fp", "web", "c-1"),
                new ConversationPointerStore.Rotation("fresh", first, first.minusSeconds(7200),
                        LocalDate.of(2026, 11, 5), true, false)))
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

        // ---- /new（ADR-0026 的第三条换段途径）：整词匹配，剩下的内容才是这一轮要问的话。
        assertThat(SessionCommand.parse("/new"))
                .as("单独一个 /new：只换段，回一句确认（不跑模型）")
                .isEqualTo(new SessionCommand.Parsed("", true));
        assertThat(SessionCommand.parse("  /NEW  ").reset())
                .as("大小写不敏感：用户不该为了换个对话去猜大小写")
                .isTrue();
        assertThat(SessionCommand.parse("/reset 帮我查华南回款"))
                .as("/reset 是别名，后面那句才是新段的第一句")
                .isEqualTo(new SessionCommand.Parsed("帮我查华南回款", true));
        assertThat(SessionCommand.parse("/newer 怎么说"))
                .as("前缀匹配会让「问一个新问题」被当成「清空上下文」，而且没有任何报错")
                .isEqualTo(new SessionCommand.Parsed("/newer 怎么说", false));
        assertThat(SessionCommand.parse("/etc 的权限"))
                .as("不是命令的斜杠开头一律当普通问题：别的命令不归我们解释")
                .isEqualTo(new SessionCommand.Parsed("/etc 的权限", false));
        assertThat(SessionCommand.parse("上周成交额").reset()).isFalse();

        // ---- /goal 命令族（ADR-0027）：整词、别名、未知动作词当新目标、多行原文保留。
        assertThat(GoalCommand.parse("/goal")).contains(new GoalCommand.Show());
        assertThat(GoalCommand.parse("/goal status")).contains(new GoalCommand.Show());
        assertThat(GoalCommand.parse("/goal 让 CI 变绿"))
                .as("/goal 后面任何不是已知动作词的文本都是新目标（OpenClaw 原文）")
                .contains(new GoalCommand.Start("让 CI 变绿"));
        assertThat(GoalCommand.parse("/goal create 让 CI 变绿"))
                .as("create 是 start 的别名，与 /goal start 等价")
                .contains(new GoalCommand.Start("让 CI 变绿"));
        assertThat(GoalCommand.parse("/goal done 已推上去"))
                .as("done 是 complete 的别名")
                .contains(new GoalCommand.Complete("已推上去"));
        assertThat(GoalCommand.parse("/goal blocked 等 CI 环境"))
                .contains(new GoalCommand.Block("等 CI 环境"));
        assertThat(GoalCommand.parse("/goal edit 让 CI 变绿并更新文档"))
                .contains(new GoalCommand.Edit("让 CI 变绿并更新文档"));
        assertThat(GoalCommand.parse("/goal start a\n  b\n    c"))
                .as("多行目标原样保留：折成空格会让一份清单变成一段读不懂的散文")
                .contains(new GoalCommand.Start("a\n  b\n    c"));
        assertThat(GoalCommand.parse("/goals 是什么"))
                .as("前缀匹配会让一个普通问题被当成命令，而用户看到的是「我的问题它没回答」")
                .isEmpty();
        assertThat(GoalCommand.parse("/goal edit"))
                .as("认出来了但参数不对：说清用法，而不是静默建一个空目标")
                .hasValueSatisfying(command ->
                        assertThat(command).isInstanceOf(GoalCommand.Invalid.class));
        assertThat(SessionCommand.parse("/goal pause").reset())
                .as("/goal 不是 /new：它不换段")
                .isFalse();
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
                    || rotation.forced()
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
    @DisplayName("清理撞上连不上的库：返回空账、只 WARN 第一次、每次都计数——不让后台任务变成刷屏的异常")
    void cleanupSurvivesAnUnavailableDatabase() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("连不上库"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SessionMaintenance maintenance = maintenance(jdbc, registry);

        assertThatCode(() -> maintenance.sweep(Instant.now()))
                .as("清理是后台任务，抛出去只会让 @Scheduled 每小时刷一次全栈；"
                        + "而「清理没跑成」与「没有东西可清」在结果上都是 0 行")
                .doesNotThrowAnyException();
        assertThat(maintenance.sweep(Instant.now())).isEqualTo(SessionMaintenance.Report.NONE);
        assertThat(registry.get(SessionMaintenance.FAILURES).counter().count())
                .as("失败不静默：这个数不为 0 就说明这些天一行都没清")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("清理的每个数越界都直接拒绝启动：0 是「全部过期」，负数是一个热循环在打库")
    void maintenanceBoundsAreRejected() {
        assertThatThrownBy(() -> new SessionMemoryProperties(null, 0, 0, 0, 0, Duration.ZERO))
                .as("fixedDelay=0 是「上一轮结束立刻下一轮」，而这里悄悄改成 1 小时，调度器照样拿到 0")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cleanup-interval");
        assertThatThrownBy(() -> new SessionMemoryProperties(null, 0, 0, 0, 0, Duration.ofMinutes(-5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(SessionMemoryProperties.defaults().cleanupInterval())
                .as("键缺失才落默认值——「没配」有合理默认，「写 0」是写错了，两件事不能混")
                .isEqualTo(SessionMemoryProperties.DEFAULT_CLEANUP_INTERVAL);

        assertThatThrownBy(() -> new SessionMaintenanceProperties(null, Duration.ZERO, null))
                .as("0 的意思是「所有段都过期」，第一次清理就会把会话表清空，"
                        + "而它看起来只是「把数调小一点」")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("prune-after");
        assertThatThrownBy(() -> new SessionMaintenanceProperties("enforce", null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-entries");

        assertThat(SessionMaintenanceProperties.defaults().mode())
                .as("默认必须是 warn：删数据不可逆，而 OpenClaw 的默认也是只报告")
                .isEqualTo(SessionMaintenanceProperties.MODE_WARN);
        assertThat(new SessionMaintenanceProperties("enfroce", null, null).mode())
                .as("拼错落回 warn，不是落回 enforce——错的方向必须朝「少删」那边倒")
                .isEqualTo(SessionMaintenanceProperties.MODE_WARN);
        assertThat(new SessionMaintenanceProperties("ENFORCE", null, null).enforces()).isTrue();
    }

    @Test
    @DisplayName("visibility 写错的词启动失败并点名四个合法值：回落成 all 是最危险的那种「容错」")
    void visibilityMustBeOneOfFourWords() {
        assertThat(SessionVisibilityProperties.defaults().scope())
                .as("缺键落 all：照它的默认值（tools.sessions.visibility 原文就是 Default: all）")
                .isEqualTo(SessionVisibility.ALL);

        assertThatThrownBy(() -> new SessionVisibilityProperties("everybody"))
                .as("上一行在拼错时回落，这里必须抛：mode 拼错能落向更保守的一侧，"
                        + "而 visibility 的四个值没有「更保守」的默认——落哪个都是在猜授权范围，"
                        + "且「写错了」与「写的是 all」在日志上一模一样")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("claw4j.tools.sessions.visibility")
                .hasMessageContaining("self / tree / agent / all");

        new ApplicationContextRunner()
                .withUserConfiguration(VisibilityOnly.class)
                .withPropertyValues("claw4j.tools.sessions.visibility=everybody")
                .run(context -> {
                    assertThat(context)
                            .as("绑定阶段就失败，不是第一次召回时才发现")
                            .hasFailed();
                    assertThat(context.getStartupFailure())
                            .as("报错要点名这个键与四个合法值，否则运维还得去翻文档")
                            .hasStackTraceContaining("self / tree / agent / all");
                });

        new ApplicationContextRunner()
                .withUserConfiguration(VisibilityOnly.class)
                .withPropertyValues("claw4j.tools.sessions.visibility=AGENT")
                .run(context -> assertThat(context.getBean(SessionVisibilityProperties.class).scope())
                        .as("大小写不敏感：yml 里写 AGENT 与 agent 是同一个档")
                        .isEqualTo(SessionVisibility.AGENT));

        new ApplicationContextRunner()
                .withUserConfiguration(VisibilityOnly.class)
                .withPropertyValues("claw4j.tools.sessions.visibility=self",
                        "claw4j.tools.profile=minimal")
                .run(context -> {
                    assertThat(context.getBean(SessionVisibilityProperties.class).scope())
                            .isEqualTo(SessionVisibility.SELF);
                    assertThat(context.getBean(ToolsProperties.class).profile())
                            .as("同一个前缀下的两棵子树各绑各的：加了 sessions 之后 profile 不能绑丢")
                            .isEqualTo(ToolTier.MINIMAL);
                });
    }

    /** 只装那两个属性，不装整套会话装配：这条用例问的是"绑定会不会拒"，不是"记忆能不能跑"。 */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({SessionVisibilityProperties.class, ToolsProperties.class})
    static class VisibilityOnly {
    }

    private static SessionMaintenance maintenance(JdbcTemplate jdbc, MeterRegistry registry) {
        return new SessionMaintenance(jdbc, SessionMaintenanceProperties.defaults(),
                ScheduleProperties.defaults(), registry, SessionSchema.DEFAULT_SCHEMA);
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
        return port(model, sessions, null);
    }

    private static ChatClientAgentPort port(ChatModel model, SessionService sessions,
                                            ConversationLane lane) {
        return port(model, sessions, lane, null);
    }

    private static ChatClientAgentPort port(ChatModel model, SessionService sessions,
                                            ConversationLane lane, ConversationPointers pointers) {
        ChatClient chat = ChatPipelineTest.clientBuilder(model)
                .defaultAdvisors(SessionMemoryAdvisor.builder(sessions)
                        .order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
                        .build())
                .build();
        return new ChatClientAgentPort(chat, null, null, null, pointers, lane);
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

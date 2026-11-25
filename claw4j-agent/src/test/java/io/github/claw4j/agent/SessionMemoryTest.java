package io.github.claw4j.agent;

import io.github.claw4j.agent.toolaccess.ToolsProperties;
import io.github.claw4j.agent.turn.ChatClientAgentPort;
import io.github.claw4j.agent.turn.ConversationLane;
import io.github.claw4j.agent.memory.PrincipalMemoryAdvisor;
import io.github.claw4j.agent.session.SessionKeys;
import io.github.claw4j.agent.approval.ElevatedCommand;
import io.github.claw4j.agent.approval.ElevatedProperties;
import io.github.claw4j.agent.approval.Elevations;
import io.github.claw4j.agent.session.SessionCommand;
import io.github.claw4j.agent.session.SessionMemoryConfiguration;
import io.github.claw4j.agent.session.SessionMemoryProperties;
import io.github.claw4j.agent.session.SessionSchema;
import io.github.claw4j.agent.session.SessionVisibility;
import io.github.claw4j.agent.session.SessionVisibilityProperties;
import io.github.claw4j.domain.ContextKeys;
import io.github.claw4j.domain.NotifyPolicy;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.port.OutboundMessage;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.principal.ToolTier;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.session.compaction.CompactionRequest;
import org.springframework.ai.session.compaction.CompactionResult;
import org.springframework.ai.session.compaction.CompactionStrategy;
import org.springframework.ai.session.compaction.CompactionTrigger;
import org.springframework.ai.session.compaction.RecursiveSummarizationCompactionStrategy;
import org.springframework.ai.session.compaction.TokenCountTrigger;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.mockito.InOrder;
import org.springframework.transaction.CannotCreateTransactionException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
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
                .hasMessageContaining("claw4j.advisor.session.schema");
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

            // 同一个人的**另一条通道**：默认 dm-scope 是 main，这两个地址本来就是同一段会话。
            // 串行化的单位是会话（OpenClaw 的 physical session），不是「通道 + chatId」——
            // 按地址分锁的话这一句会另起一轮，两条 run 同时往同一份记忆里写。
            Future<OutboundMessage> crossChannel =
                    pool.submit(() -> port.reply(channelMessage("web", "chat-b")));
            assertThat(crossChannel.get(5, TimeUnit.SECONDS).text())
                    .as("cross-channel：同一个节点应当是同一把锁")
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
    @DisplayName("会话跟着地址走：同一地址的两轮共享，换个地址就断（ADR-0025）")
    void memoryFollowsTheConversationNotJustThePrincipal() {
        MapSessionService sessions = new MapSessionService();
        FixedChatModel model = new FixedChatModel("记住了");
        ChatClientAgentPort port = port(model, sessions);
        Principal principal = new Principal("u-1");

        port.reply(new InboundMessage(principal, "web-chat", "我叫 Ethan"));
        port.reply(new InboundMessage(principal, "web-chat", "我叫什么"));

        String sameConversation = model.prompts().getLast().getInstructions().stream()
                .map(Message::getText)
                .collect(Collectors.joining("\n"));
        assertThat(sessions.createdIds())
                .as("同一段对话的两轮落在同一个会话里，否则它连上一句都记不住")
                .hasSize(1);
        assertThat(sameConversation).contains("我叫 Ethan");

        // 换的是「群聊」而不是另一个私聊：默认 dm-scope 是 main，同一个人的私聊本来就算同一条会话
        // （ADR-0029 裁决二），拿它来验"换一段就断"会验到别的东西上去。
        port.reply(new InboundMessage(principal, "oc_group", "我叫什么", Instant.now(), null,
                "feishu", NotifyPolicy.DEFAULT, null, null, false));

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
    @DisplayName("/new 清掉历史但 id 不变：侧边栏不会因为「开了新对话」多出一行")
    void newCommandClearsHistoryAndKeepsTheId() {
        MapSessionService sessions = new MapSessionService();
        FixedChatModel model = new FixedChatModel("记住了");
        ChatClientAgentPort port = port(model, sessions);
        Principal principal = new Principal("u-1");

        port.reply(new InboundMessage(principal, "web-chat", "我叫 Ethan"));
        String before = sessions.createdIds().getFirst();

        String acknowledged = port.reply(new InboundMessage(principal, "web-chat", "/new")).text();
        port.reply(new InboundMessage(principal, "web-chat", "我叫什么"));

        String afterCommand = model.prompts().getLast().getInstructions().stream()
                .map(Message::getText)
                .collect(Collectors.joining("\n"));
        assertThat(acknowledged)
                .as("单独 /new 回一句确认，而不是把命令本身送进模型")
                .isEqualTo(SessionCommand.ACK);
        assertThat(sessions.createdIds())
                .as("/new 不再换会话：id 与之前同一个，侧边栏不会多一行")
                .containsOnly(before);
        assertThat(afterCommand)
                .as("这就是用户按 /new 的理由：上下文污染之后，唯一有效的动作是清掉重说")
                .doesNotContain("我叫 Ethan");
    }

    @Test
    @DisplayName("两个用户不会共用会话：userId 变了就是另一个人")
    void differentUsersGetDifferentSessions() {
        MapSessionService sessions = new MapSessionService();
        ChatClientAgentPort port = port(new FixedChatModel("好"), sessions);

        port.reply(new InboundMessage(new Principal("u-1"), "chat", "甲说的话"));
        port.reply(new InboundMessage(new Principal("u-2"), "chat", "乙说的话"));

        assertThat(sessions.createdIds()).hasSize(2).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("会话表的 user_id 存归属键：召回按它精确匹配，写别的会让匹配对象错位")
    void sessionUserIdCarriesTheOwnerKey() {
        MapSessionService sessions = new MapSessionService();
        ChatClientAgentPort port = port(new FixedChatModel("好"), sessions);

        Principal who = new Principal("u-1");
        port.reply(new InboundMessage(who, "chat", "问一句"));

        assertThat(sessions.createdUserIds())
                .as("归属键与 userId 现在是同一个值（ADR-0030 代价：降权不重置会话），但这一列"
                        + "要存的是 ownerOf 的输出，不是随手取的 userId——将来真要按配置分区只改一处")
                .containsExactly(SessionKeys.ownerOf(who));
    }

    @Test
    @DisplayName("userId 为空的身份不许开会话：否则所有人共用一个，还记得是别人的")
    void blankUserIdIsRejected() {
        assertThatThrownBy(() -> SessionKeys.sessionIdOf(new Principal(" "), "web", "c-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId");
    }

    @Test
    @DisplayName("会话 id 就是会话键：形状稳定，没有任何段后缀")
    void sessionIdCarriesNoSegmentSuffix() {
        Principal who = new Principal("u-1");

        assertThat(SessionKeys.sessionIdOf(who, "web", "c-1"))
                .as("每次都不一样的话，这个人永远记不住上一轮，而且没有任何报错")
                .isEqualTo(SessionKeys.sessionIdOf(new Principal("u-1"), "web", "c-1"))
                .startsWith(SessionKeys.PREFIX + "u-1:")
                .endsWith(":web:c-1");

        assertThat(SessionKeys.sessionIdOf(who, "web", "c-1"))
                .as("段下线之后末尾不该再挂 <epochMilli>-<四位十六进制>：多一截就是另一个会话")
                .doesNotContainPattern("-\\d{4}$");

        // 权限指纹从键里下线（ADR-0030）：同一个 userId 换配置不换会话。这是明码标价的代价
        // ——降权之后历史里那些旧数字还在上下文里（ADR 代价第 3 条），这里显式钉住"确实不换"。
        assertThat(SessionKeys.sessionIdOf(who, "web", "c-1"))
                .as("换配置不换会话：身份现在只有 userId，没有第二种权限版本")
                .isEqualTo(SessionKeys.sessionIdOf(new Principal("u-1"), "web", "c-1"));

        assertThat(SessionKeys.sessionIdOf(who, "feishu", "oc_group"))
                .as("私聊与群聊必须是两段：同一份权限下听众可以完全不同，"
                        + "群里有二十个人，而私聊只有他自己")
                .isNotEqualTo(SessionKeys.sessionIdOf(who, "web", "c-1"));

        assertThat(SessionKeys.sessionIdOf(who, "web", null))
                .as("没有 chatId 的消息落在占位段，同通道共享——但不会串到别的通道去")
                .isNotEqualTo(SessionKeys.sessionIdOf(who, "feishu", null));
    }

    @Test
    @DisplayName("/new 的解析：整词匹配，剩下的内容才是清空之后要问的话")
    void newCommandParsesItsTwoShapes() {
        assertThat(SessionCommand.parse("/new"))
                .as("单独一个 /new：只清历史，回一句确认（不跑模型）")
                .isEqualTo(new SessionCommand.Parsed("", true));
        assertThat(SessionCommand.parse("  /NEW  ").reset())
                .as("大小写不敏感：用户不该为了清个上下文去猜大小写")
                .isTrue();
        assertThat(SessionCommand.parse("/reset 帮我查华南回款"))
                .as("/reset 是别名，后面那句才是清空之后的第一句")
                .isEqualTo(new SessionCommand.Parsed("帮我查华南回款", true));
        assertThat(SessionCommand.parse("/newer 怎么说"))
                .as("前缀匹配会让「问一个新问题」被当成「清空上下文」，而且没有任何报错")
                .isEqualTo(new SessionCommand.Parsed("/newer 怎么说", false));
        assertThat(SessionCommand.parse("/etc 的权限"))
                .as("不是命令的斜杠开头一律当普通问题：别的命令不归我们解释")
                .isEqualTo(new SessionCommand.Parsed("/etc 的权限", false));
        assertThat(SessionCommand.parse("上周成交额").reset()).isFalse();
    }

    @Test
    @DisplayName("压缩由记忆 Advisor 自己在轮末触发：事件不再线性增长")
    void theAdvisorCompactsWhenItsTriggerFires() {
        MapSessionService sessions = new MapSessionService();
        String sessionId = SessionKeys.sessionIdOf(new Principal("u-1"), "web", "c-1");
        sessions.create(CreateSessionRequest.builder().id(sessionId).userId("u-1").build());
        for (int i = 0; i < 200; i++) {
            sessions.appendEvent(event(sessionId, i));
        }
        int before = sessions.getEvents(sessionId, EventFilter.active()).size();

        ChatClientAgentPort port = portWithCompaction(sessions);

        port.reply(message("c-1", "再说一句"));

        assertThat(sessions.getEvents(sessionId, EventFilter.active()))
                .as("压完事件条数必须下降，否则等于什么都没做")
                .hasSizeLessThan(before + 1);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("窗口从配置读，启动那行打的是生效值：换主模型时改没改一眼看得见")
    void configuredContextWindowIsWhatTheStartupLineSays(CapturedOutput output) {
        AtomicReference<SessionMemoryProperties> bound = new AtomicReference<>();
        new ApplicationContextRunner()
                .withUserConfiguration(MemoryOnly.class)
                // 故意不给默认值 65536：只有真的从这一层绑上来，阈值才会是 65536
                .withPropertyValues("claw4j.advisor.session.context-window=131072")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    bound.set(context.getBean(SessionMemoryProperties.class));
                });
        SessionMemoryProperties properties = bound.get();

        assertThat(properties.compactionThreshold())
                .as("阈值 = 窗口 × 0.5；仍是 32768 说明键绑错了层级，静默走了默认值")
                .isEqualTo(65536);

        new SessionMemoryConfiguration().sessionMemoryAdvisor(mock(SessionService.class), properties,
                mock(ChatModel.class),
                new DefaultListableBeanFactory().getBeanProvider(
                        io.github.claw4j.model.catalog.ModelCatalog.class));

        assertThat(output.toString())
                .as("这一行是换模型时唯一看得见窗口的地方，三个数都必须是绑上来的值")
                .contains("窗口 131072 token", "到 65536 触发", "保留最近 20 条事件");

        // 目录优先、配置兜底：主模型那一行说 65536，就不该按配置里的 131072 去压。
        io.github.claw4j.model.catalog.ModelRow row = new io.github.claw4j.model.catalog.ModelRow();
        row.setId("deepseek-chat");
        row.setContextWindow(65536);
        io.github.claw4j.model.config.ModelProperties.Provider deepseek =
                new io.github.claw4j.model.config.ModelProperties.Provider();
        deepseek.setApi(io.github.claw4j.model.config.ModelProperties.API_DEEPSEEK);
        deepseek.setBaseUrl("http://127.0.0.1:9");
        deepseek.setApiKeys(List.of("secret:deepseek_api_key"));
        deepseek.setModels(List.of(row));
        io.github.claw4j.model.config.ModelProperties modelProperties =
                new io.github.claw4j.model.config.ModelProperties();
        modelProperties.setProviders(Map.of("deepseek", deepseek));
        io.github.claw4j.model.catalog.ModelCatalog catalog =
                io.github.claw4j.model.catalog.ModelCatalog.of(modelProperties);

        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("modelCatalog", catalog);
        new SessionMemoryConfiguration().sessionMemoryAdvisor(mock(SessionService.class), properties,
                mock(ChatModel.class),
                factory.getBeanProvider(io.github.claw4j.model.catalog.ModelCatalog.class));

        assertThat(output.toString())
                .as("窗口是模型的元数据：目录里那行才是准的，配置那个数只在没有目录时兜底")
                .contains("窗口 65536 token", "到 32768 触发");
    }

    /** 只装会话属性，不装整套会话装配：这条用例问的是"键绑在哪一层"，不是"记忆能不能跑"。 */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SessionMemoryProperties.class)
    static class MemoryOnly {
    }

    @Test
    @DisplayName("会话存储挂了就是这一轮失败，不降级成「这一轮没有记忆」（ADR-0026 修订）")
    void sessionStorageFailureFailsTheTurn() {
        SessionService broken = mock(SessionService.class);
        when(broken.findById(anyString()))
                .thenThrow(new DataAccessResourceFailureException("连不上库"));
        when(broken.create(any()))
                .thenThrow(new DataAccessResourceFailureException("连不上库"));
        ChatClientAgentPort port = port(new FixedChatModel("好"), broken);

        assertThatThrownBy(() -> port.reply(message("chat-a", "问一句")))
                .as("库挂了还照常回答，排障时看到的只是「机器人突然失忆」——"
                        + "记忆与回答走同一条链，一起失败才找得到根因")
                .isInstanceOf(DataAccessResourceFailureException.class);
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
        ChatClient chat = ChatPipelineTest.clientBuilder(model)
                .defaultAdvisors(SessionMemoryAdvisor.builder(sessions)
                        .order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
                        .build())
                .build();
        return ChatClientAgentPort.builder(chat)
                .sessions(sessions)
                .lane(lane)
                .build();
    }

    /** 带压缩的装配：触发阈值调低到 100 token，跑一轮就能看见事件被折成摘要。 */
    private static ChatClientAgentPort portWithCompaction(SessionService sessions) {
        ChatClient chat = ChatPipelineTest.clientBuilder(new FixedChatModel("好"))
                .defaultAdvisors(SessionMemoryAdvisor.builder(sessions)
                        .order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
                        .compactionTrigger(TokenCountTrigger.builder().threshold(100).build())
                        .compactionStrategy(RecursiveSummarizationCompactionStrategy.builder(
                                        ChatClient.builder(
                                                new FixedChatModel("摘要：用户连问了多轮成交额")).build())
                                .maxEventsToKeep(6)
                                .build())
                        .build())
                .build();
        return ChatClientAgentPort.builder(chat).build();
    }

    private static InboundMessage message(String chatId, String text) {
        return new InboundMessage(new Principal("u-1"), chatId, text);
    }

    /** 带通道的一条私聊：用来验「同一把锁跟的是会话，不是通道」。 */
    private static InboundMessage channelMessage(String channel, String chatId) {
        return new InboundMessage(new Principal("u-1"), chatId, "上周华东成交额",
                Instant.now(), null, channel, NotifyPolicy.DEFAULT, null, null, true);
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
    @Test
    @DisplayName("/elevated：四档、ask 是 on 的别名、裸命令看当前档、词不认识就提示用法")
    void elevatedParsesItsFourWords() {
        assertThat(ElevatedCommand.parse("/elevated"))
                .as("裸命令是「看当前档」，不是「不认识」")
                .contains(new ElevatedCommand.Invocation(null, null));
        assertThat(ElevatedCommand.parse("/elevated full").orElseThrow().level())
                .isEqualTo(io.github.claw4j.domain.principal.Elevation.FULL);
        assertThat(ElevatedCommand.parse("/elevated ask").orElseThrow().level())
                .as("ask 是 on 的别名（OpenClaw 文档里两者同义）")
                .isEqualTo(io.github.claw4j.domain.principal.Elevation.ON);
        assertThat(ElevatedCommand.parse("/elevated off").orElseThrow().level())
                .isEqualTo(io.github.claw4j.domain.principal.Elevation.OFF);
        assertThat(ElevatedCommand.parse("/elevated loudly").orElseThrow().unknownWord())
                .as("写了认不出的词要回用法，不能当成「看当前档」")
                .isTrue();
        assertThat(ElevatedCommand.parse("/elevateds 是什么"))
                .as("前缀匹配会把普通问题当成命令，而用户看到的是「我的问题它没回答」")
                .isEmpty();
        assertThat(ElevatedCommand.parse("帮我查一下上周的回款")).isEmpty();
    }

    @Test
    @DisplayName("/elevated：部署没开时拒，并说清该改哪个键（不是「你不许」）")
    void elevatedRefusedWhenDeploymentIsClosed() {
        Elevations elevations = new Elevations(ElevatedProperties.defaults(), null);

        String answer = ElevatedCommand.execute(new ElevatedCommand.Invocation(
                        io.github.claw4j.domain.principal.Elevation.FULL, "full"),
                elevations, new io.github.claw4j.domain.principal.Principal("admin"), "conversation-1");

        assertThat(answer).contains("enabled=false").contains("这个部署没有开提权");
        assertThat(elevations.level("conversation-1"))
                .as("拒了就不能顺手记上")
                .isEqualTo(io.github.claw4j.domain.principal.Elevation.OFF);
    }

    @Test
    @DisplayName("/elevated：名单为空时谁都不许——「没配」只能是「不许」")
    void elevatedRefusedForUnlistedSender() {
        Elevations openButNobody = new Elevations(
                new ElevatedProperties(true, java.util.List.of("alice"), null), null);

        String answer = ElevatedCommand.execute(new ElevatedCommand.Invocation(
                        io.github.claw4j.domain.principal.Elevation.FULL, "full"),
                openButNobody, new io.github.claw4j.domain.principal.Principal("bob"), "conversation-2");

        assertThat(answer).contains("allow-from").contains("不在提权名单");
        assertThat(openButNobody.level("conversation-2"))
                .isEqualTo(io.github.claw4j.domain.principal.Elevation.OFF);
    }

    @Test
    @DisplayName("/elevated full 有有效期：到点自动回到不提权，忘了关不会变成永久")
    void elevatedExpiresAfterItsTtl() {
        java.util.concurrent.atomic.AtomicReference<java.time.Instant> now =
                new java.util.concurrent.atomic.AtomicReference<>(java.time.Instant.parse("2026-11-18T12:00:00Z"));
        java.time.Clock moving = new java.time.Clock() {

            @Override
            public java.time.ZoneId getZone() {
                return java.time.ZoneOffset.UTC;
            }

            @Override
            public java.time.Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public java.time.Instant instant() {
                return now.get();
            }
        };
        Elevations elevations = new Elevations(
                new ElevatedProperties(true, java.util.List.of("alice"), null), moving);

        String answer = ElevatedCommand.execute(new ElevatedCommand.Invocation(
                        io.github.claw4j.domain.principal.Elevation.FULL, "full"),
                elevations, new io.github.claw4j.domain.principal.Principal("alice"), "conversation-3");

        assertThat(answer)
                .as("回执要说清三件事：生效了、跳过审批、什么时候失效")
                .contains("跳过审批")
                .contains("自动失效");
        assertThat(elevations.level("conversation-3"))
                .isEqualTo(io.github.claw4j.domain.principal.Elevation.FULL);

        now.set(now.get().plus(java.time.Duration.ofMinutes(31)));

        assertThat(elevations.level("conversation-3"))
                .as("30 分钟之后还在 full，等于把审批永久关掉了")
                .isEqualTo(io.github.claw4j.domain.principal.Elevation.OFF);
    }

    @Test
    @DisplayName("长期记忆按人分目录：甲写下的事实，乙那边看不见")
    void eachPrincipalGetsItsOwnMemoryDirectory(@TempDir Path root) throws IOException {
        PrincipalMemoryAdvisor advisor = new PrincipalMemoryAdvisor(root);

        memoryCallback(advisor.before(memoryRequest(new Principal("alice")), null), "MemoryCreate")
                .call("{\"path\":\"prefs.md\",\"fileText\":\"---\\nname: prefs\\n---\\n用 static final\"}");

        try (Stream<Path> entries = Files.list(root)) {
            assertThat(entries.filter(Files::isDirectory))
                    .as("只该有甲那一层：目录按 userId 派生，不是所有人堆在根下")
                    .hasSize(1);
        }

        assertThat(memoryCallback(
                advisor.before(memoryRequest(new Principal("bob")), null), "MemoryView")
                .call("{\"path\":\"prefs.md\"}"))
                .as("乙的根是另一个目录，甲写过的文件名在那边不存在")
                .contains("does not exist");

        memoryCallback(advisor.before(memoryRequest(new Principal("../../etc/passwd")), null),
                "MemoryCreate")
                .call("{\"path\":\"escape.md\",\"fileText\":\"x\"}");
        try (Stream<Path> entries = Files.list(root)) {
            assertThat(entries.filter(Files::isDirectory))
                    .as("三个人三层，且都在根下：身份里的 / 与 .. 不会把路径带出去")
                    .hasSize(3)
                    .allMatch(directory -> root.equals(directory.getParent()))
                    .anyMatch(directory -> directory.getFileName().toString()
                            .startsWith(".._.._etc_passwd-"));
        }
    }

    @Test
    @DisplayName("注入的是库那六个记忆工具，系统提示拼在人设后面")
    void memoryToolsComeFromTheLibrary(@TempDir Path root) {
        PrincipalMemoryAdvisor advisor = new PrincipalMemoryAdvisor(root);

        ChatClientRequest request = advisor.before(memoryRequest(new Principal("alice")), null);

        assertThat(memoryCallbacks(request))
                .as("六个都是 AutoMemoryTools 的，没有一个是我们自己写的")
                .contains("MemoryView", "MemoryCreate", "MemoryStrReplace",
                        "MemoryInsert", "MemoryDelete", "MemoryRename");
        assertThat(request.prompt().getSystemMessage().getText())
                .as("人设还是开头，记忆那一段接在后面（order 必须大于 persona）")
                .startsWith("人设")
                .contains("persistent, file-based memory system");
    }

    @Test
    @DisplayName("请求里没有身份：一个记忆工具都不注入，系统提示一个字不改")
    void withoutAPrincipalNothingIsInjected(@TempDir Path root) throws IOException {
        PrincipalMemoryAdvisor advisor = new PrincipalMemoryAdvisor(root);
        ChatClientRequest original = memoryRequest(null);

        ChatClientRequest result = advisor.before(original, null);

        assertThat(result).as("没有身份就原样返回，不 fallback 到公共目录").isSameAs(original);
        assertThat(memoryCallbacks(result)).isEmpty();
        assertThat(result.prompt().getSystemMessage().getText()).isEqualTo("人设");
        try (Stream<Path> entries = Files.list(root)) {
            assertThat(entries).as("没有身份就不该在盘上留任何东西").isEmpty();
        }
    }

    /** 一份带人设的最小请求；身份放在 context 里，与线上那条 {@code toolContext} 是同一个 map。 */
    private static ChatClientRequest memoryRequest(Principal principal) {
        Map<String, Object> context = principal == null
                ? Map.of() : Map.of(ContextKeys.PRINCIPAL, principal);
        return ChatClientRequest.builder()
                .prompt(new Prompt(List.of(new SystemMessage("人设"), new UserMessage("问一句")),
                        ToolCallingChatOptions.builder().build()))
                .context(context)
                .build();
    }

    private static List<String> memoryCallbacks(ChatClientRequest request) {
        return toolCallbacks(request).stream()
                .map(callback -> callback.getToolDefinition().name())
                .toList();
    }

    private static ToolCallback memoryCallback(ChatClientRequest request, String name) {
        return toolCallbacks(request).stream()
                .filter(callback -> name.equals(callback.getToolDefinition().name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("注入的工具里没有 " + name));
    }

    private static List<ToolCallback> toolCallbacks(ChatClientRequest request) {
        List<ToolCallback> callbacks =
                ((ToolCallingChatOptions) request.prompt().getOptions()).getToolCallbacks();
        return callbacks == null ? List.of() : callbacks;
    }
}

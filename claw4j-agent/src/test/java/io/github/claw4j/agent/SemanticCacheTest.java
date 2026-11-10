package io.github.claw4j.agent;

import io.github.claw4j.agent.cache.CacheAutoConfiguration;
import io.github.claw4j.agent.cache.CacheProperties;
import io.github.claw4j.agent.model.ModelProperties;
import io.github.claw4j.agent.persona.AgentContextAdvisor;
import io.github.claw4j.agent.persona.AgentProperties;
import io.github.claw4j.agent.persona.BuiltinPersona;
import io.github.claw4j.domain.ContextKeys;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.persona.PersonaCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.cache.semantic.SemanticCache;
import org.springframework.ai.chat.cache.semantic.SemanticCacheAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 语义缓存：同一个问题第二遍不打模型，以及哪些情况必须不命中。
 *
 * <p>这里跑的是框架的 {@link SemanticCacheAdvisor} 与 {@link AgentContextAdvisor}。存储用一个
 * 内存替身，它实现同一个 {@link SemanticCache} 接口，判定只有两条：上下文哈希相同、相似度过
 * 阈值。真 Redis 那一路不在单测里跑（要 Redis Stack），由 {@code docs/capability-tests.md:523}
 * 的人工链路兜。
 *
 * <p>向量由按文本返回预设值的假模型给（见 {@link FakeEmbedding}），相似度是实测值的复刻：
 * 0.9372 是同义改写，0.8968 是「华东 / 华南」。用真模型跑这几条要花钱、要联网，还会随模型
 * 版本漂移；而这里要验的是分数怎么用，不是分数算得准不准。
 */
class SemanticCacheTest {

    private static final String EAST_LAST_WEEK = "上周华东成交额多少";

    private static final String EAST_LAST_WEEK_REWORDED = "华东上周卖了多少";

    private static final String SOUTH_LAST_WEEK = "上周华南成交额多少";

    private static final String ANSWER = "上周华东区成交 128400 元";

    @Test
    @DisplayName("同一个问题第二遍不再打模型")
    void secondTimeDoesNotCallTheModel() {
        Fixture fixture = new Fixture();
        fixture.port.reply(message("u-1", EAST_LAST_WEEK));
        String second = fixture.port.reply(message("u-1", EAST_LAST_WEEK)).text();

        assertThat(fixture.model.prompts())
                .as("缓存命中的定义就是「这次没有模型调用」，不能只是「快了一点」")
                .hasSize(1);
        assertThat(second).contains(ANSWER);
    }

    @Test
    @DisplayName("换个说法问同一件事也命中：这才是语义缓存相对于精确匹配的全部意义")
    void rewordedQuestionHits() {
        Fixture fixture = new Fixture();
        fixture.port.reply(message("u-1", EAST_LAST_WEEK));
        String second = fixture.port.reply(message("u-1", EAST_LAST_WEEK_REWORDED)).text();

        assertThat(fixture.model.prompts()).hasSize(1);
        assertThat(second).contains(ANSWER);
    }

    @Test
    @DisplayName("换了区域不命中：相似度 0.8968 低于阈值")
    void differentRegionMisses() {
        Fixture fixture = new Fixture();
        fixture.port.reply(message("u-1", EAST_LAST_WEEK));
        String second = fixture.port.reply(message("u-1", SOUTH_LAST_WEEK)).text();

        assertThat(fixture.model.prompts())
                .as("把「华东」当成「华南」答出去，是给出另一个问题的答案——比不缓存坏得多")
                .hasSize(2);
        assertThat(second).contains(ANSWER);
    }

    @Test
    @DisplayName("换一个角色问同一句话：miss，因为上下文哈希里带着权限")
    void anotherRoleMisses() {
        Fixture fixture = new Fixture();
        fixture.port.reply(message("u-1", EAST_LAST_WEEK));
        // guest 读不到 sales_order，同一个问题的答案本来就不同
        fixture.port.reply(new Message("u-2", "guest", Set.of("region"),
                Set.of(ToolPermission.READ)).toInbound(EAST_LAST_WEEK));

        assertThat(fixture.model.prompts())
                .as("权限不同 → 系统提示词不同 → 框架算出的 contextHash 不同 → 查的是另一个分区")
                .hasSize(2);
    }

    @Test
    @DisplayName("上下文哈希只看 tables 与 actions：同角色换个人照样命中")
    void fingerprintIgnoresUserId() {
        Fixture fixture = new Fixture();
        fixture.port.reply(message("u-1", EAST_LAST_WEEK));
        fixture.port.reply(message("u-9", EAST_LAST_WEEK));

        assertThat(fixture.model.prompts())
                .as("按 userId 分指纹等于同角色的人互相命中不了，缓存就白做了")
                .hasSize(1);
    }

    @Test
    @DisplayName("阈值配错时落回 0.93：0 会让任何候选都命中")
    void badThresholdFallsBackToDefault() {
        assertThat(new CacheProperties(0.0, null).similarityThreshold())
                .isEqualTo(CacheProperties.DEFAULT_SIMILARITY_THRESHOLD);
        assertThat(new CacheProperties(1.5, null).similarityThreshold())
                .isEqualTo(CacheProperties.DEFAULT_SIMILARITY_THRESHOLD);
        assertThat(new CacheProperties(null, null).redisUri())
                .as("没配地址时落到本机 compose 的那个")
                .isEqualTo(CacheProperties.DEFAULT_REDIS_URI);
    }

    @Test
    @DisplayName("开关挂在整个装配上：关掉之后 Advisor 也不该留下")
    void disabledSwitchRemovesTheAdvisor() {
        // 存储用替身：真装配会在 afterPropertiesSet 里连 Redis，单测里没有那个实例。
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CacheAutoConfiguration.class))
                .withBean(SemanticCache.class, () -> mock(SemanticCache.class))
                .withBean(ModelProperties.class, () -> {
                    ModelProperties properties = new ModelProperties();
                    properties.getQwen().setApiKey("secret:qwen_api_key");
                    properties.getQwen().setBaseUrl("http://127.0.0.1:9/v1");
                    return properties;
                })
                // 句柄在属性里、真值在环境里（P08）：两处都写，走的才是生产那条路。
                .withPropertyValues(
                        "claw4j.model.qwen.api-key=secret:qwen_api_key",
                        "QWEN_API_KEY=test-key",
                        "claw4j.model.qwen.base-url=http://127.0.0.1:9/v1");

        // 先证明"开着时确实装了"。没有这一半，条件写错成"永不匹配"时，下半条照样绿
        runner.run(context -> assertThat(context)
                .as("默认开着：不配这个键就等于开着")
                .hasSingleBean(SemanticCacheAdvisor.class));

        runner.withPropertyValues("claw4j.cache.enabled=false").run(context -> assertThat(context)
                .as("关掉就该一个都不剩，包括 Advisor")
                .doesNotHaveBean(SemanticCacheAdvisor.class));
    }

    // ---------------------------------------------------------------- 人设与提示词

    /**
     * 人设与提示词这一组与缓存测的是同一件事：请求进入模型之前被改成了什么。缓存键就是那一步
     * 拼出来的系统提示词的哈希，所以拼装一错，命中的就是错的那一份。
     */
    @Test
    @DisplayName("每轮拼的系统提示词：人设 + 口径 + 时间，缺一样都不完整")
    void agentContextIsComposedPerTurn(@TempDir Path agents) throws IOException {
        Files.createDirectories(agents.resolve("glossary"));
        Files.writeString(agents.resolve("glossary/metrics.md"), "成交额 = sum(order_item.amount)");
        Files.writeString(agents.resolve("sales.md"),
                "---\nname: sales\ndescription: 销售助手\ntier: messaging\n"
                        + "glossary:\n  - glossary/metrics.md\n---\n\n你是销售侧的助手。\n");

        AgentProperties properties = new AgentProperties(agents.toString(), "sales", null, null, 0);
        AgentAutoConfiguration configuration = new AgentAutoConfiguration();
        PersonaCatalog.Catalog catalog = configuration.personaCatalog(properties);
        AgentContextAdvisor advisor = configuration.agentContextAdvisor(catalog, properties);

        AtomicReference<String> systemPrompt = new AtomicReference<>();
        advisor.adviseCall(
                ChatClientRequest.builder().prompt(new Prompt("上周卖得怎么样")).build(),
                capturingChain(systemPrompt));

        assertThat(advisor.persona().name()).isEqualTo("sales");
        assertThat(systemPrompt.get())
                .as("人设正文、口径、时间三样都在：%s", systemPrompt.get())
                .contains("你是销售侧的助手。")
                .contains("成交额 = sum(order_item.amount)")
                .contains("glossary/metrics.md")
                .contains("Asia/Shanghai")
                .doesNotContain("数据范围");
    }

    @Test
    @DisplayName("带身份时提示词里有数据范围：缓存的权限维度就靠这一个标识")
    void agentContextCarriesPermissionFingerprint() {
        AgentContextAdvisor advisor = contextAdvisor();

        AtomicReference<String> admin = new AtomicReference<>();
        advisor.adviseCall(
                ChatRequest.of(Principal.readOnly("u-1"), "上周卖得怎么样").toRequest(),
                capturingChain(admin));

        AtomicReference<String> guest = new AtomicReference<>();
        advisor.adviseCall(
                ChatRequest.of(Principal.of("u-2", "guest", Set.of("region"), Set.of(ToolPermission.READ)),
                        "上周卖得怎么样").toRequest(),
                capturingChain(guest));

        assertThat(admin.get())
                .as("权限不同 → 提示词不同 → 框架算出的 contextHash 不同 → 缓存不互相命中")
                .contains("数据范围")
                .isNotEqualTo(guest.get());
        assertThat(guest.get()).contains("数据范围");
    }

    @Test
    @DisplayName("流式那一侧也走同一次拼装：不是只有非流式才有人设")
    void agentContextIsComposedForStreaming(@TempDir Path agents) throws IOException {
        Files.writeString(agents.resolve("sales.md"),
                "---\nname: sales\ndescription: 销售助手\n---\n\n你是销售侧的助手。\n");

        AgentProperties properties = new AgentProperties(agents.toString(), "sales", null, null, 0);
        AgentAutoConfiguration configuration = new AgentAutoConfiguration();
        AgentContextAdvisor advisor = configuration.agentContextAdvisor(
                configuration.personaCatalog(properties), properties);

        AtomicReference<String> systemPrompt = new AtomicReference<>();
        advisor.adviseStream(
                ChatClientRequest.builder().prompt(new Prompt("你好")).build(),
                capturingStreamChain(systemPrompt));

        assertThat(systemPrompt.get()).contains("你是销售侧的助手。");
    }

    @Test
    @DisplayName("没配人设目录：用内置那份，没有口径——不是「没人设」，是一份明确的最小默认")
    void builtinPersonaWhenNoDirectory() {
        PersonaCatalog.Catalog catalog = new AgentAutoConfiguration()
                .personaCatalog(AgentProperties.defaults());

        assertThat(catalog.selected().name()).isEqualTo(BuiltinPersona.NAME);
        assertThat(catalog.selected().systemPrompt()).isNotBlank();
        assertThat(catalog.glossary()).isEmpty();
    }

    // ---------------------------------------------------------------- 夹具

    private static AgentContextAdvisor contextAdvisor() {
        PersonaCatalog.Catalog catalog = new AgentAutoConfiguration()
                .personaCatalog(AgentProperties.defaults());
        return new AgentContextAdvisor(catalog.selected(), List.of(),
                Clock.system(ZoneId.of("Asia/Shanghai")));
    }

    private static InboundMessage message(String userId, String text) {
        return new InboundMessage(Principal.readOnly(userId), "chat-1", text);
    }

    /** 带上身份的一轮请求；缓存键里的权限维度就是从 {@code ContextKeys.PRINCIPAL} 取的。 */
    private record ChatRequest(Principal who, String text) {

        static ChatRequest of(Principal who, String text) {
            return new ChatRequest(who, text);
        }

        ChatClientRequest toRequest() {
            return ChatClientRequest.builder()
                    .prompt(new Prompt(text))
                    .context(Map.of(ContextKeys.PRINCIPAL, who))
                    .build();
        }
    }

    /** 一条带身份的消息，用来构造"换角色"的那一轮。 */
    private record Message(String userId, String role, Set<String> tables, Set<String> actions) {

        InboundMessage toInbound(String text) {
            return new InboundMessage(Principal.of(userId, role, tables, actions), "chat-1", text);
        }
    }

    /** 一套装好的链路：假模型 + 内存替身 + 框架的缓存 Advisor + 人设 Advisor。 */
    private static final class Fixture {

        private final FakeEmbedding embedding = new FakeEmbedding();

        private final InMemorySemanticCache cache = new InMemorySemanticCache(embedding);

        private final FixedChatModel model =
                new FixedChatModel(prompt -> ChatPipelineTest.answer(ANSWER), List.of());

        private final ChatClientAgentPort port;

        Fixture() {
            SemanticCacheAdvisor advisor = new SemanticCacheAdvisor(cache,
                    CacheAutoConfiguration.ADVISOR_ORDER,
                    reactor.core.scheduler.Schedulers.boundedElastic());
            ChatClient chat = ChatPipelineTest.clientBuilder(model)
                    .defaultAdvisors(contextAdvisor(), advisor)
                    .build();
            this.port = new ChatClientAgentPort(chat);
        }
    }

    /**
     * 假向量模型：按文本返回一个预设方向的单位向量。
     *
     * <p>角度就是实测出来的余弦值——把"分数怎么用"与"分数怎么算"分开，
     * 前者是缓存的职责，后者是通义的事。
     */
    private static final class FakeEmbedding implements EmbeddingModel {

        private static final Map<String, float[]> VECTORS = Map.of(
                EAST_LAST_WEEK, unit(1.0),
                // 实测 0.9372：同义改写，高于 0.93 的默认阈值
                EAST_LAST_WEEK_REWORDED, unit(0.9372),
                // 实测 0.8968：换区域。意思不同，分数也不该过阈值
                SOUTH_LAST_WEEK, unit(0.8968));

        @Override
        public float[] embed(String text) {
            return VECTORS.getOrDefault(text, unit(0.0));
        }

        @Override
        public float[] embed(Document document) {
            return embed(document.getText());
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            throw new UnsupportedOperationException("测试只走 embed(String)");
        }

        /** cos(x, [1,0]) 恰好等于 target，于是"预设值"与"断言里那个数"是同一个。 */
        private static float[] unit(double target) {
            if (target >= 1.0) {
                return new float[] {1f, 0f};
            }
            return new float[] {(float) target, (float) Math.sqrt(1 - target * target)};
        }
    }

    /**
     * 内存替身：实现框架的 {@link SemanticCache} 接口，判定口径与
     * {@code DefaultSemanticCache} 一致——上下文哈希相同 + 相似度过阈值，没有第二维。
     *
     * <p>它不测"Redis 里怎么存"（那是官方的实现），只测"链路上发生了什么"。
     */
    private static final class InMemorySemanticCache implements SemanticCache {

        /** key = 上下文哈希。 */
        private final Map<String, List<Entry>> byContext = new HashMap<>();

        private final FakeEmbedding embedding;

        InMemorySemanticCache(FakeEmbedding embedding) {
            this.embedding = embedding;
        }

        @Override
        public Optional<ChatResponse> get(String query, String contextHash) {
            if (contextHash == null || contextHash.isBlank()) {
                return Optional.empty();
            }
            float[] vector = embedding.embed(query);
            return best(byContext.getOrDefault(contextHash, List.of()), vector)
                    .filter(entry -> entry.similarity() >= CacheProperties.DEFAULT_SIMILARITY_THRESHOLD)
                    .map(entry -> ChatPipelineTest.answer(entry.answer()));
        }

        @Override
        public Optional<ChatResponse> get(String query) {
            return get(query, null);
        }

        @Override
        public void set(String query, ChatResponse response, String contextHash) {
            if (contextHash == null || contextHash.isBlank()) {
                return;
            }
            String answer = response.getResult().getOutput().getText();
            if (answer == null || answer.isBlank()) {
                return;
            }
            byContext.computeIfAbsent(contextHash, key -> new ArrayList<>())
                    .add(new Entry(answer, embedding.embed(query)));
        }

        @Override
        public void set(String query, ChatResponse response) {
            // 没有上下文就不写。
        }

        @Override
        public void set(String query, ChatResponse response, Duration timeToLive) {
            // 没有上下文就不写。
        }

        @Override
        public void clear() {
            byContext.clear();
        }

        @Override
        public VectorStore getStore() {
            throw new UnsupportedOperationException("内存替身没有 VectorStore");
        }

        private static Optional<Entry> best(List<Entry> entries, float[] vector) {
            return entries.stream()
                    .map(entry -> entry.withSimilarity(cosine(entry.vector(), vector)))
                    .max((left, right) -> Double.compare(left.similarity(), right.similarity()));
        }

        private static double cosine(float[] left, float[] right) {
            double dot = 0;
            double leftNorm = 0;
            double rightNorm = 0;
            for (int i = 0; i < left.length; i++) {
                dot += left[i] * right[i];
                leftNorm += left[i] * left[i];
                rightNorm += right[i] * right[i];
            }
            return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
        }

        private record Entry(String answer, float[] vector, double similarity) {

            Entry(String answer, float[] vector) {
                this(answer, vector, 0);
            }

            Entry withSimilarity(double value) {
                return new Entry(answer, vector, value);
            }
        }
    }

    /** 只做一件事的链：把 advisor 改写后的请求抓下来，不真的往下走。 */
    private static CallAdvisorChain capturingChain(AtomicReference<String> captured) {
        return new CallAdvisorChain() {
            @Override
            public ChatClientResponse nextCall(ChatClientRequest request) {
                captured.set(systemTextOf(request));
                return null;
            }

            @Override
            public List<CallAdvisor> getCallAdvisors() {
                return List.of();
            }

            @Override
            public CallAdvisorChain copy(CallAdvisor advisor) {
                return this;
            }
        };
    }

    private static StreamAdvisorChain capturingStreamChain(AtomicReference<String> captured) {
        return new StreamAdvisorChain() {
            @Override
            public Flux<ChatClientResponse> nextStream(ChatClientRequest request) {
                captured.set(systemTextOf(request));
                return Flux.empty();
            }

            @Override
            public List<StreamAdvisor> getStreamAdvisors() {
                return List.of();
            }

            @Override
            public StreamAdvisorChain copy(StreamAdvisor advisor) {
                return this;
            }
        };
    }

    /** 改写后的第一条 system 消息。没有 system 时返回空串，让断言自己去红。 */
    private static String systemTextOf(ChatClientRequest request) {
        return request.prompt().getInstructions().stream()
                .filter(message -> message instanceof SystemMessage)
                .map(message -> ((SystemMessage) message).getText())
                .findFirst()
                .orElse("");
    }
}

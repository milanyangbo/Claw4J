package io.github.claw4j.agent;

import io.github.claw4j.agent.cache.CacheAdvisor;
import io.github.claw4j.agent.cache.CacheAutoConfiguration;
import io.github.claw4j.agent.cache.CacheProperties;
import io.github.claw4j.agent.cache.SemanticCache;
import io.github.claw4j.agent.cache.SemanticCacheCleanup;
import io.github.claw4j.agent.model.ModelProperties;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 语义缓存：同一个问题第二遍不打模型，以及**哪些情况必须不命中**。
 *
 * <p>这个功能的风险不对称：命中了正确的缓存，省一次调用；命中了错误的缓存，
 * 用户会拿到另一个问题的答案，而且它读起来完全正常。所以这里的用例里
 * "不该命中"的比"该命中"的多。
 *
 * <p>向量由一个按文本返回预设值的假模型给（见 {@link FakeEmbedding}），
 * 相似度是**实测值的复刻**：0.9372 是同义改写，0.9950 是"上周/上上周"，
 * 0.8968 是"华东/华南"。用真模型跑这几条要花钱、要联网，还会随模型版本漂移；
 * 而这里要验的是"分数怎么用"，不是"分数算得准不准"。
 */
class SemanticCacheTest {

    private static final String EAST_LAST_WEEK = "上周华东成交额多少";

    private static final String EAST_LAST_WEEK_REWORDED = "华东上周卖了多少";

    private static final String SOUTH_LAST_WEEK = "上周华南成交额多少";

    private static final String EAST_WEEK_BEFORE = "上上周华东成交额多少";

    /** 三周前。它比「上上周」多一个「上」字，意思却是另一段时间。 */
    private static final String THREE_WEEKS_BEFORE = "上上上周华东成交额多少";

    private static final String ANSWER = "上周华东区成交 128400 元";

    @Test
    @DisplayName("同一个问题第二遍不再打模型，回答带「（同上次）」")
    void secondTimeDoesNotCallTheModel() {
        Fixture fixture = new Fixture();
        fixture.port.reply(message("u-1", EAST_LAST_WEEK));
        String second = fixture.port.reply(message("u-1", EAST_LAST_WEEK)).text();

        assertThat(fixture.model.prompts())
                .as("缓存命中的定义就是「这次没有模型调用」，不能只是「快了一点」")
                .hasSize(1);
        assertThat(second).contains(ANSWER).contains(CacheAdvisor.MARKER);
        assertThat(fixture.registry.get(CacheAdvisor.HITS).counter().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("换个说法问同一件事也命中：这才是语义缓存相对于精确匹配的全部意义")
    void rewordedQuestionHits() {
        Fixture fixture = new Fixture();
        fixture.port.reply(message("u-1", EAST_LAST_WEEK));
        String second = fixture.port.reply(message("u-1", EAST_LAST_WEEK_REWORDED)).text();

        assertThat(fixture.model.prompts()).hasSize(1);
        assertThat(second).contains(ANSWER).contains(CacheAdvisor.MARKER);
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
        assertThat(second).doesNotContain(CacheAdvisor.MARKER);
        assertThat(fixture.misses("below-threshold")).isEqualTo(1);
    }

    @Test
    @DisplayName("「上周」与「上上周」相似度 0.9950，但实体签名不同，不在一个上下文里")
    void differentTimeWordMissesEvenThoughItLooksSimilar() {
        Fixture fixture = new Fixture();
        fixture.port.reply(message("u-1", EAST_LAST_WEEK));
        fixture.port.reply(message("u-1", EAST_WEEK_BEFORE));

        assertThat(fixture.model.prompts())
                .as("0.9950 比同义改写的 0.9372 还高，任何单一阈值都挡不住这一对")
                .hasSize(2);
        assertThat(fixture.misses("different-entities")).isEqualTo(1);
    }

    @Test
    @DisplayName("「上上上周」不能被当成「上上周」：差一个「上」字也是另一段时间")
    void threeWeeksAgoIsNotTwoWeeksAgo() {
        Fixture fixture = new Fixture();
        fixture.port.reply(message("u-1", EAST_WEEK_BEFORE));
        String third = fixture.port.reply(message("u-1", THREE_WEEKS_BEFORE)).text();

        assertThat(third)
                .as("三周前拿到两周前的数，读起来完全正常——这是这个功能最坏的姿势")
                .doesNotContain(CacheAdvisor.MARKER);
        assertThat(fixture.misses("different-entities"))
                .as("挡它的必须是上下文，不是分数：这两句的向量在这里故意给成一样")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("实体签名只看时间词与数字：换个说法问同一段时间，签名相同")
    void entitySignatureSeparatesTimeWindows() {
        assertThat(CacheAdvisor.entitySignature(EAST_LAST_WEEK))
                .as("同义改写不该换一个上下文，否则缓存永远命不中")
                .isEqualTo(CacheAdvisor.entitySignature(EAST_LAST_WEEK_REWORDED))
                .isNotEqualTo(CacheAdvisor.entitySignature(EAST_WEEK_BEFORE));

        assertThat(CacheAdvisor.entitySignature("3 月华东成交额"))
                .as("月份是数字，换月份就是换上下文")
                .isNotEqualTo(CacheAdvisor.entitySignature("4 月华东成交额"));

        assertThat(CacheAdvisor.entitySignature("华东成交额多少"))
                .as("没写时间的问题自成一类，不会跟任何一个具体时间窗混在一起")
                .isNotEqualTo(CacheAdvisor.entitySignature(EAST_LAST_WEEK));

        assertThat(CacheAdvisor.entitySignature("你好，你能做什么"))
                .as("闲聊也有签名：空集也是一种取值，不是 null")
                .isEqualTo(CacheAdvisor.entitySignature("你能干什么"));

        assertThat(CacheAdvisor.entitySignature("近7天华东成交额"))
                .as("「近 7 天」与「近7天」是同一个时间窗：不去掉 token 内的空白就会算出两个签名，"
                        + "表现是同一句话带空格重问永远命不中")
                .isEqualTo(CacheAdvisor.entitySignature("近 7 天华东成交额"));
    }

    @Test
    @DisplayName("换一个角色问同一句话：miss，且原因是「权限不同」")
    void anotherRoleMissesWithItsOwnReason() {
        Fixture fixture = new Fixture();
        fixture.port.reply(message("u-1", EAST_LAST_WEEK));
        // guest 读不到 sales_order，同一个问题的答案本来就不同
        String second = fixture.port.reply(new Message("u-2", "guest", Set.of("region"),
                Set.of(ToolPermission.READ)).toInbound(EAST_LAST_WEEK)).text();

        assertThat(fixture.model.prompts()).hasSize(2);
        assertThat(second).doesNotContain(CacheAdvisor.MARKER);
        assertThat(fixture.misses("different-permission"))
                .as("只写 no-candidate 的话，看起来像缓存压根没工作")
                .isEqualTo(1);
        assertThat(fixture.misses("no-candidate"))
                .as("no-candidate 只该出现在第一轮（缓存还是空的），第二轮不是它")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("权限指纹只看 tables 与 actions：同角色换个人照样命中")
    void fingerprintIgnoresUserId() {
        Fixture fixture = new Fixture();
        fixture.port.reply(message("u-1", EAST_LAST_WEEK));
        fixture.port.reply(message("u-9", EAST_LAST_WEEK));

        assertThat(fixture.model.prompts())
                .as("按 userId 分指纹等于同角色的人互相命中不了，缓存就白做了")
                .hasSize(1);
    }

    @Test
    @DisplayName("带写动作的一轮不缓存：第二次问仍然打模型")
    void sideEffectfulTurnIsNotCached() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CacheAdvisor advisor = advisor(new InMemorySemanticCache(), permissionRegistry(), registry);

        assertThat(advisor.sideEffectFree(Set.of("querySales")))
                .as("只读动作的结果可以复用")
                .isTrue();
        assertThat(advisor.sideEffectFree(Set.of()))
                .as("一次工具都没调的纯问答当然可以缓存")
                .isTrue();
        assertThat(advisor.sideEffectFree(Set.of("propose_script")))
                .as("提交脚本是写动作，把它那一轮的答案存下来给别人复用是错的")
                .isFalse();
        assertThat(advisor.sideEffectFree(Set.of("unknownTool")))
                .as("没登记权限的工具按有副作用处理：没人评估过它会碰到什么")
                .isFalse();
    }

    @Test
    @DisplayName("向量算不出来就退化成没命中，不把这一轮搞挂")
    void embeddingFailureDegradesToMiss() {
        Fixture fixture = new Fixture();
        fixture.embedding.failing = true;

        String answer = fixture.port.reply(message("u-1", EAST_LAST_WEEK)).text();

        assertThat(answer).isEqualTo(ANSWER);
        assertThat(fixture.misses("embedding-failed")).isEqualTo(1);
        assertThat(fixture.cache.stored).as("算不出向量就不该存，否则以后也匹配不上").isEmpty();
    }

    @Test
    @DisplayName("缓存查询挂了也退化成没命中：缓存没资格让一轮对话失败")
    void cacheReadFailureDegradesToMiss() {
        Fixture fixture = new Fixture();
        fixture.cache.failOnRead = true;

        assertThat(fixture.port.reply(message("u-1", EAST_LAST_WEEK)).text())
                .as("库连不上时用户仍然该拿到回答，哪怕这一轮多花一次模型调用")
                .isEqualTo(ANSWER);
        assertThat(fixture.model.prompts())
                .as("读不到缓存就该照常打模型，一次")
                .hasSize(1);
        assertThat(fixture.misses("cache-failed"))
                .as("和 embedding-failed 分开记：混在一起就分不清是向量服务还是库坏了")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("缓存写不进去只影响省成本，不影响这一轮的回答")
    void storeFailureDoesNotBreakTheAnswer() {
        Fixture fixture = new Fixture();
        fixture.cache.failOnStore = true;

        assertThat(fixture.port.reply(message("u-1", EAST_LAST_WEEK)).text()).isEqualTo(ANSWER);
    }

    @Test
    @DisplayName("阈值配错时落回 0.93：0 会让任何候选都命中")
    void badThresholdFallsBackToDefault() {
        assertThat(new CacheProperties(0.0, null, null, null, null).similarityThreshold())
                .isEqualTo(CacheProperties.DEFAULT_SIMILARITY_THRESHOLD);
        assertThat(new CacheProperties(1.5, null, null, null, null).similarityThreshold())
                .isEqualTo(CacheProperties.DEFAULT_SIMILARITY_THRESHOLD);
        assertThat(CacheProperties.defaults().timeToLive())
                .as("默认 1 小时而不是 24 小时：问的是「上周」，答案随日历翻页而变")
                .isEqualTo(CacheProperties.DEFAULT_TIME_TO_LIVE);
    }

    @Test
    @DisplayName("清理间隔配成 0 直接拒绝启动：归一回默认会让调度器拿到另一个值")
    void cleanupIntervalMustBePositive() {
        assertThatThrownBy(() -> new CacheProperties(null, null, Duration.ZERO, null, null))
                .as("调度器读的是 @Scheduled 占位符里的原文，这里悄悄改成 1 小时只会让它拿到 0，"
                        + "而 fixedDelay=0 是「上一轮结束立刻下一轮」的循环")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cleanup-interval");
        assertThatThrownBy(() -> new CacheProperties(null, null, Duration.ofHours(-1), null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(CacheProperties.defaults().cleanupInterval())
                .as("键缺失才落默认值——两种没配好必须分开处理：缺失有合理默认，写 0 是写错了")
                .isEqualTo(CacheProperties.DEFAULT_CLEANUP_INTERVAL);
    }

    @Test
    @DisplayName("开关挂在整个装配上：关掉之后清理任务也不该留下")
    void disabledSwitchRemovesEveryCacheBean() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CacheAutoConfiguration.class))
                // 装配前提是"有库、有向量模型"，这些都给它，于是下面能关掉只能是开关本身
                .withBean(DataSource.class, () -> mock(DataSource.class))
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean(ModelProperties.class, ModelProperties::new)
                .withPropertyValues(
                        "claw4j.model.qwen.api-key=test-key",
                        "claw4j.model.qwen.base-url=http://127.0.0.1:9/v1");

        // 先证明"开着时确实装了"。没有这一半，条件写错成"永不匹配"时，下半条照样绿
        runner.run(context -> assertThat(context)
                .as("默认开着：不配这个键就等于开着")
                .hasSingleBean(CacheAdvisor.class)
                .hasSingleBean(SemanticCacheCleanup.class));

        runner.withPropertyValues("claw4j.cache.enabled=false").run(context -> assertThat(context)
                .as("只关掉 Advisor 的话，清理任务照旧每小时删一次，而配置看起来已经生效了")
                .doesNotHaveBean(CacheAdvisor.class)
                .doesNotHaveBean(SemanticCacheCleanup.class)
                .doesNotHaveBean(SemanticCache.class));
    }

    /** 一轮请求的三种身份：默认只读、guest 只读一张表、换个人但同角色。 */
    private static InboundMessage message(String userId, String text) {
        return new InboundMessage(Principal.readOnly(userId), "chat-1", text);
    }

    private static CacheAdvisor advisor(SemanticCache cache, ToolPermissionRegistry permissions,
                                        SimpleMeterRegistry registry) {
        return new CacheAdvisor(cache, new FakeEmbedding(), CacheProperties.defaults(),
                List.of(permissions), registry);
    }

    private static ToolPermissionRegistry permissionRegistry() {
        return ToolPermissionRegistry.of(Map.of(
                "querySales", ToolPermission.read("sales_order"),
                "propose_script", ToolPermission.of("draft_script")));
    }

    /** 一条带身份的消息，用来构造"换角色"的那一轮。 */
    private record Message(String userId, String role, Set<String> tables, Set<String> actions) {

        InboundMessage toInbound(String text) {
            return new InboundMessage(Principal.of(userId, role, tables, actions), "chat-1", text);
        }
    }

    /** 一套装好的链路：假模型 + 假缓存 + 真 Advisor。 */
    private static final class Fixture {

        private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

        private final InMemorySemanticCache cache = new InMemorySemanticCache();

        private final FakeEmbedding embedding = new FakeEmbedding();

        private final FixedChatModel model = new FixedChatModel(ANSWER);

        private final ChatClientAgentPort port;

        Fixture() {
            CacheAdvisor advisor = new CacheAdvisor(cache, embedding, CacheProperties.defaults(),
                    List.of(permissionRegistry()), registry);
            ChatClient chat = ChatClient.builder(model, ObservationRegistry.NOOP, null, null,
                            ToolCallingAdvisor.builder()
                                    .toolCallingManager(ToolCallBudget.toolCallingManager(ObservationRegistry.NOOP)))
                    .defaultAdvisors(advisor)
                    .build();
            this.port = new ChatClientAgentPort(chat);
        }

        InboundMessage message(String userId, String text) {
            return SemanticCacheTest.message(userId, text);
        }

        double misses(String reason) {
            var counter = registry.find(CacheAdvisor.MISSES).tag("reason", reason).counter();
            return counter == null ? 0 : counter.count();
        }
    }

    /**
     * 假向量模型：按文本返回一个预设方向的单位向量。
     *
     * <p>角度就是实测出来的余弦值——把"分数怎么用"与"分数怎么算"分开，
     * 前者是这个 Advisor 的职责，后者是通义的事。
     */
    private static final class FakeEmbedding implements EmbeddingModel {

        private static final Map<String, float[]> VECTORS = Map.of(
                EAST_LAST_WEEK, unit(1.0),
                // 实测 0.9372：同义改写。低于票面原定的 0.95，高于本次的 0.93
                EAST_LAST_WEEK_REWORDED, unit(0.9372),
                // 实测 0.8968：换区域。意思不同，分数也不该过阈值
                SOUTH_LAST_WEEK, unit(0.8968),
                // 实测 0.9950：只差一个「上」字，意思完全不同
                EAST_WEEK_BEFORE, unit(0.9950),
                // 故意给成和「上上周」**一模一样**的向量（余弦 1.0，比任何阈值都高）：
                // 三周前与两周前的真实余弦没有单独实测过，这里不需要它——
                // 要证明的是"签名把它挡住了"，那就让分数完全挡不住
                THREE_WEEKS_BEFORE, unit(0.9950));

        private boolean failing;

        @Override
        public float[] embed(String text) {
            if (failing) {
                throw new IllegalStateException("向量服务 503");
            }
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
     * 内存版缓存。相似度用余弦自己算，与 pgvector 的 {@code 1 - (a <=> b)} 同口径。
     *
     * <p>不连库：这里要验的是"拿到分数之后怎么办"，真库那条路径用一次真链路验证覆盖，
     * 换来的好处是用例跑起来不需要容器、也没有时序问题。
     */
    private static final class InMemorySemanticCache implements SemanticCache {

        /** key = 权限指纹 + {@code |} + 实体签名，即一个完整的上下文。 */
        private final Map<String, List<Entry>> byContext = new HashMap<>();

        private final List<String> stored = new ArrayList<>();

        private boolean failOnStore;

        private boolean failOnRead;

        @Override
        public Optional<Candidate> nearest(String fingerprint, String entities, float[] embedding) {
            if (failOnRead) {
                throw new IllegalStateException("库连不上");
            }
            return best(byContext.getOrDefault(context(fingerprint, entities), List.of()), embedding);
        }

        @Override
        public Optional<Candidate> nearestInFingerprint(String fingerprint, float[] embedding) {
            return best(byContext.entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith(fingerprint + "|"))
                    .flatMap(entry -> entry.getValue().stream())
                    .toList(), embedding);
        }

        @Override
        public Optional<Candidate> nearestAnyFingerprint(float[] embedding) {
            return best(byContext.values().stream().flatMap(List::stream).toList(), embedding);
        }

        @Override
        public void store(String fingerprint, String entities, String question, float[] embedding,
                          String answer) {
            if (failOnStore) {
                throw new IllegalStateException("库连不上");
            }
            byContext.computeIfAbsent(context(fingerprint, entities), key -> new ArrayList<>())
                    .add(new Entry(question, answer, embedding));
            stored.add(question);
        }

        @Override
        public int deleteExpired() {
            return 0;
        }

        private static String context(String fingerprint, String entities) {
            return fingerprint + "|" + entities;
        }

        private static Optional<Candidate> best(List<Entry> entries, float[] embedding) {
            return entries.stream()
                    .map(entry -> new Candidate(entry.question, entry.answer, cosine(entry.vector, embedding)))
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

        private record Entry(String question, String answer, float[] vector) {
        }
    }
}

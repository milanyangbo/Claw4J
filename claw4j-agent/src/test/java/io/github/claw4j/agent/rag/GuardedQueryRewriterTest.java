package io.github.claw4j.agent.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 改写保护层的单测。
 *
 * <p>这一层只做框架不做的四件事：跳过、超时、校验、清洗。所以要验的也是这四件——尤其是
 * <b>四种失败都回落原问题</b>：跳过、超时、丢标识、delegate 抛错。任何一条破了，
 * 这个默认关的功能一旦打开就是"检索不到东西"的单点故障。
 *
 * <p>改写本身（怎么拼 Prompt、怎么调模型）在框架里，测它没有意义；这里用一个记调用次数的
 * 假 transformer 顶上。
 */
class GuardedQueryRewriterTest {

    private static final Duration ONE_SECOND = Duration.ofSeconds(1);

    @Test
    @DisplayName("数值型问题一次模型都不调：知道改写了会坏事，就别发请求")
    void measurementQuestionsSkipTheModel() {
        RecordingDelegate delegate = new RecordingDelegate(q -> q.mutate().text("不该被用上").build());
        GuardedQueryRewriter rewriter = new GuardedQueryRewriter(() -> delegate,
                guard(), ONE_SECOND);

        for (String question : List.of("违约金按万分之五算吗", "押金 500 元什么时候退",
                "7 个工作日是几天", "20 万的项目验收后要付多少比例", "2027 年的价格表是多少",
                "买一年延保要多少钱")) {
            assertThat(rewriter.transform(query(question)).text()).as("原样返回：%s", question)
                    .isEqualTo(question);
        }
        assertThat(delegate.calls).as("跳过必须是零调用，不是调完再丢掉").isEmpty();
    }

    @Test
    @DisplayName("标识型问题照样改写：型号不是数值，靠结果校验兜")
    void identifierQuestionsAreRewritten() {
        RecordingDelegate delegate = new RecordingDelegate(
                q -> q.mutate().text("SKU-2024-M6 规格 尺寸 每卷张数").build());
        GuardedQueryRewriter rewriter = new GuardedQueryRewriter(() -> delegate,
                guard(), ONE_SECOND);

        assertThat(rewriter.transform(query("SKU-2024-M6 的尺寸和每卷张数是多少？")).text())
                .isEqualTo("SKU-2024-M6 规格 尺寸 每卷张数");
        assertThat(delegate.calls).hasSize(1);
    }

    @Test
    @DisplayName("改写丢了标识就回落：改掉型号等于检索到另一款产品的文档，而且看不出来")
    void rewriteThatDropsIdentifierFallsBack() {
        RecordingDelegate delegate = new RecordingDelegate(
                q -> q.mutate().text("桌面机价格与型号对比").build());
        GuardedQueryRewriter rewriter = new GuardedQueryRewriter(() -> delegate,
                guard(), ONE_SECOND);

        assertThat(rewriter.transform(query("A2 桌面机多少钱？")).text())
                .as("结果里没有 A2：宁可这次不改写")
                .isEqualTo("A2 桌面机多少钱？");
    }

    @Test
    @DisplayName("标识比对不分大小写：模型把 sku-2024 写成 SKU-2024，那是同一样东西")
    void identifierMatchIgnoresCase() {
        RecordingDelegate delegate = new RecordingDelegate(
                q -> q.mutate().text("SKU-2024 库存数量").build());
        GuardedQueryRewriter rewriter = new GuardedQueryRewriter(() -> delegate,
                guard(), ONE_SECOND);

        assertThat(rewriter.transform(query("sku-2024 还剩多少")).text()).isEqualTo("SKU-2024 库存数量");
    }

    @Test
    @DisplayName("标准号被保留：ISO 9001 里的 9001 不是可近似化的量")
    void standardNumberIsKept() {
        RecordingDelegate delegate = new RecordingDelegate(
                q -> q.mutate().text("ISO 9001 质量管理体系认证").build());
        GuardedQueryRewriter rewriter = new GuardedQueryRewriter(() -> delegate,
                guard(), ONE_SECOND);

        assertThat(rewriter.transform(query("你们有 ISO 9001 认证吗？")).text())
                .isEqualTo("ISO 9001 质量管理体系认证");
    }

    @Test
    @DisplayName("模型输出带标签或解释时清掉：那些字进了检索就成了噪声词")
    void outputIsCleaned() {
        RecordingDelegate delegate = new RecordingDelegate(
                q -> q.mutate().text("改写：「备机押金的退还流程」\n说明：…").build());
        GuardedQueryRewriter rewriter = new GuardedQueryRewriter(() -> delegate,
                guard(), ONE_SECOND);

        assertThat(rewriter.transform(query("备机押金怎么退")).text()).isEqualTo("备机押金的退还流程");
    }

    @Test
    @DisplayName("delegate 抛错就按原问题检索：改写是增强，不该让这一轮检索失败")
    void delegateFailureFallsBackToOriginal() {
        RecordingDelegate delegate = new RecordingDelegate(q -> {
            throw new IllegalStateException("502");
        });
        GuardedQueryRewriter rewriter = new GuardedQueryRewriter(() -> delegate,
                guard(), ONE_SECOND);

        assertThat(rewriter.transform(query("没白纸黑字写能退吗")).text()).isEqualTo("没白纸黑字写能退吗");
    }

    @Test
    @DisplayName("超过超时就按原问题检索：用户等的是首字，不能陪一次改写等下去")
    void slowDelegateFallsBackToOriginal() {
        RecordingDelegate delegate = new RecordingDelegate(q -> {
            try {
                Thread.sleep(2_000);
            }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return q.mutate().text("慢死了").build();
        });
        GuardedQueryRewriter rewriter = new GuardedQueryRewriter(() -> delegate,
                guard(), Duration.ofMillis(100));

        long started = System.nanoTime();
        Query result = rewriter.transform(query("没白纸黑字写能退吗"));
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        assertThat(result.text()).isEqualTo("没白纸黑字写能退吗");
        assertThat(elapsedMillis).as("不能在超时值之后还等两秒").isLessThan(1_500);
    }

    @Test
    @DisplayName("delegate 交回空文本时回落：模型只回了空白，不能拿空白去检索")
    void blankRewriteFallsBack() {
        RecordingDelegate delegate = new RecordingDelegate(q -> q.mutate().text("   ").build());
        GuardedQueryRewriter rewriter = new GuardedQueryRewriter(() -> delegate,
                guard(), ONE_SECOND);

        assertThat(rewriter.transform(query("没白纸黑字写能退吗")).text()).isEqualTo("没白纸黑字写能退吗");
    }

    @Test
    @DisplayName("null 与空查询直接放过：不去构造 transformer")
    void blankQueryIsNotRewritten() {
        AtomicInteger built = new AtomicInteger();
        GuardedQueryRewriter rewriter = new GuardedQueryRewriter(() -> {
            built.incrementAndGet();
            return new RecordingDelegate(q -> q);
        }, guard(), ONE_SECOND);

        assertThat(rewriter.transform(null)).isNull();
        assertThat(built).hasValue(0);
    }

    @Test
    @DisplayName("真正的 transformer 只构造一次：Supplier 是延迟解析，不是每次新建")
    void delegateIsBuiltOnce() {
        AtomicInteger built = new AtomicInteger();
        RecordingDelegate delegate = new RecordingDelegate(q -> q.mutate().text("改写结果").build());
        GuardedQueryRewriter rewriter = new GuardedQueryRewriter(() -> {
            built.incrementAndGet();
            return delegate;
        }, guard(), ONE_SECOND);

        rewriter.transform(query("第一个问题"));
        rewriter.transform(query("第二个问题"));

        assertThat(built).as("解析模型有启动开销，每轮重建等于把那笔开销摊到每次提问上").hasValue(1);
    }

    @Test
    @DisplayName("与框架的 RewriteQueryTransformer 真的能拼上：{target} 与 {query} 都被替换")
    void worksWithFrameworkTransformer() {
        ChatModel model = mock(ChatModel.class);
        AtomicReference<Prompt> captured = new AtomicReference<>();
        // getOptions 必须给值：ChatClient 内部会拿它 mutate 一份再发请求，返回 null 就是 NPE。
        // 这是单测的假模型才有的问题——真实模型的 ChatModel 不会返回 null。
        when(model.getOptions()).thenReturn(ChatOptions.builder().build());
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            captured.set(invocation.getArgument(0));
            return new ChatResponse(List.of(new Generation(new AssistantMessage("退货处理规则"))));
        });
        QueryTransformer delegate = RewriteQueryTransformer.builder()
                .chatClientBuilder(ChatClient.builder(model))
                .promptTemplate(new PromptTemplate("目标：{target}\n问题：{query}"))
                .targetSearchSystem("知识库")
                .build();
        GuardedQueryRewriter rewriter = new GuardedQueryRewriter(() -> delegate,
                guard(), ONE_SECOND);

        // 先单独跑一次 delegate：这条不通过说明是假模型没接上，与本类的保护逻辑无关。
        assertThat(delegate.transform(query("没白纸黑字写能退吗")).text())
                .as("框架那一层要先是通路的")
                .isEqualTo("退货处理规则");

        Query result = rewriter.transform(query("没白纸黑字写能退吗"));

        assertThat(result.text()).as("框架给的那句话要原样传出来").isEqualTo("退货处理规则");
        assertThat(captured.get().getContents())
                .as("这条断言是给配置项的：target 与 query 都必须进到发给模型的提示词里")
                .contains("目标：知识库")
                .contains("问题：没白纸黑字写能退吗");
    }

    /**
     * 部署侧那两条正则的取值（与 {@code application.yml} 一致）。
     *
     * <p>常量放在测试里而不是引用代码里的某个字段，是因为代码里<b>没有</b>这份词表：它是配置，
     * 不是实现。测试验的是"这份配置在编排层的行为"，换一份正则这些断言就该跟着变。
     */
    private static final String SKIP = "百分之|万分之|千分之"
            + "|\\d+\\s*(元|块钱?|天|日|个工作日|个?月|年|%|％|份|件|台|倍|折|次|小时|分钟|秒|万|千)"
            + "|[零一二三四五六七八九十百千万亿]+\\s*(元|块|天|日|个工作日|%|％|份|件|台|倍|折|年)";

    private static final String KEEP = "[A-Za-z0-9]+(?:[._-][A-Za-z0-9]+)*";

    private static RewriteGuard guard() {
        return new PatternGuard(Pattern.compile(SKIP), Pattern.compile(KEEP));
    }

    private static Query query(String text) {
        return new Query(text, new ArrayList<>(), Map.of());
    }

    /** 记录被调用的问题、按给定行为返回的假 transformer。 */
    private static final class RecordingDelegate implements QueryTransformer {

        private final java.util.function.UnaryOperator<Query> behaviour;

        private final List<String> calls = new ArrayList<>();

        private RecordingDelegate(java.util.function.UnaryOperator<Query> behaviour) {
            this.behaviour = behaviour;
        }

        @Override
        public Query transform(Query query) {
            calls.add(query.text());
            return behaviour.apply(query);
        }
    }
}

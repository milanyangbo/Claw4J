package io.github.claw4j.agent;

import io.github.claw4j.agent.toolaccess.ChineseToolIndex;
import io.github.claw4j.agent.observability.MeteredToolIndex;
import io.github.claw4j.agent.toolaccess.HybridToolIndex;
import io.github.claw4j.agent.toolaccess.ToolDescriptionTranslator;
import io.github.claw4j.agent.toolaccess.TranslatedVectorIndex;
import io.github.claw4j.agent.toolaccess.NamespacedToolIndex;
import io.github.claw4j.agent.toolaccess.ToolCategories;
import io.github.claw4j.agent.toolaccess.ToolSearch;
import io.github.claw4j.agent.toolaccess.ToolSearchProperties;
import io.github.claw4j.agent.turn.ChatClientAgentPort;
import io.github.claw4j.agent.turn.ToolCallBudget;
import io.github.claw4j.model.config.ModelProperties;
import io.github.claw4j.model.catalog.ModelRef;
import io.github.claw4j.model.failover.CooldownStore;
import io.github.claw4j.model.failover.ProbeThrottle;
import io.github.claw4j.model.routing.ModelRouter;
import io.github.claw4j.model.routing.RouteGate;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.principal.Principal;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.tool.toolsearch.index.vectorstore.VectorToolIndex;
import org.springframework.ai.tool.toolsearch.index.lucene.LuceneToolIndex;
import org.springframework.ai.tool.toolsearch.ToolReference;
import org.springframework.ai.tool.toolsearch.ToolSearchRequest;
import org.springframework.ai.tool.toolsearch.ToolSearchResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 一轮请求长什么样：用哪个模型、模型看得见哪些工具，两件都是「选」。
 *
 * <p>模型那一半验路由边界：主挂了下一次换谁、换了之后还能不能干活、流式在哪一块之后不再换人。
 * 这里不验模型会不会答。工具那一半断言的是这一轮模型看到的工具清单，不是「配置里有一个索引
 * 对象」：清单没变短，检索就什么都没做。
 *
 * <p>末尾两条架构闸写成普通测试方法，不挂 ArchUnit 的 JUnit 注解：同一个类同时挂两种注解时，
 * Jupiter 与 ArchUnit 引擎会各跑一遍，两份 surefire 报告落在同一个文件名上，后跑的覆盖先跑的，
 * 于是这个类的用例数会显示成 2。规则本身不变，只是换成 {@code rule.check(importedClasses)}。
 * 别处（core、channel、capability）是独立文件加注解，那里没有这个问题。
 *
 * <p>写这个文件的注释时不要出现那两个注解名：门禁数方法数是按行匹配注解字符串的，写进注释
 * 会被算成多出来的方法。
 */
class ModelAndToolSelectionTest {

    private static final ToolCallback TOOL = new RecordingTool("querySales", "128400");

    @Test
    @DisplayName("降级后工具真的跑得起来：拿回的是库里的数，不是「我无法访问数据库」")
    void toolsActuallyRunAfterFallback() {
        List<Prompt> backupPrompts = new CopyOnWriteArrayList<>();
        RecordingTool tool = new RecordingTool("querySales", "128400");

        ModelRef primaryRef = new ModelRef("deepseek", "deepseek-chat");
        ModelRef backupRef = new ModelRef("qwen", "qwen-plus");
        ModelRouter.Candidate primary = new ModelRouter.Candidate(primaryRef, model(
                DeepSeekChatOptions.builder().build(),
                prompt -> {
                    throw statusFailure(429, "429 too many requests");
                },
                prompt -> Flux.error(statusFailure(429, "429 too many requests"))),
                ModelRouter.Origin.REQUESTED);
        ModelRouter.Candidate backup = new ModelRouter.Candidate(backupRef, model(
                OpenAiChatOptions.builder().build(),
                prompt -> {
                    backupPrompts.add(prompt);
                    return wantsTool("call-1", "querySales");
                },
                prompt -> Flux.just(wantsTool("call-1", "querySales"))),
                ModelRouter.Origin.CONFIGURED_FALLBACK);
        ModelRouter routing = new ModelRouter(
                ModelRouter.chain(primary, List.of(backup), primary),
                Map.of(primaryRef, 65536, backupRef, 131072),
                Map.of(primaryRef, "http://primary", backupRef, "http://backup"),
                new CooldownStore(), new RouteGate(), new ProbeThrottle());

        Prompt incoming = new Prompt(List.of(new UserMessage("上周华东成交额")),
                DeepSeekChatOptions.builder()
                        .toolCallbacks(List.of(tool))
                        .toolContext(Map.of("claw4j.principal", "guest"))
                        .build());

        ChatResponse response = routing.call(incoming);
        assertThat(response.getResult().getOutput().getToolCalls())
                .as("降级之后备模型要得到工具，否则它只会反问口径")
                .hasSize(1);

        // 真正的一步：拿"备模型看到的那份参数"去跑工具。上面断言 callbacks 还在，
        // 只说明参数里有；这里说明这套参数交回框架之后，工具能被找到、被执行、结果能回灌。
        org.springframework.ai.model.tool.ToolExecutionResult executed =
                org.springframework.ai.model.tool.ToolCallingManager.builder()
                        .build()
                        .executeToolCalls(backupPrompts.getFirst(), response);

        assertThat(tool.calls()).as("工具被执行了一次").isEqualTo(1);
        assertThat(executed.conversationHistory())
                .filteredOn(org.springframework.ai.chat.messages.ToolResponseMessage.class::isInstance)
                .singleElement()
                .satisfies(message -> assertThat(
                        ((org.springframework.ai.chat.messages.ToolResponseMessage) message).getResponses())
                        .as("库里那个数要有机会回到模型面前")
                        .anySatisfy(toolResponse -> assertThat(toolResponse.responseData()).contains("128400")));
    }

    private static ChatResponse wantsTool(String id, String name) {
        return ChatResponse.builder()
                .generations(List.of(new Generation(AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", name, "{}")))
                        .build())))
                .build();
    }

    private static com.openai.errors.OpenAIServiceException statusFailure(int statusCode, String message) {
        return com.openai.errors.UnexpectedStatusCodeException.builder()
                .statusCode(statusCode)
                .headers(com.openai.core.http.Headers.builder().build())
                .cause(new IllegalStateException(message))
                .build();
    }

    private static Prompt prompt(ChatOptions options) {
        return new Prompt(List.of(new UserMessage("上周华东成交额")), options);
    }

    private static String textOf(ChatResponse response) {
        return response.getResult() == null ? "" : response.getResult().getOutput().getText();
    }

    private static ChatResponse answer(String text) {
        return ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage(text))))
                .build();
    }

    private static ChatModel model(ChatOptions options,
                                   Function<Prompt, ChatResponse> call,
                                   Function<Prompt, Flux<ChatResponse>> stream) {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return call.apply(prompt);
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return stream.apply(prompt);
            }

            @Override
            public ChatOptions getOptions() {
                return options;
            }
        };
    }


    @Test
    @DisplayName("mode=always 时只有一个工具也要先检索：这一档不随工具数量改变")
    void singleToolIsSearchedFirstWhenModeSaysAlways() {
        RecordingTool lookup = new RecordingTool("lookup", "128400", "查询销售成交额");
        AtomicInteger turn = new AtomicInteger();
        FixedChatModel model = new FixedChatModel(prompt -> switch (turn.getAndIncrement()) {
            case 0 -> ChatPipelineTest.requestTool(ToolSearch.TOOL_NAME, "{\"query\":\"成交额\"}");
            case 1 -> ChatPipelineTest.requestTool("lookup", "{}");
            default -> ChatPipelineTest.answer("查到了 128400");
        });

        String text = port(model, List.of(lookup), properties(ToolSearchProperties.Mode.ALWAYS, 0,
                List.of(), null))
                .reply(message("上周华东成交额")).text();

        assertThat(toolNames(model.prompts().getFirst()))
                .as("这一档永远是「先检索」：工具只有 1 个也一样")
                .containsExactly(ToolSearch.TOOL_NAME);
        assertThat(toolNames(model.prompts().get(1)))
                .as("检索回来的工具要进清单，检索工具自己也要留着（还可能要继续找）")
                .contains(ToolSearch.TOOL_NAME, "lookup")
                .hasSize(2);
        assertThat(lookup.calls()).isEqualTo(1);
        assertThat(text).isEqualTo("查到了 128400");
    }

    @Test
    @DisplayName("一堆工具里检索挑得出来：找到之后才进清单，并且真的被执行")
    void searchPicksTheRightToolOutOfMany() {
        RecordingTool lookup = new RecordingTool("lookup", "128400", "查询销售成交额");
        List<ToolCallback> tools = withNoise(lookup, 20);
        AtomicInteger turn = new AtomicInteger();
        FixedChatModel model = new FixedChatModel(prompt -> switch (turn.getAndIncrement()) {
            case 0 -> ChatPipelineTest.requestTool(ToolSearch.TOOL_NAME, "{\"query\":\"成交额\"}");
            case 1 -> ChatPipelineTest.requestTool("lookup", "{}");
            default -> ChatPipelineTest.answer("查到了 128400");
        });

        String text = port(model, tools, ToolSearchProperties.defaults())
                .reply(message("上周华东成交额")).text();

        assertThat(toolNames(model.prompts().get(0)))
                .as("第一轮只该看见检索工具：这一份清单就是省下来的 token")
                .containsExactly(ToolSearch.TOOL_NAME);
        assertThat(toolNames(model.prompts().get(1)))
                .as("20 个占位工具一个都不该进来：检索按描述匹配，只有 lookup 对得上")
                .contains(ToolSearch.TOOL_NAME, "lookup")
                .hasSize(2);
        assertThat(lookup.calls()).isEqualTo(1);
        assertThat(text).isEqualTo("查到了 128400");
    }

    /**
     * 会话 id 不在上下文里时，这条路没有"退化成全塞"的兜底——它直接抛。
     *
     * <p>这条钉的是 {@code ChatClientAgentPort} 里那个 {@code chat_memory_conversation_id} 参数：
     * 有人以为它只服务会话记忆、删掉也没事，而实际后果是每一次对话都失败。
     */
    @Test
    @DisplayName("上下文里没有会话 id 时直接报错，不会静默退化成全塞")
    void missingSessionIdFailsLoudly() {
        ChatClient client = ChatClient.builder(new FixedChatModel("好的"),
                        ObservationRegistry.NOOP, null, null,
                        ToolSearch.toolLoop(ToolSearchProperties.defaults(), new ChineseToolIndex(),
                                ObservationRegistry.NOOP, ToolCallBudget.MAX_TOTAL_CALLS))
                .defaultToolCallbacks(new RecordingTool("lookup", "128400", "查询销售成交额"))
                .build();

        // 注意断言写在 content() 上：call() 只是拿到一个待执行的 spec，不触发请求。
        assertThatThrownBy(() -> client.prompt("查成交额").call().content())
                .hasMessageContaining("chat_memory_conversation_id");
    }

    /**
     * 一次检索返回几个候选，夹在框架约定的 1–10 之间。
     *
     * <p>这条守的是"配错一个数会静默变成另一件事"：0 或负数会让检索空手而归，
     * 大于 10 则被框架自己截断，两种都不该让调用方以为配置生效了。
     */
    @Test
    @DisplayName("配置边界：max-results 夹到 1–10、权重夹到 0–1、四档索引各建各的")
    void configBoundariesAreHonoured() {
        assertThat(ToolSearchProperties.defaults().maxResults())
                .as("没配时是 5")
                .isEqualTo(ToolSearchProperties.DEFAULT_MAX_RESULTS);
        assertThat(new ToolSearchProperties(3).maxResults()).isEqualTo(3);
        assertThat(new ToolSearchProperties(99).maxResults())
                .as("框架写的是 1–10，超出的部分由它兜底，这里提前夹住")
                .isEqualTo(10);

        new ApplicationContextRunner()
                .withUserConfiguration(BindingOnly.class)
                .run(context -> assertThat(context.getBean(ToolSearchProperties.class).maxResults())
                        .as("没写这个键 = 用默认值")
                        .isEqualTo(ToolSearchProperties.DEFAULT_MAX_RESULTS));

        new ApplicationContextRunner()
                .withUserConfiguration(BindingOnly.class)
                .withPropertyValues("claw4j.advisor.tool-search.max-results=3")
                .run(context -> assertThat(context.getBean(ToolSearchProperties.class).maxResults())
                        .isEqualTo(3));

        assertThat(ToolSearchProperties.defaults().hybrid().keywordWeight())
                .as("hybrid 的关键字权重没配时是 0.5，不是 0——0 是「纯向量」，那是另一个配置")
                .isEqualTo(ToolSearchProperties.Hybrid.DEFAULT_KEYWORD_WEIGHT);

        new ApplicationContextRunner()
                .withUserConfiguration(BindingOnly.class)
                .withPropertyValues("claw4j.advisor.tool-search.hybrid.keyword-weight=2")
                .run(context -> assertThat(context.getBean(ToolSearchProperties.class)
                        .hybrid().keywordWeight())
                        .as("越界夹到 1，而不是让一个 2 参与分母")
                        .isEqualTo(1.0));
        new ApplicationContextRunner()
                .withUserConfiguration(BindingOnly.class)
                .withPropertyValues("claw4j.advisor.tool-search.hybrid.keyword-weight=-1")
                .run(context -> assertThat(context.getBean(ToolSearchProperties.class)
                        .hybrid().keywordWeight())
                        .isEqualTo(0.0));

        // ---- 同一个入口的另一半：index 四档选谁、分组贴在哪一层

        ToolCategories categories = ToolCategories.of(Map.of());
        // 开着中文化的翻译器：它只该出现在向量那一路外面，关键字档必须不包（票 11 修正）。
        ToolDescriptionTranslator zh = new ToolDescriptionTranslator(new FixedChatModel("查文件"),
                new ToolSearchProperties.ZhDescription(true, List.of()));
        assertThat(inner(ToolSearch.index(ToolSearchProperties.defaults(), null, categories, zh)))
                .as("不写 index 时是中文那一版；而且不包翻译层——关键字读原文")
                .isInstanceOf(ChineseToolIndex.class);

        ToolSearchProperties lucene = new ToolSearchProperties(
                ToolSearchProperties.Index.LUCENE, 5, new ToolSearchProperties.Lucene(0.6f));
        ToolIndex luceneIndex = inner(ToolSearch.index(lucene, null, categories, zh));
        assertThat(luceneIndex)
                .as("lucene 那一档外面包着翻译层（票 21）：它按字切中文 query，不换中文描述就撞不上英文原文")
                .isInstanceOf(TranslatedVectorIndex.class);
        assertThat(untranslated(luceneIndex))
                .as("lucene 那一档不再需要容器里有什么")
                .isInstanceOf(LuceneToolIndex.class);

        ToolSearchProperties vector = new ToolSearchProperties(
                ToolSearchProperties.Index.VECTOR, 5, null);
        assertThatThrownBy(() -> ToolSearch.index(vector, null, categories, zh))
                .isInstanceOf(IllegalStateException.class)
                .as("回落成 regex 会让\"我明明配了向量\"变成一个查不出来的谎")
                .hasMessageContaining("VectorStore")
                .hasMessageContaining("index 改回 regex");
        assertThat(inner(ToolSearch.index(vector, new FakeVectorStore(), categories, zh)))
                .as("向量档外面包着翻译层：改的是入库文本，不是检索逻辑")
                .isInstanceOf(TranslatedVectorIndex.class);
        assertThat(((TranslatedVectorIndex) inner(ToolSearch.index(vector, new FakeVectorStore(), categories, zh)))
                .delegate())
                .isInstanceOf(VectorToolIndex.class);

        ToolIndex fused = ToolSearch.index(new ToolSearchProperties(ToolSearchProperties.Index.HYBRID, 5, null),
                new FakeVectorStore(), categories, zh);
        assertThat(fused)
                .as("hybrid 不在外面再包一层分组：两翼各自被包，融合在最外面")
                .isInstanceOf(HybridToolIndex.class);
        assertThat(((HybridToolIndex) fused).keywordWing())
                .as("分组必须贴在里面：包在融合外面会让某一组先被别组顶掉名额")
                .isInstanceOf(NamespacedToolIndex.class);
        assertThat(((HybridToolIndex) fused).vectorWing())
                .isInstanceOf(NamespacedToolIndex.class);
        assertThat(inner(((HybridToolIndex) fused).vectorWing()))
                .as("向量翼里包着翻译层")
                .isInstanceOf(TranslatedVectorIndex.class);
        assertThat(inner(((HybridToolIndex) fused).keywordWing()))
                .as("关键字翼也包着翻译层（票 21）：两翼读同一份中文描述，只是检索方式不同")
                .isInstanceOf(TranslatedVectorIndex.class);
        assertThat(untranslated(inner(((HybridToolIndex) fused).keywordWing())))
                .as("关键字翼是 Lucene，不是自研的 ChineseToolIndex")
                .isInstanceOf(LuceneToolIndex.class);

        new ApplicationContextRunner()
                .withUserConfiguration(BindingOnly.class)
                .withPropertyValues("claw4j.advisor.tool-search.index=lucene",
                        "claw4j.advisor.tool-search.lucene.min-score=0.6")
                .run(context -> {
                    ToolSearchProperties bound = context.getBean(ToolSearchProperties.class);
                    assertThat(bound.index()).isEqualTo(ToolSearchProperties.Index.LUCENE);
                    assertThat(bound.lucene().minScore()).isEqualTo(0.6f);
                });

        new ApplicationContextRunner()
                .withUserConfiguration(BindingOnly.class)
                .withPropertyValues("claw4j.advisor.tool-search.index=hybrid",
                        "claw4j.advisor.tool-search.hybrid.keyword-weight=0.3")
                .run(context -> {
                    ToolSearchProperties bound = context.getBean(ToolSearchProperties.class);
                    assertThat(bound.index()).isEqualTo(ToolSearchProperties.Index.HYBRID);
                    assertThat(bound.hybrid().keywordWeight())
                            .as("权重是数字，配了就得按配的来")
                            .isEqualTo(0.3);
                });

        new ApplicationContextRunner()
                .withUserConfiguration(BindingOnly.class)
                .withPropertyValues("claw4j.advisor.tool-search.index=bogus")
                .run(context -> assertThat(context).as("非法取值不让它悄悄落回默认").hasFailed());
    }


    /** 分组那层统一在最里面，验"选哪一档"时要先剥掉它。 */
    private static ToolIndex inner(ToolIndex index) {
        return index instanceof NamespacedToolIndex namespaced ? namespaced.delegate() : index;
    }

    /** 再往里剥一层翻译包装：断言"真正干活的索引是哪个"时要穿过它。 */
    private static ToolIndex untranslated(ToolIndex index) {
        return index instanceof TranslatedVectorIndex translated ? translated.delegate() : index;
    }

    /**
     * 流式路径走的是另一对钩子（{@code doInitializeLoopStream} / {@code doBeforeStream}）。
     *
     * <p>生产上真正的入口是 SSE：只给检索工具、追加那段提示词这两件事都发生在流式钩子上，
     * 同步路径绿不能推出它绿。这里只钉两件事——第一轮的工具清单、提示词确实被追加了。
     */
    @Test
    @DisplayName("流式路径同样先检索：SSE 上模型看到的也不是全部工具")
    void streamPathAlsoUsesProgressiveDisclosure() {
        RecordingTool lookup = new RecordingTool("lookup", "128400", "查询销售成交额");
        FixedChatModel model = new FixedChatModel(
                prompt -> ChatPipelineTest.answer("同步路径不该被走到"),
                prompt -> Flux.just(ChatPipelineTest.answer("查到了 128400")));

        port(model, List.of(lookup), properties(ToolSearchProperties.Mode.ALWAYS, 0, List.of(), null))
                .stream(message("上周华东成交额"), chunk -> {
                });

        Prompt first = model.prompts().getFirst();
        assertThat(toolNames(first))
                .as("流式第一轮同样只看得见检索工具")
                .containsExactly(ToolSearch.TOOL_NAME);
        assertThat(textsOf(first))
                .as("检索提示词也要追加到流式的系统消息上，否则模型会说「我没有工具」")
                .contains("不代表没有这个能力");
    }

    /**
     * 中文检索原本要求模型自己用空格分词；现在整句直接能命中。
     *
     * <p>一条方法里放多段不是「一条测多件事」：它们同一个入口（{@code ToolIndex#search}）、
     * 同一种断言形状（结果里有没有那个工具），差的只是输入，与
     * {@code retryableClassificationTable} 拿一张表跑多行是同一个写法。
     *
     * <p>为什么断言「在前 5 里」而不是「第一个就是它」：二元组的假阳性是真实的，排序能压住
     * 大部分但不是全部；断言「不许出现别的」会给自己埋假红。
     */
    @Test
    @DisplayName("中文检索：整句能命中，关键词不变差，工具从 20 加到 100 也不变差")
    void chineseQueryNeedsNoSpaces() {
        ChineseToolIndex index = new ChineseToolIndex();
        List<ToolReference> tools = List.of(
                ToolReference.builder().toolName("recall_session")
                        .summary("按关键词检索历史对话：回忆、之前说过什么、以前的结论").build(),
                ToolReference.builder().toolName("querySales")
                        .summary("查询销售成交额与订单明细，可按地区与时间统计").build(),
                ToolReference.builder().toolName("searchDocs")
                        .summary("在知识库里检索文档：退货政策、定价口径、售后流程").build(),
                ToolReference.builder().toolName("systemStatus")
                        .summary("查看系统运行状态、服务健康度与版本信息").build(),
                ToolReference.builder().toolName("sendEmail")
                        .summary("给客户发送邮件通知，支持附件").build(),
                ToolReference.builder().toolName("createTicket")
                        .summary("创建工单并指派负责人跟进").build(),
                ToolReference.builder().toolName("scheduleMeeting")
                        .summary("预约会议室，安排日程与参会人").build(),
                ToolReference.builder().toolName("runScript")
                        .summary("在沙箱里执行脚本，用于批量处理数据").build(),
                // 描述照抄框架那一份：全英文。这正是网页这一族要跨语言搭桥的原因。
                ToolReference.builder().toolName("WebFetch")
                        .summary("Fetches content from a specified URL and processes it using an AI "
                                + "model. Takes a URL and a prompt, fetches the URL content using "
                                + "HTTP GET method, converts HTML to markdown.").build());

        // 一、整句命中。不切词的话整句是一个 token，当子串去描述里找什么都找不到。
        index.indexTools("chat-1", tools);
        assertThat(foundTools(index, "chat-1", "回忆以前说过的话"))
                .as("中文整句不该空手而归")
                .contains("recall_session");

        // 二、空格关键词不能因此变差——这条守的是"修好中文顺手弄坏英文写法"。
        assertThat(foundTools(index, "chat-1", "回忆 历史 会话"))
                .as("空格分隔的关键词仍然命中同一个工具")
                .contains("recall_session");

        // 三、英文 query 逐字不变：没有中文就走父类，正则形状与父类一致。
        //     反例是把 ASCII 段也打散成 re|ca|ll —— 那样英文检索全线掉。
        assertThat(index.convertQueryToRegexPattern("sales revenue last week"))
                .as("没有中文时原样交回父类，一个 token 都不该被拆开")
                .isEqualTo("(?i)(sales|revenue|last|week)");

        // 四、切分本身可断言，不是黑盒：光看"搜出来的是不是它"说不清变的是切分还是排序。
        //     七个二元组之后是同义词表追加的两个词（整句里出现了"回忆"）。
        assertThat(index.tokens("回忆以前说过的话"))
                .as("相邻两字一组；单字不单独成词，含停用字的“过的”照样保留")
                .containsExactlyInAnyOrder(
                        "回忆", "忆以", "以前", "前说", "说过", "过的", "的话", "历史", "会话");

        // 四之二、网页那一族（票 04）：用户说"打开这个网页""这个链接讲什么"，而 WebFetch 的描述
        //     只有英文（框架自带）。不给 fetch/url 这座桥，中文二元组一个都匹配不上——
        //     实测四问四否，模型每次都答"我没有能抓网页的工具"。
        assertThat(foundTools(index, "chat-1", "打开这个网页看看 https://example.com"))
                .as("中文问网页要能落到 WebFetch 上，不能靠 query 里那个 URL 碰巧命中")
                .contains("WebFetch");
        assertThat(foundTools(index, "chat-1", "这个链接讲了什么"))
                .as("只有中文、连 URL 都没有的时候才最能说明问题")
                .contains("WebFetch");

        // 五、空 query 不能返回全部。父类返回 .* 会把五个工具一起交出去，
        //     模型随便发个空查询就把渐进式披露静默关掉了，而日志上一切正常。
        assertThat(foundTools(index, "chat-1", ""))
                .as("空查询一个工具都不给").isEmpty();
        assertThat(foundTools(index, "chat-1", "   "))
                .as("全是空白的查询同样一个都不给").isEmpty();

        // 六、提示词与索引必须一起改：只改索引，等于留着一段教模型绕一个已不存在的限制。
        assertThat(ToolSearch.SYSTEM_SUFFIX)
                .as("索引能认整句之后，提示词里不该还要求模型自己分词")
                .doesNotContain("空格")
                .as("但“看不到工具不代表没有这个能力”必须留着——不写它模型会直接答“我没有工具”")
                .contains("不代表没有这个能力")
                .as("检索的触发条件不能只挂在“业务问题”上：看网页、读文件同样是动手做事，"
                        + "限定成业务问题会让它直接答“我没有这个工具”（票 04 实测四问四否）")
                .contains("网址")
                .contains("文件路径");

        // 七、默认 bean 真的换掉了。只改测试构造等于测的还是旧实现。
        //     "没有向量库、没有 embedding、没有库" 用空工厂的 provider 表示：默认那一档一个都不读。
        DefaultListableBeanFactory empty = new DefaultListableBeanFactory();
        ToolIndex assembled = new AgentAutoConfiguration().toolIndex(ToolSearchProperties.defaults(),
                empty.getBeanProvider(org.springframework.jdbc.core.JdbcTemplate.class),
                empty.getBeanProvider(org.springframework.ai.embedding.EmbeddingModel.class),
                1024,
                empty.getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class),
                empty.getBeanProvider(ToolDescriptionTranslator.class));
        assertThat(assembled)
                .as("最外层是计量：包在里层记到的是收窄之前那一批，答不了「模型看到几个」")
                .isInstanceOf(MeteredToolIndex.class);
        assertThat(((MeteredToolIndex) assembled).delegate())
                .as("第二层是分组：框架的 categoryFilter 只有它能读")
                .isInstanceOf(NamespacedToolIndex.class);
        assertThat(((NamespacedToolIndex) ((MeteredToolIndex) assembled).delegate()).delegate())
                .as("里层仍是中文那档——默认值没跟着分组一起变")
                .isInstanceOf(ChineseToolIndex.class);

        // 八、20 → 100：同一组 query，工具变多之后答案不变差。
        //     真实清单加起来不到 20 个（注解式 3 个 + 手工构造的若干 + MCP），
        //     100 个只能用跟业务无关的假工具造——这正是 ADR-0021「行为不随工具数量变化」要的动作。
        List<ToolReference> fillers = new ArrayList<>();
        for (int n = 0; n < 92; n++) {
            fillers.add(ToolReference.builder()
                    .toolName("filler_" + n)
                    .summary("第 " + n + " 号假工具：用于验证工具变多时排序是否稳定")
                    .build());
        }
        List<String> queries = List.of(
                "回忆以前说过的话",
                "上周华东区的成交额是多少",
                "无理由退货的政策写在哪份文档里",
                "系统现在运行状态正常吗",
                "给客户发一封邮件",
                "帮我建一个工单安排人跟进",
                "约一个会议室开会",
                "跑个脚本批量处理数据");
        List<String> expected = List.of(
                "recall_session", "querySales", "searchDocs", "systemStatus",
                "sendEmail", "createTicket", "scheduleMeeting", "runScript");

        List<ToolReference> twenty = new ArrayList<>(tools);
        twenty.addAll(fillers.subList(0, 12));
        assertGrowsWithoutLoss(index, "grow-20", twenty, queries, expected);

        List<ToolReference> hundred = new ArrayList<>(tools);
        hundred.addAll(fillers);
        assertGrowsWithoutLoss(index, "grow-100", hundred, queries, expected);

        // 九、拼坏正则的表现是"0 个结果"，不是报错——框架把 PatternSyntaxException 吞成了空响应，
        //     与"这个词真的没有对应工具"分不开。这条把那条路堵死：正则元字符混在中文里，
        //     产出的必须仍然是合法正则（拼坏了 convertQueryToRegexPattern 会自己抛）。
        assertThatCode(() -> index.convertQueryToRegexPattern("回忆 (a|b) [x]* $ ^ \\ 中的 * 号"))
                .as("中文里混进正则元字符不能把模式拼坏")
                .doesNotThrowAnyException();
    }

    /** 把一批工具索引进一个新会话，逐条 query 断言目标还留在前 5 里。 */
    private static void assertGrowsWithoutLoss(ChineseToolIndex index, String sessionId,
                                               List<ToolReference> tools,
                                               List<String> queries, List<String> expected) {
        index.indexTools(sessionId, tools);
        for (int i = 0; i < queries.size(); i++) {
            assertThat(foundTools(index, sessionId, queries.get(i)))
                    .as("%d 个工具时「%s」仍应命中 %s", tools.size(), queries.get(i), expected.get(i))
                    .contains(expected.get(i));
        }
    }

    private static List<String> foundTools(ChineseToolIndex index, String sessionId, String query) {
        return index.search(new ToolSearchRequest(sessionId, query, 5, null))
                .toolReferences()
                .stream()
                .map(ToolReference::toolName)
                .toList();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({ToolSearchProperties.class, ModelProperties.class})
    static class BindingOnly {
    }

    private static ChatClientAgentPort port(FixedChatModel model, List<ToolCallback> tools,
                                            ToolSearchProperties properties) {
        return new ChatClientAgentPort(client(model, tools, properties));
    }

    private static ChatClient client(FixedChatModel model, List<ToolCallback> tools,
                                     ToolSearchProperties properties) {
        ToolIndex index = new ChineseToolIndex();
        return ChatClient.builder(model, ObservationRegistry.NOOP, null, null,
                        ToolSearch.toolLoop(properties, index, ObservationRegistry.NOOP,
                                ToolCallBudget.MAX_TOTAL_CALLS))
                .defaultToolCallbacks(tools)
                .build();
    }

    /** 这一轮模型看到的工具清单。检索的全部价值都在它身上。 */
    private static List<String> toolNames(Prompt prompt) {
        ToolCallingChatOptions options = (ToolCallingChatOptions) prompt.getOptions();
        return options.getToolCallbacks().stream()
                .map(callback -> callback.getToolDefinition().name())
                .toList();
    }

    private static String textsOf(Prompt prompt) {
        return prompt.getInstructions().stream()
                .map(Message::getText)
                .reduce("", (left, right) -> left + "\n" + right);
    }

    private static InboundMessage message(String text) {
        return new InboundMessage(new Principal("u-1"), "chat-1", text);
    }

    /** 占位工具：名字与描述都不含业务词，检索它们时不该命中。 */
    private static List<ToolCallback> noisyTools(int count) {
        List<ToolCallback> tools = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            String name = String.format("tool%02d", i);
            tools.add(new RecordingTool(name, "ok", "占位能力 " + name));
        }
        return List.copyOf(tools);
    }

    /** 一个真工具 + 一堆占位工具，凑到"工具很多"的样子。 */
    private static List<ToolCallback> withNoise(RecordingTool real, int noise) {
        List<ToolCallback> tools = new ArrayList<>(List.of(real));
        tools.addAll(noisyTools(noise));
        return List.copyOf(tools);
    }

    // ------------------------------------------------------------ 工具面（票 06）

    /**
     * 常驻：配置里写了的工具，每轮都在清单里，不用先检索。
     *
     * <p>它解决的是"检索这一下能不能想到"：用户问"读一下那个文件"时，模型得先想到"我可能需要一个
     * 读文件的工具"。常驻把这一步省掉——工具就在眼前。
     */
    @Test
    @DisplayName("pinned 的工具每轮都在清单里，不必先检索")
    void pinnedToolsAreAlwaysVisible() {
        RecordingTool lookup = new RecordingTool("lookup", "128400", "查询销售成交额");
        FixedChatModel model = new FixedChatModel("好");

        port(model, withNoise(lookup, 20), properties(ToolSearchProperties.Mode.ALWAYS, 0,
                List.of("lookup"), null))
                .reply(message("上周华东成交额"));

        assertThat(toolNames(model.prompts().get(0)))
                .as("检索工具 + 常驻，第一轮就该是这样")
                .containsExactlyInAnyOrder(ToolSearch.TOOL_NAME, "lookup");
    }

    /**
     * 工具少时全塞：这时多花一轮检索是纯开销，而且模型会因为"清单里只有一个找工具的工具"
     * 直接回答"我没有能列目录的工具"（票 05 的五次实测）。
     */
    @Test
    @DisplayName("工具数不超过阈值时全塞，也不拼“只看得见检索工具”那段提示")
    void revealsEverythingBelowTheThreshold() {
        RecordingTool lookup = new RecordingTool("lookup", "128400", "查询销售成交额");
        FixedChatModel model = new FixedChatModel("好");

        port(model, withNoise(lookup, 2), properties(ToolSearchProperties.Mode.AUTO, 10, List.of(), null))
                .reply(message("上周华东成交额"));

        assertThat(toolNames(model.prompts().get(0)))
                .as("三个工具全在，且没有检索工具")
                .hasSize(3)
                .doesNotContain(ToolSearch.TOOL_NAME);
        assertThat(textsOf(model.prompts().get(0)))
                .as("全塞时那句“你只看得见 finder”是假的，模型会照它先去检索一遍眼前的东西")
                .doesNotContain("只看得见");
    }

    /** 超过阈值才检索：这是大部署的那一档，也是改动之前的唯一行为。 */
    @Test
    @DisplayName("工具数超过阈值时只看得见检索工具")
    void hidesLongTailAboveTheThreshold() {
        RecordingTool lookup = new RecordingTool("lookup", "128400", "查询销售成交额");
        FixedChatModel model = new FixedChatModel("好");

        port(model, withNoise(lookup, 20), properties(ToolSearchProperties.Mode.AUTO, 10, List.of(), null))
                .reply(message("上周华东成交额"));

        assertThat(toolNames(model.prompts().get(0)))
                .as("21 个工具 > 10，回到检索那一档")
                .containsExactly(ToolSearch.TOOL_NAME);
    }

    /** off：全塞，连索引都不建。工具只有几个的部署用这一档。 */
    @Test
    @DisplayName("mode=off 永不检索，全部工具都在清单里")
    void offModeNeverHidesTools() {
        RecordingTool lookup = new RecordingTool("lookup", "128400", "查询销售成交额");
        FixedChatModel model = new FixedChatModel("好");

        port(model, withNoise(lookup, 20), properties(ToolSearchProperties.Mode.OFF, 0, List.of(), null))
                .reply(message("上周华东成交额"));

        assertThat(toolNames(model.prompts().get(0)))
                .as("21 个全在")
                .hasSize(21)
                .doesNotContain(ToolSearch.TOOL_NAME);

        // 票 10 之后默认配置里 pinned 是六个名字：off 档的语义是"全塞"，不是"只留常驻那几个"。
        // 这两件事混了的话，off 会变成一个隐藏的白名单，而它名字里的 off 是"关掉检索"。
        FixedChatModel pinnedModel = new FixedChatModel("好");
        port(pinnedModel, withNoise(lookup, 20), properties(ToolSearchProperties.Mode.OFF, 0,
                List.of("lookup"), null))
                .reply(message("上周华东成交额"));
        assertThat(toolNames(pinnedModel.prompts().get(0)))
                .as("pinned 不影响全塞：该在的 21 个一个不少")
                .hasSize(21)
                .doesNotContain(ToolSearch.TOOL_NAME);
    }

    /** 名字里带了不存在的 pinned：告警并跳过，不能让整个部署起不来。 */
    @Test
    @DisplayName("pinned 写了不存在的名字只忽略，不影响这一轮")
    void unknownPinnedNameIsIgnored() {
        RecordingTool lookup = new RecordingTool("lookup", "128400", "查询销售成交额");
        FixedChatModel model = new FixedChatModel("好");

        port(model, withNoise(lookup, 20), properties(ToolSearchProperties.Mode.ALWAYS, 0,
                List.of("no_such_tool"), null))
                .reply(message("上周华东成交额"));

        assertThat(toolNames(model.prompts().get(0)))
                .as("写错的名字被跳过，清单里不会多出一个空位")
                .containsExactly(ToolSearch.TOOL_NAME);
    }

    /** 分组：配置优先，没配按名字前缀。这是"通用"的那一半——代码里不认任何具体工具名。 */
    @Test
    @DisplayName("类别：配置优先，否则取名字第一个下划线之前的部分")
    void categoryDefaultsToNamePrefix() {
        ToolCategories derived = ToolCategories.of(Map.of());
        assertThat(derived.categoryOf("github_issue_list")).isEqualTo("github");
        assertThat(derived.categoryOf("Read"))
                .as("没有下划线时它自己就是一组——这是部署该配 categories 的信号")
                .isEqualTo("Read");

        ToolCategories configured = ToolCategories.of(Map.of("file", List.of("Read", "Glob")));
        assertThat(configured.categoryOf("Read")).isEqualTo("file");
        assertThat(configured.categoryOf("web_fetch"))
                .as("配置里没有的仍然按前缀推导，不是「配了就全得配」")
                .isEqualTo("web");
    }

    /** 检索时按组收窄：框架把这个参数交给模型了，但现成索引都不读它。 */
    @Test
    @DisplayName("categoryFilter 真的能收窄，而不是传了等于没传")
    void categoryFilterNarrowsTheSearch() {
        NamespacedToolIndex index = new NamespacedToolIndex(new ChineseToolIndex(), ToolCategories.of(Map.of()));
        index.indexTools("c-1", List.of(
                ToolReference.builder().toolName("file_read").summary("读取文件内容").build(),
                ToolReference.builder().toolName("web_fetch").summary("抓取网页内容").build()));

        assertThat(names(index, "c-1", "内容", null))
                .as("不收窄时两个都该在候选里")
                .containsExactlyInAnyOrder("file_read", "web_fetch");
        assertThat(names(index, "c-1", "内容", "file"))
                .as("收窄到 file 组，只剩 file_read")
                .containsExactly("file_read");
        assertThat(names(index, "c-1", "内容", "web"))
                .containsExactly("web_fetch");
    }

    // ------------------------------------------------------------ 检索的可观测性（票 08）

    /**
     * 三个数各记什么：搜到、空手、收窄之后空手。
     *
     * <p>一段三段不是"一条测三件事"：同一个入口（{@code MeteredToolIndex#search}）、同一种断言
     * （按标签读计数），差的只是这一次检索的结果与有没有收窄——与「一张表跑多行」同一个写法。
     *
     * <p>为什么非要分开：{@code miss} 与 {@code narrowed=true 的 miss} 修法不同。前者是索引与描述
     * 的问题（去改描述、换索引），后者是分组配错了、或者模型在乱填 {@code categoryFilter}。
     * 混在一个数里时，前者的修法会被套到后者的症状上。
     */
    @Test
    @DisplayName("三个数分开记：hit / miss / 收窄之后的 miss")
    void searchMetricsSeparateTheThreeOutcomes() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MeteredToolIndex plain = new MeteredToolIndex(new ChineseToolIndex(), registry);
        plain.indexTools("m-1", List.of(
                ToolReference.builder().toolName("lookup").summary("查询销售成交额").build()));
        plain.search(new ToolSearchRequest("m-1", "成交额", 5, null));
        plain.search(new ToolSearchRequest("m-1", "帮我订一张去上海的机票", 5, null));

        assertThat(count(registry, MeteredToolIndex.HIT, false)).isEqualTo(1);
        assertThat(count(registry, MeteredToolIndex.MISS, false)).isEqualTo(1);

        MeteredToolIndex grouped = new MeteredToolIndex(
                new NamespacedToolIndex(new ChineseToolIndex(), ToolCategories.of(Map.of())), registry);
        grouped.indexTools("m-2", List.of(
                ToolReference.builder().toolName("file_read").summary("读取文件内容").build(),
                ToolReference.builder().toolName("web_fetch").summary("抓取网页内容").build()));
        grouped.search(new ToolSearchRequest("m-2", "读取文件", 5, "web"));

        assertThat(count(registry, MeteredToolIndex.MISS, true)).isEqualTo(1);
        assertThat(count(registry, MeteredToolIndex.MISS, false))
                .as("不分开的话这一条会被记成「索引不好使」，而实际是收窄收没了；上面那条不受影响")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("索引抛异常记 error 并往上抛：不让模型以为「没搜到」")
    void searchFailureCountsAsErrorAndPropagates() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MeteredToolIndex index = new MeteredToolIndex(new FailingToolIndex(), registry);

        assertThatThrownBy(() -> index.search(new ToolSearchRequest("m-3", "成交额", 5, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("索引挂了");

        assertThat(count(registry, MeteredToolIndex.ERROR, false)).isEqualTo(1);
        assertThat(count(registry, MeteredToolIndex.MISS, false))
                .as("吞成 miss 的话，「检索坏了」会表现成「这个库没有那个工具」")
                .isZero();
    }

    @Test
    @DisplayName("返回条数进分布：一次 1 个、一次 0 个")
    void resultCountRecordsHowManyCameBack() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MeteredToolIndex index = new MeteredToolIndex(new ChineseToolIndex(), registry);
        index.indexTools("m-4", List.of(
                ToolReference.builder().toolName("lookup").summary("查询销售成交额").build()));

        index.search(new ToolSearchRequest("m-4", "成交额", 5, null));
        index.search(new ToolSearchRequest("m-4", "订机票", 5, null));

        DistributionSummary summary = registry.find(MeteredToolIndex.SEARCH_RESULTS).summary();
        assertThat(summary).as("没有 registry 时这里会是 null，说明装配漏了").isNotNull();
        assertThat(summary.count()).isEqualTo(2);
        assertThat(summary.totalAmount()).as("1 + 0").isEqualTo(1.0);
    }

    // ------------------------------------------------------------ 检索的 hybrid（票 07）

    /**
     * 关键字与向量两路都空手，才是真的找不到。
     *
     * <p>这里钉的是本票要救的那类失败：中文问法、英文描述，字面一个字都不重合。关键字那一路
     * （{@link ChineseToolIndex}）在这种问法上返回空，这不是"描述写得不好"，是词面匹配的上限。
     * 向量那一路按语义带回来，融合之后模型才看得见它。
     */
    @Test
    @DisplayName("零字面重叠：中文问法 + 英文描述，关键字空手、向量带回来")
    void hybridFindsToolWithNoLiteralOverlap() {
        String summary = "Fetches content from a specified URL using HTTP GET and returns markdown";
        // 问法是「这个地址」而不是「这个网页」：后者已经被票 04 的同义词表桥接（页 → fetch,url），
        // 那座桥上过得去，就不是本票要救的那类空手。
        String query = "这个地址写了什么";
        ToolIndex keywordOnly = new ChineseToolIndex();
        keywordOnly.indexTools("h-1", List.of(ToolReference.builder().toolName("WebFetch")
                .summary(summary).build()));

        assertThat(names(keywordOnly, "h-1", query, null))
                .as("中文二元组撞不上英文描述，这是票 04 靠同义词表一座桥一座桥补的那个洞")
                .isEmpty();

        HybridToolIndex hybrid = HybridToolIndex.of(ToolCategories.of(Map.of()), keywordOnly,
                new StubToolIndex(List.of(reference("WebFetch", summary, 0.42))), 0.5);
        assertThat(names(hybrid, "h-1", query, null))
                .as("一路空手不该把另一路的结果也吞掉")
                .containsExactly("WebFetch");
    }

    /**
     * 权重两端各归一端，这是"换索引能对照"的前提。
     *
     * <p>1 必须与改之前逐字一致（纯关键字），0 必须是纯向量。若写成"两路都查、按权重加权"，
     * 权重 1 时向量那一路的候选仍会以 0 分进列表——看起来权重生效了，结果里却混着只由向量带回来的
     * 工具，对照就不成立。
     */
    @Test
    @DisplayName("keyword-weight 两端各归一端：1 只看关键字，0 只看向量")
    void keywordWeightDecidesWhoWins() {
        ToolCategories categories = ToolCategories.of(Map.of());
        StubToolIndex keywordWing = new StubToolIndex(List.of(
                reference("file_read", "读取文件内容", 0.9),
                reference("lookup", "查询销售成交额", 0.5)));
        StubToolIndex vectorWing = new StubToolIndex(List.of(reference("WebFetch", "抓网页", 0.8)));

        assertThat(names(HybridToolIndex.of(categories, keywordWing, vectorWing, 1.0), "h-2", "随便问", null))
                .as("权重 1 = 改之前的现状，用来做对照")
                .containsExactly("file_read", "lookup");
        assertThat(names(HybridToolIndex.of(categories, keywordWing, vectorWing, 0.0), "h-2", "随便问", null))
                .as("权重 0 = 纯向量：关键字那一路整个不参与，不是「参与但得 0 分」")
                .containsExactly("WebFetch");
    }

    /**
     * 融合按名次，不按原始分；分组在两翼之内、融合之前。
     *
     * <p>两段同一个入口（{@code search}）、同一种断言（回来的顺序与名单），与
     * {@code chineseQueryNeedsNoSpaces} 拿一张表跑多行是同一个写法。
     *
     * <p>第一段：关键字那一路的分数（命中次数）与向量那一路的分数（余弦）量纲不同，直接加权求和
     * 要让量纲大的一路占满——上面那组数按求和排是 {@code [X, Y, Z]}，按名次是 {@code [Y, X, Z]}。
     * 第二段：分组若包在融合外面，排在前面的那一组会先把名额占满，收窄之后反而"更少了"。
     */
    @Test
    @DisplayName("融合按名次不按原始分；分组在两翼之内、融合之前")
    void fusionUsesRanksAndGroupsBeforeFusion() {
        ToolCategories categories = ToolCategories.of(Map.of());

        StubToolIndex keywordWing = new StubToolIndex(List.of(
                reference("X", "关键字那路的第一名，分很高", 1.0),
                reference("Y", "两路都在，名次靠前", 0.001)));
        StubToolIndex vectorWing = new StubToolIndex(List.of(
                reference("Y", "两路都在，名次靠前", 0.9),
                reference("Z", "只在向量那路", 0.8)));
        assertThat(names(HybridToolIndex.of(categories, keywordWing, vectorWing, 0.5), "h-3", "随便问", null))
                .as("按名次：Y 两路都靠前所以第一；按求和：X 的 1.0 会压住一切")
                .containsExactly("Y", "X", "Z");

        // 第二段：两路索引的是同一批工具（现实就是如此），file_* 二十个排在前面，web_* 两个垫底。
        List<ToolReference> catalogue = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            catalogue.add(reference("file_op" + i, "文件操作第 " + i + " 个", 0.9 - i * 0.01));
        }
        catalogue.add(reference("web_fetch", "抓取网页", 0.2));
        catalogue.add(reference("web_post", "提交表单", 0.1));
        HybridToolIndex grouped = HybridToolIndex.of(categories,
                new StubToolIndex(catalogue), new StubToolIndex(catalogue), 0.5);
        // 分组表是"索引时记住的"，不是"检索时猜的"：不调这一次，两翼都收窄不了。
        grouped.indexTools("h-4", catalogue);

        assertThat(names(grouped, "h-4", "随便问", "web"))
                .as("分组包在融合外面时，融合后的前排（外层要的 3×5 条）全是 file_*，收窄完一条不剩")
                .containsExactlyInAnyOrder("web_fetch", "web_post");
    }

    private static double count(SimpleMeterRegistry registry, String result, boolean narrowed) {
        Counter counter = registry.find(MeteredToolIndex.SEARCH_CALLS)
                .tag("result", result)
                .tag("narrowed", Boolean.toString(narrowed))
                .counter();
        return counter == null ? 0 : counter.count();
    }

    /** 只会炸的索引：验「检索失败」与「没搜到」在指标上不是一件事。 */
    private static final class FailingToolIndex implements ToolIndex {

        @Override
        public void indexTool(String sessionId, ToolReference toolReference) {
        }

        @Override
        public void indexTools(String sessionId, List<ToolReference> toolReferences) {
        }

        @Override
        public ToolSearchResponse search(ToolSearchRequest toolSearchRequest) {
            throw new IllegalStateException("索引挂了");
        }

        @Override
        public void clearIndex(String sessionId) {
        }
    }

    private static List<String> names(ToolIndex index, String sessionId, String query, String category) {
        return index.search(new ToolSearchRequest(sessionId, query, 5, category))
                .toolReferences()
                .stream()
                .map(ToolReference::toolName)
                .toList();
    }

    /** 固定返回一批候选的索引：本票验的是融合的算术，不是检索准不准。 */
    private static ToolReference reference(String toolName, String summary, double score) {
        return ToolReference.builder().toolName(toolName).summary(summary)
                .relevanceScore(score).build();
    }

    /**
     * 只按顺序交回固定候选的索引。
     *
     * <p>照 {@code maxResults} 截断，与真索引一致：替身不该比真实实现更宽松，否则"窗口里够不够"
     * 这类断言会在替身上成立、在真库上不成立。
     */
    private static final class StubToolIndex implements ToolIndex {

        private final List<ToolReference> hits;

        StubToolIndex(List<ToolReference> hits) {
            this.hits = List.copyOf(hits);
        }

        @Override
        public void indexTool(String sessionId, ToolReference toolReference) {
        }

        @Override
        public void indexTools(String sessionId, List<ToolReference> toolReferences) {
        }

        @Override
        public ToolSearchResponse search(ToolSearchRequest toolSearchRequest) {
            int max = toolSearchRequest.maxResults() == null ? 5 : toolSearchRequest.maxResults();
            List<ToolReference> slice = this.hits.stream().limit(max).toList();
            return ToolSearchResponse.builder().toolReferences(slice).totalMatches(slice.size()).build();
        }

        @Override
        public void clearIndex(String sessionId) {
        }
    }

    /**
     * 只记读写、不连库的 JdbcTemplate。
     *
     * <p>落表这件事要在单测里可验，而为一个"几十行 SQL"的行为拉一个 Postgres 不值得
     * （容器起一次 20 秒，还要维护一份与迁移脚本同步的表结构）。这里只关心
     * "存了什么、读到没有、失败时退到哪"，SQL 本身由真机与迁移脚本保证。
     */
    private static final class FakeDescriptionTable extends JdbcTemplate {

        private volatile boolean broken;

        // 键是工具名，值里带 hash：这样才与 SQL 的 primary key (tool_name) 一致。
        // 按 "名字|hash" 记会假装 on conflict 不存在，于是"改了描述"那条断言看起来通过了、
        // 实际上表里越攒越多。
        private final Map<String, String[]> byName = new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public <T> List<T> queryForList(String sql, Class<T> elementType, Object... args) {
            if (this.broken) {
                throw new org.springframework.dao.DataAccessResourceFailureException("表没了");
            }
            String[] row = this.byName.get((String) args[0]);
            if (row == null || !row[0].equals(args[1])) {
                return List.of();
            }
            return List.of(elementType.cast(row[1]));
        }

        @Override
        public int update(String sql, Object... args) {
            if (this.broken) {
                throw new org.springframework.dao.DataAccessResourceFailureException("表没了");
            }
            this.byName.put((String) args[0], new String[] {(String) args[1], (String) args[3]});
            return 1;
        }

        void breakIt() {
            this.broken = true;
        }

        int rows() {
            return this.byName.size();
        }
    }

    /** 只记"入库时收到了什么"的索引：两翼的文本差别是票 11 修正的核心，得看得见。 */
    private static final class RecordingIndex implements ToolIndex {

        private final List<ToolReference> indexed = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public void indexTool(String sessionId, ToolReference toolReference) {
            this.indexed.add(toolReference);
        }

        @Override
        public void indexTools(String sessionId, List<ToolReference> toolReferences) {
            this.indexed.addAll(toolReferences);
        }

        @Override
        public ToolSearchResponse search(ToolSearchRequest toolSearchRequest) {
            return ToolSearchResponse.builder().toolReferences(List.of()).totalMatches(0).build();
        }

        @Override
        public void clearIndex(String sessionId) {
        }

        List<ToolReference> indexed() {
            return List.copyOf(this.indexed);
        }
    }

    private static ToolSearchProperties properties(ToolSearchProperties.Mode mode, int revealBelow,
                                                   List<String> pinned, Map<String, List<String>> categories) {
        return new ToolSearchProperties(null, 5, null, mode, revealBelow, pinned, categories, null, null);
    }

    // ------------------------------------------------------------ 描述本地化（票 11）

    /**
     * 中文化的作用域与退化：开关、名单、两翼文本、已有中文、译失败。
     *
     * <p>五段合成一条不是"一条测五件事"：同一个入口（`translateReferences`）、同一种断言
     * （模型被调用了几次 / 入库文本是哪一份）。分开写只会让五段共享的夹具抄五遍。
     *
     * <p>作用域那两段（只喂向量翼、关键字翼读原文）是 2026-11-26 那次对照的落点：中文化让
     * 向量受益、让纯关键字下降（描述变长、二元组碰撞变多）。所以断言的不是"译了"，而是
     * "**只译给该译的那一路**"。
     */
    @Test
    @DisplayName("开着：只有向量翼入库中文+原文，关键字翼与模型仍是原文；关着/名单外不翻；已有中文不翻；译失败退原文")
    void zhDescriptionScopeAndFallback() {
        ToolReference read = reference("Read", "Reads a file from the local filesystem", 0.0);
        FixedChatModel model = new FixedChatModel("读取本地文件的内容并返回");

        ToolDescriptionTranslator off = new ToolDescriptionTranslator(model,
                new ToolSearchProperties.ZhDescription(false, List.of()));
        assertThat(off.translateReferences(List.of(read)))
                .as("关着时连新对象都不造：返回的是同一个")
                .containsExactly(read);
        ToolDescriptionTranslator scoped = new ToolDescriptionTranslator(model,
                new ToolSearchProperties.ZhDescription(true, List.of("WebFetch")));
        assertThat(scoped.translateReferences(List.of(read))).containsExactly(read);
        assertThat(model.prompts())
                .as("关着还在调，等于为一个不生效的开关付钱；名单外还在调，等于名单没生效")
                .isEmpty();

        ToolDescriptionTranslator translator = new ToolDescriptionTranslator(model,
                new ToolSearchProperties.ZhDescription(true, List.of()));
        RecordingIndex keywordWing = new RecordingIndex();
        RecordingIndex vectorWing = new RecordingIndex();
        HybridToolIndex hybrid = HybridToolIndex.of(ToolCategories.of(Map.of()), keywordWing,
                new TranslatedVectorIndex(vectorWing, translator), 0.5);
        hybrid.indexTools("zh", List.of(read));

        assertThat(keywordWing.indexed())
                .as("关键字那一路读原文：描述一变长，中文二元组就会撞出更多噪声")
                .extracting(ToolReference::summary)
                .containsExactly("Reads a file from the local filesystem");
        assertThat(vectorWing.indexed())
                .as("向量那一路要的是语义，中文一句在前、原文在后")
                .extracting(ToolReference::summary)
                .singleElement()
                .asString()
                .startsWith("读取本地文件的内容并返回")
                .contains("Reads a file from the local filesystem");

        ToolReference chinese = reference("querySales", "查询销售成交额与订单明细", 0.0);
        int before = model.prompts().size();
        assertThat(translator.translateReferences(List.of(chinese)))
                .as("本来就是中文，原样返回")
                .containsExactly(chinese);
        assertThat(model.prompts()).as("已有中文还去翻，是白花钱").hasSize(before);

        ToolDescriptionTranslator failing = new ToolDescriptionTranslator(
                new FixedChatModel(prompt -> {
                    throw new IllegalStateException("模型挂了");
                }),
                new ToolSearchProperties.ZhDescription(true, List.of()));
        assertThat(failing.translateReferences(List.of(read)))
                .as("译不出来就用原文：中文化是增强，不该让整轮请求挂掉")
                .containsExactly(read);
    }

    /**
     * 译文落表（票 16）：一次译完存起来，下一个进程直接读。
     *
     * <p>为什么非要有这条：票 13 的第三版量出中文化有收益，但**开的那一侧三次跑出三个 R@1**
     * ——译文只在进程内缓存，每跑一次评测就重译一遍，措辞不同 embedding 就不同。一个自己会抖的
     * 指标不能当回归门。
     *
     * <p>五段：首次译写表、新实例读表不再调模型、原文变了重译、译失败不写表、表坏了退内存缓存。
     * 同一个入口、同一种断言（模型调用次数 / 表里几行），所以并成一条。
     */
    @Test
    @DisplayName("译文落表：跨实例复用、原文变了重译、译失败不写表、表坏了退内存缓存")
    void zhDescriptionStoresOnceAndReuses() {
        FakeDescriptionTable table = new FakeDescriptionTable();
        FixedChatModel model = new FixedChatModel("读取本地文件的内容并返回");
        ToolSearchProperties.ZhDescription on = new ToolSearchProperties.ZhDescription(true, List.of());
        ToolReference read = reference("Read", "Reads a file from the local filesystem", 0.0);

        ToolDescriptionTranslator first = new ToolDescriptionTranslator(model, on, table);
        assertThat(first.translateReferences(List.of(read)).getFirst().summary())
                .startsWith("读取本地文件的内容并返回");
        assertThat(model.prompts()).as("第一次要真译一次").hasSize(1);
        assertThat(table.rows()).as("译完就存：不然下个进程还得再译").isEqualTo(1);

        ToolDescriptionTranslator second = new ToolDescriptionTranslator(model, on, table);
        assertThat(second.translateReferences(List.of(read)).getFirst().summary())
                .as("新实例读表，译文与上次那一份逐字相同——这就是「可复现」的来源")
                .startsWith("读取本地文件的内容并返回");
        assertThat(model.prompts()).as("跨实例不该再调模型").hasSize(1);

        ToolReference changed = reference("Read", "Reads a file and returns its content", 0.0);
        assertThat(second.translateReferences(List.of(changed)).getFirst().summary())
                .as("原文变了必须重译：按工具名命中的话，改了描述还在用旧译文")
                .contains("Reads a file and returns its content");
        assertThat(model.prompts()).hasSize(2);
        assertThat(table.rows()).as("同名工具覆盖那一行，不是越攒越多").isEqualTo(1);

        FakeDescriptionTable untouched = new FakeDescriptionTable();
        ToolDescriptionTranslator failing = new ToolDescriptionTranslator(
                new FixedChatModel(prompt -> {
                    throw new IllegalStateException("模型挂了");
                }), on, untouched);
        assertThat(failing.translateReferences(List.of(read)).getFirst())
                .as("译失败退原文")
                .isSameAs(read);
        assertThat(untouched.rows())
                .as("译失败不写表：把原文当译文存进去，以后永远不会再译，表面上看是缓存命中")
                .isZero();

        FakeDescriptionTable noisy = new FakeDescriptionTable();
        ToolDescriptionTranslator echoing = new ToolDescriptionTranslator(
                new FixedChatModel("工具名：Read\n描述：读取本地文件并返回它的内容"),
                on, noisy);
        assertThat(echoing.translateReferences(List.of(read)).getFirst().summary())
                .as("模型把提示词那两行抄回来时，入库的必须是「描述：」之后那一句"
                        + "——以前只活在进程里看不出来，落表之后它会长期待在索引里")
                .startsWith("读取本地文件并返回它的内容")
                .doesNotContain("工具名");

        FakeDescriptionTable broken = new FakeDescriptionTable();
        broken.breakIt();
        ToolDescriptionTranslator degraded = new ToolDescriptionTranslator(model, on, broken);
        assertThat(degraded.translateReferences(List.of(read)).getFirst().summary())
                .as("表读不出来就用进程内缓存：中文化是增强，缺了只是命中率低一点")
                .startsWith("读取本地文件的内容并返回");
        degraded.translateReferences(List.of(read));
        assertThat(model.prompts())
                .as("表坏了也不该每个工具都重试一次读：只告警一次、然后走内存")
                .hasSize(3);
    }

    // ------------------------------------------------------------ 本模块的两条架构闸

    /**
     * 反向依赖不会编译失败，也不会当场报错：agent 一旦认识 channel，
     * 换一个入口就要改 Agent，单元测试也得先起一个通道。ADR-0010 的边界在这里落成机器校验。
     */
    @Test
    @DisplayName("agent 不认识 channel（架构闸）")
    void agentKnowsNoChannel() {
        noClasses().that().resideInAPackage("io.github.claw4j.agent..")
                .should().dependOnClassesThat()
                .resideInAPackage("io.github.claw4j.channel..")
                .check(agentClasses());
    }

    /**
     * 本仓库自己的 Advisor 必须同时挂上同步与流式两条路径。
     *
     * <p>不是洁癖：流式链取的是 {@code getStreamAdvisors()}，只实现 {@code CallAdvisor} 的
     * Advisor 在 SSE 上会被静默跳过——编译过、容器起得来、日志里一句没有，表现是这条约束在
     * 流式入口上根本不存在。而 HTTP 通道同时提供 call 与 SSE，少挂一条路径等于少一个入口的防线。
     *
     * <p><b>按类型圈，不按包名圈</b>：这条规则原先写的是 {@code resideInAPackage("...agent.advisor..")}，
     * 于是放在 {@code memory} 与 {@code persona} 里的三个自研 Advisor 根本不在闸内——它们碰巧
     * 用的是 {@code BaseAdvisor}（它 extends 两个接口）所以没出事，但下一次有人在那两个包里的新
     * Advisor 只写一个接口，闸不会响。边界由"实现了 Advisor"定义，不由"放在哪个目录"定义。
     *
     * <p>做成规则而不是靠人记：漏实现一个接口不会有任何报错。
     */
    @Test
    @DisplayName("本仓库的 Advisor 两条路径都挂（架构闸）")
    void advisorsCoverBothPaths() {
        classes().that().resideInAPackage("io.github.claw4j.agent..")
                .and().implement(Advisor.class)
                .should().implement(CallAdvisor.class)
                .andShould().implement(StreamAdvisor.class)
                .check(agentClasses());
    }

    /**
     * 模型实现类不在 agent 里（ADR-0035）：agent 只拿一个 {@code ChatModel} 去组 ChatClient。
     *
     * <p>搬走之前，路由、供应商装配、embedding 与指标都寄生在 agent 里，"换模型"和"改 Agent"
     * 是同一次改动。规则一旦破，症状不是报错：新加的模型功能会重新长回 agent，
     * 而模型层那张表又少一行。
     */
    @Test
    @DisplayName("模型实现类不在 agent 里（架构闸）")
    void agentHasNoModelImplementation() {
        noClasses().that().resideInAPackage("io.github.claw4j.agent..")
                .should().implement(ChatModel.class)
                .check(agentClasses());
    }

    /**
     * 模型层只依赖 core（ADR-0035）：不认识 agent / capability / rag / channel。
     *
     * <p>反过来依赖会编译通过（那些模块在同一个 reactor 里），代价是一个环：
     * agent 依赖 model，model 又去问 agent 要会话或工具，谁也不能单独替换。
     */
    @Test
    @DisplayName("模型层只依赖 core：不认识 agent / capability / rag / channel（架构闸）")
    void modelDependsOnCoreOnly() {
        noClasses().that().resideInAPackage("io.github.claw4j.model..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("io.github.claw4j.agent..", "io.github.claw4j.capability..",
                        "io.github.claw4j.rag..", "io.github.claw4j.channel..",
                        "io.github.claw4j.app..")
                .check(agentClasses());
    }

    /** 两条规则看的是同一批类，导入一次就够。 */
    private static JavaClasses agentClasses() {
        return AgentClasses.IMPORTED;
    }

    /** 向量库的替身：这三档验的是"选了谁"，不碰写入与检索。 */
    private static final class FakeVectorStore implements VectorStore {

        @Override
        public void add(List<Document> documents) {
            // 不落任何东西
        }

        @Override
        public void delete(List<String> idList) {
            // 同上
        }

        @Override
        public void delete(Filter.Expression filterExpression) {
            // 同上
        }

        @Override
        public List<Document> similaritySearch(SearchRequest request) {
            return List.of();
        }
    }

    /**
     * 放在 holder 里而不是静态初始化块：导入失败时 JUnit 报的是"哪条用例红了、为什么"，
     * 而不是一个 {@code ExceptionInInitializerError}（那时连哪个类都说不清）。
     */
    private static final class AgentClasses {

        private static final JavaClasses IMPORTED = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("io.github.claw4j.agent", "io.github.claw4j.model");
    }
}

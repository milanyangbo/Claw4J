package io.github.claw4j.agent;

import io.github.claw4j.agent.model.ModelFailures;
import io.github.claw4j.agent.model.ModelRoutingException;
import io.github.claw4j.agent.model.RoutingChatModel;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import io.github.claw4j.agent.advisor.AuthorizationAdvisor;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.ai.tool.toolsearch.ToolReference;
import org.springframework.ai.tool.toolsearch.ToolSearchRequest;
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
    @DisplayName("主模型正常时备模型一次都不碰")
    void primarySuccessDoesNotTouchBackup() {
        AtomicInteger backupCalls = new AtomicInteger();
        RoutingChatModel routing = new RoutingChatModel(List.of(
                new RoutingChatModel.Route("primary", model(
                        DeepSeekChatOptions.builder().model("deepseek-chat").build(),
                        prompt -> answer("主模型的回答"),
                        prompt -> Flux.just(answer("主模型的回答")))),
                new RoutingChatModel.Route("backup", model(
                        OpenAiChatOptions.builder().model("qwen-plus").build(),
                        prompt -> {
                            backupCalls.incrementAndGet();
                            return answer("备模型的回答");
                        },
                        prompt -> Flux.just(answer("备模型的回答"))))));

        ChatResponse response = routing.call(prompt(DeepSeekChatOptions.builder().model("deepseek-chat").build()));

        assertThat(textOf(response)).isEqualTo("主模型的回答");
        assertThat(backupCalls).hasValue(0);
    }

    @Test
    @DisplayName("主模型抛错时备模型接住，工具与工具上下文一个都不丢")
    void fallbackKeepsToolsAndToolContext() {
        List<Prompt> backupPrompts = new CopyOnWriteArrayList<>();
        RoutingChatModel routing = new RoutingChatModel(List.of(
                new RoutingChatModel.Route("primary", model(
                        DeepSeekChatOptions.builder().model("deepseek-chat").build(),
                        prompt -> {
                            throw statusFailure(429, "429 too many requests");
                        },
                        prompt -> Flux.error(statusFailure(429, "429 too many requests")))),
                new RoutingChatModel.Route("backup", model(
                        OpenAiChatOptions.builder().model("qwen-plus").build(),
                        prompt -> {
                            backupPrompts.add(prompt);
                            return answer("备模型接住了");
                        },
                        prompt -> Flux.just(answer("备模型接住了"))))));

        ChatOptions incoming = DeepSeekChatOptions.builder()
                .model("deepseek-chat")
                .temperature(0.3)
                .toolCallbacks(List.of(TOOL))
                .toolContext(Map.of("claw4j.principal", "guest"))
                .build();

        ChatResponse response = routing.call(prompt(incoming));

        assertThat(textOf(response)).isEqualTo("备模型接住了");
        assertThat(backupPrompts).hasSize(1);

        ChatOptions sent = backupPrompts.getFirst().getOptions();
        assertThat(sent).isInstanceOf(OpenAiChatOptions.class);
        assertThat(sent.getModel())
                .as("把主模型的 model 名带给备模型，等于让别家跑一个它没有的模型")
                .isEqualTo("qwen-plus");
        assertThat(sent.getTemperature()).isEqualTo(0.3);
        assertThat(((org.springframework.ai.model.tool.ToolCallingChatOptions) sent).getToolCallbacks())
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactly("querySales");
        assertThat(((org.springframework.ai.model.tool.ToolCallingChatOptions) sent).getToolContext())
                .containsEntry("claw4j.principal", "guest");
    }

    @Test
    @DisplayName("两路都失败时抛 ModelRoutingException，不静默返回空回答")
    void allRoutesFailingThrowsInsteadOfReturningEmpty() {
        RoutingChatModel routing = new RoutingChatModel(List.of(
                new RoutingChatModel.Route("primary", model(
                        DeepSeekChatOptions.builder().build(),
                        prompt -> {
                            throw statusFailure(500, "主挂了");
                        },
                        prompt -> Flux.error(statusFailure(500, "主挂了")))),
                new RoutingChatModel.Route("backup", model(
                        OpenAiChatOptions.builder().build(),
                        prompt -> {
                            throw statusFailure(503, "备也挂了");
                        },
                        prompt -> Flux.error(statusFailure(503, "备也挂了"))))));

        assertThatThrownBy(() -> routing.call(prompt(DeepSeekChatOptions.builder().build())))
                .isInstanceOf(ModelRoutingException.class)
                .hasMessageContaining("primary")
                .hasMessageContaining("backup")
                .satisfies(failure -> assertThat(failure.getSuppressed())
                        .hasSize(2)
                        .allSatisfy(suppressed -> assertThat(suppressed)
                                .isInstanceOf(com.openai.errors.OpenAIServiceException.class)));
    }

    @Test
    @DisplayName("空路由在构造时就失败：没有候选的「降级」没有意义")
    void emptyRoutesAreRejected() {
        assertThatThrownBy(() -> new RoutingChatModel(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("至少要有一个");
    }

    @Test
    @DisplayName("流式首块之前失败才降级：备模型的块原样交给客户端")
    void streamFallsBackBeforeFirstChunk() {
        AtomicInteger backupCalls = new AtomicInteger();
        RoutingChatModel routing = new RoutingChatModel(List.of(
                new RoutingChatModel.Route("primary", model(
                        DeepSeekChatOptions.builder().build(),
                        prompt -> answer(""),
                        prompt -> Flux.error(new java.net.ConnectException("连接被重置")))),
                new RoutingChatModel.Route("backup", model(
                        OpenAiChatOptions.builder().build(),
                        prompt -> answer(""),
                        prompt -> {
                            backupCalls.incrementAndGet();
                            return Flux.just(answer("备"), answer("接住"));
                        }))));

        List<String> texts = routing.stream(prompt(DeepSeekChatOptions.builder().build()))
                .map(ModelAndToolSelectionTest::textOf)
                .collectList()
                .block();

        assertThat(texts).containsExactly("备", "接住");
        assertThat(backupCalls).hasValue(1);
    }

    @Test
    @DisplayName("已经吐过一块再失败就不换人：同一条回答发两遍比报错更难接受")
    void streamDoesNotRestartAfterFirstChunk() {
        AtomicInteger backupCalls = new AtomicInteger();
        RoutingChatModel routing = new RoutingChatModel(List.of(
                new RoutingChatModel.Route("primary", model(
                        DeepSeekChatOptions.builder().build(),
                        prompt -> answer(""),
                        prompt -> Flux.concat(
                                Flux.just(answer("前半截")),
                                Flux.error(new java.net.ConnectException("流中断了"))))),
                new RoutingChatModel.Route("backup", model(
                        OpenAiChatOptions.builder().build(),
                        prompt -> answer(""),
                        prompt -> {
                            backupCalls.incrementAndGet();
                            return Flux.just(answer("全文"));
                        }))));

        assertThatThrownBy(() -> routing.stream(prompt(DeepSeekChatOptions.builder().build()))
                .map(ModelAndToolSelectionTest::textOf)
                .collectList()
                .block())
                .as("流中断是可重试的失败，这里没换人只可能是因为「已经吐过一块」")
                .hasCauseInstanceOf(java.net.ConnectException.class);
        assertThat(backupCalls).hasValue(0);
    }

    @Test
    @DisplayName("路由顺序就是主在前：日志与降级都按这个顺序")
    void routeOrderIsPrimaryFirst() {
        RoutingChatModel routing = new RoutingChatModel(List.of(
                new RoutingChatModel.Route("deepseek", model(
                        DeepSeekChatOptions.builder().build(), prompt -> answer(""), prompt -> Flux.empty())),
                new RoutingChatModel.Route("qwen", model(
                        OpenAiChatOptions.builder().build(), prompt -> answer(""), prompt -> Flux.empty()))));

        assertThat(routing.routeOrder()).containsExactly("deepseek", "qwen");
    }

    @Test
    @DisplayName("流式上「备模型接住」也带工具：换了一家不是退化成只能说话")
    void streamingFallbackAlsoCarriesTools() {
        List<Prompt> backupPrompts = new CopyOnWriteArrayList<>();
        RoutingChatModel routing = new RoutingChatModel(List.of(
                new RoutingChatModel.Route("primary", model(
                        DeepSeekChatOptions.builder().build(),
                        prompt -> answer(""),
                        prompt -> Flux.error(new java.net.ConnectException("首块前挂了")))),
                new RoutingChatModel.Route("backup", model(
                        OpenAiChatOptions.builder().build(),
                        prompt -> answer(""),
                        prompt -> {
                            backupPrompts.add(prompt);
                            return Flux.just(answer("备答"));
                        }))));

        ChatOptions incoming = DeepSeekChatOptions.builder()
                .model("deepseek-chat")
                .toolCallbacks(List.of(TOOL))
                .toolContext(Map.of("claw4j.principal", "guest"))
                .build();
        routing.stream(prompt(incoming)).blockLast();

        assertThat(backupPrompts).hasSize(1);
        var options = (org.springframework.ai.model.tool.ToolCallingChatOptions) backupPrompts.getFirst().getOptions();
        assertThat(options.getToolCallbacks()).hasSize(1);
        assertThat(options.getToolContext()).containsEntry("claw4j.principal", "guest");
    }

    @Test
    @DisplayName("主模型 401 直接报出来并点名是哪一家，备模型一次都不碰")
    void unauthorizedDoesNotFallBack() {
        AtomicInteger backupCalls = new AtomicInteger();
        RoutingChatModel routing = new RoutingChatModel(List.of(
                new RoutingChatModel.Route("deepseek", model(
                        DeepSeekChatOptions.builder().build(),
                        prompt -> {
                            throw statusFailure(401, "Incorrect API key provided");
                        },
                        prompt -> Flux.error(statusFailure(401, "Incorrect API key provided")))),
                new RoutingChatModel.Route("qwen", model(
                        OpenAiChatOptions.builder().build(),
                        prompt -> {
                            backupCalls.incrementAndGet();
                            return answer("备模型的回答");
                        },
                        prompt -> Flux.just(answer("备模型的回答"))))));

        assertThatThrownBy(() -> routing.call(prompt(DeepSeekChatOptions.builder().build())))
                .isInstanceOf(ModelRoutingException.class)
                .hasMessageContaining("deepseek")
                .hasMessageContaining("401")
                .hasMessageContaining("没有降级");
        assertThat(backupCalls)
                .as("key 失效时去撞第二家，只会把一次确定的错误拖成两次网络往返")
                .hasValue(0);
    }

    @Test
    @DisplayName("可重试与不可重试各一张表：这条判据只有一处，加供应商不用重新讨论")
    void retryableClassificationTable() {
        Map<Throwable, Boolean> table = new java.util.LinkedHashMap<>();
        // 换一家可能好
        table.put(statusFailure(429, "限流"), true);
        table.put(statusFailure(500, "服务端错误"), true);
        table.put(statusFailure(503, "服务不可用"), true);
        table.put(new TransientAiException("限流"), true);
        table.put(new java.net.ConnectException("连接被拒"), true);
        table.put(new java.net.http.HttpTimeoutException("读超时"), true);
        table.put(new RuntimeException("被框架包了一层", statusFailure(502, "网关错误")), true);
        // 换一家也一样
        table.put(statusFailure(401, "key 无效"), false);
        table.put(statusFailure(403, "无权限"), false);
        table.put(statusFailure(400, "参数错"), false);
        table.put(statusFailure(422, "参数不可处理"), false);
        table.put(new NonTransientAiException("key 无效"), false);
        table.put(new IllegalArgumentException("空消息"), false);
        table.put(new IllegalStateException("模型返回了不该有的东西"), false);
        table.put(new RuntimeException("被框架包了一层", statusFailure(401, "key 无效")), false);

        table.forEach((failure, retryable) -> assertThat(ModelFailures.worthTryingAnother(failure))
                .as("%s 应%s降级", failure, retryable ? "该" : "不该")
                .isEqualTo(retryable));
    }

    @Test
    @DisplayName("降级后工具真的跑得起来：拿回的是库里的数，不是「我无法访问数据库」")
    void toolsActuallyRunAfterFallback() {
        List<Prompt> backupPrompts = new CopyOnWriteArrayList<>();
        RecordingTool tool = new RecordingTool("querySales", "128400");

        RoutingChatModel routing = new RoutingChatModel(List.of(
                new RoutingChatModel.Route("primary", model(
                        DeepSeekChatOptions.builder().build(),
                        prompt -> {
                            throw statusFailure(429, "429 too many requests");
                        },
                        prompt -> Flux.error(statusFailure(429, "429 too many requests")))),
                new RoutingChatModel.Route("backup", model(
                        OpenAiChatOptions.builder().build(),
                        prompt -> {
                            backupPrompts.add(prompt);
                            return wantsTool("call-1", "querySales");
                        },
                        prompt -> Flux.just(wantsTool("call-1", "querySales"))))));

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
    @DisplayName("只有一个工具也要先检索：模型看见的清单不随工具数量改变")
    void singleToolIsStillSearchedFirst() {
        RecordingTool lookup = new RecordingTool("lookup", "128400", "查询销售成交额");
        AtomicInteger turn = new AtomicInteger();
        FixedChatModel model = new FixedChatModel(prompt -> switch (turn.getAndIncrement()) {
            case 0 -> ChatPipelineTest.requestTool(ToolSearch.TOOL_NAME, "{\"query\":\"成交额\"}");
            case 1 -> ChatPipelineTest.requestTool("lookup", "{}");
            default -> ChatPipelineTest.answer("查到了 128400");
        });

        String text = port(model, List.of(lookup), ToolSearchProperties.defaults())
                .reply(message("上周华东成交额")).text();

        assertThat(toolNames(model.prompts().getFirst()))
                .as("第一轮只该看见检索工具：这就是渐进式披露，与工具总数无关")
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

    @Test
    @DisplayName("检索出来的工具不等于能调：没登记权限的照样在调用前被拦下")
    void searchIsNotAuthorization() {
        RecordingTool secret = new RecordingTool("secretTool", "机密数据", "查询机密档案");
        List<ToolCallback> tools = withNoise(secret, 20);
        AtomicInteger turn = new AtomicInteger();
        FixedChatModel model = new FixedChatModel(prompt -> turn.getAndIncrement() == 0
                ? ChatPipelineTest.requestTool(ToolSearch.TOOL_NAME, "{\"query\":\"机密\"}")
                : ChatPipelineTest.requestTool("secretTool", "{}"));

        String text = new ChatClientAgentPort(client(model, tools,
                // 只登记检索工具自己。secretTool 谁也调不动——它能不能调由登记表决定，
                // 跟"检索得到"没有关系。
                ToolPermissionRegistry.of(Map.of(ToolSearch.TOOL_NAME, ToolPermission.read())),
                ToolSearchProperties.defaults())).reply(message("机密档案")).text();

        assertThat(secret.calls()).as("越权的工具一次都不能被执行").isZero();
        assertThat(text).contains(AuthorizationAdvisor.DENIAL_PREFIX);
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
    @DisplayName("max-results 越界夹到 1–10，键缺失落回 5")
    void maxResultsIsClamped() {
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
                .withPropertyValues("claw4j.tool-search.max-results=3")
                .run(context -> assertThat(context.getBean(ToolSearchProperties.class).maxResults())
                        .isEqualTo(3));
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

        port(model, List.of(lookup), ToolSearchProperties.defaults())
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
                        .summary("在沙箱里执行脚本，用于批量处理数据").build());

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
                .contains("不代表没有这个能力");

        // 七、默认 bean 真的换掉了。只改测试构造等于测的还是旧实现。
        assertThat(new AgentAutoConfiguration().toolIndex())
                .as("装配出来的索引必须是新实现")
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
    @EnableConfigurationProperties(ToolSearchProperties.class)
    static class BindingOnly {
    }

    private static ChatClientAgentPort port(FixedChatModel model, List<ToolCallback> tools,
                                            ToolSearchProperties properties) {
        return new ChatClientAgentPort(client(model, tools, readsEverything(tools), properties));
    }

    private static ChatClient client(FixedChatModel model, List<ToolCallback> tools,
                                     ToolPermissionRegistry registry, ToolSearchProperties properties) {
        ToolIndex index = new ChineseToolIndex();
        List<ToolPermissionRegistry> registries = List.of(registry,
                // 与装配里那个 bean 同一条：检索工具自己按 read 放行
                ToolPermissionRegistry.of(Map.of(ToolSearch.TOOL_NAME, ToolPermission.read())));
        return ChatClient.builder(model, ObservationRegistry.NOOP, null, null,
                        ToolSearch.toolLoop(properties, index, ObservationRegistry.NOOP,
                                ToolCallBudget.MAX_TOTAL_CALLS))
                .defaultAdvisors(new AuthorizationAdvisor(registries))
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
        return new InboundMessage(Principal.readOnly("u-1"), "chat-1", text);
    }

    /** 所有工具都能调，用来把"检索"与"鉴权"分开验。 */
    private static ToolPermissionRegistry readsEverything(List<ToolCallback> tools) {
        Map<String, ToolPermission> permissions = tools.stream()
                .collect(java.util.stream.Collectors.toMap(
                        callback -> callback.getToolDefinition().name(),
                        callback -> ToolPermission.read("sales_order")));
        return ToolPermissionRegistry.of(permissions);
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
     * <p>做成规则而不是靠人记：漏实现一个接口不会有任何报错。
     */
    @Test
    @DisplayName("本仓库的 Advisor 两条路径都挂（架构闸）")
    void advisorsCoverBothPaths() {
        classes().that().resideInAPackage("io.github.claw4j.agent.advisor..")
                .and().implement(Advisor.class)
                .should().implement(CallAdvisor.class)
                .andShould().implement(StreamAdvisor.class)
                .check(agentClasses());
    }

    /** 两条规则看的是同一批类，导入一次就够。 */
    private static JavaClasses agentClasses() {
        return AgentClasses.IMPORTED;
    }

    /**
     * 放在 holder 里而不是静态初始化块：导入失败时 JUnit 报的是"哪条用例红了、为什么"，
     * 而不是一个 {@code ExceptionInInitializerError}（那时连哪个类都说不清）。
     */
    private static final class AgentClasses {

        private static final JavaClasses IMPORTED = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("io.github.claw4j.agent");
    }
}

package io.github.claw4j.agent.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 把多个 {@link ChatModel} 排成一队：主模型先上，失败退到下一个。
 *
 * <p>自己写而不用现成的：Spring AI 2.x 没有多模型主备这个抽象，它的路由是按用途分流
 * （不同 ChatClient 挂不同模型），没有"这家挂了换那家"。
 *
 * <p>降级条件只认"换一家可能好"的失败（判断在 {@link ModelFailures}，只此一处）：限流、5xx、
 * 连接失败、超时退到下一家；401 / 403 / 400 / 422 与本地构造错误直接报出。不做
 * {@code catch (RuntimeException)} 一刀切：那会让 key 失效时撞完所有供应商再捆成一堆抛出来，
 * 一次确定的错误被拖成 N 次网络往返。
 *
 * <p>流式有额外一条规矩：只在一个字节都还没吐出去时才降级。一旦有块流给了客户端，再转投备用
 * 模型就会把同一条回答发两遍（前半截主 + 全文备），这种重复在 IM 里比直接报错更难看。
 */
public class RoutingChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(RoutingChatModel.class);

    /**
     * 一个候选。
     *
     * @param name  供应商名，用于日志与错误信息
     * @param model 真正干活的那个模型
     */
    public record Route(String name, ChatModel model) {

        public Route {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(model, "model");
        }
    }

    private final List<Route> routes;

    /**
     * @param routes 候选，第一个是主；不能为空
     */
    public RoutingChatModel(List<Route> routes) {
        Objects.requireNonNull(routes, "routes");
        if (routes.isEmpty()) {
            throw new IllegalArgumentException("至少要有一个可用模型，空路由没有意义");
        }
        this.routes = List.copyOf(routes);
    }

    /** 路由顺序，主在前。启动日志与测试断言都看这个。 */
    public List<String> routeOrder() {
        return routes.stream().map(Route::name).toList();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        ModelRoutingException failure =
                new ModelRoutingException("所有模型都调用失败，路由顺序：" + routeOrder());
        for (Route route : routes) {
            try {
                return route.model().call(optionsFor(prompt, route));
            }
            catch (RuntimeException e) {
                if (!ModelFailures.worthTryingAnother(e)) {
                    log.warn("模型 {} 调用失败，这类错误换一家也不会好，直接报出：{}", route.name(), e.toString());
                    throw new ModelRoutingException(
                            "模型 " + route.name() + " 调用失败，这类错误换一家也不会好，因此没有降级：" + e, e);
                }
                log.warn("模型 {} 调用失败，降级到下一个（若有）", route.name(), e);
                failure.addSuppressed(e);
            }
        }
        throw failure;
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return streamFrom(prompt, 0);
    }

    private Flux<ChatResponse> streamFrom(Prompt prompt, int index) {
        Route route = routes.get(index);
        // "已经吐出过东西"是流式的降级开关：吐过就不再换人，否则客户端会收到重复内容。
        AtomicBoolean started = new AtomicBoolean(false);

        return route.model().stream(optionsFor(prompt, route))
                .doOnNext(chunk -> started.set(true))
                .onErrorResume(error -> {
                    boolean worthRetrying = ModelFailures.worthTryingAnother(error);
                    log.warn("模型 {} 流式调用失败（已吐出内容={}，可重试={}）",
                            route.name(), started.get(), worthRetrying, error);
                    int next = index + 1;
                    // 吐过内容就不再换人（会重复）；不可重试的失败也不换（换一家同样失败）。
                    if (started.get() || !worthRetrying || next >= routes.size()) {
                        return Flux.error(error);
                    }
                    return streamFrom(prompt, next);
                });
    }

    /**
     * 默认参数取主模型的：调用方拿它来填没指定的选项，用主模型的口径比自己造一份更符合主备语义。
     *
     * <p>覆写 {@code getOptions()} 而不是已废弃的 {@code getDefaultOptions()}——后者默认转调前者，
     * 两边都写没必要，还会多一条编译警告。
     *
     * @return 主模型的默认选项
     */
    @Override
    public ChatOptions getOptions() {
        return routes.get(0).model().getOptions();
    }

    /**
     * 把 Prompt 上的参数换成"这一家认识的类型"。
     *
     * <p>必须换：{@code ChatOptions} 的每家实现互不兼容，而厂商的 ChatModel 直接把
     * {@code prompt.getOptions()} 强转成自己那一型。主是通义、备是 DeepSeek 时，降级会把
     * {@code OpenAiChatOptions} 递给 DeepSeek，当场 {@code ClassCastException}——降级这条
     * 救命路径反而必崩。
     *
     * <p>换法：以这一家的默认参数为底，把调用方显式给的厂商无关参数（温度、topP、maxTokens 等）
     * 搬过去，model 一律用这一家的——把主模型的 model 名带给备模型，等于让 DeepSeek 去跑
     * qwen-plus。
     *
     * @param prompt 调用方给的 Prompt
     * @param route  这一次要用的候选
     * @return 选项类型与该候选匹配的 Prompt
     */
    private static Prompt optionsFor(Prompt prompt, Route route) {
        ChatOptions own = route.model().getOptions();
        ChatOptions incoming = prompt.getOptions();
        if (incoming == null) {
            return new Prompt(prompt.getInstructions(), own);
        }
        if (own.getClass().isInstance(incoming)) {
            return prompt;
        }
        ChatOptions.Builder<?> builder = own.mutate()
                .temperature(incoming.getTemperature())
                .topP(incoming.getTopP())
                .topK(incoming.getTopK())
                .maxTokens(incoming.getMaxTokens())
                .frequencyPenalty(incoming.getFrequencyPenalty())
                .presencePenalty(incoming.getPresencePenalty())
                .stopSequences(incoming.getStopSequences())
                .model(own.getModel());
        copyTools(builder, incoming);
        return new Prompt(prompt.getInstructions(), builder.build());
    }

    /**
     * 工具回调与工具上下文必须跟着一起搬。
     *
     * <p>上面只搬了厂商无关的标量，工具不在其中，而工具恰恰是这条链路的全部意义。漏掉它的表现不是
     * 报错，是降级后模型看不到工具：实测把主的 base-url 指到没人听的端口，备模型接住请求后答
     * "我无法直接访问数据库"，然后反过来跟用户核对口径。它没崩、没报异常，只是再也查不到数。
     *
     * <p>只在两边都支持工具时才搬：不支持工具的那一家本来也用不上。
     *
     * @param builder  这一家的选项 Builder
     * @param incoming 调用方给的选项
     */
    private static void copyTools(ChatOptions.Builder<?> builder, ChatOptions incoming) {
        if (!(builder instanceof ToolCallingChatOptions.Builder<?> target)
                || !(incoming instanceof ToolCallingChatOptions source)) {
            return;
        }
        List<ToolCallback> callbacks = source.getToolCallbacks();
        if (callbacks != null && !callbacks.isEmpty()) {
            target.toolCallbacks(callbacks);
        }
        Map<String, Object> context = source.getToolContext();
        if (context != null && !context.isEmpty()) {
            target.toolContext(context);
        }
    }
}

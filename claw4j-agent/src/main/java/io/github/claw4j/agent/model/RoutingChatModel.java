package io.github.claw4j.agent.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 把多个 {@link ChatModel} 排成一队：主模型先上，失败退到下一个。
 *
 * <p>为什么自己写而不是用现成的：Spring AI 2.x 没有"多模型主备"这个抽象，
 * 它的路由是按用途分流（不同 ChatClient 挂不同模型），没有"这家挂了换那家"。
 * 而"主供应商限流或宕机时还能答"恰恰是企业场景最需要的一件事。
 *
 * <p>降级条件刻意做得粗暴：**任何异常都降级**。代价是 400 类参数错误也会白跑一次备用模型，
 * 换来的是不必在此处解析各家的异常类型（DeepSeek 与 OpenAI SDK 的异常体系完全不同）。
 * 真的被限流打疼了再收窄成"只对超时与 5xx 降级"，那时要按状态码判定，属于 M2 的事。
 *
 * <p>流式有额外一条规矩：**只在一个字节都还没吐出去时才降级**。
 * 一旦有块流给了客户端，再转投备用模型就会把同一条回答发两遍（前半截主 + 全文备），
 * 这种重复在 IM 里比直接报错更难看。所以流式的降级窗口只有"首个块之前"。
 */
public class RoutingChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(RoutingChatModel.class);

    /** 一个候选：名字用于日志与错误信息，模型是真正干活的那个。 */
    public record Route(String name, ChatModel model) {

        public Route {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(model, "model");
        }
    }

    private final List<Route> routes;

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
            } catch (RuntimeException e) {
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
                    log.warn("模型 {} 流式调用失败（已吐出内容={}）", route.name(), started.get(), error);
                    if (started.get()) {
                        return Flux.error(error);
                    }
                    int next = index + 1;
                    if (next >= routes.size()) {
                        return Flux.error(error);
                    }
                    return streamFrom(prompt, next);
                });
    }

    /**
     * 默认参数取主模型的：调用方拿它来填没指定的选项，
     * 用主模型的口径比自己造一份更符合"主备"的语义。
     *
     * <p>覆写 {@code getOptions()} 而不是已废弃的 {@code getDefaultOptions()} ——
     * 后者默认就是转调前者，两边都写没必要，还会多一条编译警告。
     */
    @Override
    public ChatOptions getOptions() {
        return routes.get(0).model().getOptions();
    }

    /**
     * 把 Prompt 上的参数换成"这一家认识的类型"。
     *
     * <p>为什么必须换：{@code ChatOptions} 的每家实现互不兼容，而厂商的 ChatModel
     * 是直接把 {@code prompt.getOptions()} 强转成自己那一型的。于是主模型是通义、
     * 备是 DeepSeek 时，降级会把 {@code OpenAiChatOptions} 递给 DeepSeek，
     * 当场 {@code ClassCastException} —— 降级这条救命路径反而必崩。
     * （这个 bug 是靠假端点跑真实链路才暴露的，单元测试里两家都是假模型，谁也转不崩。）
     *
     * <p>换法：以这一家的默认参数为底，把调用方显式给的**厂商无关**参数
     * （温度、topP、maxTokens 等）搬过去，model 一律用这一家的 ——
     * 把主模型的 model 名带给备模型，等于让 DeepSeek 去跑 qwen-plus，必错。
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
        return new Prompt(prompt.getInstructions(), builder.build());
    }
}

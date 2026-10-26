package io.github.claw4j.agent.model;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import io.github.claw4j.agent.model.DeepSeekModelCondition;
import io.github.claw4j.agent.model.ModelProperties;
import io.github.claw4j.agent.model.QwenModelCondition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 真实模型装配：每家供应商各有自己的 bean，装不装由这家有没有 key 决定。
 *
 * <p>为什么自己构造而不吃 Spring AI 的自动配置：DeepSeek 的自动配置条件是
 * {@code spring.ai.model.chat=deepseek} 且 matchIfMissing=true，跟 key 无关。
 * 于是 classpath 里一旦有这个 starter，缺 key 时它照样激活并在构造时抛
 * "API key must be set"——应用根本起不来。
 *
 * <p>本模块因此只依赖纯模型包 {@code spring-ai-deepseek} / {@code spring-ai-openai}（不带 starter），
 * 自动配置压根不在 classpath 上，用不着去关。每家装不装由对应的 Condition
 * 按 key 有无决定；一家都没装上时，由 {@link MockModelConfiguration} 兜底。
 * 于是"没有 key 也要能启动"和"拿到 key 不用改代码"同时成立，且不需要 Maven profile 重新构建。
 *
 * <p>这些 bean 不该被业务直接使用 —— 业务拿到的 {@code ChatModel} 是
 * {@link RoutingModelConfiguration} 里那个带主备降级的 {@code routingChatModel}。
 */
@AutoConfiguration
@EnableConfigurationProperties(ModelProperties.class)
public class ModelConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ModelConfiguration.class);

    /** 具名 bean：RoutingModelConfiguration 按名字把它们排成主备。 */
    public static final String DEEPSEEK_BEAN = "deepSeekChatModel";

    public static final String QWEN_BEAN = "qwenChatModel";

    @Bean(DEEPSEEK_BEAN)
    @Conditional(DeepSeekModelCondition.class)
    public ChatModel deepSeekChatModel(ModelProperties properties) {
        ModelProperties.DeepSeek config = properties.getDeepseek();
        requireConfigured(config);

        // 超时是必须项：不设的话，同步调用会在 Tomcat 请求线程上无限等。
        // 两个路径语义不同：
        //  - 同步（RestClient）：connect + read 都要——一次调用就该有始有终；
        //  - 流式（WebClient）：只设 connect。read 超时会掐断长回答，
        //    整体上限交给 HTTP 通道的 streamTimeout + 取消传播（客户端断开即中断模型调用）。
        // 用 JDK 自带的 HttpClient，不为此引 reactor-netty。
        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(config.getConnectTimeout())
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(config.getReadTimeout());

        JdkClientHttpConnector webClientConnector = new JdkClientHttpConnector(httpClient);

        DeepSeekApi api = DeepSeekApi.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                .webClientBuilder(WebClient.builder().clientConnector(webClientConnector))
                .build();

        logModel("DeepSeek", config.getBaseUrl(), config.getModel(),
                config.getConnectTimeout(), config.getReadTimeout());

        return DeepSeekChatModel.builder()
                .deepSeekApi(api)
                // 必须显式给 model：默认 options 的 model 是 null，
                // 请求不带 model 时 DeepSeek 会返回空内容，而且不报错——很难排查。
                .options(DeepSeekChatOptions.builder()
                        .model(config.getModel())
                        .temperature(config.getTemperature())
                        .build())
                // 模型内部只留短重试（10 次 × 100ms，合计约 1 秒），不用默认的
                // RetryUtils.DEFAULT_RETRY_TEMPLATE（10 次、2s 起、×5 递增、上限 180s）。
                // 上层 RoutingChatModel 已经在做"换一家"，这里再叠一层指数退避就是乘法：
                // 真实联调时把主的 base-url 指到没人听的端口，等了 4 分 40 秒还没轮到备，
                // 用户看到的是一直转圈——降级写出来等于没写。短重试足够扛一次抖，
                // 真出问题让路由去换供应商，两边职责不重叠。
                .retryTemplate(RetryUtils.SHORT_RETRY_TEMPLATE)
                .build();
    }

    /**
     * 通义没有官方适配器，走它自己提供的 OpenAI 兼容端点（{@code /compatible-mode/v1}），
     * 因此这里用 {@code OpenAiChatModel}。
     *
     * <p>这一侧的超时旋钮比 DeepSeek 少：OpenAI SDK 只给了一个整体 timeout，
     * connect 与 read 不分家，所以取 readTimeout；连接建立由底层 okhttp 自己的默认超时兜底。
     * 另外把 SDK 内部的重试关掉 —— 重试与 {@code RoutingChatModel} 的降级叠加会把
     * "换一家试试"拖成"在这一家上死等三轮"，超时预算也跟着翻倍。
     */
    @Bean(QWEN_BEAN)
    @Conditional(QwenModelCondition.class)
    public ChatModel qwenChatModel(ModelProperties properties) {
        ModelProperties.Qwen config = properties.getQwen();
        requireConfigured(config);

        SpringAiOpenAiHttpClient httpClient = SpringAiOpenAiHttpClient.builder()
                .timeout(config.getReadTimeout())
                .build();

        ClientOptions options = ClientOptions.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .httpClient(httpClient)
                .timeout(config.getReadTimeout())
                .maxRetries(0)
                // openai-java 会校验 Jackson 版本；Spring Boot 4 带的是 Jackson 3，
                // 两边共存但版本号对不上，这项检查开着会在启动时报错。
                .checkJacksonVersionCompatibility(false)
                .build();

        OpenAIClient client = new OpenAIClientImpl(options);

        logModel("通义（OpenAI 兼容端点）", config.getBaseUrl(), config.getModel(),
                config.getConnectTimeout(), config.getReadTimeout());

        return OpenAiChatModel.builder()
                // 同步与异步客户端都给：call 走前者，stream 走后者，
                // 只给一个时流式路径要临时取 async()，不如在这里钉死。
                .openAiClient(client)
                .openAiClientAsync(client.async())
                .options(OpenAiChatOptions.builder()
                        .model(config.getModel())
                        .temperature(config.getTemperature())
                        .build())
                .build();
    }

    /**
     * 配置缺一半就启动失败，并且说清去哪配。
     * 静默拿空端点、空模型名去请求，表现出来的是"调用成功但回答为空"，极难排查。
     */
    private static void requireConfigured(ModelProperties.Provider config) {
        if (config.getApiKey().isEmpty()) {
            // 一个 key 都没读到。最常见的原因不是"没配"，而是"配了但没读进来"：
            // .env 只有在 local profile 下才被 import，而 IDEA 默认不激活它。
            throw new IllegalStateException(
                    "没有配置任何 API key，应用拒绝启动（ADR-0011 起不再降级到 mock 模型）。二选一："
                            + "① 仓库根目录放 .env 并用 local profile 启动（.env 由它 import）；"
                            + "② 直接设环境变量 DEEPSEEK_API_KEY 或 QWEN_API_KEY"
                            + "（IDEA：Run Configuration → Environment variables）。");
        }
        if (config.getBaseUrl() == null || config.getBaseUrl().isBlank()
                || config.getModel() == null || config.getModel().isBlank()) {
            throw new IllegalStateException(
                    "claw4j.model." + config.name() + ".base-url / model 未配置，唯一默认值在 application.yml。"
                            + "可用环境变量 " + config.envPrefix() + "_BASE_URL / "
                            + config.envPrefix() + "_MODEL 覆盖。");
        }
    }

    /**
     * 用了哪个模型必须在日志里看得见，否则"到底有没有连上真实模型"只能靠猜。
     * 只打 baseUrl 与 model，不打 key。
     */
    private static void logModel(String label, String baseUrl, String model,
                                 java.time.Duration connectTimeout, java.time.Duration readTimeout) {
        log.info("已装配 {} 真实模型：baseUrl={}, model={}, connectTimeout={}, readTimeout={}",
                label, baseUrl, model, connectTimeout, readTimeout);
    }
}

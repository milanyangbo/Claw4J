package io.github.claw4j.agent.model;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import com.openai.credential.BearerTokenCredential;
import io.github.claw4j.agent.model.DeepSeekModelCondition;
import io.github.claw4j.agent.model.ModelProperties;
import io.github.claw4j.agent.model.QwenModelCondition;
import io.github.claw4j.capability.secret.SpringSecretResolver;
import io.github.claw4j.secret.SecretResolver;
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
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 真实模型装配：每家供应商一个 bean，装不装由这家有没有 key 决定。
 *
 * <p>自己构造而不吃 Spring AI 的自动配置：DeepSeek 的自动配置条件是
 * {@code spring.ai.model.chat=deepseek} 且 matchIfMissing=true，与 key 无关。classpath 里一旦有
 * 这个 starter，缺 key 时它照样激活，并在构造时抛 "API key must be set"，应用根本起不来。
 *
 * <p>本模块因此只依赖纯模型包 {@code spring-ai-deepseek} / {@code spring-ai-openai}（不带
 * starter），自动配置不在 classpath 上，用不着去关。每家装不装由对应的 Condition 按 key 有无
 * 决定；一家都没装上时，装配方法自己抛异常让启动失败并说清去哪配（ADR-0011）。
 *
 * <p>这些 bean 不该被业务直接使用：业务拿到的 {@code ChatModel} 是
 * {@link RoutingModelConfiguration} 里那个带主备降级的 {@code routingChatModel}。
 */
@AutoConfiguration
@EnableConfigurationProperties(ModelProperties.class)
public class ModelConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ModelConfiguration.class);

    /** 具名 bean：RoutingModelConfiguration 按名字把它们排成主备。 */
    public static final String DEEPSEEK_BEAN = "deepSeekChatModel";

    public static final String QWEN_BEAN = "qwenChatModel";

    /**
     * DeepSeek 的模型 bean（主）。只在 {@code claw4j.model.deepseek.api-key} 有值时装。
     *
     * <p>超时是必须项：不设的话，同步调用会在 Tomcat 请求线程上无限等。两个路径语义不同——
     * 同步（RestClient）要 connect + read 两者，一次调用就该有始有终；流式（WebClient）只设
     * connect，read 超时会掐断长回答，整体上限交给 HTTP 通道的 streamTimeout 与取消传播。
     * 用 JDK 自带的 HttpClient，不为此引 reactor-netty。
     *
     * @param properties  模型配置，取 DeepSeek 那一段
     * @param environment 用来解析密钥句柄
     * @return DeepSeek 的同步与流式聊天模型
     */
    @Bean(DEEPSEEK_BEAN)
    @Conditional(DeepSeekModelCondition.class)
    public ChatModel deepSeekChatModel(ModelProperties properties, Environment environment) {
        ModelProperties.DeepSeek config = properties.getDeepseek();
        SecretResolver secrets = SpringSecretResolver.of(environment);
        requireConfigured(config, secrets);

        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(config.getConnectTimeout())
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(config.getReadTimeout());

        JdkClientHttpConnector webClientConnector = new JdkClientHttpConnector(httpClient);

        DeepSeekApi api = DeepSeekApi.builder()
                .baseUrl(config.getBaseUrl())
                // 每建一次请求取一次真值。DeepSeekApi 把 ApiKey 接到 RestClient / WebClient 的
                // defaultHeaders 上，那个 consumer 每次请求都会执行，所以给一个 lambda 就够——
                // 真值不会在构造时被取出来存进字段，轮换凭据也不必重启进程。
                .apiKey(() -> config.apiKey().render(secrets))
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                .webClientBuilder(WebClient.builder().clientConnector(webClientConnector))
                .build();

        logModel("DeepSeek", config.getBaseUrl(), config.getModel(), config.apiKey().text(),
                config.getConnectTimeout(), config.getReadTimeout());

        return DeepSeekChatModel.builder()
                .deepSeekApi(api)
                // 必须显式给 model：默认 options 的 model 是 null，
                // 请求不带 model 时 DeepSeek 会返回空内容，而且不报错——很难排查。
                .options(DeepSeekChatOptions.builder()
                        .model(config.getModel())
                        .temperature(config.getTemperature())
                        .build())
                // 模型内部只留短重试（10 次 × 100ms），不用默认的
                // RetryUtils.DEFAULT_RETRY_TEMPLATE（2s 起、×5 递增、上限 180s）。
                // 上层 RoutingChatModel 已经在做"换一家"，这里再叠指数退避就是乘法：
                // 实测把主的 base-url 指到没人听的端口，等 4 分 40 秒还没轮到备。
                .retryTemplate(RetryUtils.SHORT_RETRY_TEMPLATE)
                .build();
    }

    /**
     * 通义没有官方适配器，走它自己提供的 OpenAI 兼容端点（{@code /compatible-mode/v1}），
     * 因此这里用 {@code OpenAiChatModel}。
     *
     * <p>这一侧的超时旋钮比 DeepSeek 少：OpenAI SDK 只给了一个整体 timeout，connect 与 read 不分家，
     * 所以取 readTimeout，连接建立由底层 okhttp 的默认超时兜底。SDK 内部的重试关掉——重试与
     * {@code RoutingChatModel} 的降级叠加会把"换一家试试"拖成"在这一家上死等三轮"。
     *
     * @param properties  模型配置，取通义那一段
     * @param environment 用来解析密钥句柄
     * @return 走 OpenAI 兼容端点的同步与异步聊天模型
     */
    @Bean(QWEN_BEAN)
    @Conditional(QwenModelCondition.class)
    public ChatModel qwenChatModel(ModelProperties properties, Environment environment) {
        ModelProperties.Qwen config = properties.getQwen();
        SecretResolver secrets = SpringSecretResolver.of(environment);
        requireConfigured(config, secrets);

        SpringAiOpenAiHttpClient httpClient = SpringAiOpenAiHttpClient.builder()
                .timeout(config.getReadTimeout())
                .build();

        ClientOptions options = ClientOptions.builder()
                .baseUrl(config.getBaseUrl())
                // 与 DeepSeek 那一路同理，只是接缝不同：SDK 的 Credential 接受 Supplier，
                // 每次请求 authenticate 时现取。不用 apiKey(String)：那个重载会把真值
                // 钉进 ClientOptions，而那个对象活到进程结束。
                .credential(BearerTokenCredential.create(() -> config.apiKey().render(secrets)))
                .httpClient(httpClient)
                .timeout(config.getReadTimeout())
                .maxRetries(0)
                // openai-java 会校验 Jackson 版本；Spring Boot 4 带的是 Jackson 3，
                // 两边共存但版本号对不上，这项检查开着会在启动时报错。
                .checkJacksonVersionCompatibility(false)
                .build();

        OpenAIClient client = new OpenAIClientImpl(options);

        logModel("通义（OpenAI 兼容端点）", config.getBaseUrl(), config.getModel(), config.apiKey().text(),
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
     * 配置缺一半就启动失败，并且说清去哪配。静默拿空端点、空模型名去请求，表现出来的是
     * "调用成功但回答为空"，极难排查。
     *
     * <p>句柄在这里探一次真值（P08）：只问在不在，不取值。一个跑起来之后才在第 200 次请求上失败
     * 的系统，比起不来的系统更难查——所以缺失发生在启动期，报错只说句柄名。
     *
     * @param config  这一家的配置
     * @param secrets 密钥解析器
     */
    private static void requireConfigured(ModelProperties.Provider config, SecretResolver secrets) {
        if (config.apiKey().isAbsent()) {
            // 一个 key 都没读到。最常见的原因不是"没配"，而是"配了但没读进来"：
            // .env 只有在 local profile 下才被 import，而 IDEA 默认不激活它。
            throw new IllegalStateException(
                    "没有配置任何 API key，应用拒绝启动（ADR-0011 起不再降级到 mock 模型）。二选一："
                            + "① 仓库根目录放 .env 并用 local profile 启动（.env 由它 import）；"
                            + "② 直接设环境变量 DEEPSEEK_API_KEY 或 QWEN_API_KEY"
                            + "（IDEA：Run Configuration → Environment variables）。"
                            + "配置里写的是句柄，不是 key：claw4j.model.deepseek.api-key: secret:deepseek_api_key");
        }
        // 配了句柄却没有真值：在这里失败，别等到第一次提问。
        config.apiKey().requireResolvable(secrets);
        if (config.getBaseUrl() == null || config.getBaseUrl().isBlank()
                || config.getModel() == null || config.getModel().isBlank()) {
            throw new IllegalStateException(
                    "claw4j.model." + config.name() + ".base-url / model 未配置，唯一默认值在 application.yml。"
                            + "可用环境变量 " + config.envPrefix() + "_BASE_URL / "
                            + config.envPrefix() + "_MODEL 覆盖。");
        }
    }

    /**
     * 用了哪个模型、哪把钥匙必须在日志里看得见，否则"有没有连上真实模型""这次用的是哪个句柄"
     * 只能靠猜。打的是句柄名，不是真值——日志会被转发、会被贴进工单。
     *
     * @param label        供应商名，写进日志
     * @param baseUrl      请求端点
     * @param model        模型名
     * @param apiKeyHandle 密钥句柄名，不是真值
     * @param connectTimeout 连接超时
     * @param readTimeout    读超时
     */
    private static void logModel(String label, String baseUrl, String model, String apiKeyHandle,
                                 java.time.Duration connectTimeout, java.time.Duration readTimeout) {
        log.info("已装配 {} 真实模型：baseUrl={}, model={}, 凭据={}, connectTimeout={}, readTimeout={}",
                label, baseUrl, model, apiKeyHandle, connectTimeout, readTimeout);
    }
}

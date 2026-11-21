package io.github.claw4j.model.config;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import com.openai.credential.BearerTokenCredential;
import io.github.claw4j.secret.PropertySecretResolver;
import io.github.claw4j.secret.SecretResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 真实模型装配：遍历 {@code claw4j.model.providers}，按 {@code api} 选适配器建模型。
 *
 * <p>本票的核心变化：<b>加一家供应商只改配置</b>。以前每家一个 {@code @Bean} 方法、再在路由类里
 * 登记一行 {@code ADAPTERS}，加一家要改两处 Java；现在只有"协议"需要 Java 实现，
 * 供应商数量不再影响代码。
 *
 * <p>自己构造而不吃 Spring AI 的自动配置：厂商自动配置的条件是
 * {@code spring.ai.model.chat=deepseek} 且 matchIfMissing=true，与 key 无关，classpath 里有
 * starter 就会在缺 key 时抛异常，应用根本起不来。本模块只依赖纯模型包，装配权留在这里（ADR-0006）。
 *
 * <p>新形状下"配了但没钥匙"不再静默跳过：一家写进表里就是要用它，
 * 缺钥匙且没有 {@code auth: none} 就在启动时报错并点名是哪一家。不想用就删掉那一行。
 *
 * <p>这些 bean 不该被业务直接使用：业务拿到的 {@code ChatModel} 是
 * {@link RoutingModelConfiguration} 里那个带主备降级的 {@code routingChatModel}。
 */
@AutoConfiguration
@EnableConfigurationProperties(ModelProperties.class)
public class ModelConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ModelConfiguration.class);

    /**
     * 建好的模型，键是 {@code provider/model}。
     *
     * <p>一个目录行一个 ChatModel——不是一家一个。上下文窗口、最大输出都是行的属性，
     * 一家两行共用同一个 ChatModel 的话，调用时就没法按"这一次回答用的是哪一行"去算阈值。
     *
     * <p>用一个 holder 而不是"每行一个 {@code ChatModel} bean"：bean 的集合要在运行期按配置长度
     * 决定，{@code @Bean} 方法数却是编译期固定的。
     *
     * @param byRef 引用 → 模型，顺序与目录一致
     */
    public record ProviderModels(Map<io.github.claw4j.model.catalog.ModelRef, ChatModel> byRef) {

        public ProviderModels {
            // 不用 Map.copyOf：它不保证顺序，而备选顺序就是目录的顺序
            byRef = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(byRef));
        }
    }

    /**
     * 按 {@code api} 把每一家都建出来。
     *
     * @param properties  模型配置（表里每一家都建）
     * @param environment 用来解析凭据句柄
     * @return id → 模型
     * @throws IllegalStateException 某一家缺钥匙、缺端点或 {@code api} 不认识
     */
    @Bean
    public ProviderModels providerChatModels(ModelProperties properties, Environment environment) {
        SecretResolver secrets = PropertySecretResolver.of(environment::getProperty);
        Map<io.github.claw4j.model.catalog.ModelRef, ChatModel> built = new LinkedHashMap<>();
        for (String id : properties.names()) {
            ModelProperties.Provider config = properties.require(id);
            requireConfigured(config, secrets);
            for (io.github.claw4j.model.catalog.ModelRow row : config.getModels()) {
                ChatModel model = switch (config.getApi()) {
                    case ModelProperties.API_DEEPSEEK -> deepSeek(config, row, secrets);
                    case ModelProperties.API_OPENAI_COMPLETIONS -> openAiCompatible(config, row, secrets);
                    // 绑定时的 validate() 已经拒过未知值，这里只是编译期的穷尽性兜底
                    default -> throw new IllegalStateException("claw4j.model.providers." + id + ".api="
                            + config.getApi() + " 没有对应的适配器，可选 " + ModelProperties.KNOWN_APIS);
                };
                built.put(new io.github.claw4j.model.catalog.ModelRef(id, row.getId()), model);
            }
        }
        return new ProviderModels(built);
    }

    /**
     * 目录：每一行的元数据摊平成 {@code provider/model → 行}，主模型那一行也从这里定。
     *
     * @param properties 供应商表
     * @return 目录
     */
    @Bean
    public io.github.claw4j.model.catalog.ModelCatalog modelCatalog(ModelProperties properties) {
        return io.github.claw4j.model.catalog.ModelCatalog.of(properties);
    }

    /**
     * DeepSeek 官方适配器。
     *
     * <p>超时是必须项：不设的话，同步调用会在请求线程上无限等。两个路径语义不同——同步
     * （RestClient）要 connect + read 两者；流式（WebClient）只设 connect，read 超时会掐断长回答。
     * 用 JDK 自带的 HttpClient，不为此引 reactor-netty。
     *
     * @param config  这一家的配置
     * @param secrets 密钥解析器
     * @return 同步与流式聊天模型
     */
    private static ChatModel deepSeek(ModelProperties.Provider config,
                                      io.github.claw4j.model.catalog.ModelRow row,
                                      SecretResolver secrets) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(config.getConnectTimeout())
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(config.getReadTimeout());

        JdkClientHttpConnector webClientConnector = new JdkClientHttpConnector(httpClient);

        DeepSeekApi api = DeepSeekApi.builder()
                .baseUrl(config.getBaseUrl())
                // 每建一次请求取一次真值：ApiKey 接到 RestClient / WebClient 的 defaultHeaders 上，
                // 那个 consumer 每次请求都会执行，真值不会在构造时被取出来存进字段。
                .apiKey(() -> config.apiKey().render(secrets))
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                .webClientBuilder(WebClient.builder().clientConnector(webClientConnector))
                .build();

        logModel(config, row, "DeepSeek 适配器");

        return DeepSeekChatModel.builder()
                .deepSeekApi(api)
                // 必须显式给 model：默认 options 的 model 是 null，
                // 请求不带 model 时 DeepSeek 会返回空内容，而且不报错——很难排查。
                .options(DeepSeekChatOptions.builder()
                        .model(row.getId())
                        .temperature(config.getTemperature())
                        .build())
                // 模型内部只留短重试（10 次 × 100ms），不用默认的两秒起跳、上限 180s 那一份。
                // 上层路由已经在做"换一家"，这里再叠指数退避就是乘法。
                .retryTemplate(RetryUtils.SHORT_RETRY_TEMPLATE)
                .build();
    }

    /**
     * 任何 OpenAI 兼容端点（通义、vLLM、内网网关）。{@code auth: none} 时不装凭据。
     *
     * <p>这一侧的超时旋钮比 DeepSeek 少：SDK 只给一个整体 timeout，connect 与 read 不分家，
     * 所以取 readTimeout。SDK 内部重试关掉——重试与路由层的降级叠加会把"换一家试试"
     * 拖成"在这一家上死等三轮"。
     *
     * @param config  这一家的配置
     * @param secrets 密钥解析器
     * @return 走 OpenAI 兼容端点的同步与异步聊天模型
     */
    private static ChatModel openAiCompatible(ModelProperties.Provider config,
                                              io.github.claw4j.model.catalog.ModelRow row,
                                              SecretResolver secrets) {
        SpringAiOpenAiHttpClient httpClient = SpringAiOpenAiHttpClient.builder()
                .timeout(config.getReadTimeout())
                .build();

        ClientOptions.Builder options = ClientOptions.builder()
                .baseUrl(config.getBaseUrl())
                .httpClient(httpClient)
                .timeout(config.getReadTimeout())
                .maxRetries(0)
                // openai-java 会校验 Jackson 版本；Spring Boot 4 带的是 Jackson 3，
                // 两边共存但版本号对不上，这项检查开着会在启动时报错。
                .checkJacksonVersionCompatibility(false);
        if (config.requiresCredential()) {
            // SDK 的 Credential 接受 Supplier，每次请求 authenticate 时现取；
            // 不用 apiKey(String)：那个重载会把真值钉进 ClientOptions，活到进程结束。
            options.credential(BearerTokenCredential.create(() -> config.apiKey().render(secrets)));
        }
        else {
            // auth: none。SDK 的 build() 不接受"完全没有凭据"：它有一个"自己实现认证器"的入口，
            // 但那个方法是 @JvmSynthetic（Java 看不见），公开 API 里只能给 Credential。
            // 于是取空 token——发出去的是 `Authorization: Bearer`（空值），不校验头部的端点照常工作。
            // 严格"不发这个头"要等适配器给出口子，记在 ADR-0035 的偏差里。
            options.credential(BearerTokenCredential.create(() -> ""));
        }

        OpenAIClient client = new OpenAIClientImpl(options.build());

        logModel(config, row, config.requiresCredential()
                ? "OpenAI 兼容端点" : "OpenAI 兼容端点（auth: none）");

        return OpenAiChatModel.builder()
                .openAiClient(client)
                .openAiClientAsync(client.async())
                .options(OpenAiChatOptions.builder()
                        .model(row.getId())
                        .temperature(config.getTemperature())
                        .build())
                .build();
    }

    /**
     * 配置缺一半就启动失败，并且说清去哪配。静默拿空端点、空模型名去请求，表现出来的是
     * "调用成功但回答为空"，极难排查。
     *
     * <p>句柄在这里探一次真值（P08）：只问在不在，不取值。一个跑起来之后才在第 200 次请求上失败
     * 的系统，比起不来的系统更难查。
     *
     * @param config  这一家的配置
     * @param secrets 密钥解析器
     */
    private static void requireConfigured(ModelProperties.Provider config, SecretResolver secrets) {
        if (config.requiresCredential()) {
            if (config.apiKey().isAbsent()) {
                throw new IllegalStateException("claw4j.model.providers." + config.id()
                        + " 没有 api-keys，应用拒绝启动（ADR-0011 起不再降级到 mock 模型）。二选一："
                        + "① 仓库根目录放 .env 并用 local profile 启动（.env 由它 import）；"
                        + "② 直接设环境变量 " + config.envPrefix() + "_API_KEY。"
                        + "确实不需要鉴权的端点写 auth: none。");
            }
            config.apiKey().requireResolvable(secrets);
        }
        if (config.getBaseUrl() == null || config.getBaseUrl().isBlank()) {
            throw new IllegalStateException(
                    "claw4j.model.providers." + config.id() + " 的 base-url 未配置，"
                            + "唯一默认值在 application.yml。"
                            + "可用环境变量 " + config.envPrefix() + "_BASE_URL 覆盖。");
        }
    }

    /**
     * 用了哪份协议、哪个端点、哪把钥匙必须在日志里看得见。打的是句柄名，不是真值——
     * 日志会被转发、会被贴进工单。
     *
     * @param config 这一家的配置
     * @param label  适配器名，写进日志
     */
    private static void logModel(ModelProperties.Provider config,
                                 io.github.claw4j.model.catalog.ModelRow row, String label) {
        log.info("已装配 {}：id={}, baseUrl={}, model={}, 窗口={}, 凭据={}, connectTimeout={}, readTimeout={}",
                label, config.id(), config.getBaseUrl(), row.getId(), row.getContextWindow(),
                config.requiresCredential() ? config.apiKey().text() : "（auth: none）",
                config.getConnectTimeout(), config.getReadTimeout());
    }
}

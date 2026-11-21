package io.github.claw4j.model.config;

import io.github.claw4j.secret.SecretValue;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 模型供应商的凭据、协议与路由顺序。
 *
 * <p>供应商是<b>以 id 为键的表</b>（{@code claw4j.model.providers.<id>}），照 OpenClaw 的形状
 * （`docs/concepts/model-providers/custom-providers.md`）。为什么不是列表 + {@code name} 字段：
 * 那样"重名"是一个必须靠校验才能发现的状态，而 map 里结构上不可能重名；更重要的是，
 * 引用（{@code primary}、角色、白名单）写的就是这个 id，写错时能对着键集合报错。
 *
 * <p>一家要说清两件事：{@code api} 决定用哪个 Spring AI 适配器（{@code deepseek} /
 * {@code openai-completions}），{@code api-keys} 是凭据句柄。**加一家供应商只改配置**——
 * 这是本形状相对"每家一个 Java bean"的唯一实质收益。
 *
 * <p>凭据是句柄，不是值（P08）：yaml 里写 {@code api-keys: [secret:deepseek_api_key]}，
 * 真值在发请求那一刻才由 {@code SecretResolver} 换出来。类里没有返回明文 key 的方法。
 */
@ConfigurationProperties(prefix = "claw4j.model")
public class ModelProperties {

    /** 协议：DeepSeek 官方适配器。 */
    public static final String API_DEEPSEEK = "deepseek";

    /** 协议：OpenAI 兼容端点（通义、DeepSeek 自家的 openai 兼容层都走这一种）。 */
    public static final String API_OPENAI_COMPLETIONS = "openai-completions";

    /** 认得的协议。未知值启动失败并列出它。 */
    public static final List<String> KNOWN_APIS = List.of(API_DEEPSEEK, API_OPENAI_COMPLETIONS);

    /** {@code auth} 的唯一取值：不发 {@code Authorization}。只对 {@code openai-completions} 生效。 */
    public static final String AUTH_NONE = "none";

    /**
     * 主模型供应商 id。配了不存在的 id 时启动失败并列出可选值——静默忽略会让"以为在用千问、
     * 其实还在用 DeepSeek"这种事查半天。
     */
    private String primary = "deepseek";

    /** id → 一家供应商。顺序即备选顺序（{@code primary} 那家永远排第一个）。 */
    private Map<String, Provider> providers = new LinkedHashMap<>();

    public String getPrimary() {
        return primary;
    }

    public void setPrimary(String primary) {
        this.primary = primary == null ? "" : primary.trim().toLowerCase(Locale.ROOT);
    }

    public Map<String, Provider> getProviders() {
        return providers;
    }

    public void setProviders(Map<String, Provider> providers) {
        this.providers = providers == null ? new LinkedHashMap<>() : providers;
    }

    /** 表里配了哪几家，按配置顺序。报错信息与"primary 可选值"都用它。 */
    public List<String> names() {
        return List.copyOf(providers.keySet());
    }

    /**
     * 按 id 取一家。<b>找不到不返回 null</b>，而是给一个只带 id 的空壳——与"这一家没配"在语义上
     * 是一回事，读的一方不必处处判空。装配层不要用它，那边用 {@link #require(String)}。
     *
     * @param id 供应商 id
     * @return 这一家的配置，或一个空壳
     */
    public Provider provider(String id) {
        Provider found = providers.get(id);
        return found == null ? Provider.absent(id) : found;
    }

    /**
     * 按 id 取一家，缺了就抛。报错里带上"现在配了哪几家"，因为这类错误的成因几乎都是
     * "改了 id 没改引用"。
     *
     * @param id 供应商 id
     * @return 这一家的配置
     * @throws IllegalStateException 表里没有这一条
     */
    public Provider require(String id) {
        Provider found = providers.get(id);
        if (found != null) {
            return found;
        }
        if (providers.isEmpty()) {
            throw new IllegalStateException("claw4j.model.providers 是空的，一个模型供应商都没有"
                    + "（ADR-0011 起没有 mock 兜底，缺 key 就是起不来）。二选一："
                    + "① 仓库根放 .env 并用 local profile 启动（.env 由它 import）；"
                    + "② 直接设环境变量 DEEPSEEK_API_KEY 或 QWEN_API_KEY。");
        }
        throw new IllegalStateException("claw4j.model.providers 里没有 id=" + id + " 这一条，现在配了 "
                + names() + "。claw4j.model.primary=" + primary + "，两者指的不是同一家时会在这里对不上。");
    }

    /**
     * 绑定完就查形状：id 规范化、{@code api} 认得、{@code auth} 写得对、该有钥匙的有钥匙。
     *
     * <p>这里只管"配置写得对不对"。真值在不在部署环境里由装配层判——那个要看 {@code Environment}，
     * 不属于绑定。
     *
     * @throws IllegalStateException 形状不对
     */
    @PostConstruct
    void validateShape() {
        Map<String, Provider> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Provider> entry : providers.entrySet()) {
            String raw = entry.getKey();
            String id = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            if (id.isEmpty()) {
                throw new IllegalStateException("claw4j.model.providers 里有一个空的 id。"
                        + "键就是供应商的名字，也是 primary / 角色 / 白名单引用它时写的那个字符串。");
            }
            if (id.chars().allMatch(Character::isDigit)) {
                // 旧写法是列表：providers[0].name=deepseek。绑成 map 时那个下标会变成一个键 "0"，
                // 而 "0" 既不是谁的名字，也不会被 primary 引用——不在这里拦住的话，
                // 表现是"配了却不生效"，报错离真正的原因很远。
                throw new IllegalStateException("claw4j.model.providers 的键是数字（" + raw + "），"
                        + "这是旧的列表写法。改成以 id 为键的表："
                        + "claw4j.model.providers.deepseek.api / .api-keys / .base-url / .model，"
                        + "并把原来的 name 删掉（键就是名字）。");
            }
            Provider provider = entry.getValue();
            if (provider == null) {
                throw new IllegalStateException("claw4j.model.providers." + id + " 是空的，"
                        + "至少要有 api 与 api-keys 两项。");
            }
            provider.attach(id);
            provider.validate();
            normalized.put(id, provider);
        }
        this.providers = normalized;
        if (!providers.isEmpty() && !providers.containsKey(primary)) {
            throw new IllegalStateException("claw4j.model.primary=" + primary
                    + " 不在 claw4j.model.providers 里，表里配的是 " + names() + "。");
        }
    }

    /**
     * 一家供应商要配的东西。两家共用同一套字段，装配与校验才能写成一份。
     */
    public static class Provider {

        /** 由 {@link ModelProperties#validateShape()} 按 map 的键填进来；配置里不写。 */
        private String id;

        /** 协议：决定用哪个 Spring AI 适配器。见 {@link ModelProperties#KNOWN_APIS}。 */
        private String api;

        /** 只有 {@code none} 一个取值：不发 Authorization。不写表示要钥匙。 */
        private String auth;

        /** 凭据句柄。多把为将来的轮换留位置，本次只用第一把。 */
        private List<String> apiKeys = new ArrayList<>();

        /** {@link #apiKey()} 的解析结果，第一次要的时候才算。 */
        private SecretValue apiKey;

        /**
         * 端点与模型名的默认值只活在 application.yml（ADR-0006）。
         * 这里刻意不放 Java 侧默认值：两处各写一份必然改一处漏一处。
         */
        private String baseUrl;

        private String model;

        /**
         * 建立连接的最长时间。这个默认值是防呆兜底，刻意放代码里而不是 yml：
         * 忘了配置也必须有超时，否则同步调用会在请求线程上无限等。
         */
        private Duration connectTimeout = Duration.ofSeconds(5);

        /**
         * 同步调用的读超时。流式不走这个（读超时会掐断长回答），
         * 流式的整体上限由 HTTP 通道的 streamTimeout 加取消传播兜底。
         */
        private Duration readTimeout = Duration.ofSeconds(60);

        /**
         * 采样温度。默认 {@code 0.0}：这是个查数、调工具的 Agent，不是写诗的——同一个问题两次问出
         * 两个不同的数是故障。
         */
        private Double temperature = 0.0;

        /**
         * "这一家没配"的空壳：只有 id，凭据与端点都是空。
         *
         * @param id 供应商 id
         * @return 空壳
         */
        static Provider absent(String id) {
            Provider absent = new Provider();
            absent.id = id;
            return absent;
        }

        /**
         * 把 map 的键认成这一家的 id。报错信息、环境变量前缀都靠它。
         *
         * @param id 供应商 id
         */
        void attach(String id) {
            this.id = id;
            this.apiKey = null;
        }

        /** 形状校验。见 {@link ModelProperties#validateShape()}。 */
        void validate() {
            if (api == null || api.isBlank()) {
                throw new IllegalStateException("claw4j.model.providers." + id + ".api 未配置。"
                        + "它决定用哪个适配器，可选 " + KNOWN_APIS + "。");
            }
            api = api.trim().toLowerCase(Locale.ROOT);
            if (!KNOWN_APIS.contains(api)) {
                throw new IllegalStateException("claw4j.model.providers." + id + ".api=" + api
                        + " 不认识。可选 " + KNOWN_APIS + "："
                        + "deepseek 是 DeepSeek 官方适配器，openai-completions 是任何 OpenAI 兼容端点"
                        + "（通义、vLLM、内网网关都走这一种）。");
            }
            if (auth != null && !auth.isBlank() && !AUTH_NONE.equalsIgnoreCase(auth.trim())) {
                throw new IllegalStateException("claw4j.model.providers." + id + ".auth=" + auth
                        + " 不认识，只有两种写法：不写（要钥匙）或 " + AUTH_NONE + "（不发 Authorization）。");
            }
            if (AUTH_NONE.equalsIgnoreCase(auth == null ? "" : auth.trim())) {
                if (API_DEEPSEEK.equals(api)) {
                    throw new IllegalStateException("claw4j.model.providers." + id
                            + " 同时写了 api=deepseek 与 auth=none：DeepSeek 官方端点必须带钥匙，"
                            + "不发 Authorization 的只有 openai-completions。");
                }
                auth = AUTH_NONE;
            }
            else {
                auth = null;
                if (apiKeys == null || apiKeys.stream().allMatch(key -> key == null || key.isBlank())) {
                    throw new IllegalStateException("claw4j.model.providers." + id + ".api-keys 是空的。"
                            + "要么配一把（句柄写法 secret:" + id + "_api_key，真值放部署环境），"
                            + "要么这一家不支持鉴权时写 auth: none。");
                }
            }
        }

        /** 这一家要不要发 Authorization。 */
        public boolean requiresCredential() {
            return !AUTH_NONE.equals(auth);
        }

        public String id() {
            return id;
        }

        public String getApi() {
            return api;
        }

        public void setApi(String api) {
            this.api = api;
        }

        public String getAuth() {
            return auth;
        }

        public void setAuth(String auth) {
            this.auth = auth;
        }

        public List<String> getApiKeys() {
            return apiKeys;
        }

        public void setApiKeys(List<String> apiKeys) {
            this.apiKeys = apiKeys == null ? new ArrayList<>() : apiKeys;
            this.apiKey = null;
        }

        /**
         * 这一家用的凭据句柄（第一把）。空（{@code isAbsent()}）表示没配。
         *
         * <p>不提供 {@code String} 形态的 getter：一旦有 {@code getApiKey()} 返回字符串，
         * 下一个人就会拿它去拼 header。要真值只有一个入口——{@code apiKey().render(resolver)}。
         *
         * <p>解析推迟到第一次调用而不是在 setter 里：yaml 里 id 由 map 的键给出，
         * 报错信息要带上它才有用。
         *
         * @return 凭据句柄，可能是 absent
         */
        public SecretValue apiKey() {
            if (apiKey == null) {
                String handle = apiKeys == null || apiKeys.isEmpty() ? null : apiKeys.getFirst();
                apiKey = SecretValue.parse(handle, "claw4j.model.providers." + (id == null ? "?" : id)
                        + ".api-keys[0]", false);
            }
            return apiKey;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }

        public Double getTemperature() {
            return temperature;
        }

        public void setTemperature(Double temperature) {
            this.temperature = temperature;
        }

        /** 环境变量前缀，只用于启动失败时告诉人去哪里配。 */
        public String envPrefix() {
            return id == null ? "" : id.toUpperCase(Locale.ROOT);
        }
    }
}

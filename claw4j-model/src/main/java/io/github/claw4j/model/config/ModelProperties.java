package io.github.claw4j.model.config;

import io.github.claw4j.secret.SecretValue;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 模型供应商的凭据、开关与路由顺序。
 *
 * <p>供应商是<b>一张表</b>（{@code providers} 是列表，每项自带 {@code name}），不是以名字为键的嵌套节点：
 * 每一家要配的东西是同一套（端点、凭据、模型名、两个超时、温度），表里一行就是一家。
 * 代价认下来——{@code name} 成了普通字段，写重、写空只有校验能发现，所以 {@link #validateShape()}
 * 在绑定时就查。
 *
 * <p>凭据是句柄，不是值（P08）：yaml 里写 {@code api-key: secret:deepseek_api_key}，真值在发请求
 * 那一刻才由 {@code SecretResolver} 从部署环境换出来。于是这个类、它的 toString、它的日志，
 * 从头到尾都拿不到 key，只知道"这次用的是哪把钥匙"。不写成
 * {@code ${DEEPSEEK_API_KEY:}}：Spring 在绑定期就解成真值，字段活到进程结束，任何能读到配置的
 * 组件都能随手取走。
 *
 * <p>没有"要不要 mock"的开关：ADR-0011 取消了 mock 兜底，一个 key 都没有就启动失败，
 * 那个三态字段只会多一处"配了也不生效"的入口。
 *
 * <p>{@code primary} 决定 {@code RoutingChatModel} 里谁先上：主模型调用失败才退到备。这是主备
 * 降级，不是按用途分流（摘要、子 Agent 走便宜模型）——后者还没做。
 */
@ConfigurationProperties(prefix = "claw4j.model")
public class ModelProperties {

    /** 供应商名字，同时是 {@code primary} 的取值与路由键。 */
    public static final String DEEPSEEK = "deepseek";

    public static final String QWEN = "qwen";

    /**
     * 主模型供应商。配了不存在的名字时启动失败并列出可选值——静默忽略会让"以为在用千问、
     * 其实还在用 DeepSeek"这种事查半天。
     */
    private String primary = DEEPSEEK;

    /** 供应商表，顺序即备选顺序（{@code primary} 那家永远排在第一个）。 */
    private List<Provider> providers = new ArrayList<>();

    public String getPrimary() {
        return primary;
    }

    public void setPrimary(String primary) {
        this.primary = primary == null ? "" : primary.trim().toLowerCase(Locale.ROOT);
    }

    public List<Provider> getProviders() {
        return providers;
    }

    public void setProviders(List<Provider> providers) {
        this.providers = providers == null ? new ArrayList<>() : providers;
    }

    /** 表里配了哪几家，按配置顺序。报错信息与"primary 可选值"都用它。 */
    public List<String> names() {
        return providers.stream().map(Provider::name).filter(Objects::nonNull).toList();
    }

    /**
     * 按名字取一家。<b>找不到不返回 null</b>，而是给一个只带名字的空壳（凭据为空、端点为空）——
     * 与"这一家没配"在语义上是一回事，读的一方因此不必处处判空。
     *
     * <p>装配层不要用它：那边缺一条就该让启动失败并说清缺哪条，那里用 {@link #require(String)}。
     *
     * @param name 供应商名
     * @return 这一家的配置，或一个空壳
     */
    public Provider provider(String name) {
        Provider found = find(providers, name);
        return found == null ? Provider.absent(name) : found;
    }

    /**
     * 按名字取一家，缺了就抛。装配层专用：报错里带上"现在配了哪几家"，
     * 因为这类错误的成因几乎都是"改了名字没改引用"。
     *
     * <p>整张表空着是另一回事：那多半是这段配置被整块删了，报"没有 name=xxx 这一条"会把人引到
     * 名字上去，所以单独给 ADR-0011 那条指引（去哪配 key）。
     *
     * @param name 供应商名
     * @return 这一家的配置
     * @throws IllegalStateException 表里没有这一条
     */
    public Provider require(String name) {
        Provider found = find(providers, name);
        if (found != null) {
            return found;
        }
        if (providers.isEmpty()) {
            throw new IllegalStateException("claw4j.model.providers 是空的，一个模型供应商都没有"
                    + "（ADR-0011 起没有 mock 兜底，缺 key 就是起不来）。二选一："
                    + "① 仓库根放 .env 并用 local profile 启动（.env 由它 import）；"
                    + "② 直接设环境变量 DEEPSEEK_API_KEY 或 QWEN_API_KEY。");
        }
        throw new IllegalStateException("claw4j.model.providers 里没有 name=" + name + " 这一条，现在配了 "
                + names() + "。claw4j.model.primary=" + primary + "，两者指的不是同一家时会在这里对不上。");
    }

    /**
     * 按名字找一家，找不到返回 {@code null}。
     *
     * <p>三处查找共用这一份：{@link #provider(String)}、{@link #require(String)}、以及装配条件
     * （它比别处更早拿到表，只能自己绑，但"算不算同一家"的判断不该跟着再写一遍）。
     *
     * @param providers 供应商表
     * @param name      供应商名
     * @return 找到的那一家，或 null
     */
    static Provider find(List<Provider> providers, String name) {
        return providers.stream()
                .filter(provider -> name.equals(provider.name()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 绑定完就查形状：名字不能空、不能重。剩下两项（primary 指的是谁、这一家的 key 有没有真值）
     * 要等容器起来才判，在装配层。
     *
     * @throws IllegalStateException 有空白名字或重名
     */
    @PostConstruct
    void validateShape() {
        Set<String> seen = new LinkedHashSet<>();
        for (int index = 0; index < providers.size(); index++) {
            Provider provider = providers.get(index);
            String name = provider.name();
            if (name == null || name.isBlank()) {
                throw new IllegalStateException("claw4j.model.providers[" + index
                        + "] 没有 name。每一条都要写名字：它是 primary 的取值，也是路由与日志里的称呼。");
            }
            if (!seen.add(name)) {
                throw new IllegalStateException("claw4j.model.providers 里 name=" + name
                        + " 出现了两次。两家同名时哪一条生效取决于绑定顺序，"
                        + "这种不确定性不该存在于配置里。");
            }
        }
    }

    /**
     * 一家供应商要配的东西。两家共用同一套字段，装配与校验才能写成一份。
     */
    public static class Provider {

        /**
         * 名字。写成可读字段而不是由子类覆写：供应商是一个列表，绑定层只认字段。
         * 它是 {@code primary} 的取值，也是路由与日志里的称呼。
         */
        private String name;

        /** 句柄原文。取值只能经 {@link #apiKey()} 拿到，而它只肯吐出名字。 */
        private String apiKeyHandle;

        /** {@link #apiKey()} 的解析结果，第一次要的时候才算。 */
        private SecretValue apiKey;

        /**
         * 端点与模型名的默认值只活在 application.yml（ADR-0006）。
         * 这里刻意不放 Java 侧默认值：两处各写一份必然改一处漏一处，
         * 缺失时装配层直接启动失败并说明原因，好过拿错误端点慢慢排查。
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
         * 两个不同的数是故障。温度也是测试可复现的前提：连真实模型之后，非零温度等于给每条断言
         * 掺一份随机。要它发散时按环境变量调。
         */
        private Double temperature = 0.0;

        /**
         * "这一家没配"的空壳：只有名字，凭据与端点都是空。
         *
         * @param name 供应商名
         * @return 空壳
         */
        static Provider absent(String name) {
            Provider absent = new Provider();
            absent.setName(name);
            return absent;
        }

        public String name() {
            return name;
        }

        public void setName(String name) {
            this.name = name == null ? null : name.trim().toLowerCase(Locale.ROOT);
            // 名字变了，报错里的配置项名跟着变，缓存那份解析结果作废。
            this.apiKey = null;
        }

        /**
         * 这一家用的凭据句柄。空（{@code isAbsent()}）表示这一家没配。
         *
         * <p>不提供 {@code String} 形态的 getter：一旦有 {@code getApiKey()} 返回字符串，
         * 下一个人就会拿它去拼 header，那把钥匙就有了第二个出口。要真值只有一个入口——
         * {@code apiKey().render(resolver)}，它只该出现在装配类的出口。
         *
         * <p>解析推迟到第一次调用而不是在 setter 里：yaml 里 {@code name} 写在 {@code api-key}
         * 之后是合法的，绑定时先到的可能是句柄；报错信息里带上名字才有用。
         *
         * @return 凭据句柄，可能是 absent
         */
        public SecretValue apiKey() {
            if (apiKey == null) {
                apiKey = SecretValue.parse(apiKeyHandle, "claw4j.model.providers[name="
                        + (name == null ? "?" : name) + "].api-key", false);
            }
            return apiKey;
        }

        /**
         * 绑定时收下句柄原文，校验留到 {@link #apiKey()}：这一步只存字符串。
         *
         * @param apiKey 配置里写的句柄
         */
        public void setApiKey(String apiKey) {
            this.apiKeyHandle = apiKey;
            this.apiKey = null;
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
            return name == null ? "" : name.toUpperCase(Locale.ROOT);
        }
    }
}

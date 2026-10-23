package io.github.claw4j.agent.model;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 模型供应商的凭据、开关与路由顺序。
 *
 * <p>凭据一律从环境变量来，yaml 里只写占位符 —— 真实 key 不进版本库（ADR-0006）。
 *
 * <p>{@code mock} 三态：留空是"有没有 key 就用不用真实模型"的自动判断；
 * 显式给 true/false 则是强制，用于本地演示和 CI。
 *
 * <p>{@code primary} 决定 {@code RoutingChatModel} 里谁先上：主模型调用失败才退到备。
 * 它是"主备降级"而不是"按用途分流"——按用途分流（摘要、子 Agent 走便宜模型）是 M2 的事，
 * 现在只有一条链，先把"主挂了还能答"这件事做实。
 */
@ConfigurationProperties(prefix = "claw4j.model")
public class ModelProperties {

    /** 供应商名字，同时是 {@code primary} 的取值与路由键。 */
    public static final String DEEPSEEK = "deepseek";

    public static final String QWEN = "qwen";

    /**
     * 空 = 自动（有 key 走真实模型，无 key 降级 mock）；
     * {@code true} = 强制 mock；{@code false} = 强制真实模型（此时若一个 key 都没有直接启动失败）。
     */
    private String mock = "";

    /**
     * 主模型供应商。配了不存在的名字时启动失败并列出可选值 —— 静默忽略会让
     * "我以为在用千问、其实还在用 DeepSeek" 这种事查半天。
     */
    private String primary = DEEPSEEK;

    private final DeepSeek deepseek = new DeepSeek();

    private final Qwen qwen = new Qwen();

    public String getMock() {
        return mock;
    }

    public void setMock(String mock) {
        this.mock = mock == null ? "" : mock.trim();
    }

    public String getPrimary() {
        return primary;
    }

    public void setPrimary(String primary) {
        this.primary = primary == null ? "" : primary.trim().toLowerCase();
    }

    public DeepSeek getDeepseek() {
        return deepseek;
    }

    public Qwen getQwen() {
        return qwen;
    }

    /**
     * 一个供应商要配的东西。两家共用同一套字段，装配与校验才能写成一份。
     */
    public abstract static class Provider {

        private String apiKey = "";

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
         * 采样温度。默认 {@code 0.0}：这是个查数、调工具的 Agent，
         * 不是写诗的——同一个问题两次问出两个不同的数是故障，不是个性。
         * 而且温度是测试可复现的前提：ADR-0011 让测试连真实模型之后，
         * 非零温度等于给每一条断言都掺一份随机。想让它发散时按环境变量调。
         */
        private Double temperature = 0.0;

        /** 路由键，与 {@code claw4j.model.primary} 的取值对应。 */
        public abstract String name();

        /** 环境变量前缀，只用于启动失败时告诉人去哪里配。 */
        public abstract String envPrefix();

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey == null ? "" : apiKey.trim();
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
    }

    public static class DeepSeek extends Provider {

        @Override
        public String name() {
            return DEEPSEEK;
        }

        @Override
        public String envPrefix() {
            return "DEEPSEEK";
        }
    }

    /**
     * 通义走自己提供的 OpenAI 兼容端点（{@code /compatible-mode/v1}），
     * 所以适配器是 {@code spring-ai-openai} —— 没有官方的 Qwen 模块。
     */
    public static class Qwen extends Provider {

        @Override
        public String name() {
            return QWEN;
        }

        @Override
        public String envPrefix() {
            return "QWEN";
        }
    }
}

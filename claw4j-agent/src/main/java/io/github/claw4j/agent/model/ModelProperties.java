package io.github.claw4j.agent.model;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 模型供应商的凭据与开关。
 *
 * <p>凭据一律从环境变量来，yaml 里只写占位符 —— 真实 key 不进版本库（ADR-0006）。
 *
 * <p>{@code mock} 三态：留空是"有没有 key 就用不用真实模型"的自动判断；
 * 显式给 true/false 则是强制，用于本地演示和 CI。
 */
@ConfigurationProperties(prefix = "claw4j.model")
public class ModelProperties {

    /**
     * 空 = 自动（有 key 走真实模型，无 key 降级 mock）；
     * {@code true} = 强制 mock；{@code false} = 强制真实模型（此时若无 key 直接启动失败）。
     */
    private String mock = "";

    private final DeepSeek deepseek = new DeepSeek();

    public String getMock() {
        return mock;
    }

    public void setMock(String mock) {
        this.mock = mock == null ? "" : mock.trim();
    }

    public DeepSeek getDeepseek() {
        return deepseek;
    }

    public static class DeepSeek {
        private String apiKey = "";
        private String baseUrl = "https://api.deepseek.com";
        private String model = "deepseek-chat";

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
    }
}

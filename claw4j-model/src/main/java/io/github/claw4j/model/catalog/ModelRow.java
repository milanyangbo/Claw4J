package io.github.claw4j.model.catalog;

import java.util.Map;

/**
 * 目录里的一行：一个模型以及它的元数据。
 *
 * <p>照 OpenClaw 的目录行（`packages/model-catalog-core/src/model-catalog-types.ts:258-292`）。
 * 元数据为什么要跟着模型走、而不放在会话配置里：降级到备用模型之后，压缩阈值与"这次能塞多少"
 * 都应该按**当次回答的那个模型**算；放在会话配置里，换了模型也还是主模型那个数。
 *
 * <p>{@code context-window} 是总窗口，{@code context-tokens} 是"实际可用的提示容量"——
 * 有的供应商在总窗口里留了一段给输出。没写后者就取前者。
 *
 * <p>{@code cost} 只解析、不消费：本仓还没有账单需求，字段先留着，接账单时再算（ADR-0035 第九节）。
 */
public final class ModelRow {

    private String id;

    /** 总上下文窗口（token）。 */
    private int contextWindow;

    /** 有效提示容量；不写等于 {@link #contextWindow}。 */
    private Integer contextTokens;

    /** 单次输出上限。只解析，暂不消费。 */
    private Integer maxTokens;

    /** 三档价格。只解析，暂不消费。 */
    private Cost cost = new Cost();

    /** 供应商兼容性标记。只解析，暂不消费（缓存标记在票 12）。 */
    private Map<String, Object> compat = Map.of();

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id == null ? null : id.trim();
    }

    public int getContextWindow() {
        return contextWindow;
    }

    public void setContextWindow(int contextWindow) {
        this.contextWindow = contextWindow;
    }

    public Integer getContextTokens() {
        return contextTokens;
    }

    public void setContextTokens(Integer contextTokens) {
        this.contextTokens = contextTokens;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    public Cost getCost() {
        return cost;
    }

    public void setCost(Cost cost) {
        this.cost = cost == null ? new Cost() : cost;
    }

    public Map<String, Object> getCompat() {
        return compat;
    }

    public void setCompat(Map<String, Object> compat) {
        this.compat = compat == null ? Map.of() : compat;
    }

    /**
     * 有效提示容量：没写 {@code context-tokens} 就是总窗口。
     *
     * @return 上下文重排（票 10）用的那个数
     */
    public int effectiveContextTokens() {
        return contextTokens == null || contextTokens <= 0 ? contextWindow : contextTokens;
    }

    /**
     * 三档价格，单位按供应商给的原样（通常是每百万 token 的单价）。
     *
     * <p>只解析、不消费。写成嵌套类而不是 record：{@code ConfigurationProperties} 的绑定
     * 对嵌套类的 setter 更宽容，也方便以后加 {@code cache-write-1h} 这类档位。
     */
    public static class Cost {

        private Double input;

        private Double output;

        private Double cacheRead;

        private Double cacheWrite;

        public Double getInput() {
            return input;
        }

        public void setInput(Double input) {
            this.input = input;
        }

        public Double getOutput() {
            return output;
        }

        public void setOutput(Double output) {
            this.output = output;
        }

        public Double getCacheRead() {
            return cacheRead;
        }

        public void setCacheRead(Double cacheRead) {
            this.cacheRead = cacheRead;
        }

        public Double getCacheWrite() {
            return cacheWrite;
        }

        public void setCacheWrite(Double cacheWrite) {
            this.cacheWrite = cacheWrite;
        }
    }
}

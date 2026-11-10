package io.github.claw4j.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 工具检索的候选数上限。
 *
 * <p>只有这一个数。没有「要不要开检索」的开关，也没有「工具少于几个就全塞」的阈值：
 * 前者会让检索在某个环境里被悄悄关掉，后者会让同一句问话在工具数量跨过阈值时换一套规则，
 * 而这两件事都没有地方能看出来。循环只有一种，见 {@link ToolSearch#toolLoop}。
 *
 * @param maxResults 一次检索最多返回几个候选工具（框架约定 1–10，默认 5）
 */
@ConfigurationProperties("claw4j.tool-search")
public record ToolSearchProperties(int maxResults) {

    /**
     * 一次检索返回几个候选。
     *
     * <p>取 5：模型一次只挑一两个来调，返回十个等于把省下来的 token 又还回去一部分。
     */
    public static final int DEFAULT_MAX_RESULTS = 5;

    /** 框架在工具描述里写的是 1–10（Spring AI {@code ToolSearchTool}:50），这里提前夹住。 */
    private static final int FRAMEWORK_MAX_RESULTS_CAP = 10;

    /**
     * 用原始类型而不是 {@code Integer}：0 与「没配」落到同一个默认值，不需要靠 null 区分。
     */
    public ToolSearchProperties {
        maxResults = maxResults <= 0
                ? DEFAULT_MAX_RESULTS : Math.min(maxResults, FRAMEWORK_MAX_RESULTS_CAP);
    }

    /**
     * 全部走默认值。配置绑定在键缺失时传的也是 0，两条路落到同一处。
     *
     * @return 默认配置
     */
    public static ToolSearchProperties defaults() {
        return new ToolSearchProperties(0);
    }
}

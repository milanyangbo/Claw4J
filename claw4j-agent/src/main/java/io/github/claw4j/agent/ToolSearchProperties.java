package io.github.claw4j.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 工具检索唯一的一个数：一次检索最多返回几个候选。
 *
 * <p><b>没有"要不要开检索"的配置项，也没有"工具少于几个就全塞"的阈值</b>：
 * 前者会让"检索在某个环境里被关掉"变成没人知道的事；后者会让模型看到的行为随工具数量改变
 * ——同一句问话在工具涨到第 21 个的版本里换了规则，而这件事没有任何一处能看出来。
 * 循环只有一种，见 {@link ToolSearch#toolLoop}。
 *
 * @param maxResults 一次检索最多返回几个候选工具（框架约定 1–10，默认取 5）
 */
@ConfigurationProperties("claw4j.tool-search")
public record ToolSearchProperties(int maxResults) {

    /**
     * 一次检索返回几个候选。5 的理由：模型一次只会挑一两个来调，
     * 返回十个等于把省下来的 token 又还回去一部分。
     */
    public static final int DEFAULT_MAX_RESULTS = 5;

    /** 框架自己在工具描述里写的是 1–10，超出这个范围由它兜底，这里提前夹住。 */
    private static final int FRAMEWORK_MAX_RESULTS_CAP = 10;

    /**
     * 字段用原始类型，而不像 M3-06 当初那样用 {@code Integer}：那时要靠 null 把"没配"与
     * "0（永远检索）"分开，现在 0 不是一个有意义的取值——它和"没配"落到同一个默认值上。
     * 少一层包装，就少一处"两件事其实是一件事"的解释。
     */
    public ToolSearchProperties {
        maxResults = maxResults <= 0
                ? DEFAULT_MAX_RESULTS : Math.min(maxResults, FRAMEWORK_MAX_RESULTS_CAP);
    }

    /** 全部走默认值。配置绑定在键缺失时传的就是 0，两条路落到同一处。 */
    public static ToolSearchProperties defaults() {
        return new ToolSearchProperties(0);
    }
}

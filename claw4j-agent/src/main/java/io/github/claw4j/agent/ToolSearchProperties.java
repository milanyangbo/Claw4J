package io.github.claw4j.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 工具检索的两个数：提示词里最多塞几个工具、一次检索最多返回几个。
 *
 * <p><b>阈值不是开关</b>：没有"要不要开检索"这个配置项。工具数在阈值以内时全塞是对的
 * （全塞比检索简单，也不多一次模型往返），超过就自动走检索。做成开关的话，
 * 要么所有人都得记住去开它，要么它在某个环境里被关掉了而没人知道。
 *
 * <p>阈值为 0 表示"只要是工具集就检索"，这是算术结果而不是特例：{@code 工具数 > 0} 恒成立。
 *
 * <p><b>字段为什么是 {@link Integer} 而不是 {@code int}</b>：配置绑定只能用 null 表达
 * "这个键没写"，原始类型会被填成 0——于是"没配"和"阈值 0（永远检索）"变成同一件事。
 * 这个模块被当成库引用、外面没有 yml 时正好会走到那一步。默认值只能在这里兜。
 *
 * @param maxToolsInPrompt 提示词里最多直接列几个工具，超过这个数就改走检索
 * @param maxResults       一次检索最多返回几个候选工具（框架约定 1–10，默认取 5）
 */
@ConfigurationProperties("claw4j.tool-search")
public record ToolSearchProperties(Integer maxToolsInPrompt, Integer maxResults) {

    /** 超过这个工具数就走检索。20 是"一整页工具定义、但还没到塞不进上下文"的位置。 */
    public static final int DEFAULT_MAX_TOOLS_IN_PROMPT = 20;

    /**
     * 一次检索返回几个候选。5 的理由：模型一次只会挑一两个来调，
     * 返回十个等于把省下来的 token 又还回去一部分。
     */
    public static final int DEFAULT_MAX_RESULTS = 5;

    /** 框架自己在工具描述里写的是 1–10，超出这个范围由它兜底，这里提前夹住。 */
    private static final int FRAMEWORK_MAX_RESULTS_CAP = 10;

    public ToolSearchProperties {
        // 负数是写错了，按没配处理；0 是有效值（永远走检索），不能和"没配"混成一个。
        maxToolsInPrompt = (maxToolsInPrompt == null || maxToolsInPrompt < 0)
                ? DEFAULT_MAX_TOOLS_IN_PROMPT : maxToolsInPrompt;
        maxResults = (maxResults == null || maxResults <= 0)
                ? DEFAULT_MAX_RESULTS : Math.min(maxResults, FRAMEWORK_MAX_RESULTS_CAP);
    }

    /** 全部走默认值。配置绑定在键缺失时传的就是 null，两条路落到同一处。 */
    public static ToolSearchProperties defaults() {
        return new ToolSearchProperties(null, null);
    }

    /**
     * 这批工具是"全塞"还是"检索"。
     *
     * <p>用严格大于：阈值 20 的意思是"塞 20 个还行"，第 21 个才换做法。
     */
    public boolean searchInsteadOfStuffing(int toolCount) {
        return toolCount > maxToolsInPrompt;
    }
}

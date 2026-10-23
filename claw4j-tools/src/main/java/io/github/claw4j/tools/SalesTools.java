package io.github.claw4j.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 销售域查询工具，能力三层里的第一层（Java {@code @Tool}，ADR-0003）。
 *
 * <p><b>当前是桩</b>：返回一个固定占位值，不碰数据库。它存在的意义是把
 * "模型会调工具、工具结果会回到模型"这条链路先打通（M1-01），
 * 真实的 Text-to-SQL 在 M1-05 落到同一个方法上——方法名与参数不变，
 * 所以 01 号票留下的测试不需要改。
 *
 * <p>这个类刻意不认识 Agent 也不认识通道：只认参数、只给返回值。
 * 一旦工具里出现"查一下当前用户是谁"或者"回一条消息"，它就长进 Agent 里了，
 * 从此只能在 Agent 的上下文里测（见 {@code ToolsArchitectureTest}）。
 */
public class SalesTools {

    /** 占位返回值。端到端测试靠它出现在第二轮的提示词里来证明工具真的执行过。 */
    public static final String STUB_RESULT = "stub:sales-result";

    /**
     * 查询指定区域与时间窗内的成交额。
     *
     * <p>参数上是两个字符串而不是一个结构化对象：让模型来填空比让它拼 JSON 稳，
     * 也让 05 号票做 Text-to-SQL 时可以在方法内部校验这两个值（例如区域必须来自字典表）。
     */
    @Tool(description = "查询指定区域与时间窗内的成交额。注意：当前是占位实现，"
            + "返回的是占位值而不是真实金额，不要把它当成真实数据用。")
    public String querySales(
            @ToolParam(description = "区域，例如「华东」「华南」") String region,
            @ToolParam(description = "时间窗，例如「上周」「本月」") String period) {
        return STUB_RESULT + " region=" + region + " period=" + period;
    }
}

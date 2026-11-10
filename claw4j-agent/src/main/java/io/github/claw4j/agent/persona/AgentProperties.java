package io.github.claw4j.agent.persona;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;

import java.time.Duration;
import java.time.ZoneId;

/**
 * 这个部署是哪个 agent：人设目录、用哪一个、以及它的时间按哪口钟算。
 *
 * <p>三个键各自回答一件事，缺一不可：
 * <ul>
 *   <li>{@code directory}：人设从哪来。不配就用内置的那份最小人设（{@link BuiltinPersona}）。
 *   <li>{@code default}：目录里用哪一个。目录里只有一份时可以不写，多份不写就启动失败。
 *   <li>{@code zone}：今天按哪个时区算。
 * </ul>
 *
 * <p>时区是这里的一等公民，不是顺手取系统默认：{@code ZoneId.systemDefault()} 在容器里通常是
 * UTC，差八小时意味着每天晚上八点之后，模型看到的今天已经是明天，而它会照常回答，没有任何
 * 异常。调度那边 {@code ScheduleStore.zoneOf} 的兜底是同一个形状。
 *
 * <p>默认 {@code Asia/Shanghai} 是业务口径，不是技术默认。海外部署要显式写自己的。
 *
 * <p>{@code tool-call-limit} 是一轮里最多调几次工具（默认 6，见
 * {@link io.github.claw4j.agent.ToolCallBudget}）。它不是额度：额度是目标上的 token 预算
 * （ADR-0027 决定五）。放在这里是因为它是「这个部署的 Agent 怎么跑」的一个数。
 *
 * @param directory 人设目录；空表示用内置人设
 * @param defaultName 目录里用哪一份；空表示目录只有一份时自动选
 * @param zone 业务时区
 * @param laneWait 同一段对话等上一位说完的最长时长
 * @param toolCallLimit 一轮的工具调用次数上限
 */
@ConfigurationProperties("claw4j.agent")
public record AgentProperties(String directory,
                              @Name("default") String defaultName,
                              ZoneId zone,
                              Duration laneWait,
                              int toolCallLimit) {

    /** 业务所在时区。写成常量是为了让「默认值是什么」能被搜到，而不是藏在代码里。 */
    public static final String DEFAULT_ZONE = "Asia/Shanghai";

    /**
     * 同一段对话等上一位说完，最多等多久（ADR-0026 决定二）。
     *
     * <p>60 秒与通道池能容忍的时间同一量级：等更久的话，用户早就又发了一句，而排在前面那条
     * 自己也可能已经被墙钟推着走。
     *
     * <p>配成 0 是「忙就说话」：连发第二条会立刻收到「现在有点忙」，而不是排队。这不是默认值，
     * 但它是用户明确要的一种行为。
     */
    public static final Duration DEFAULT_LANE_WAIT = Duration.ofSeconds(60);

    public AgentProperties {
        directory = directory == null ? "" : directory.trim();
        defaultName = defaultName == null ? "" : defaultName.trim();
        zone = zone == null ? ZoneId.of(DEFAULT_ZONE) : zone;
        // 负数不是一个可以解释的等待时长；0 是（见上），所以不能一起归一。
        laneWait = laneWait == null || laneWait.isNegative() ? DEFAULT_LANE_WAIT : laneWait;
        // 0 与"没配"落到同一个默认值：0 在这里不是"不限"，那会让一轮可以无限绕。
        toolCallLimit = toolCallLimit <= 0
                ? io.github.claw4j.agent.ToolCallBudget.MAX_TOTAL_CALLS : toolCallLimit;
    }

    /**
     * 造一份全默认的配置：没有人设目录、用内置的，时间是业务时区，lane 等 60 秒，
     * 工具调用上限取 {@link io.github.claw4j.agent.ToolCallBudget#MAX_TOTAL_CALLS}。
     *
     * @return 全默认的配置
     */
    public static AgentProperties defaults() {
        return new AgentProperties(null, null, null, null, 0);
    }

    /**
     * 是否配了人设目录。
     *
     * @return {@code directory} 非空时为 true
     */
    public boolean hasDirectory() {
        return !directory.isEmpty();
    }
}

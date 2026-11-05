package io.github.claw4j.agent.persona;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;

import java.time.Duration;
import java.time.ZoneId;

/**
 * 这个部署是哪个 agent：人设目录、用哪一个、以及它对表的是哪一口钟。
 *
 * <p>三个键各自回答一件事，缺一不可：
 * <ul>
 *   <li>{@code directory} —— 人设从哪来。不配就用内置的那份最小人设（{@link BuiltinPersona}）。</li>
 *   <li>{@code default} —— 目录里用哪一个。目录里只有一份时可以不写；多份不写就启动失败。</li>
 *   <li>{@code zone} —— "今天"按哪个时区算。</li>
 * </ul>
 *
 * <p><b>为什么时区是这里的一等公民，而不是顺手取系统默认</b>：
 * {@code ZoneId.systemDefault()} 在容器里通常是 UTC。差八小时意味着每天晚上八点之后，
 * 模型看到的"今天"已经是明天——而它会照常回答，没有任何异常。
 * 这个项目里已经有过同一个坑的另一个形状：调度那边 {@code ScheduleStore.zoneOf} 兜底也是系统默认。
 *
 * <p>默认 {@code Asia/Shanghai} 是业务口径，不是技术默认。海外部署要显式写自己的。
 */
@ConfigurationProperties("claw4j.agent")
public record AgentProperties(String directory,
                              @Name("default") String defaultName,
                              ZoneId zone,
                              Duration laneWait) {

    /** 业务所在时区。写出来是为了让"默认值是什么"能被搜到，而不是藏在代码里。 */
    public static final String DEFAULT_ZONE = "Asia/Shanghai";

    /**
     * 同一段对话等上一位说完，最多等多久（ADR-0026 决定二）。
     *
     * <p>60 秒与通道池能容忍的时间同一量级：等更久的话，用户早就又发了一句，
     * 而排在前面那条自己也可能已经被墙钟推着走。
     *
     * <p><b>配成 0 是"忙就说话"</b>：那样连发第二条会立刻收到"现在有点忙"，
     * 而不是排队。这不是默认值，但它是用户明确要的一种行为。
     */
    public static final Duration DEFAULT_LANE_WAIT = Duration.ofSeconds(60);

    public AgentProperties {
        directory = directory == null ? "" : directory.trim();
        defaultName = defaultName == null ? "" : defaultName.trim();
        zone = zone == null ? ZoneId.of(DEFAULT_ZONE) : zone;
        // 负数不是一个可以解释的等待时长；0 是（见上），所以不能一起归一。
        laneWait = laneWait == null || laneWait.isNegative() ? DEFAULT_LANE_WAIT : laneWait;
    }

    /** 全默认：没有人设目录，用内置的；时间是业务时区，lane 等 60 秒。 */
    public static AgentProperties defaults() {
        return new AgentProperties(null, null, null, null);
    }

    public boolean hasDirectory() {
        return !directory.isEmpty();
    }
}

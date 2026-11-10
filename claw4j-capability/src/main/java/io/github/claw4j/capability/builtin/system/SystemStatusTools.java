package io.github.claw4j.capability.builtin.system;

import org.springframework.ai.tool.annotation.Tool;

import java.util.function.Supplier;

/**
 * 运行时状态工具，能力三层里的第一层（Java {@code @Tool}，ADR-0003 保留场景之一）。
 *
 * <p>写成 Java 而不是脚本：它读的东西（JVM 与容器的指标）本来就在本进程里，
 * 不需要依赖外部 CLI 或跨语言，而那才是走 MCP 或脚本层的理由。这几行读数也没有第三方依赖。
 *
 * <p>它不认识任何业务表：登记的权限是 {@code read_system} 且表清单为空，因为 JVM 指标不是业务数据。
 * 表清单为空在这里的含义是"没有表要受表级约束"，于是"能不能看"只由动作决定——角色配置里不给这个
 * 动作的身份看不到。运行时指标不涉业务数据，但也不是人人都该看的运维信息。
 *
 * <p>采样动作被抽成 {@code Supplier<SystemStatus>}：默认采真实的，测试塞一个固定读数进来，
 * 于是断言钉的是"工具怎么把读数交给模型"，而不是"这台机器此刻的堆是多少"。
 */
public class SystemStatusTools {

    private static final String DESCRIPTION = "读取本进程当前的运行时状态：堆内存已用与上限、"
            + "JVM 可见的处理器数、线程数、已运行时长，以及容器给的 CPU 与内存限额"
            + "（不在容器里时如实写未设限）。回答关于“这个服务现在跑得怎么样”的问题时用得到。";

    private final Supplier<SystemStatus> sampler;

    /** 采样真实的运行时状态。 */
    public SystemStatusTools() {
        this(SystemStatus::sample);
    }

    /**
     * 用给定的采样动作建工具。
     *
     * @param sampler 采样一次运行时状态的动作；测试用来塞固定读数
     */
    public SystemStatusTools(Supplier<SystemStatus> sampler) {
        this.sampler = java.util.Objects.requireNonNull(sampler, "sampler");
    }

    /**
     * 采一次样，把读数格式化成给模型看的那段文本。
     *
     * @return 多行文本形式的运行时状态
     */
    @Tool(name = "systemStatus", description = DESCRIPTION)
    public String systemStatus() {
        return sampler.get().format();
    }
}

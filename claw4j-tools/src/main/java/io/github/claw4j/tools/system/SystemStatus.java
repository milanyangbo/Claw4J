package io.github.claw4j.tools.system;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * 一次 system-status 的读数：JVM 自己的指标 + 容器给它的限额。
 *
 * <p>为什么是 record 而不是直接拼一段字符串：拼好的文本要给模型读，
 * 而"堆上限是多少"这类事实要能被断言。两层分开之后，格式化只发生一次（在
 * {@link #format()}），测试钉的是数字而不是措辞——模型看到的措辞随时可以改，
 * 数字被改错了才是一条真缺陷。
 *
 * <p>取数的动作以 {@code Supplier} 形式传进来（{@link SystemStatusTools} 负责给），
 * 是为了让这个类型自己不偷偷读系统状态：读过一次的状态在断言里是"当时的数"，
 * 而不是"测试跑到这一行时的数"，两者差一个 GC 就足够让断言不稳定。
 */
public record SystemStatus(long heapUsedBytes,
                           long heapMaxBytes,
                           int processors,
                           int threads,
                           long uptimeMillis,
                           ContainerLimits containerLimits) {

    public SystemStatus {
        Objects.requireNonNull(containerLimits, "containerLimits");
    }

    /** 采样一次当前进程的状态。 */
    public static SystemStatus sample() {
        return sample(Runtime.getRuntime(),
                java.lang.management.ManagementFactory.getThreadMXBean(),
                java.lang.management.ManagementFactory.getRuntimeMXBean(),
                ContainerLimits::fromFileSystem);
    }

    /** 采样一次，读数来源由外部给定（测试用）。 */
    public static SystemStatus sample(Runtime runtime,
                                      java.lang.management.ThreadMXBean threads,
                                      java.lang.management.RuntimeMXBean runtimeMxBean,
                                      Supplier<ContainerLimits> limits) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(threads, "threads");
        Objects.requireNonNull(runtimeMxBean, "runtimeMxBean");
        Objects.requireNonNull(limits, "limits");
        return new SystemStatus(runtime.totalMemory() - runtime.freeMemory(),
                runtime.maxMemory(),
                runtime.availableProcessors(),
                threads.getThreadCount(),
                runtimeMxBean.getUptime(),
                limits.get());
    }

    /** 给模型看的那一版：一段文本，读不到的项如实写成"未设限/未知"。 */
    public String format() {
        StringBuilder text = new StringBuilder("运行时状态（采样时刻）\n");
        text.append("堆内存：已用 ").append(human(heapUsedBytes))
                .append(" / 上限 ").append(human(heapMaxBytes)).append('\n');
        text.append("处理器：").append(processors).append(" 核（JVM 可见）\n");
        text.append("线程数：").append(threads).append('\n');
        text.append("已运行：").append(uptimeMillis / 1000).append(" 秒\n");
        text.append("容器限额：").append(container()).append('\n');
        return text.toString();
    }

    private String container() {
        if (!containerLimits.known()) {
            return "不在容器里，或容器未设限（读不到 cgroup 限额文件）";
        }
        StringBuilder text = new StringBuilder();
        if (containerLimits.memoryLimitBytes() != ContainerLimits.UNKNOWN) {
            text.append("内存 ").append(human(containerLimits.memoryLimitBytes()));
        }
        else {
            text.append("内存未设限");
        }
        if (containerLimits.cpuQuotaCores() != ContainerLimits.UNKNOWN) {
            text.append("，CPU ").append(String.format("%.2f", containerLimits.cpuQuotaCores())).append(" 核");
        }
        else {
            text.append("，CPU 未设限");
        }
        return text.toString();
    }

    /** 字节数说给人（与模型）听：整 MB 就够了，精确到字节的堆大小没有意义。 */
    private static String human(long bytes) {
        if (bytes == ContainerLimits.UNKNOWN) {
            return "未知";
        }
        return String.format("%d MB (%d 字节)", bytes / (1024 * 1024), bytes);
    }
}

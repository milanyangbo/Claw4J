package io.github.claw4j.capability.builtin.system;

import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 运行时状态：cgroup 限额怎么解析，以及这些读数怎么交给模型。
 *
 * <p>这一组守的是一句话：<b>"读不到"与"没有限额"与"限额是零"是三件不同的事</b>。
 * 把它们混成一个数，模型会照着"内存无上限"去解释一次 OOM，或者报出一个假的 9.2EB 上限
 * ——那比"不知道"更糟，因为它看起来是个数。
 */
class SystemToolsTest {

    private static final String UNKNOWN_TEXT = "容器限额：不在容器里，或容器未设限";

    private static final ContainerLimits NO_LIMITS = ContainerLimits.of(path -> Optional.empty());

    private static final ContainerLimits IN_CONTAINER = ContainerLimits.of(path ->
            Optional.ofNullable(Map.of(
                    ContainerLimits.V2_MEMORY_MAX, "536870912",
                    ContainerLimits.V2_CPU_MAX, "200000 100000").get(path)));

    @Test
    @DisplayName("v2 的 memory.max 是数字时按字节解析")
    void v2数字() {
        ContainerLimits limits = ContainerLimits.of(files(Map.of(ContainerLimits.V2_MEMORY_MAX, "536870912")));

        assertThat(limits.memoryLimitBytes()).isEqualTo(536870912L);
        assertThat(limits.known()).isTrue();
    }

    @Test
    @DisplayName("max 是「没设限」，不是「限额是 max」")
    void v2写max就是没设限() {
        // 把 max 当数字用出去，模型会照着"限额是 max"解释一次 OOM
        assertThat(ContainerLimits.of(files(Map.of(ContainerLimits.V2_MEMORY_MAX, "max"))).memoryLimitBytes())
                .isEqualTo(ContainerLimits.UNKNOWN);
    }

    @Test
    @DisplayName("v1 那个天文数字是「没设限」，不是「限额 9.2EB」")
    void v1天文数字是没设限() {
        assertThat(ContainerLimits.of(files(Map.of(
                ContainerLimits.V1_MEMORY_LIMIT, "9223372036854771712"))).memoryLimitBytes())
                .isEqualTo(ContainerLimits.UNKNOWN);
    }

    @Test
    @DisplayName("v1 在 v2 读不到时接上；CPU 配额按 quota / period 折成核数，quota 位写 max 即不限")
    void v1兜底与cpu折算() {
        assertThat(ContainerLimits.of(files(Map.of(ContainerLimits.V1_MEMORY_LIMIT, "268435456")))
                .memoryLimitBytes()).isEqualTo(268435456L);

        assertThat(ContainerLimits.of(files(Map.of(ContainerLimits.V2_CPU_MAX, "200000 100000")))
                .cpuQuotaCores()).isEqualTo(2.0);

        assertThat(ContainerLimits.of(files(Map.of(ContainerLimits.V2_CPU_MAX, "max 100000")))
                .cpuQuotaCores()).isEqualTo(ContainerLimits.UNKNOWN);

        assertThat(ContainerLimits.of(files(Map.of(
                ContainerLimits.V1_CPU_QUOTA, "150000",
                ContainerLimits.V1_CPU_PERIOD, "100000"))).cpuQuotaCores()).isEqualTo(1.5);
    }

    @Test
    @DisplayName("一个文件都读不到、或读到脏内容，都是未设限而不是零；生产路径不抛异常")
    void 读不到时是未设限而不是零() {
        ContainerLimits nothing = ContainerLimits.of(files(Map.of()));
        assertThat(nothing.memoryLimitBytes()).isEqualTo(ContainerLimits.UNKNOWN);
        assertThat(nothing.cpuQuotaCores()).isEqualTo(ContainerLimits.UNKNOWN);
        assertThat(nothing.known()).isFalse();

        ContainerLimits dirty = ContainerLimits.of(files(Map.of(ContainerLimits.V2_MEMORY_MAX, "not-a-number")));
        assertThat(dirty.memoryLimitBytes()).isEqualTo(ContainerLimits.UNKNOWN);

        // 真文件系统那条路径单独断言一次：容器里跑时这两个文件是存在的，
        // "读不到"不能靠"当前机器读不到"来证明
        assertThat(ContainerLimits.fromFileSystem().memoryLimitBytes()).isNotEqualTo(0L);
    }

    @Test
    @DisplayName("读数原样进文本：堆、处理器、线程、运行时长；限额两种情形都如实说")
    void 读数进文本() {
        String outside = new SystemStatusTools(() -> new SystemStatus(
                64L * 1024 * 1024, 512L * 1024 * 1024, 8, 42, 91_000L, NO_LIMITS)).systemStatus();

        assertThat(outside)
                .contains("64 MB")
                .contains("512 MB")
                .contains("8 核")
                .contains("42")
                .contains("91 秒")
                .contains(UNKNOWN_TEXT)
                .doesNotContain("-1");

        String inside = new SystemStatusTools(() -> new SystemStatus(1, 2, 1, 1, 0L, IN_CONTAINER)).systemStatus();
        assertThat(inside).contains("512 MB").contains("2.00 核");
    }

    @Test
    @DisplayName("真实采样拿到的是当下的数：处理器与线程至少 1，运行时长不为负")
    void 真实采样不是空值() {
        SystemStatus status = SystemStatus.sample();

        assertThat(status.processors()).isGreaterThanOrEqualTo(1);
        assertThat(status.threads()).isGreaterThanOrEqualTo(1);
        assertThat(status.uptimeMillis()).isGreaterThanOrEqualTo(0L);
        assertThat(status.heapMaxBytes()).isGreaterThan(0L);
        assertThat(status.containerLimits()).isNotNull();
    }

    /**
     * 权限边界：动作是 {@code read_system} 且不带表。
     *
     * <p>不带表的含义是"不受表级约束"（JVM 指标不是业务数据），
     * 但动作那一条仍然要过——"不带表"不能被理解成"不看动作"。
     */
    @Test
    @DisplayName("装配出工具并登记 read_system；没有这个动作的身份看不到运行时状态")
    void 装配与权限() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(SystemStatusAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(toolNames(context)).contains("systemStatus");

                    ToolPermissionRegistry registry = context.getBean(ToolPermissionRegistry.class);
                    assertThat(registry.permissionFor("systemStatus").orElseThrow())
                            .isEqualTo(ToolPermission.of(SystemStatusAutoConfiguration.ACTION));
                });

        ToolPermission permission = ToolPermission.of(SystemStatusAutoConfiguration.ACTION);
        assertThat(permission.grantedTo(Principal.of("u", "guest", Set.of("region"), Set.of("read"))))
                .as("guest 能读 region，但运行时状态不是业务数据，不给它这个动作")
                .isFalse();
        assertThat(permission.grantedTo(Principal.of("u", "read-only", Set.of("*"),
                Set.of("read", "read_system")))).isTrue();
    }

    private static List<String> toolNames(ApplicationContext context) {
        return context.getBeansOfType(ToolCallbackProvider.class).values().stream()
                .flatMap(provider -> Arrays.stream(provider.getToolCallbacks()))
                .map(callback -> callback.getToolDefinition().name())
                .toList();
    }

    private static ContainerLimits.Reader files(Map<String, String> contents) {
        Map<String, String> copy = Map.copyOf(contents);
        return path -> Optional.ofNullable(copy.get(path));
    }
}

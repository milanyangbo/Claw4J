package io.github.claw4j.capability.builtin.system;

import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;

import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 一个完整功能：运行状态。
 *
 * <p>这一层最容易坏的地方是"读不到"被说成一个数字：cgroup 文件在开发机上不存在、
 * v1 用天文数字表示不限额、堆上限可能是 -1。三种情况都不是"限额是 0"，
 * 而把它念给模型听之后，它会照着"没有上限"去解释一次 OOM。
 */
class SystemStatusTest {

    private static final long MB = 1024L * 1024;

    @Test
    @DisplayName("cgroup v2 的内存上限与 CPU 配额都读得到时按真实值报")
    void readsCgroupV2Limits() {
        ContainerLimits limits = limits(Map.of(
                ContainerLimits.V2_MEMORY_MAX, "536870912\n",
                ContainerLimits.V2_CPU_MAX, "200000 100000\n"));

        assertThat(limits.memoryLimitBytes()).isEqualTo(512 * MB);
        assertThat(limits.cpuQuotaCores()).isEqualTo(2.0);
        assertThat(limits.known()).isTrue();
    }

    @Test
    @DisplayName("只有 cgroup v1 时也能算：quota/period 换算成核数")
    void fallsBackToCgroupV1Files() {
        ContainerLimits limits = limits(Map.of(
                ContainerLimits.V1_MEMORY_LIMIT, "268435456\n",
                ContainerLimits.V1_CPU_QUOTA, "150000\n",
                ContainerLimits.V1_CPU_PERIOD, "100000\n"));

        assertThat(limits.memoryLimitBytes()).isEqualTo(256 * MB);
        assertThat(limits.cpuQuotaCores()).isEqualTo(1.5);
    }

    @Test
    @DisplayName("\"max\" 与 v1 的天文数字都是“没设限”，不能当成一个巨大的限额")
    void normalizesUnlimitedValuesToUnknown() {
        ContainerLimits v2 = limits(Map.of(
                ContainerLimits.V2_MEMORY_MAX, "max\n",
                ContainerLimits.V2_CPU_MAX, "max 100000\n"));
        ContainerLimits v1 = limits(Map.of(
                ContainerLimits.V1_MEMORY_LIMIT, String.valueOf(Long.MAX_VALUE / 2),
                ContainerLimits.V1_CPU_QUOTA, "-1\n",
                ContainerLimits.V1_CPU_PERIOD, "100000\n"));

        assertThat(v2.memoryLimitBytes()).isEqualTo(ContainerLimits.UNKNOWN);
        assertThat(v2.cpuQuotaCores()).isEqualTo(ContainerLimits.UNKNOWN);
        assertThat(v2.known()).isFalse();
        assertThat(v1.memoryLimitBytes()).isEqualTo(ContainerLimits.UNKNOWN);
        assertThat(v1.known()).isFalse();
    }

    @Test
    @DisplayName("容器外读不到限额文件时如实说“未设限”，哨兵 -1 不出现在状态文本里")
    void reportsMissingCgroupAsUnsetInsteadOfASentinel() {
        SystemStatus status = new SystemStatus(64 * MB, 512 * MB, 8, 12, 5_000, limits(Map.of()));

        String text = status.format();

        assertThat(text).contains("不在容器里，或容器未设限");
        assertThat(text).doesNotContain("-1");
    }

    @Test
    @DisplayName("只读到一半限额时，缺的那一项单独说“未设限”，不把另一项也一起丢掉")
    void namesTheLimitThatIsMissing() {
        SystemStatus status = new SystemStatus(64 * MB, 512 * MB, 8, 12, 5_000,
                limits(Map.of(ContainerLimits.V2_MEMORY_MAX, "536870912\n",
                        ContainerLimits.V2_CPU_MAX, "max 100000\n")));

        assertThat(status.format())
                .contains("内存 512 MB")
                .contains("CPU 未设限");
    }

    @Test
    @DisplayName("堆读数取不到（-1）时写“未知”，不写成一个负数或 0")
    void unknownHeapIsNotRenderedAsANumber() {
        SystemStatus status = new SystemStatus(ContainerLimits.UNKNOWN, ContainerLimits.UNKNOWN,
                8, 12, 5_000, limits(Map.of()));

        assertThat(status.format())
                .contains("堆内存：已用 未知 / 上限 未知")
                .doesNotContain("-1 字节");
    }

    @Test
    @DisplayName("工具把采样到的数字原样交给模型：堆、核数、线程、运行时长都在")
    void toolReturnsTheSampledStructure() {
        Supplier<SystemStatus> sampling = () -> new SystemStatus(120 * MB, 512 * MB, 4, 7, 65_000,
                limits(Map.of(ContainerLimits.V2_MEMORY_MAX, "536870912\n",
                        ContainerLimits.V2_CPU_MAX, "200000 100000\n")));

        String text = new SystemStatusTools(sampling).systemStatus();

        assertThat(text).contains("堆内存：已用 120 MB").contains("上限 512 MB")
                .contains("处理器：4 核").contains("线程数：7").contains("已运行：65 秒")
                .contains("内存 512 MB").contains("CPU 2.00 核");
    }

    @Test
    @DisplayName("这一层登记成 systemStatus + read_system，且不受任何表级约束")
    void registersToolNameAndActionWithoutTables() {
        ToolCallbackProvider provider = new SystemStatusAutoConfiguration().systemStatusToolCallbackProvider();
        ToolCallback callback = provider.getToolCallbacks()[0];
        ToolPermissionRegistry registry = new SystemStatusAutoConfiguration()
                .systemStatusPermissionRegistry(provider);

        ToolPermission permission = registry.permissionFor(callback.getToolDefinition().name()).orElseThrow();

        assertThat(callback.getToolDefinition().name()).isEqualTo("systemStatus");
        assertThat(permission.action()).isEqualTo(SystemStatusAutoConfiguration.ACTION);
        assertThat(permission.tables()).isEmpty();
        assertThat(Principal.readOnly("reader").canUse(permission)).isFalse();
    }

    private static ContainerLimits limits(Map<String, String> files) {
        ContainerLimits.Reader reader = path -> Optional.ofNullable(files.get(path));
        return ContainerLimits.of(reader);
    }
}

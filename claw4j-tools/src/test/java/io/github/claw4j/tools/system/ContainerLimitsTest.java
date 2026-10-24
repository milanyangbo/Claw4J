package io.github.claw4j.tools.system;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * cgroup 限额的解析。
 *
 * <p>这两条规则最容易写错、也最容易写成"看起来对"：{@code memory.max} 里写 {@code max}
 * 表示**没设限**，v1 里写 9223372036854771712 也是同一个意思。把这两者当成数字
 * 用出去，模型会照着"限额是 9.2EB"去解释一次 OOM——那比"不知道"更糟，
 * 因为它看起来是个数。所以这两个取值都要归一成 {@link ContainerLimits#UNKNOWN}。
 */
class ContainerLimitsTest {

    @Test
    @DisplayName("v2 的 memory.max 是数字时按字节解析")
    void v2内存上限() {
        ContainerLimits limits = ContainerLimits.of(files(Map.of(
                ContainerLimits.V2_MEMORY_MAX, "536870912")));

        assertThat(limits.memoryLimitBytes()).isEqualTo(536870912L);
        assertThat(limits.known()).isTrue();
    }

    @Test
    @DisplayName("max 是“没设限”，不是“限额是 max”")
    void v2写max就是没设限() {
        ContainerLimits limits = ContainerLimits.of(files(Map.of(
                ContainerLimits.V2_MEMORY_MAX, "max")));

        assertThat(limits.memoryLimitBytes()).isEqualTo(ContainerLimits.UNKNOWN);
    }

    @Test
    @DisplayName("v1 那个天文数字是“没设限”，不是“限额 9.2EB”")
    void v1的天文数字是没设限() {
        ContainerLimits limits = ContainerLimits.of(files(Map.of(
                ContainerLimits.V1_MEMORY_LIMIT, "9223372036854771712")));

        assertThat(limits.memoryLimitBytes()).isEqualTo(ContainerLimits.UNKNOWN);
    }

    @Test
    @DisplayName("v1 的文件在 v2 的读不到时接上")
    void v1兜底() {
        ContainerLimits limits = ContainerLimits.of(files(Map.of(
                ContainerLimits.V1_MEMORY_LIMIT, "268435456")));

        assertThat(limits.memoryLimitBytes()).isEqualTo(268435456L);
    }

    @Test
    @DisplayName("CPU 配额按 quota / period 折成核数")
    void cpu配额折算成核数() {
        ContainerLimits limits = ContainerLimits.of(files(Map.of(
                ContainerLimits.V2_CPU_MAX, "200000 100000")));

        assertThat(limits.cpuQuotaCores()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("cpu.max 的 quota 位写 max 就是没设限")
    void cpu没设限() {
        ContainerLimits limits = ContainerLimits.of(files(Map.of(
                ContainerLimits.V2_CPU_MAX, "max 100000")));

        assertThat(limits.cpuQuotaCores()).isEqualTo(ContainerLimits.UNKNOWN);
    }

    @Test
    @DisplayName("v1 的 CPU 是两个文件，算法一样")
    void v1的cpu配额() {
        ContainerLimits limits = ContainerLimits.of(files(Map.of(
                ContainerLimits.V1_CPU_QUOTA, "150000",
                ContainerLimits.V1_CPU_PERIOD, "100000")));

        assertThat(limits.cpuQuotaCores()).isEqualTo(1.5);
    }

    /** 这是常态而不是边界：开发机与容器外都读不到这两个文件。 */
    @Test
    @DisplayName("一个文件都读不到时是未设限，不是零")
    void 读不到时是未设限而不是零() {
        ContainerLimits limits = ContainerLimits.of(files(Map.of()));

        assertThat(limits.memoryLimitBytes()).isEqualTo(ContainerLimits.UNKNOWN);
        assertThat(limits.cpuQuotaCores()).isEqualTo(ContainerLimits.UNKNOWN);
        assertThat(limits.known()).isFalse();
    }

    @Test
    @DisplayName("读到的内容不是数字时不抛异常，算读不到")
    void 脏内容算读不到() {
        ContainerLimits limits = ContainerLimits.of(files(Map.of(
                ContainerLimits.V2_MEMORY_MAX, "not-a-number")));

        assertThat(limits.memoryLimitBytes()).isEqualTo(ContainerLimits.UNKNOWN);
        assertThat(limits.known()).isFalse();
    }

    /**
     * 真文件系统那一条单独断言一次：容器里跑 CI 时这两个文件是存在的，
     * 于是"读不到"不能靠"当前机器读不到"来证明——这里要钉的是"读不到时不是零"，
     * 用注入的空 reader 表达，与机器无关。
     */
    @Test
    @DisplayName("生产那条路径不会抛异常（有没有文件都一样）")
    void 真文件系统那条路径不抛异常() {
        ContainerLimits limits = ContainerLimits.fromFileSystem();

        assertThat(limits.memoryLimitBytes()).isNotEqualTo(0L);
    }

    private static ContainerLimits.Reader files(Map<String, String> contents) {
        Map<String, String> copy = new HashMap<>(contents);
        return path -> Optional.ofNullable(copy.get(path));
    }
}

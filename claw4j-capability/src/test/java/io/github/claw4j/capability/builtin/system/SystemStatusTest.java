package io.github.claw4j.capability.builtin.system;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 运行时状态：读数怎么来、读不到时怎么说、模型拿到的是什么。
 *
 * <p>最贵的一条是"读不到不能写成 0 或 -1 这样的数字"：那会被读成"没有限额"，
 * 而模型随后会照着这个前提解释一次 OOM。cgroup 文件用 Reader 替身喂进去，
 * 因为那两个路径只在 Linux 容器里存在。
 */
class SystemStatusTest {

    private static final long MB = 1024L * 1024L;

    @Test
    @DisplayName("采样到的数字原样进入给模型的那段文本")
    void sampledNumbersReachTheModel() {
        SystemStatus status = new SystemStatus(128 * MB, 512 * MB, 8, 42, 65_000,
                ContainerLimits.of(path -> Optional.empty()));

        String text = new SystemStatusTools(() -> status).systemStatus();

        assertThat(text)
                .contains("堆内存：已用 128 MB (" + 128 * MB + " 字节) / 上限 512 MB")
                .contains("处理器：8 核（JVM 可见）")
                .contains("线程数：42")
                .contains("已运行：65 秒");
    }

    @Test
    @DisplayName("cgroup v2 的内存与 CPU 配额被读成字节数与核数")
    void cgroupV2LimitsAreReadAsBytesAndCores() {
        ContainerLimits limits = ContainerLimits.of(path -> switch (path) {
            case ContainerLimits.V2_MEMORY_MAX -> Optional.of("536870912\n");
            case ContainerLimits.V2_CPU_MAX -> Optional.of("150000 100000\n");
            default -> Optional.empty();
        });

        assertThat(limits.memoryLimitBytes()).isEqualTo(512 * MB);
        assertThat(limits.cpuQuotaCores()).isEqualTo(1.5);
        assertThat(limits.known()).isTrue();
    }

    @Test
    @DisplayName("内存上限写 max 时如实说未设限，不说成 0 MB")
    void unlimitedCgroupIsNotReportedAsZero() {
        ContainerLimits limits = ContainerLimits.of(path -> switch (path) {
            case ContainerLimits.V2_MEMORY_MAX -> Optional.of("max\n");
            case ContainerLimits.V2_CPU_MAX -> Optional.of("max 100000\n");
            default -> Optional.empty();
        });

        assertThat(limits.memoryLimitBytes()).isEqualTo(ContainerLimits.UNKNOWN);
        assertThat(limits.cpuQuotaCores()).isEqualTo(ContainerLimits.UNKNOWN);
        assertThat(limits.known()).isFalse();

        String text = new SystemStatus(1, 2, 1, 1, 0, limits).format();
        assertThat(text)
                .contains("不在容器里，或容器未设限")
                .doesNotContain("内存 0 MB");
    }

    @Test
    @DisplayName("只有一项读得到时，另一项单独说未设限")
    void oneKnownLimitDoesNotHideTheOther() {
        ContainerLimits limits = ContainerLimits.of(path -> switch (path) {
            case ContainerLimits.V2_MEMORY_MAX -> Optional.of("1073741824\n");
            case ContainerLimits.V2_CPU_MAX -> Optional.of("max 100000\n");
            default -> Optional.empty();
        });

        String text = new SystemStatus(1, 2, 1, 1, 0, limits).format();

        assertThat(text).contains("容器限额：内存 1024 MB").contains("CPU 未设限");
    }

    @Test
    @DisplayName("v1 的配额文件单独成两件时也能折算成核数")
    void cgroupV1QuotaIsReadWhenV2FilesAreAbsent() {
        ContainerLimits limits = ContainerLimits.of(path -> switch (path) {
            case ContainerLimits.V1_MEMORY_LIMIT -> Optional.of("268435456\n");
            case ContainerLimits.V1_CPU_QUOTA -> Optional.of("200000\n");
            case ContainerLimits.V1_CPU_PERIOD -> Optional.of("100000\n");
            default -> Optional.empty();
        });

        assertThat(limits.memoryLimitBytes()).isEqualTo(256 * MB);
        assertThat(limits.cpuQuotaCores()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("v1 那个天文数字是「没限」而不是「限了这么多」")
    void cgroupV1UnlimitedMemoryIsNotReportedAsAHugeLimit() {
        ContainerLimits limits = ContainerLimits.of(path -> path.equals(ContainerLimits.V1_MEMORY_LIMIT)
                ? Optional.of("9223372036854771712\n")
                : Optional.empty());

        assertThat(limits.memoryLimitBytes()).isEqualTo(ContainerLimits.UNKNOWN);
        assertThat(limits.known()).isFalse();
    }

    @Test
    @DisplayName("真实采样拿到的是本进程的数字，不是哨兵值")
    void realSampleReportsLiveProcessNumbers() {
        SystemStatus status = SystemStatus.sample();

        assertThat(status.heapUsedBytes()).isPositive();
        assertThat(status.heapMaxBytes()).isPositive();
        assertThat(status.processors()).isPositive();
        assertThat(status.threads()).isPositive();
        assertThat(status.uptimeMillis()).isNotNegative();
    }
}

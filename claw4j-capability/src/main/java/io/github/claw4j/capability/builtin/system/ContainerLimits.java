package io.github.claw4j.capability.builtin.system;

import java.util.Optional;
import java.util.function.Function;

/**
 * 容器给这个进程划的限额：Linux cgroup 里那几个文件。
 *
 * <p>单独一个类型而不是在工具类里读两行文件，是因为这两个值在容器里与容器外是两件不同的事。
 * 容器外（macOS 开发机、直接跑 jar）没有这些文件，"读不到"是常态而不是故障；而"读不到却报成 0"
 * 会被当成"没有限额"，模型会照着"内存无上限"解释一次 OOM。所以读不到保留成可判定的
 * {@link #UNKNOWN}，不在格式化时退化成一个数字。
 *
 * <p>两个版本都认：cgroup v2（{@code memory.max}、{@code cpu.max}）与 v1（{@code memory.limit_in_bytes}、
 * {@code cpu.cfs_quota_us}）。v1 没设限时给的是一个巨大的数（约 9.2E18），那是"没有限"，
 * 这里一并归一成 {@link #UNKNOWN}。
 *
 * <p>读文件抽成 {@link Reader} 而不是直接 {@code Files.readString}：这两个路径只在 Linux 容器里存在，
 * 不抽出来这条逻辑就只能"在容器里测过"。
 */
public final class ContainerLimits {

    /** 读不到时的取值。负数是为了让它不可能与一个真实字节数混淆。 */
    public static final long UNKNOWN = -1L;

    private static final long NO_LIMIT_SENTINEL = Long.MAX_VALUE / 2;

    /** cgroup v2 的内存上限文件路径。 */
    public static final String V2_MEMORY_MAX = "/sys/fs/cgroup/memory.max";

    /** cgroup v2 的 CPU 配额文件路径，格式是 "{@code quota period}"。 */
    public static final String V2_CPU_MAX = "/sys/fs/cgroup/cpu.max";

    /** cgroup v1 的内存上限文件路径。 */
    public static final String V1_MEMORY_LIMIT = "/sys/fs/cgroup/memory/memory.limit_in_bytes";

    /** cgroup v1 的 CPU 配额：period 与 quota 分成两个文件。 */
    public static final String V1_CPU_QUOTA = "/sys/fs/cgroup/cpu/cpu.cfs_quota_us";

    public static final String V1_CPU_PERIOD = "/sys/fs/cgroup/cpu/cpu.cfs_period_us";

    /** 按路径读文件内容的动作；抽出来是为了让这两个文件不存在的机器上也能测。 */
    @FunctionalInterface
    public interface Reader {

        /**
         * 读一个文件。
         *
         * @param path 绝对路径
         * @return 文件内容；不存在、不可读或读失败时为空
         */
        Optional<String> read(String path);
    }

    private final long memoryLimitBytes;

    private final double cpuQuotaCores;

    private ContainerLimits(long memoryLimitBytes, double cpuQuotaCores) {
        this.memoryLimitBytes = memoryLimitBytes;
        this.cpuQuotaCores = cpuQuotaCores;
    }

    /**
     * 真的去读文件系统。生产路径走这个。
     *
     * @return 读到的限额；容器外两项都是 {@link #UNKNOWN}
     */
    public static ContainerLimits fromFileSystem() {
        return of(FileSystemReader.INSTANCE);
    }

    /**
     * 用给定的读法解析限额。
     *
     * @param reader 读文件的动作，测试用它喂固定内容
     * @return 解析出来的限额
     */
    public static ContainerLimits of(Reader reader) {
        long memory = firstKnown(reader, V2_MEMORY_MAX, V1_MEMORY_LIMIT);
        return new ContainerLimits(memory, cpuCores(reader));
    }

    /** 内存上限（字节）；容器外是 {@link #UNKNOWN}。 */
    public long memoryLimitBytes() {
        return memoryLimitBytes;
    }

    /** CPU 配额（核数）；读不到或没设限是 {@link #UNKNOWN}。 */
    public double cpuQuotaCores() {
        return cpuQuotaCores;
    }

    /**
     * 两项里至少有一项读到了。
     *
     * @return 内存或 CPU 有一项不是 {@link #UNKNOWN} 时为 {@code true}
     */
    public boolean known() {
        return memoryLimitBytes != UNKNOWN || cpuQuotaCores != UNKNOWN;
    }

    /** 两个路径里第一个读得到值的；都读不到就是没限。 */
    private static long firstKnown(Reader reader, String... paths) {
        for (String path : paths) {
            long value = parseLimit(reader.read(path).orElse(""));
            if (value != UNKNOWN) {
                return value;
            }
        }
        return UNKNOWN;
    }

    /**
     * {@code memory.max} 里写 {@code max} 表示没设限，v1 里写的是一个天文数字表示同一件事。
     * 两者都归一成 {@link #UNKNOWN}：那不是"限额是这么多"，是"没有限额"。
     */
    private static long parseLimit(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (value.isEmpty() || "max".equalsIgnoreCase(value)) {
            return UNKNOWN;
        }
        try {
            long parsed = Long.parseLong(value);
            return parsed <= 0 || parsed >= NO_LIMIT_SENTINEL ? UNKNOWN : parsed;
        }
        catch (NumberFormatException notANumber) {
            return UNKNOWN;
        }
    }

    /**
     * CPU 配额折算成核数。v2 一行两个数（quota period）、v1 是两个文件，
     * 算法一样：{@code quota / period}。{@code cpu.max} 里 quota 那一位写 {@code max} 即不限。
     */
    private static double cpuCores(Reader reader) {
        Optional<String> v2 = reader.read(V2_CPU_MAX);
        if (v2.isPresent()) {
            String[] parts = v2.get().strip().split("\\s+");
            if (parts.length == 2 && !"max".equalsIgnoreCase(parts[0])) {
                double quota = parseDouble(parts[0]);
                double period = parseDouble(parts[1]);
                if (quota > 0 && period > 0) {
                    return quota / period;
                }
            }
        }
        double quota = parseDouble(reader.read(V1_CPU_QUOTA).orElse(""));
        double period = parseDouble(reader.read(V1_CPU_PERIOD).orElse(""));
        return quota > 0 && period > 0 ? quota / period : UNKNOWN;
    }

    private static double parseDouble(String raw) {
        try {
            return Double.parseDouble(raw.strip());
        }
        catch (NumberFormatException notANumber) {
            return UNKNOWN;
        }
    }

    /** 生产用的读法：文件不存在、不可读、或不是文件时都算"读不到"。 */
    private enum FileSystemReader implements Reader {

        INSTANCE;

        @Override
        public Optional<String> read(String path) {
            try {
                java.nio.file.Path file = java.nio.file.Path.of(path);
                if (!java.nio.file.Files.isRegularFile(file) || !java.nio.file.Files.isReadable(file)) {
                    return Optional.empty();
                }
                return Optional.of(java.nio.file.Files.readString(file));
            }
            catch (java.io.IOException | RuntimeException unreadable) {
                return Optional.empty();
            }
        }
    }
}

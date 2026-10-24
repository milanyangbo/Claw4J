package io.github.claw4j.tools.generator;

import java.util.Map;
import java.util.Objects;

/**
 * ADR-0003 第 ② 类沙箱的规格：给<b>未审核代码</b>用的那一个执行位置。
 *
 * <p>与第 ① 类（{@code SandboxPolicy}，既有 Script Package 在应用容器内跑）
 * 在代码上刻意是两套东西，不是同一套参数换几个数字：
 * <ul>
 *   <li>跑的东西不同：① 跑的是人审过的包，② 跑的是模型刚写出来的脚本，
 *       而"还没人看过"是这两者之间唯一重要的区别——它决定了默认姿态是"先别信"。
 *   <li>跑的位置不同：① 是包目录（包自己声明的目录），
 *       ② 是每次自测新建、跑完即删的临时目录，于是脚本写下的任何东西都留不下来，
 *       也够不到暂存区里那份"等着被人审的"脚本与审批标记。
 *   <li>预算不同：① 是 15 秒（默认）/ 30 秒（上限），② 是 5 秒，
 *       因为自测要的是"这段脚本跑得起来吗"，不是"这个能力好不好用"。
 * </ul>
 *
 * <p><b>{@link Network} 这一项是本形态唯一给不了的，也是规格里唯一必须说清的。</b>
 * 枚举里给它单独立一项，就是为了让"本地形态没有网络隔离"这句话有地方写，
 * 而不是被一个叫 {@code networkIsolated=true} 的布尔量藏起来。
 * 真正的网络隔离要由容器运行时给（那是部署层的事），
 * 在 CI 上起不来的形态不能写进 README 当承诺——03 号票定下的判据在这里同样适用。
 */
public final class IsolationSpec {

    /** 网络这一项到底有没有被隔离，以及为什么。 */
    public enum Network {

        /** 由执行位置保证：容器内没有网络（部署层给）。 */
        ISOLATED,

        /**
         * 本形态不做网络隔离。
         *
         * <p>纯 Java 起的子进程没有可移植的网络限制手段：能用的只有
         * {@code unshare} / {@code sandbox-exec} 这类命令，而为了限制脚本
         * 再去起一条命令，正是 ADR-0002 禁止的那件事（把宿主机命令行交出去）。
         * 于是这一项交给部署层：跑在容器里时由容器的网络策略给，
         * 跑在 CI 与本机时这一项不存在。
         */
        NOT_ISOLATED
    }

    /** 自测的墙上时钟上限。5 秒够跑完"算个数、打一行字"，也够暴露一个跑飞的脚本。 */
    public static final long MAX_TIMEOUT_MILLIS = 5_000;

    /** 进回答的输出上限，比第 ① 类更紧：自测输出只是给模型看一眼，不需要长篇。 */
    public static final int MAX_OUTPUT_CHARS = 2_000;

    private static final IsolationSpec LOCAL = new IsolationSpec(
            MAX_TIMEOUT_MILLIS, MAX_OUTPUT_CHARS, Map.of("PATH", "/usr/bin:/bin"),
            Network.NOT_ISOLATED, "本地形态：临时目录 + 只给 PATH，网络隔离由部署层的容器网络策略给，"
            + "本进程不提供（纯 Java 起的子进程没有可移植的网络限制手段，而另起一条命令违反 ADR-0002）");

    private final long timeoutMillis;

    private final int maxOutputChars;

    private final Map<String, String> environment;

    private final Network network;

    private final String networkNote;

    public IsolationSpec(long timeoutMillis, int maxOutputChars, Map<String, String> environment,
                         Network network, String networkNote) {
        if (timeoutMillis <= 0 || timeoutMillis > MAX_TIMEOUT_MILLIS) {
            throw new IllegalArgumentException("自测超时必须在 (0, " + MAX_TIMEOUT_MILLIS + "] 内，拿到的是 "
                    + timeoutMillis + "ms");
        }
        this.timeoutMillis = timeoutMillis;
        this.maxOutputChars = maxOutputChars;
        this.environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
        this.network = Objects.requireNonNull(network, "network");
        this.networkNote = Objects.requireNonNull(networkNote, "networkNote");
    }

    /** 本地形态：临时目录 + 最小环境 + 5 秒；网络一项如实写成"没隔离"。 */
    public static IsolationSpec local() {
        return LOCAL;
    }

    public long timeoutMillis() {
        return timeoutMillis;
    }

    public int maxOutputChars() {
        return maxOutputChars;
    }

    /**
     * 子进程能看到的全部环境变量。
     *
     * <p>只有 {@code PATH}，且不继承 JVM 的任何一项——与第 ① 类同一个口径
     * （白名单而不是黑名单）：黑名单的失效方式是静默的，加一个新的
     * {@code XXX_API_KEY} 的人不会想到回来补一条。
     */
    public Map<String, String> environment() {
        return environment;
    }

    public Network network() {
        return network;
    }

    /** 网络这一项为什么是这个值。它必须写清楚，否则"没隔离"会被读成"已隔离"。 */
    public String networkNote() {
        return networkNote;
    }
}

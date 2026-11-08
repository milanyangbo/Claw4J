package io.github.claw4j.capability.exec;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * {@code exec} 的四条边界：工作目录、环境、超时、账号。**判定在这里，执行不在这里。**
 *
 * <p>为什么不做成脚本层 {@code SandboxPolicy} 的一个方法：两者的边界值一样，但**对模型说的话不一样**
 * ——那边是"脚本包目录"、这边是"workspace"；共用一处的代价是那句提示变成两句都不像。
 * 共用的只有超时上限那一个常量，声明在 {@code SandboxPolicy} 上，这里引用它。
 */
public final class ExecPolicy {

    /** 覆盖它等于换一个解释器/加载一段库：二进制劫持那条路，必须拒（照 OpenClaw 的做法）。 */
    private static final String PATH = "PATH";

    private static final String[] LOADER_PREFIXES = {"LD_", "DYLD_"};

    /** 让被执行的东西知道自己是 exec 跑的（照它设 {@code OPENCLAW_SHELL=exec} 的做法）。 */
    public static final String SHELL_MARKER = "CLAW4J_SHELL";

    private static final Pattern ROOT = Pattern.compile("^root$");

    private ExecPolicy() {
    }

    /**
     * 相对路径 → workspace 内的绝对路径，越界即拒。
     *
     * <p>两道检查：{@code normalize()} 挡 {@code ..} 字面量，{@code toRealPath()} 挡符号链接。
     * 只做前者时，workspace 里放一个指向 {@code /} 的软链就能整盘穿出去，而 {@code ..} 一个字都没出现。
     */
    public static Path resolveInside(Path workspace, String relative) {
        Objects.requireNonNull(workspace, "workspace");
        if (relative == null || relative.isBlank()) {
            return workspace.toAbsolutePath().normalize();
        }
        Path candidate;
        try {
            candidate = Path.of(relative);
        }
        catch (InvalidPathException failure) {
            throw new ExecViolationException("workdir 不是合法路径：" + relative, failure);
        }
        if (candidate.isAbsolute()) {
            throw new ExecViolationException("workdir 必须是相对路径（相对 workspace），拿到的是 " + relative);
        }
        Path root = workspace.toAbsolutePath().normalize();
        Path resolved = root.resolve(candidate).normalize();
        if (!resolved.startsWith(root)) {
            throw new ExecViolationException("workdir " + relative + " 越出了 workspace " + root);
        }
        if (!Files.exists(resolved)) {
            throw new ExecViolationException("workdir " + relative + " 在 workspace 里不存在");
        }
        if (Files.isSymbolicLink(resolved) || !Files.isDirectory(resolved)) {
            throw new ExecViolationException("workdir " + relative + " 不是 workspace 里的普通目录");
        }
        return resolved;
    }

    /**
     * 继承 + 覆盖，但 {@code PATH} 与 {@code LD_*}/{@code DYLD_*} 的覆盖直接拒。
     *
     * <p>照 OpenClaw 的 {@code exec}：{@code env} 是"在该继承的环境之上合并"，宿主执行时
     * 拒绝 {@code env.PATH} 与加载器变量（"to prevent binary hijacking or injected code"）。
     * 我们只多一个标记变量，让被执行的东西知道自己在 exec 里。
     */
    public static Map<String, String> environment(Map<String, String> inherited, Map<String, String> overrides) {
        Objects.requireNonNull(inherited, "inherited");
        Map<String, String> merged = new LinkedHashMap<>(inherited);
        if (overrides != null) {
            overrides.forEach((key, value) -> {
                String name = Objects.requireNonNull(key, "env 的键不能为空").strip();
                if (name.isEmpty()) {
                    throw new ExecViolationException("env 里有空的键名");
                }
                if (forbidden(name)) {
                    throw new ExecViolationException("env 不许覆盖 " + name
                            + "：换掉 PATH 或加载器变量等于换一个程序或注入一段代码，要改请改部署环境");
                }
                if (value == null || value.isBlank()) {
                    throw new ExecViolationException("env 里 " + name + " 的值是空的");
                }
                merged.put(name, value);
            });
        }
        merged.put(SHELL_MARKER, "exec");
        return Map.copyOf(merged);
    }

    private static boolean forbidden(String name) {
        if (PATH.equals(name)) {
            return true;
        }
        for (String prefix : LOADER_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** 超时必须在上限内，不夹取：夹取会让"我申请了 5 分钟"和"实际 30 秒"在配置里看起来一样。 */
    public static long timeoutMillis(Duration requested, Duration fallback, Duration cap) {
        Duration value = requested == null ? fallback : requested;
        if (value.isNegative() || value.isZero()) {
            throw new ExecViolationException("timeout 必须是正数，拿到的是 " + value);
        }
        if (value.compareTo(cap) > 0) {
            throw new ExecViolationException("timeout " + value.toMillis() + "ms 超过上限 "
                    + cap.toMillis() + "ms：exec 不接长任务（长任务该走别的执行位置）");
        }
        return value.toMillis();
    }

    /**
     * 拒绝以 root 跑。判据取 {@code user.name} 而不是 uid：uid 没有跨平台的纯 Java 读法。
     * 真正的保证是容器镜像的 {@code USER}，这一条只挡"应用被跑成 root"。
     */
    public static void requireNonRoot(String accountName) {
        if (accountName == null || accountName.isBlank()) {
            throw new ExecViolationException("取不到当前执行账号，按最坏情况拒绝执行");
        }
        if (ROOT.matcher(accountName).matches()) {
            throw new ExecViolationException("拒绝以 root 身份执行命令：exec 必须跑在非 root 账号下");
        }
    }

    /** 跳过符号链接本身也要能判：这里不跟随链接去取真实路径，只在"它本身就是链接"时拒。 */
    static boolean isPlainDirectory(Path path) {
        return Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path);
    }

    /**
     * 白名单：整条命令等于某一项，或以"某一项 + 空格"开头；**并且整条命令里不许有 shell 控制符**。
     *
     * <p>后半条是这条白名单唯一的价值所在。只做前缀匹配时 {@code ls; curl … | sh} 与
     * {@code ls -la; rm -rf …} 都能过——它们的开头确实是 {@code ls}。控制符一票否决之后，
     * "一条白名单命令"就真的只是那一条命令。
     *
     * <p>代价要认：{@code ls | wc -l}、{@code grep x f > out} 在白名单档里也不能跑
     * （它们含 {@code |} 与 {@code >}）。要管道就配 {@code ask} 或 {@code full}——那里有人批或明确放开。
     * 引号里的分号（{@code echo "a;b"}）同样会被拒：这一层不解析引号，宁可少放一条。
     */
    public static boolean permits(java.util.List<String> allow, String command) {
        if (command == null || command.isBlank() || hasControlOperator(command)) {
            return false;
        }
        for (String entry : allow) {
            if (entry == null) {
                continue;
            }
            String allowed = entry.strip();
            if (allowed.isEmpty()) {
                continue;
            }
            if (command.equals(allowed) || command.startsWith(allowed + " ")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasControlOperator(String command) {
        for (char c : new char[]{';', '|', '&', '>', '<', '`', '\n', '\r'}) {
            if (command.indexOf(c) >= 0) {
                return true;
            }
        }
        return command.contains("$(");
    }
}

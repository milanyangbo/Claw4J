package io.github.claw4j.capability.exec;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * {@code exec} 的三条边界：账号、白名单、环境。判定在这里，执行不在这里。
 *
 * <p>不做成脚本层 {@code SandboxPolicy} 的方法：那边判"入口与参数有没有出包目录"，这边判
 * "模型给的一条命令准不准跑"，两条判据没有交集，凑在一处会让两边都说不清。
 *
 * <p>工作目录不在这里判：{@code ShellTools} 的工具参数里没有 {@code workdir}（只有
 * {@code command} / {@code timeout} / {@code description} / {@code runInBackground}），目录由装配时
 * 定的 workspace 决定，模型改不了，也就没有"越界"这条要判。
 */
public final class ExecPolicy {

    /**
     * 覆盖它等于换一个解释器或加载一段库，二进制劫持那条路，必须拒。OpenClaw 的宿主执行同样拒绝
     * {@code env.PATH} 与加载器变量（{@code docs/tools/exec.md:134}）。
     */
    private static final String PATH = "PATH";

    private static final String[] LOADER_PREFIXES = {"LD_", "DYLD_"};

    /**
     * 让被执行的东西知道自己是 exec 跑的，对应 OpenClaw 的 {@code OPENCLAW_SHELL=exec}
     * （{@code docs/tools/exec.md:136}）。
     */
    public static final String SHELL_MARKER = "CLAW4J_SHELL";

    private static final Pattern ROOT = Pattern.compile("^root$");

    private ExecPolicy() {
    }

    /**
     * 继承加覆盖，但 {@code PATH} 与 {@code LD_*} / {@code DYLD_*} 的覆盖直接拒。
     *
     * <p>与 OpenClaw 的 {@code exec} 一致：{@code env} 是"在该继承的环境之上合并"，宿主执行时
     * 拒绝 {@code env.PATH} 与加载器变量，防的是二进制劫持与注入代码（{@code docs/tools/exec.md:134}）。
     * 这里只多一个标记变量，让被执行的东西知道自己在 exec 里。
     *
     * <p>今天 {@code ShellTools} 传进来的覆盖恒为空（工具签名里没有 {@code env}，模型要临时改一个变量
     * 就写 {@code FOO=bar cmd}）。方法仍然收 {@code overrides}，因为拒改 {@code PATH} 这条判据
     * 不依赖"调用方现在能不能传"。
     *
     * @param inherited 继承来的环境
     * @param overrides 本次调用要覆盖的变量，可以为 {@code null}
     * @return 合并后的不可变环境，含 {@link #SHELL_MARKER}
     * @throws ExecViolationException 键为空、覆盖了 {@code PATH} 或加载器变量，或值为空白
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

    /**
     * 拒绝以 root 跑。判据取 {@code user.name} 而不是 uid：uid 没有跨平台的纯 Java 读法。
     * 真正的保证是容器镜像的 {@code USER}，这一条只挡"应用被跑成 root"。
     *
     * @param accountName 当前执行账号
     * @throws ExecViolationException 账号取不到，或账号就是 root
     */
    public static void requireNonRoot(String accountName) {
        if (accountName == null || accountName.isBlank()) {
            throw new ExecViolationException("取不到当前执行账号，按最坏情况拒绝执行");
        }
        if (ROOT.matcher(accountName).matches()) {
            throw new ExecViolationException("拒绝以 root 身份执行命令：exec 必须跑在非 root 账号下");
        }
    }

    /**
     * 白名单：整条命令等于某一项，或以"某一项 + 空格"开头，并且整条命令里不许有 shell 控制符。
     *
     * <p>后半条是这条白名单的价值所在。只做前缀匹配时 {@code ls; curl … | sh} 与
     * {@code ls -la; rm -rf …} 都能过——它们的开头确实是 {@code ls}。控制符一票否决之后，
     * "一条白名单命令"就真的只是那一条命令。
     *
     * <p>代价要认：{@code ls | wc -l}、{@code grep x f > out} 在白名单档里也不能跑（含 {@code |}
     * 与 {@code >}）。要管道就配 {@code ask} 或 {@code full}。引号里的分号（{@code echo "a;b"}）
     * 同样会被拒：这一层不解析引号，宁可少放一条。
     *
     * @param allow 白名单条目，逐项 strip 后比较；{@code null} 项被跳过
     * @param command 模型给的整条命令
     * @return 放行返回 {@code true}；命令为空、含控制符，或没命中任何一项时返回 {@code false}
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

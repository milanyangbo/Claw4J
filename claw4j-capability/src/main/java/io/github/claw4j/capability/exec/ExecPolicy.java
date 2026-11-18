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

    /**
     * 解释器 → 内联求值的旗标。表照 ADR-0030 裁决四抄，改动要先改那张表。
     *
     * <p>shell 这一族（{@code sh} / {@code bash} / {@code zsh} / {@code dash} / {@code ksh}）也在这张表里：
     * 它们加一个 {@code -c} 之后跟的是任意一段命令，与 {@code python3 -c} 是同一件事。
     * 不认它，这张表就没有意义。
     */
    private static final Map<String, java.util.Set<String>> INLINE_EVAL_FLAGS = Map.ofEntries(
            Map.entry("sh", java.util.Set.of("-c")),
            Map.entry("bash", java.util.Set.of("-c")),
            Map.entry("zsh", java.util.Set.of("-c")),
            Map.entry("dash", java.util.Set.of("-c")),
            Map.entry("ksh", java.util.Set.of("-c")),
            Map.entry("python", java.util.Set.of("-c")),
            Map.entry("python2", java.util.Set.of("-c")),
            Map.entry("python3", java.util.Set.of("-c")),
            Map.entry("pypy", java.util.Set.of("-c")),
            Map.entry("node", java.util.Set.of("-e", "--eval", "-p", "--print")),
            Map.entry("nodejs", java.util.Set.of("-e", "--eval", "-p", "--print")),
            Map.entry("bun", java.util.Set.of("-e", "--eval", "-p", "--print")),
            Map.entry("deno", java.util.Set.of("-e", "--eval", "-p", "--print")),
            Map.entry("awk", java.util.Set.of("-e", "--source")),
            Map.entry("gawk", java.util.Set.of("-e", "--source")),
            Map.entry("mawk", java.util.Set.of("-e", "--source")),
            Map.entry("nawk", java.util.Set.of("-e", "--source")),
            Map.entry("ruby", java.util.Set.of("-e")),
            Map.entry("perl", java.util.Set.of("-e", "-E")),
            Map.entry("php", java.util.Set.of("-r")),
            Map.entry("lua", java.util.Set.of("-e")),
            Map.entry("osascript", java.util.Set.of("-e")),
            Map.entry("find", java.util.Set.of("-exec", "-execdir", "-ok", "-okdir")),
            Map.entry("make", java.util.Set.of("-f", "--eval", "-E")),
            Map.entry("gmake", java.util.Set.of("-f", "--eval", "-E")),
            Map.entry("sed", java.util.Set.of("-e")),
            Map.entry("gsed", java.util.Set.of("-e")));

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

    // ---------------------------------------------------------------------
    // 三段式：security × ask × autoReview（ADR-0030 裁决五）
    // ---------------------------------------------------------------------

    /**
     * 一次生效的 exec 策略。三个字段是本体，{@code host} / {@code strictInlineEval} 跟着一起传，
     * 因为它们同样只在"执行那一刻"有用。
     *
     * @param security         放行到什么程度
     * @param ask              什么时候问人
     * @param autoReview       未命中时是否先问复核器
     * @param host             在哪跑
     * @param strictInlineEval 解释器内联求值一律走审批
     * @param askFallback      等不到人时按哪一档处置
     */
    public record Settings(ExecProperties.Security security,
                           ExecProperties.Ask ask,
                           boolean autoReview,
                           ExecProperties.Host host,
                           boolean strictInlineEval,
                           ExecProperties.AskFallback askFallback) {

        /**
         * 不带兜底档的那一版：兜底取 {@link ExecProperties.AskFallback#DENY}（默认档）。
         *
         * <p>只给"只想摆五个字段"的调用方（用例、以及只关心三段式的诊断代码）用。生产路径走
         * {@code ExecProperties#settings()}，它总会带上配置里那一档。
         *
         * @param security 放行到什么程度
         * @param ask 什么时候问人
         * @param autoReview 未命中时是否先问复核器
         * @param host 在哪跑
         * @param strictInlineEval 解释器内联求值一律走审批
         */
        public Settings(ExecProperties.Security security, ExecProperties.Ask ask, boolean autoReview,
                        ExecProperties.Host host, boolean strictInlineEval) {
            this(security, ask, autoReview, host, strictInlineEval, ExecProperties.AskFallback.DENY);
        }
    }

    /**
     * 把一档预设展开成三个字段。照 OpenClaw 的 {@code resolveExecPolicyForMode}，逐行对应。
     *
     * <p>预设不管兜底档：{@code askFallback} 是部署级的一个独立决定（"等不到人怎么办"），
     * 与"哪一档"没有对应关系。这里给 {@link ExecProperties.AskFallback#DENY}，
     * 与外层的默认值一致；真正生效的那个由 {@code ExecProperties#settings()} 盖上来。
     *
     * @param mode 预设
     * @return 展开后的三段式
     */
    public static Settings expand(ExecProperties.Mode mode) {
        return switch (mode) {
            case DENY -> new Settings(ExecProperties.Security.DENY, ExecProperties.Ask.OFF,
                    false, ExecProperties.Host.AUTO, false, ExecProperties.AskFallback.DENY);
            case ALLOWLIST -> new Settings(ExecProperties.Security.ALLOWLIST, ExecProperties.Ask.OFF,
                    false, ExecProperties.Host.AUTO, false, ExecProperties.AskFallback.DENY);
            case ASK -> new Settings(ExecProperties.Security.ALLOWLIST, ExecProperties.Ask.ON_MISS,
                    false, ExecProperties.Host.AUTO, false, ExecProperties.AskFallback.DENY);
            case AUTO -> new Settings(ExecProperties.Security.ALLOWLIST, ExecProperties.Ask.ON_MISS,
                    true, ExecProperties.Host.AUTO, false, ExecProperties.AskFallback.DENY);
            case FULL -> new Settings(ExecProperties.Security.FULL, ExecProperties.Ask.OFF,
                    false, ExecProperties.Host.AUTO, false, ExecProperties.AskFallback.DENY);
        };
    }

    /**
     * 等不到人时，这一次放不放。
     *
     * <p>兜底档要先被本次生效的 {@code security} 夹住（照它的
     * {@code minSecurity(effectiveSecurity, host.askFallback)}）：{@code security=allowlist} 配
     * {@code askFallback=full}，字面上是"没人看见就放行"，而 {@code allowlist} 那一档的全部意义
     * 是"未命中就是不跑"。两个配置项打架时取更严的那个，否则"硬边界"这个词就不成立了。
     *
     * <p>{@code ALLOWLIST} 档在这里恒等于不放：能走到"等不到人"这一步的，正是<b>没有命中</b>白名单
     * 的那些命令（命中的早就 {@code RUN} 了）。写成"再查一次白名单"是同一件事的两次实现，
     * 而两处口径迟早会分开。
     *
     * @param settings 生效策略
     * @return 放行一次时为 {@code true}
     */
    public static boolean fallbackAllows(Settings settings) {
        Objects.requireNonNull(settings, "settings");
        if (settings.security() == ExecProperties.Security.DENY) {
            return false;
        }
        ExecProperties.AskFallback allowed = settings.askFallback();
        if (settings.security() == ExecProperties.Security.ALLOWLIST
                && allowed == ExecProperties.AskFallback.FULL) {
            return false;
        }
        return allowed == ExecProperties.AskFallback.FULL;
    }

    /**
     * 反解：三个字段回落到最贴近的一档。照它的 {@code resolveExecModeFromPolicy}。
     *
     * <p><b>它认不出 {@code auto}</b>：{@code allowlist} + {@code on-miss} + {@code autoReview}
     * 会归到 {@code ASK}——因为"有没有复核器"是另一件事，档位不该假装表达了它。照抄。
     *
     * <p>这条反解最关键的一句：{@code security=full} 配 {@code ask=always} <b>反解回 {@code ASK}</b>，
     * 意思是"每条都问"。实现里别把 {@code full} 当成"什么都不问"。
     *
     * @param settings 三段式
     * @return 最贴近的那一档
     */
    public static ExecProperties.Mode modeOf(Settings settings) {
        if (settings.security() == ExecProperties.Security.DENY) {
            return ExecProperties.Mode.DENY;
        }
        if (settings.security() == ExecProperties.Security.ALLOWLIST
                && settings.ask() == ExecProperties.Ask.OFF) {
            return ExecProperties.Mode.ALLOWLIST;
        }
        if (settings.security() == ExecProperties.Security.FULL
                && settings.ask() != ExecProperties.Ask.ALWAYS) {
            return ExecProperties.Mode.FULL;
        }
        return ExecProperties.Mode.ASK;
    }

    /**
     * 这次调用要不要人先批。照它的 {@code requiresExecApproval}，三行照抄：
     *
     * <pre>
     * ask === "always"                → true
     * durableApprovalSatisfied === true → false
     * ask === "on-miss" &amp;&amp; security === "allowlist" &amp;&amp; (!analysisOk || !allowlistSatisfied)
     * </pre>
     *
     * <p>两条容易实现错的次序：
     *
     * <ul>
     *   <li><b>{@code always} 优先于持久批准</b>：批过一次之后，{@code ask=always} 的下一次<b>仍然要问</b>。
     *       这是"每次都要人看"的唯一含义，也是卡片上不给 {@code allow-always} 的原因。</li>
     *   <li><b>{@code full} + {@code on-miss} 不问</b>：最后一句要求 {@code security=allowlist}，
     *       {@code full} 下没有"未命中"这回事。</li>
     * </ul>
     *
     * @param settings                 生效策略
     * @param analysisOk               命令分析是否成功（解析不出 argv 时为 {@code false}）
     * @param allowlistSatisfied       这条命令是否命中白名单
     * @param durableApprovalSatisfied 是否已有持久批准（{@code allow-always} 落的那条）
     * @return 需要人先批时为 {@code true}
     */
    public static boolean requiresApproval(Settings settings,
                                           boolean analysisOk,
                                           boolean allowlistSatisfied,
                                           boolean durableApprovalSatisfied) {
        if (settings.ask() == ExecProperties.Ask.ALWAYS) {
            return true;
        }
        if (durableApprovalSatisfied) {
            return false;
        }
        return settings.ask() == ExecProperties.Ask.ON_MISS
                && settings.security() == ExecProperties.Security.ALLOWLIST
                && (!analysisOk || !allowlistSatisfied);
    }

    /**
     * 这条命令分析出结果了吗——每段都取得到可执行名才算。
     *
     * <p>它就是 {@link #requiresApproval} 的 {@code analysisOk} 那一维。OpenClaw 在调用点还另 OR 了
     * 一条 {@code allowlist 计划不可用}，我们这两条是同一件事：能落成 {@code pattern} 的段说明它分析得
     * 出来，落不成的（{@code ./run.sh}、{@code FOO=bar cmd}、引号开头）既没有白名单计划、也说不清
     * "要放行的是什么"，于是只能问人。
     *
     * <p>差别只在 {@code security=allowlist} + {@code ask=on-miss} 下"文本命中白名单但入口说不清"这一格：
     * 白名单按整条命令匹配，{@code ./run.sh} 可以配进去，而它是什么程序取决于那个文件本身——
     * {@code full} 档不看白名单，所以不受这条影响。
     *
     * @param command 模型的原始命令
     * @return 至少一段、且每段都取得到可执行名时为 {@code true}
     */
    public static boolean analysisOk(String command) {
        java.util.List<String> segments = segments(command);
        return !segments.isEmpty()
                && segments.stream().allMatch(segment -> patternOf(segment).isPresent());
    }

    /**
     * 这一条命令即使命中白名单也必须问人：heredoc 与内联求值（照它的 {@code requiresAsk} 追加项）。
     *
     * <p>两条都"看起来像白名单允许的那条命令"，实际上不是：
     *
     * <ul>
     *   <li><b>heredoc</b>：{@code cat <<EOF} 之后是任意内容，白名单里那条 {@code cat} 一个字符都没覆盖到它。
     *       只认 {@code <<} 这个记号，不做词法分析——引号里的 {@code <<} 会被误判成 heredoc，
     *       代价是"多问一次"，反过来（漏判）是"任意内容不经人看"。</li>
     *   <li><b>内联求值</b>：{@code python3 -c "任意代码"} 的可执行文件是 {@code python3}，
     *       按它放行等于放行一切。{@code strictInlineEval} 开着时这些形态一律降级成"问人"，
     *       即使解释器本身在白名单里。</li>
     * </ul>
     *
     * <p>它在 {@link #verdict} 之后叠加，而不是塞进 {@code requiresApproval}：那一组判据的入参是
     * "命中没命中"，这两条要看命令文本本身。三类结局里只把 {@link Verdict#RUN} 抬成
     * {@link Verdict#ASK}——已经 {@code DENY} 的不该因为"要问人"反而变得更容易过。
     *
     * @param settings 生效策略，取 {@code strictInlineEval}
     * @param command  模型的原始命令
     * @return 必须问人时为 {@code true}
     */
    public static boolean forceAsk(Settings settings, String command) {
        Objects.requireNonNull(settings, "settings");
        if (heredoc(command)) {
            return true;
        }
        return settings.strictInlineEval()
                && segments(command).stream().anyMatch(ExecPolicy::inlineEval);
    }

    /**
     * 命令里有没有 heredoc / here-string 记号（{@code <<}）。
     *
     * @param command 整条命令，可以为 {@code null}
     * @return 命中时为 {@code true}
     */
    public static boolean heredoc(String command) {
        return command != null && command.contains("<<");
    }

    /**
     * 这一段是不是"解释器 + 内联求值"的形态。
     *
     * <p>清单照 ADR-0030 裁决四那张表抄全，不只抄七个解释器：漏一个就等于给那个解释器留了一条
     * "白名单直通任意代码"的路。按<b>可执行文件名的 basename</b> 认（{@code /usr/bin/python3} 与
     * {@code python3} 是同一个东西），参数里出现对应旗标即算命中。
     *
     * @param segment 一段命令
     * @return 命中时为 {@code true}
     */
    public static boolean inlineEval(String segment) {
        if (segment == null || segment.isBlank()) {
            return false;
        }
        String[] tokens = segment.strip().split("\\s+");
        String executable = basename(tokens[0]);
        java.util.Set<String> flags = INLINE_EVAL_FLAGS.get(executable);
        if (flags == null) {
            return false;
        }
        for (int index = 1; index < tokens.length; index++) {
            if (flags.contains(tokens[index])) {
                return true;
            }
        }
        return false;
    }

    private static String basename(String token) {
        int slash = token.lastIndexOf('/');
        return slash < 0 ? token : token.substring(slash + 1);
    }

    /**
     * 一次调用的三个结局。
     *
     * <p>单独一个枚举而不是两个 boolean：{@code RUN} 与 {@code DENY} 之间差的不是"批不批"，而是
     * "这条路径根本不该被问到人"。合成 {@code (needsApproval, allowed)} 两个布尔，迟早有一处写成
     * {@code !needsApproval} 就放行。
     */
    public enum Verdict {
        /** 直接跑。 */
        RUN,

        /** 问人。批了跑，不批不跑。 */
        ASK,

        /** 直接拒，不打扰人。 */
        DENY
    }

    /**
     * 这次调用该走哪条路。{@link #requiresApproval} 只回答"要不要问人"，这里补上"不问人的时候放不放"。
     *
     * <p>三种组合都出现过，不能省：
     *
     * <ul>
     *             <li>{@code allowlist} + {@code ask=off} + 未命中 → {@link Verdict#DENY}：这是"硬边界"档，
     *       它存在的意义就是不给人点按钮的机会。已落过 {@code allow-always} 的不算未命中——
     *       那条持久批准就是"命中"的等价物。</li>
     *   <li>{@code ask=on-miss} + 未命中 → {@link Verdict#ASK}。</li>
     *   <li>{@code full} + {@code on-miss} + 未命中 → {@link Verdict#RUN}：{@code full} 下没有
     *       "未命中"这回事。</li>
     * </ul>
     *
     * @param settings    生效策略
     * @param command     模型的原始命令，用来判 {@link #analysisOk}
     * @param allowlisted 是否命中白名单（{@code security=full} 时忽略）
     * @param durable     是否已有持久批准
     * @return 该走哪条路
     */
    public static Verdict verdict(Settings settings, String command, boolean allowlisted,
                                  boolean durable) {
        if (settings.security() == ExecProperties.Security.DENY) {
            return Verdict.DENY;
        }
        if (requiresApproval(settings, analysisOk(command), allowlisted, durable)) {
            return Verdict.ASK;
        }
        if (settings.security() == ExecProperties.Security.ALLOWLIST && !allowlisted && !durable) {
            return Verdict.DENY;
        }
        return Verdict.RUN;
    }

    /**
     * 把一条命令按 shell 分隔符拆成段。{@code &&} / {@code ||} / {@code ;} / {@code |} / 换行 /
     * 单个 {@code &} 都算分隔符，空的段丢掉。
     *
     * <p><b>不解析引号</b>：{@code echo "a;b"} 会被拆成 {@code echo "a} 与 {@code b"}。这是有意的——
     * 与 {@link #permits} 同一个取向（宁可少放一条）。拆错的后果是第二段落不出可用的 pattern，
     * 于是整条命令不落库、下次还问，而不是落一条过宽的。
     *
     * @param command 整条命令，可以为 {@code null}
     * @return 段清单；命令为空时返回空清单
     */
    public static java.util.List<String> segments(String command) {
        if (command == null || command.isBlank()) {
            return java.util.List.of();
        }
        java.util.List<String> out = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (c == ';' || c == '\n') {
                addSegment(out, current);
                continue;
            }
            if (c == '&' || c == '|') {
                // && 与 || 当成一个分隔符，不拆出空段
                if (i + 1 < command.length() && command.charAt(i + 1) == c) {
                    i++;
                }
                addSegment(out, current);
                continue;
            }
            current.append(c);
        }
        addSegment(out, current);
        return java.util.List.copyOf(out);
    }

    private static void addSegment(java.util.List<String> out, StringBuilder current) {
        String text = current.toString().strip();
        if (!text.isEmpty()) {
            out.add(text);
        }
        current.setLength(0);
    }

    /**
     * 一段命令能落成的 {@code pattern}。取首个 token；取不到、或它长得不像一个可执行名时返回空。
     *
     * <p>三条拒绝，都朝"不落库"而不是"落宽一点"：
     *
     * <ul>
     *   <li>首 token 含 {@code /}：那是路径。OpenClaw 会解析成真实路径再落，我们做不到（要解软链接），
     *       退而求其次取 basename 会让 {@code /tmp/evil/rg} 与 {@code rg} 共用一条 pattern——
     *       比不落库危险得多。</li>
     *   <li>首 token 含 {@code =}：{@code FOO=bar cmd} 的形式，真正的可执行名在第二个 token，本层不解析。</li>
     *   <li>首 token 以 {@code $} 或引号开头：变量展开与引号，本层都不解析。</li>
     * </ul>
     *
     * @param segment 一段命令
     * @return 可以落库的 pattern；表达不出来时为空
     */
    public static java.util.Optional<String> patternOf(String segment) {
        if (segment == null || segment.isBlank()) {
            return java.util.Optional.empty();
        }
        String text = segment.strip();
        int end = 0;
        while (end < text.length() && !Character.isWhitespace(text.charAt(end))) {
            end++;
        }
        String first = text.substring(0, end);
        if (first.isEmpty()
                || first.indexOf('/') >= 0
                || first.indexOf('=') >= 0
                || first.startsWith("$")
                || first.startsWith("\"")
                || first.startsWith("'")) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(first);
    }
}

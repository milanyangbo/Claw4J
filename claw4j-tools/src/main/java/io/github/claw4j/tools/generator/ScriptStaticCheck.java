package io.github.claw4j.tools.generator;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 生成脚本进门前的<b>静态检查</b>：一个纯函数，输入脚本正文，输出命中的规则。
 *
 * <p><b>它是这条链路上唯一一道机器关，也是最便宜的一环</b>：自测要起进程、
 * 要花时间、还要有沙箱，而这一关既不落盘也不执行就能挡下绝大多数危险写法。
 * 放在最前面的代价是零，放在后面的代价是"危险代码已经写到了磁盘上、
 * 已经在沙箱里跑过一次"。
 *
 * <p>纯函数是有意的，也有测试盯着（{@code ScriptStaticCheckTest}）：
 * 不读文件、不看时间、不取随机数，同一份输入永远给同一份输出。
 * 于是"这一关挡住了什么"可以被一张表钉死，不用起进程、不用建目录，
 * 用例想加多少条就加多少条。
 *
 * <p><b>它做得不是"这段代码安全"的判断</b>。它认的是写法（装包、提权、连网、
 * 读凭据、删东西、动态执行），认不出意图——一个把危险动作藏在变量里、
 * 或者分几步拼出来的脚本，它看不出来。它挡的是"明着写"的那部分，
 * 而那部分在真实事故里占绝大多数：1.x 那次让脚本自行
 * {@code pip install --break-system-packages} 就是明着写的。
 */
public final class ScriptStaticCheck {

    /** 入口不是认识的语言：规则集选不出来，于是不接受，而不是"检查通过"。 */
    public static final String UNKNOWN_LANGUAGE = "UNKNOWN_LANGUAGE";

    /**
     * 规则表：一条规则 = 一个规则号、适用语言、一个正则、一句为什么拒。
     *
     * <p>为什么是正则表而不是一个真的解析器：这份检查的职责是"挡住明着写的危险写法"，
     * 引一个 shell / Python 解析器换来的准确率用不上，换不走的那部分（意图）
     * 它依然看不出来。表的形态还让"加一条规则"变成加一行，
     * 而每条都能单独被一个用例钉住。
     */
    private static final List<StaticRule> RULES = List.of(
            // ---- 两种语言都适用 ----
            rule("CREDENTIAL_FILE", null,
                    "(?i)(\\.env|id_rsa|id_dsa|\\.pem|credentials|application(-[a-z]+)?\\.(yml|yaml|properties)"
                            + "|\\.aws/|\\.ssh/|\\.netrc)",
                    "读凭据文件：脚本层拿不到任何业务凭据，会去读就说明它想绕过这件事"),
            rule("CREDENTIAL_ENV", null,
                    "(?i)\\$(|\\{)?[A-Z0-9_]*(KEY|TOKEN|PASSWORD|SECRET|CREDENTIAL)[A-Z0-9_]*",
                    "读凭据环境变量：沙箱环境里没有这些东西，读它只会拿到空值，其余情况属于越权意图"),
            rule("PATH_ESCAPE", null,
                    "(^|[^\\w.])(\\.\\./|\\.\\.\\\\|/etc/|/root/|/home/|/var/|/usr/|/private/|~/)",
                    "访问沙箱目录之外的路径：脚本只能碰自己的目录，路径里出现这些形式一律拒"),
            rule("DYNAMIC_EXEC", null,
                    "(?i)\\b(eval\\s|base64\\s+(-d|--decode)|__import__\\s*\\(|pickle\\.loads)",
                    "动态执行：把内容拼出来再执行绕过了静态检查，等于让这一关不生效"),

            // ---- shell ----
            rule("PRIVILEGE", ScriptLanguage.SHELL, "(?i)(^|\\s|;|\\|)(sudo|su\\s|doas)\\s",
                    "提权：脚本层跑在非 root 下，会去提权就说明它要的东西不该由它拿"),
            rule("PACKAGE_INSTALL", ScriptLanguage.SHELL,
                    "(?i)\\b(pip|pip3|npm|yarn|apt-get|apt|brew|dnf|yum)\\s+(install|-m\\s+pip)",
                    "自装依赖：装包装的是未审核的代码，而且会改动这台机器，不属自测范围"),
            rule("REMOTE_EXEC", ScriptLanguage.SHELL,
                    "(?i)\\b(curl|wget)\\b[^|\\n]*\\|\\s*(sudo\\s+)?(ba)?sh",
                    "下载即执行：把网上的东西直接喂给 shell，内容在这份脚本里看不见"),
            rule("NETWORK_CLIENT", ScriptLanguage.SHELL,
                    "(?i)\\b(curl|wget|nc|ncat|netcat|ssh|scp|rsync|ftp)\\b",
                    "主动联网：沙箱不提供网络（ADR-0003 的第 ② 类），会去连的脚本自测必然失败"),
            rule("REVERSE_SHELL", ScriptLanguage.SHELL,
                    "(?i)(/dev/tcp/|bash\\s+-i|\\bsh\\s+-i\\b)",
                    "反弹 shell：这是把这台机器的命令行交出去，与执行一个脚本完全是两回事"),
            rule("DESTRUCTIVE_RM", ScriptLanguage.SHELL,
                    "(?i)\\brm\\s+(-[a-z]*[rf][a-z]*\\s|\\s*[^\"']*/)",
                    "删除：自测脚本不需要删任何东西，需要删就说明它超出了「算个数、给段文本」的范围"),
            rule("WRITE_OUTSIDE", ScriptLanguage.SHELL,
                    "(?i)(>\\s*/|>>\\s*/|\\btee\\s+/|\\bdd\\s+)",
                    "往绝对路径写：写的位置不受沙箱工作目录约束"),
            rule("SHELL_FORK_BOMB", ScriptLanguage.SHELL, "(?i)(:\\(\\)\\{|:\\|&)",
                    "fork 炸弹：它会把这台机器拖死，而沙箱不提供资源上限"),

            // ---- python ----
            rule("PY_SUBPROCESS", ScriptLanguage.PYTHON,
                    "(?i)(import\\s+subprocess|from\\s+subprocess|import\\s+pty|\\bos\\.system\\s*\\("
                            + "|\\bos\\.popen\\s*\\(|\\bos\\.execv)",
                    "起子进程：脚本层的能力边界是「跑这一个脚本」，再起一个进程就出界了"),
            rule("PY_NETWORK", ScriptLanguage.PYTHON,
                    "(?i)(import\\s+socket|from\\s+socket|import\\s+requests|urllib\\.request"
                            + "|http\\.client|import\\s+ftplib|import\\s+smtplib)",
                    "主动联网：沙箱不提供网络，会去连的脚本自测必然失败"),
            rule("PY_DESTRUCTIVE", ScriptLanguage.PYTHON,
                    "(?i)(shutil\\.rmtree\\s*\\(|\\bos\\.remove\\s*\\(|\\bos\\.unlink\\s*\\("
                            + "|\\bos\\.rmdir\\s*\\()",
                    "删除：自测脚本不需要删任何东西"),
            rule("PY_FILE_ESCAPE", ScriptLanguage.PYTHON,
                    "(?i)open\\s*\\(\\s*['\"](/|\\.\\./)",
                    "打开绝对路径：读写的位置不受沙箱工作目录约束"));

    /**
     * 一条规则的形状。
     *
     * @param language 只在哪种语言下生效；{@code null} 表示两种都查
     */
    private record StaticRule(String id, ScriptLanguage language, Pattern pattern, String why) {

        boolean appliesTo(ScriptLanguage language) {
            return this.language == null || this.language == language;
        }
    }

    private static StaticRule rule(String id, ScriptLanguage language, String regex, String why) {
        return new StaticRule(id, language, Pattern.compile(regex), why);
    }

    /** 命中一条：规则号、行号、那一行（截断）、为什么拒。 */
    public record Finding(String ruleId, int line, String evidence, String why) {
    }

    /**
     * 一份检查报告。
     *
     * @param accepted 是否放行
     * @param language 判定出的语言；认不出时为空，此时 {@code accepted} 必为假
     * @param findings 命中的规则，按行号排
     */
    public record Report(boolean accepted, Optional<ScriptLanguage> language, List<Finding> findings) {

        /**
         * 给模型看的那一段。
         *
         * <p>为什么要把"为什么拒"一起给回去：模型要靠它改脚本。只说"不通过"的话，
         * 它下一次提交多半是同一份脚本换了个名字——它没有可改的信息。
         */
        public String render() {
            if (accepted) {
                return "静态检查：通过（" + findings.size() + " 条命中）";
            }
            StringBuilder text = new StringBuilder("静态检查：不通过，命中 " + findings.size() + " 条");
            for (Finding finding : findings) {
                text.append("\n  - ")
                        .append(finding.ruleId())
                        .append(" 第 ")
                        .append(finding.line())
                        .append(" 行：")
                        .append(finding.why())
                        .append("\n    原文：")
                        .append(finding.evidence());
            }
            return text.toString();
        }
    }

    /**
     * 查一份脚本。
     *
     * <p><b>不去注释里找</b>是有意的：把 {@code curl} 写进注释里是常见的
     * （"这里本来该用 curl，但沙箱没有网"），按注释拒掉会挡住一份本来能过的脚本；
     * 而真要藏的人本来也不会写在明面上。这一关拦的是明着写的危险写法，
     * 不假装能读懂意图。
     */
    public Report check(String entrypoint, String script) {
        Objects.requireNonNull(entrypoint, "entrypoint");
        if (script == null || script.isBlank()) {
            return new Report(false, ScriptLanguage.ofEntrypoint(entrypoint),
                    List.of(new Finding("EMPTY_SCRIPT", 0, "", "脚本是空的：没有可检查、也没有可自测的东西")));
        }
        Optional<ScriptLanguage> language = ScriptLanguage.ofEntrypoint(entrypoint);
        if (language.isEmpty()) {
            return new Report(false, language, List.of(new Finding(UNKNOWN_LANGUAGE, 0, entrypoint,
                    "只接受 .sh 与 .py 的入口：别的语言选不出规则集，而选不出规则集的「检查通过」是空话")));
        }
        List<Finding> findings = new ArrayList<>();
        String[] lines = script.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (isComment(line)) {
                continue;
            }
            for (StaticRule rule : RULES) {
                if (!rule.appliesTo(language.get())) {
                    continue;
                }
                if (rule.pattern().matcher(line).find()) {
                    findings.add(new Finding(rule.id(), i + 1, trim(line), rule.why()));
                }
            }
        }
        return new Report(findings.isEmpty(), language, List.copyOf(findings));
    }

    /**
     * 注释行不参与检查。
     *
     * <p>两种语言的行注释都是 {@code #}。跳过它们的理由不是"注释无害"
     * （真要藏的人不会写在注释里），而是相反：把 {@code curl} 写进注释是常见的写法
     * ——"这里本来该联网取，但沙箱没有网"——按注释拒掉会挡住一份本来能过的脚本，
     * 而那种误伤的代价是模型被迫改掉一段其实没问题的说明。
     */
    private static boolean isComment(String line) {
        return line.stripLeading().startsWith("#");
    }

    private static String trim(String line) {
        String text = line.strip();
        return text.length() <= 120 ? text : text.substring(0, 120) + "…";
    }
}

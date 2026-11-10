package io.github.claw4j.secret;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一个配置值：它现在是句柄，不是真值（P08）。
 *
 * <p>三种形态：句柄、显式常量、没配。写法只有两种：
 *
 * <ul>
 *   <li>{@code secret:<名字>}：句柄。出口那一刻换成真值，配置对象从头到尾只有名字。</li>
 *   <li>{@code plain:<文本>}：显式常量，只有非凭据位接受它（MCP 的 {@code env} 与
 *       {@code headers} 里偶尔要带 {@code HOME=/tmp/x} 这种非凭据常量）。写成一个前缀而不是让
 *       明文裸着，是为了让"这里确实是一段明文"在配置里看得见；凭据位上它是被拒绝的。</li>
 * </ul>
 *
 * <p>值里可以出现多个句柄，也可以带前后缀（{@code Bearer secret:deepwiki_token}）：第三方端点
 * 常见的形状就是"固定前缀 + 一把钥匙"，要求整值必须是句柄等于把这类配置逼回明文。
 *
 * <p>形状必须可校验，不能靠"看着像"：一个不含 {@code secret:} 的值在凭据位上直接失败，并点名
 * 是哪一行；句柄名限定为小写字母、数字与 {@code . _ -}，因为它是环境变量名的小写写法
 * （{@link SecretResolver#environmentName}）。写错大小写时立刻失败，好过启动之后表现为
 * "401 但配置看起来是对的"。
 *
 * <p>它不持有真值，也不打印真值：{@link #toString()} 给的是原文（句柄或 {@code plain:}），
 * {@code render} 的结果只该流向出口，永远不该进日志或审计。
 */
public final class SecretValue {

    /** 句柄前缀。 */
    public static final String SCHEME = "secret:";

    /** 显式常量前缀。 */
    public static final String LITERAL = "plain:";

    /** 句柄名的形状：环境变量名的小写写法。 */
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9._-]*");

    /** 值里一个句柄片段：到空白、引号、逗号、分号或反引号为止。 */
    private static final Pattern REF = Pattern.compile("secret:([^\\s\"'`,;]+)");

    private static final SecretValue ABSENT = new SecretValue("", "", List.of(), "", true, false);

    /** 原文。日志与审计只打这个。 */
    private final String text;

    /** 渲染模板：{@code plain:} 时是剥掉前缀的字面量，其余情况等于原文。 */
    private final String template;

    private final List<String> handles;

    /** 配置项全名，只用于报错点名。 */
    private final String where;

    private final boolean absent;

    private final boolean literal;

    private SecretValue(String text, String template, List<String> handles, String where,
                        boolean absent, boolean literal) {
        this.text = text;
        this.template = template;
        this.handles = List.copyOf(handles);
        this.where = where;
        this.absent = absent;
        this.literal = literal;
    }

    /** 没配。与"配了空串"是同一件事：{@code api-key:} 之后什么都不写。 */
    public static SecretValue absent() {
        return ABSENT;
    }

    /**
     * 校验并收下一个配置值。
     *
     * <p>报错里不回显原值：写错位的那一行很可能就是一段真 key，把它打进日志等于把凭据搬到
     * 日志里去——这正是本票要防的事。
     *
     * @param raw        配置里写的原文
     * @param where      配置项全名，例如 {@code claw4j.model.deepseek.api-key}
     * @param allowPlain 是否接受 {@code plain:}。凭据位一律 false（模型 key 这种"只能是凭据"的
     *                   位置，连明文常量都没有存在的理由）
     * @return 校验过的值；{@code raw} 为空时是 {@link #absent()}
     * @throws IllegalArgumentException 凭据位给了明文常量、{@code plain:} 与句柄混用、句柄名不合
     *                                  法、或值里没有任何句柄
     */
    public static SecretValue parse(String raw, String where, boolean allowPlain) {
        Objects.requireNonNull(where, "where");
        if (raw == null || raw.isBlank()) {
            return ABSENT;
        }
        String value = raw.strip();
        if (value.startsWith(LITERAL)) {
            if (!allowPlain) {
                throw new IllegalArgumentException(where
                        + " 不接受明文常量：这一项只能是句柄 " + SCHEME + "<名字>。");
            }
            String literal = value.substring(LITERAL.length());
            if (literal.contains(SCHEME)) {
                throw new IllegalArgumentException(where
                        + " 里的 " + LITERAL + " 与 " + SCHEME + " 不能混用：整值要么是一段常量，"
                        + "要么是含句柄的模板。");
            }
            return new SecretValue(value, literal, List.of(), where, false, true);
        }
        List<String> handles = new ArrayList<>();
        Matcher ref = REF.matcher(value);
        while (ref.find()) {
            String name = ref.group(1);
            if (!NAME.matcher(name).matches()) {
                throw new IllegalArgumentException(where + " 里的句柄名不合法：" + SCHEME + name
                        + "。句柄名是环境变量名的小写写法（" + SCHEME + "deepseek_api_key 对应 "
                        + SecretResolver.environmentName("deepseek_api_key") + "），"
                        + "只允许小写字母、数字与 . _ -。");
            }
            handles.add(name);
        }
        if (handles.isEmpty()) {
            throw new IllegalArgumentException(where + " 只能是句柄：" + SCHEME + "<名字>"
                    + (allowPlain ? "，或显式常量 " + LITERAL + "<文本>" : "")
                    + "。真值不进配置——写名字进来，值留给环境变量。");
        }
        return new SecretValue(value, value, handles, where, false, false);
    }

    /** 原文（句柄，或 {@code plain:} 那段常量的原文）。日志、审计、审批卡片只该出现它。 */
    public String text() {
        return text;
    }

    /** 这个值里出现的句柄名，按出现顺序。空表示 {@code plain:} 常量或没配。 */
    public List<String> handles() {
        return handles;
    }

    /**
     * 这个值是不是没配。
     *
     * @return 没配时为 {@code true}
     */
    public boolean isAbsent() {
        return absent;
    }

    /**
     * 出口：把句柄换成真值。
     *
     * <p>只在出口调：发模型请求（{@code ModelConfiguration}、{@code EmbeddingAutoConfiguration}、
     * {@code RagAutoConfiguration}）、起 MCP 子进程（{@code ProcessStdioMcpEndpoint}）、写 MCP
     * 的 http header（{@code HttpMcpEndpoint}）。别处的调用都在把真值往"能活很久的地方"搬。
     *
     * @param resolver 真值来源
     * @return 换好真值的字符串；没配时是空串
     * @throws MissingSecretException 句柄取不到真值。出口才发现缺失比启动期更糟，所以启动期会先
     *                                用 {@link #requireResolvable} 探一次
     */
    public String render(SecretResolver resolver) {
        Objects.requireNonNull(resolver, "resolver");
        if (absent) {
            return "";
        }
        if (literal || handles.isEmpty()) {
            return template;
        }
        Matcher ref = REF.matcher(template);
        StringBuilder out = new StringBuilder(template.length());
        while (ref.find()) {
            ref.appendReplacement(out, Matcher.quoteReplacement(resolver.require(ref.group(1), where)));
        }
        ref.appendTail(out);
        return out.toString();
    }

    /**
     * 启动期探一次：句柄都在，就什么都不做；缺一个就失败。
     *
     * <p>只问"在不在"，不留值——真值仍然每次出口现取。
     *
     * @param resolver 真值来源
     * @throws MissingSecretException 有一个句柄取不到真值
     */
    public void requireResolvable(SecretResolver resolver) {
        Objects.requireNonNull(resolver, "resolver");
        for (String name : handles) {
            resolver.require(name, where);
        }
    }

    /** 原文，不是真值。 */
    @Override
    public String toString() {
        return text;
    }
}

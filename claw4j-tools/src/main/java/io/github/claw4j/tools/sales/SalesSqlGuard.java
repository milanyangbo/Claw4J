package io.github.claw4j.tools.sales;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 只读约束的第一道：把模型生成的 SQL 挡在"能改写数据库"之外。
 *
 * <p>这是<b>纯字符串校验</b>，不含任何数据库连接，所以它能在没有 Docker 的地方跑，
 * 也能被单测逐条钉住。它的定位是"快速失败并给出模型看得懂的拒绝理由"，
 * 不是"唯一的防线"——见 {@link JdbcSalesQueryExecutor} 的第二道。
 *
 * <p>为什么字符串校验本身不够、必须有第二道：PostgreSQL 有可写 CTE，
 * {@code with x as (insert into ... returning *) select * from x} 是<b>一条</b>语句，
 * 而且以 {@code with} 开头。任何"首关键字是 select/with 就放行"的规则都会放过它。
 * 同理，注释里夹分号（{@code select 1 -- ; drop table t}）在字符串层面看是多语句，
 * 在数据库层面却是单语句——所以校验前要先去掉注释与字符串字面量，
 * 否则会把合法语句误拒（误拒的方向是拒绝，比误放行安全，但会让模型莫名其妙被拒）。
 *
 * <p>结论写在这里是因为它决定了这个类的形状：<b>只做校验、不做保证</b>。
 * 保证由数据库的只读事务给出。
 */
public final class SalesSqlGuard {

    /** 一次查询最多返回多少行给模型。超了就截断并明确告知，不静默丢行。 */
    public static final int MAX_ROWS = 50;

    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    private static final Pattern LINE_COMMENT = Pattern.compile("--[^\\n]*");

    /** 单引号字符串，'' 是转义后的单引号，不是字符串结束。 */
    private static final Pattern STRING_LITERAL = Pattern.compile("'(?:[^']|'')*'");

    /**
     * 会改写数据或会话状态的关键字。用 {@code \b} 定界，避免 {@code selected} 这种
     * 词被当成 {@code select}（那是首关键字判断的问题，这里只拦写操作）。
     *
     * <p>刻意收录了 {@code set} / {@code reset} / {@code lock} / {@code copy} ：
     * 它们不直接改业务数据，但能改会话或把数据搬出数据库（{@code copy ... to program}）。
     * 宁可误拒也不能漏——误拒的代价是模型换一种写法，漏的代价是数据没了。
     */
    private static final Pattern WRITE_KEYWORD = Pattern.compile(
            "\\b(insert|update|delete|truncate|merge|create|drop|alter|rename|grant|revoke|copy|call|do|set|reset"
                    + "|lock|comment|vacuum|reindex|refresh|cluster|discard|listen|notify|prepare|execute"
                    + "|begin|commit|rollback|savepoint|declare|import|load)\\b",
            Pattern.CASE_INSENSITIVE);

    /** 允许的首关键字。不含 {@code values} / {@code table}：它们没有业务查询的用途。 */
    private static final Pattern ALLOWED_LEADING = Pattern.compile("^(select|with)\\b", Pattern.CASE_INSENSITIVE);

    private SalesSqlGuard() {
    }

    /**
     * 判定这条语句能不能以只读方式执行。
     *
     * @return 拒绝理由；为空表示通过。返回理由而不是抛异常，是因为拒绝是<b>正常流程</b>——
     *         模型写出越权 SQL 是预期内的高频事件，理由要原样回给模型让它自纠错。
     */
    public static Optional<String> rejectionOf(String sql) {
        if (sql == null || sql.isBlank()) {
            return Optional.of("语句为空");
        }

        // 校验一律在"去掉注释与字面量"的副本上做：注释里的分号不该被算作语句分隔，
        // 字面量里的 'drop' 不该被当成写操作关键字。
        String cleaned = stripNoise(sql).trim();
        if (cleaned.isEmpty()) {
            return Optional.of("去掉注释与字符串字面量之后没有留下任何语句");
        }

        String body = stripTrailingSemicolon(cleaned);
        if (body.contains(";")) {
            return Optional.of("只允许一条语句：去掉注释与字符串字面量后仍出现多个分号");
        }

        // 先查写操作关键字再查首关键字：两种规则都会拦下 delete，
        // 而"你写了 delete"比"首关键字不是 select"对模型有用得多——
        // 它要改的是那一个词，不是整句的开头。
        Matcher write = WRITE_KEYWORD.matcher(body);
        if (write.find()) {
            return Optional.of("语句里出现了会改写数据或会话状态的关键字「"
                    + write.group().toLowerCase(Locale.ROOT) + "」");
        }

        if (!ALLOWED_LEADING.matcher(body).find()) {
            return Optional.of("只允许以 select 或 with 开头的查询语句，实际以「"
                    + firstWord(body) + "」开头");
        }
        return Optional.empty();
    }

    /**
     * 给语句套一层行上限。
     *
     * <p><b>为什么是包一层子查询，而不是在末尾拼 {@code limit}</b>：模型自带的
     * {@code order by ... limit 10} 后面再拼一个 limit 就是语法错误，
     * 而包一层对里面写了什么没有要求（PG 允许子查询里带 CTE 与 order by）。
     * 拼字符串改别人的 SQL 是这类功能最常见的翻车点。
     *
     * <p>多取一行是为了能区分"刚好 N 行"与"被截断了"——只取 N 行的话，
     * 这两种情况返回的行数一样，模型会以为那就是全部。
     */
    public static String withRowLimit(String sql, int maxRows) {
        String trimmed = sql == null ? "" : sql.trim();
        return "select * from (" + stripTrailingSemicolon(trimmed) + ") as claw4j_limited limit " + maxRows;
    }

    /**
     * 去掉注释与字符串字面量后的语句。只用于<b>校验</b>，绝不用于执行——
     * 执行的是原句，否则模型写的文本条件会被悄悄改掉。
     */
    static String stripNoise(String sql) {
        return STRING_LITERAL
                .matcher(LINE_COMMENT.matcher(BLOCK_COMMENT.matcher(sql).replaceAll(" ")).replaceAll(" "))
                .replaceAll("''");
    }

    private static String stripTrailingSemicolon(String sql) {
        String trimmed = sql.trim();
        return trimmed.endsWith(";") ? trimmed.substring(0, trimmed.length() - 1).trim() : trimmed;
    }

    private static String firstWord(String body) {
        int end = 0;
        while (end < body.length() && !Character.isWhitespace(body.charAt(end)) && body.charAt(end) != '(') {
            end++;
        }
        return end == 0 ? "（空）" : body.substring(0, end);
    }
}

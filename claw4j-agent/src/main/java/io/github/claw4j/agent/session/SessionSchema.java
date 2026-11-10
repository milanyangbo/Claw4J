package io.github.claw4j.agent.session;

import java.util.Objects;

/**
 * 把"会话表落在哪个 schema"压成一个 URL 参数。
 *
 * <p>为什么是 URL 参数：{@code spring-ai-session} 的建表与查询 SQL 里，表名不带 schema 前缀
 * （{@code AI_SESSION} / {@code AI_SESSION_EVENT}），表落到哪完全由连接的 {@code search_path}
 * 决定。能改 search_path 的地方有三个：数据库角色默认值、连接上的 {@code SET}、
 * JDBC URL 的 {@code currentSchema}。选 URL，是因为它跟着连接池走：改一次配置就能看见，
 * 不会像角色默认值那样"在别处被改过所以这里生效了"，也不会像 {@code SET} 那样被连接池复用冲掉。
 *
 * <p>为什么是独立连接池而不是改主连接的 search_path：主连接上跑的是业务查询，它靠全限定名
 * （{@code business.sales_order}），改 search_path 不影响那些，但会影响所有没写全限定名的语句
 * ——包括 Flyway 的迁移历史表落在哪、将来 pgvector 的表落在哪。
 */
public final class SessionSchema {

    /** 会话 schema 名，与业务数据的 {@code business} 分开（ADR-0005）。 */
    public static final String DEFAULT_SCHEMA = "claw4j_session";

    private SessionSchema() {
    }

    /**
     * 在 JDBC URL 上追加 {@code currentSchema} 参数。
     *
     * @param url    主库的 JDBC URL
     * @param schema 会话 schema 名
     * @return 带 {@code currentSchema} 的 URL
     * @throws IllegalArgumentException schema 为空或带空白时。拼进 URL 的空参数不会报错，
     *                                  它只是让会话表悄悄落到 public——那正是这段代码要防的事
     */
    public static String urlWithSchema(String url, String schema) {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(schema, "schema");
        String trimmed = schema.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("会话 schema 不能为空：空了会话表会落到 public，"
                    + "与 ADR-0005 的“业务与会话不同 schema”相违背");
        }
        if (trimmed.contains(" ") || trimmed.contains("?")) {
            throw new IllegalArgumentException("会话 schema 含空白或 ? ：" + schema);
        }
        return url + (url.contains("?") ? "&" : "?") + "currentSchema=" + trimmed;
    }

    /**
     * 把 schema 名变成可以拼进 SQL 的前缀（{@code claw4j_session.}），并在这里挡掉非法字符。
     *
     * <p>需要这一道，是因为本仓库自己的 SQL 写全限定表名（不靠连接的 {@code search_path}，
     * 见 {@link JdbcCrossSessionEvents}），而 schema 名来自配置。SQL 里标识符的位置没法用占位符，
     * 只能拼字符串——那就必须在这里保证它真的是一个标识符，而不是一串能改写语句的东西。
     *
     * <p>口径比 {@link #urlWithSchema} 严：那边只挡空白与 {@code ?}（URL 里够用），这边按
     * Postgres 的未加引号标识符收（首字母或下划线开头，其余字母数字下划线美元符，63 字节内）。
     *
     * @param schema 会话 schema 名
     * @return 带结尾点的 schema 前缀
     * @throws IllegalArgumentException 不是合法标识符时。拼错了会让每条 SQL 都报
     *                                  {@code BadSqlGrammarException}，而那条错误在跨会话那一档
     *                                  会被降级成"没找到"
     */
    public static String qualifier(String schema) {
        Objects.requireNonNull(schema, "schema");
        String trimmed = schema.trim();
        if (!trimmed.matches("[A-Za-z_][A-Za-z0-9_$]{0,62}")) {
            throw new IllegalArgumentException(
                    "claw4j.session.schema 必须是合法的 Postgres 标识符（字母或下划线开头，"
                            + "其余字母数字下划线美元符，最长 63），收到的是「" + schema + "」");
        }
        return trimmed + '.';
    }
}

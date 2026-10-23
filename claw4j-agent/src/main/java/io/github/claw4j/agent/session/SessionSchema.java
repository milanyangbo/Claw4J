package io.github.claw4j.agent.session;

import java.util.Objects;

/**
 * 把"会话表落在哪个 schema"这件事压成一个 URL 参数。
 *
 * <p><b>为什么是 URL 参数</b>：{@code spring-ai-session} 的建表与查询 SQL 里，
 * 表名是不带 schema 前缀的（{@code AI_SESSION} / {@code AI_SESSION_EVENT}），
 * 表落到哪完全由连接的 {@code search_path} 决定。能改 search_path 的地方有三个：
 * 数据库角色默认值、连接上的 {@code SET}、JDBC URL 的 {@code currentSchema}。
 * 选 URL，是因为它跟着连接池走：改一次配置就能看见，不会像角色默认值那样
 * "在别处被改过所以这里生效了"，也不会像 {@code SET} 那样被连接池复用给冲掉。
 *
 * <p><b>为什么是独立连接池而不是改主连接的 search_path</b>：主连接上跑的是业务查询，
 * 它靠的是全限定名（{@code business.sales_order}），改它的 search_path 不影响那些查询，
 * 但会影响所有**没写全限定名**的语句——包括 Flyway 的迁移历史表落在哪、
 * 将来 pgvector 的表落在哪。为一个存储改掉另一个存储的默认落点，是那种
 * "改的时候全绿、半年后找不出原因"的耦合。
 */
public final class SessionSchema {

    /** 会话 schema 名，与业务数据的 {@code business} 分开（ADR-0005）。 */
    public static final String DEFAULT_SCHEMA = "claw4j_session";

    private SessionSchema() {
    }

    /**
     * 在 JDBC URL 上追加 {@code currentSchema} 参数。
     *
     * @throws IllegalArgumentException schema 为空或带空白时。拼进 URL 的空参数不会报错，
     *                                  它只是让会话表悄悄落到 public——那正是这条代码要防的事。
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
}

package io.github.claw4j.agent.session;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 会话存储的两个可调项。刻意只留两个：会话存储没有"要不要用"这个选项（ADR-0005），
 * 也没有"换实现"这个选项（{@code InMemorySessionRepository} 被架构测试挡住），
 * 所以这里放的是部署相关的东西，不是行为开关。
 *
 * @param schema    会话表所在的 schema，默认 {@value SessionSchema#DEFAULT_SCHEMA}
 * @param poolSize  会话连接池大小。默认是 2：会话读写是"每轮一写一读"的小事务，
 *                  不是业务查询那种可能跑满 5 秒的重活，给多了只是占连接
 */
@ConfigurationProperties("claw4j.session")
public record SessionMemoryProperties(String schema, int poolSize) {

    /** 连接池的默认值。 */
    public static final int DEFAULT_POOL_SIZE = 2;

    public SessionMemoryProperties {
        schema = (schema == null || schema.isBlank()) ? SessionSchema.DEFAULT_SCHEMA : schema.trim();
        poolSize = poolSize <= 0 ? DEFAULT_POOL_SIZE : poolSize;
    }

    public static SessionMemoryProperties defaults() {
        return new SessionMemoryProperties(null, 0);
    }
}

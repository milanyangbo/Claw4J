package io.github.claw4j.agent.session;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 旧会话怎么清（ADR-0026 的收尾）。键在 {@code claw4j.session.maintenance} 下。
 *
 * <p><b>为什么不是"TTL"</b>：框架那个 {@code spring.ai.session.time-to-live} 在<b>建行</b>时
 * 写死一个 {@code expires_at}，之后不随活动刷新。于是"每天都在用"与"三天前用过一次"
 * 在库里长得一样，到期一起删。判断"该不该清"的正确依据只有一条：<b>多久没动</b>。
 * 这正是 OpenClaw 的 {@code session.maintenance.pruneAfter} 判 {@code lastInteractionAt}
 * 的做法，三个键的名字与默认值都照它。
 *
 * <p><b>{@code mode} 默认 {@code warn}</b>，与它一致：只算不删，把"本来会删多少"打进日志与指标。
 * 这是一个刻意的保守默认——删数据不可逆，而 OpenClaw 在单机形态下也认为它需要人确认。
 * 代价是本项目"到期必须删"那条留存承诺<b>默认没有落实</b>，要靠部署时显式设 {@code enforce}
 * （记在 {@code docs/agents/guardrails.md}）。
 *
 * <p><b>{@code mode} 认不出时落回 {@code warn}，不是 {@code enforce}</b>：拼错一个词的后果
 * 必须朝"少删"那边倒。这与 {@link SessionResetProperties} 的"认不出就按默认换段"不同——
 * 那边错的方向是"记忆不换、越来越长"，可恢复；这边错的方向是"数据被删"，不可恢复。
 *
 * <p><b>另外两个数越界直接拒绝启动</b>：{@code prune-after: 0} 是"所有段都算过期"，
 * {@code max-entries: 0} 是"一个都不留"——两种配置都会在第一次清理时把库清空，
 * 而它们看起来只是"把数调小一点"。
 *
 * @param mode         {@value #MODE_WARN}（默认，只算不删）/ {@value #MODE_ENFORCE}（真删）
 * @param pruneAfter   多久没动算陈旧。默认 30 天，与 OpenClaw 的 {@code pruneAfter} 同值
 * @param maxEntries   指针行数上限。默认 500，与 OpenClaw 的 {@code maxEntries} 同值；
 *                     <b>它是单机个人助手的量级</b>，多租户部署要按"人数 × 通道数"调大
 */
@ConfigurationProperties("claw4j.session.maintenance")
public record SessionMaintenanceProperties(String mode, Duration pruneAfter, Integer maxEntries) {

    /** 只算不删，把将要删的数量打进日志与指标。默认。 */
    public static final String MODE_WARN = "warn";

    /** 真删。 */
    public static final String MODE_ENFORCE = "enforce";

    public static final Duration DEFAULT_PRUNE_AFTER = Duration.ofDays(30);

    public static final int DEFAULT_MAX_ENTRIES = 500;

    public SessionMaintenanceProperties {
        mode = MODE_ENFORCE.equalsIgnoreCase(mode == null ? "" : mode.trim()) ? MODE_ENFORCE : MODE_WARN;
        if (pruneAfter == null) {
            pruneAfter = DEFAULT_PRUNE_AFTER;
        } else if (pruneAfter.isNegative() || pruneAfter.isZero()) {
            throw new IllegalArgumentException(
                    "claw4j.session.maintenance.prune-after 必须为正：" + pruneAfter
                            + "。0 的意思是“所有段都过期”，第一次清理就会把会话表清空");
        }
        maxEntries = maxEntries == null ? DEFAULT_MAX_ENTRIES : maxEntries;
        if (maxEntries <= 0) {
            throw new IllegalArgumentException(
                    "claw4j.session.maintenance.max-entries 必须为正：" + maxEntries
                            + "。0 的意思是“一个会话都不留”");
        }
    }

    public static SessionMaintenanceProperties defaults() {
        return new SessionMaintenanceProperties(null, null, null);
    }

    /** 要不要真删。 */
    public boolean enforces() {
        return MODE_ENFORCE.equals(mode);
    }
}

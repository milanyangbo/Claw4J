package io.github.claw4j.agent.session;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Locale;

/**
 * 旧会话怎么清（ADR-0026 的收尾）。键在 {@code claw4j.session.maintenance} 下。
 *
 * <p>不用 TTL：框架的 {@code spring.ai.session.time-to-live} 在建行时写死 {@code expires_at}，
 * 之后不随活动刷新，「每天都在用」与「三天前用过一次」到期一起删。判据只有一条：多久没动。
 * 这对应 OpenClaw 的 {@code session.maintenance.pruneAfter} 判 {@code lastInteractionAt}
 * （{@code docs/gateway/config-agents/sessions.md:129}）。
 *
 * <p>{@code mode} 默认 {@code enforce}，与它同值（同文件 {@code :129}）：到期就删。
 * 想要「只算不删」的部署显式配 {@code warn}——那时「到期必须删」的留存承诺没有落实，
 * 只把「本来会删多少」打进日志与指标。
 *
 * <p>认不出的值直接拒绝启动，不落回任何一档。落回 {@code warn} 会让一个拼错的配置看起来生效了，
 * 实际一条都不删；落回 {@code enforce} 则是拼错反而开始删数据。两种都比启动失败更难查。
 *
 * <p>{@code prune-after: 0} 与 {@code max-entries: 0} 同样拒绝启动：前者是「所有段都过期」，
 * 后者是「一个都不留」，第一次清理就会把库清空，而它们看起来只是「把数调小一点」。
 *
 * @param mode         {@value #MODE_ENFORCE}（默认，到期真删）/ {@value #MODE_WARN}（只算不删）
 * @param pruneAfter   多久没动算陈旧。默认 30 天，与 OpenClaw 的 {@code pruneAfter} 同值
 * @param maxEntries   会话行数上限。默认 5000，与 OpenClaw 同值（{@code sessions.md:131}）；
 *                     单机个人助手用不到这么多，多租户按「人数 × 通道数」估
 */
@ConfigurationProperties("claw4j.session.maintenance")
public record SessionMaintenanceProperties(String mode, Duration pruneAfter, Integer maxEntries) {

    /** 只算不删，把将要删的数量打进日志与指标。 */
    public static final String MODE_WARN = "warn";

    /** 真删。默认。 */
    public static final String MODE_ENFORCE = "enforce";

    public static final Duration DEFAULT_PRUNE_AFTER = Duration.ofDays(30);

    public static final int DEFAULT_MAX_ENTRIES = 5000;

    public SessionMaintenanceProperties {
        mode = parseMode(mode);
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

    private static String parseMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return MODE_ENFORCE;
        }
        String trimmed = mode.trim().toLowerCase(Locale.ROOT);
        if (MODE_ENFORCE.equals(trimmed) || MODE_WARN.equals(trimmed)) {
            return trimmed;
        }
        throw new IllegalArgumentException(
                "claw4j.session.maintenance.mode 只能是 " + MODE_ENFORCE + " 或 " + MODE_WARN
                        + "：" + mode + "。拼错不能落回任何一档——落回 warn 是一条都不删，"
                        + "落回 enforce 是拼错反而开始删数据");
    }
}

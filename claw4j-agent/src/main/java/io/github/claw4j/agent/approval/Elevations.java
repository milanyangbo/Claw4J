package io.github.claw4j.agent.approval;

import io.github.claw4j.domain.principal.Elevation;
import io.github.claw4j.domain.principal.Principal;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 这一条对话现在的提权档。
 *
 * <p>为什么是内存而不是一张表：它是会话的临时状态，重启即失效。落库会让"上次提权到现在过了三天"
 * 变成一条要继续维护的记录，而那正是 {@code ttl} 要避免的事。JVM 重启之后回到 {@code off}
 * 也是对的——重启本来就要重新决定这类事。
 *
 * <p>键用会话键（{@code conversationKey}，不含段）：同一把键会被下一次问话复用，因为它是
 * "这个人在这段对话里说过的话"，不是"那一次的元数据"。反过来（键里带一次性成分）的表现是
 * "刚 /elevated full 完，换个话题再问它又开始问了"，而用户不会把这两件事联系起来。
 */
public final class Elevations {

    private final ElevatedProperties properties;

    private final Clock clock;

    private final Map<String, Entry> levels = new ConcurrentHashMap<>();

    /**
     * 用配置与时钟建。
     *
     * @param properties 提权配置
     * @param clock      时钟；用例用它把"过期"变成可断言的
     */
    public Elevations(ElevatedProperties properties, Clock clock) {
        this.properties = properties == null ? ElevatedProperties.defaults() : properties;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /**
     * 这一段现在是什么档。
     *
     * @param conversationKey 会话目标键，可以为 {@code null}
     * @return 提权档；没设过或已过期时为 {@link Elevation#OFF}
     */
    public Elevation level(String conversationKey) {
        if (conversationKey == null || conversationKey.isBlank()) {
            return Elevation.OFF;
        }
        Entry entry = levels.get(conversationKey);
        if (entry == null) {
            return Elevation.OFF;
        }
        if (entry.expiresAt().isBefore(clock.instant())) {
            levels.remove(conversationKey, entry);
            return Elevation.OFF;
        }
        return entry.level();
    }

    /**
     * 改这一段的档。
     *
     * @param conversationKey 会话目标键
     * @param level           新档位
     * @return 生效到什么时候；{@link Elevation#OFF} 时为 {@code null}（没有"有效期"这回事）
     */
    public Instant set(String conversationKey, Elevation level) {
        if (conversationKey == null || conversationKey.isBlank()) {
            return null;
        }
        if (level == null || level == Elevation.OFF) {
            levels.remove(conversationKey);
            return null;
        }
        Instant until = clock.instant().plus(properties.ttl());
        levels.put(conversationKey, new Entry(level, until));
        return until;
    }

    /**
     * 这个人能不能改这一段。
     *
     * @param principal 发起人，可以为 {@code null}
     * @return 允许为 {@code true}
     */
    public boolean allows(Principal principal) {
        return principal != null && properties.allows(principal.userId());
    }

    /**
     * 这一族命令开没开（不管是谁）。
     *
     * @return 开着为 {@code true}
     */
    public boolean enabled() {
        return properties.enabled();
    }

    private record Entry(Elevation level, Instant expiresAt) {
    }
}

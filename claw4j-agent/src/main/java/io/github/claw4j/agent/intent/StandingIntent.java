package io.github.claw4j.agent.intent;

import java.time.Instant;
import java.util.Locale;

/**
 * 一条事件条件意图（ADR-0036）。字段与它的 {@code standing_intents} 一一对应。
 *
 * @param id              对外标识，模型报编号用它
 * @param owner           归属人；它每个 agent 一个库，我们一个库多租户，只能显式写
 * @param description     命中时提醒什么
 * @param triggerKeywords 逗号或分号分段的触发词
 * @param channelScope    作用频道；null 表示任意
 * @param senderScope     作用发送者；null 表示任意
 * @param creatorSender   谁建的
 * @param status          六态
 * @param expiresAt       到期时刻
 * @param maxFires        最多触发几次
 * @param fireCount       已经触发几次
 * @param cooldownSeconds 两次触发之间至少隔多久
 * @param lastFiredAt     上一次触发的时刻
 * @param createdAt       创建时刻
 * @param sourceSessionId 出处；只是记录，不参与作用域判定
 */
public record StandingIntent(String id, String owner, String description, String triggerKeywords,
                             String channelScope, String senderScope, String creatorSender,
                             Status status, Instant expiresAt, int maxFires, int fireCount,
                             int cooldownSeconds, Instant lastFiredAt, Instant createdAt,
                             String sourceSessionId) {

    /** 六态，与它的词表一致。 */
    public enum Status {

        /** 存下来了但还没武装。 */
        PENDING,
        /** 可以参与匹配。 */
        ARMED,
        /** 刚触发，等冷却。 */
        FIRED,
        /** 次数用完了。 */
        DONE,
        /** 被显式取消。 */
        CANCELLED,
        /** 过期。 */
        EXPIRED;

        /**
         * @return 库里存的小写形式
         */
        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        /**
         * @param raw 库里的值
         * @return 对应状态
         */
        public static Status of(String raw) {
            return Status.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        }
    }

    /**
     * @param now 现在
     * @return 是否已过期
     */
    public boolean expiredAt(Instant now) {
        return !expiresAt.isAfter(now);
    }

    /**
     * @param now 现在
     * @return 是否还在冷却里
     */
    public boolean coolingDown(Instant now) {
        return lastFiredAt != null && lastFiredAt.plusSeconds(cooldownSeconds).isAfter(now);
    }
}

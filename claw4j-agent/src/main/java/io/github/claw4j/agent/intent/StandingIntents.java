package io.github.claw4j.agent.intent;

import java.time.Instant;
import java.util.List;

/**
 * 事件条件意图的存取（ADR-0036）。
 *
 * <p>只有五个动作：建、列、撤、取可匹配的、记账触发。第五个是原子的——"命中"与"计数 +1"
 * 必须一起成功，否则并发两条消息会把同一条意图各触发一次，而用户的感受是"它说了两遍"。
 */
public interface StandingIntents {

    /** 默认冷却：24 小时（与它一致）。 */
    int DEFAULT_COOLDOWN_SECONDS = 86_400;

    /** 默认最多触发 3 次。 */
    int DEFAULT_MAX_FIRES = 3;

    /** 默认 90 天过期。 */
    int DEFAULT_EXPIRES_DAYS = 90;

    /**
     * 建一条，直接进 {@code armed}。
     *
     * <p>不经过 {@code pending}：它是给"批量导入、等人确认"留的态，本票没有那条路径。
     *
     * @param spec 新建参数
     * @return 落库后的样子（带 id）
     */
    StandingIntent create(New spec);

    /**
     * 列出这个人的意图。
     *
     * @param owner 归属人
     * @return 全部，按创建时间倒序
     */
    List<StandingIntent> list(String owner);

    /**
     * 显式取消。
     *
     * @param owner 归属人
     * @param id    意图编号
     * @return 是否改动了行
     */
    boolean cancel(String owner, String id);

    /**
     * 这一轮可以参与匹配的候选。
     *
     * @param owner   归属人
     * @param channel 这一轮的频道，可为 null
     * @param sender  这一轮的发送者，可为 null
     * @param now     现在
     * @param limit   上限（它的口径是 256）
     * @return 候选，按创建时间升序
     */
    List<StandingIntent> eligible(String owner, String channel, String sender, Instant now, int limit);

    /**
     * 记一次触发：复查作用域之外的一切（状态、过期、冷却、预算）后 {@code fire_count + 1}。
     *
     * @param id  意图编号
     * @param now 现在
     * @return 真的记上了才 true；冷却中、用完了、过期了都是 false
     */
    boolean fire(String id, Instant now);

    /**
     * 新建参数。
     *
     * @param owner           归属人
     * @param description     命中时提醒什么
     * @param triggerKeywords 触发词
     * @param channelScope    作用频道，null 表示任意
     * @param senderScope     作用发送者，null 表示任意
     * @param creatorSender   谁建的
     * @param expiresAt       到期时刻
     * @param maxFires        最多触发几次
     * @param cooldownSeconds 冷却秒数
     * @param sourceSessionId 出处
     */
    record New(String owner, String description, String triggerKeywords, String channelScope,
               String senderScope, String creatorSender, Instant expiresAt, int maxFires,
               int cooldownSeconds, String sourceSessionId) {
    }
}

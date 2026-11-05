package io.github.claw4j.agent.session;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Instant;
import java.time.ZoneId;

/**
 * 一段对话什么时候结束（ADR-0026 决定一）。键在 {@code claw4j.session.reset} 下。
 *
 * <p><b>为什么这条规则要单独存在</b>：在它之前，"会话什么时候结束"有四个答案，各有各的
 * 使用者——记忆认为永不结束、预算按 120 分钟空闲自己判、存储按 30 天删行、定时任务借用
 * 创建者那一份。四个答案各自都说得通，合起来就是"会话"这个词没有定义。
 * 段（{@link ConversationPointers}）是这条规则唯一的执行点。
 *
 * <p><b>不判"用户这件事做完了"</b>：判不了，判错的代价是把人卡住。行业里能判的只有时间：
 * OpenClaw 的会话只有 {@code idleMinutes}（默认 120）、每日重置（{@code atHour}）与
 * 手动 {@code /new} 三种结束方式，没有"任务完成"这一种。这里照抄它的形状。
 *
 * <p><b>三种模式</b>：
 * <ul>
 *   <li>{@code daily-and-idle}（默认）：跨了逻辑日，或者空闲超过阈值，谁先到算谁；
 *   <li>{@code idle}：只看空闲。适合"一段对话跟一个项目走"但在同一工作日内反复拾起的用法；
 *   <li>{@code none}：永不结束。此时 {@code sessionId} 退回加段之前的形状，
 *       而且<b>一次库都不查</b>——不做这件事就别付它的代价。
 * </ul>
 *
 * <p><b>认不出的值按默认处理</b>，与 {@link io.github.claw4j.agent.run.RunBudgetProperties}
 * 里"0 与没配落到同一处"是同一个判断：拼错一个词不该让服务起不来，但也不该悄悄变成
 * "永不结束"——那会让段悄悄失效，而失效的表现是"记忆越来越长、预算永远不清零"，
 * 没有人会把它和配置里的一个拼写错误联系起来。
 *
 * @param mode        见上，缺省 {@link #MODE_DAILY_AND_IDLE}
 * @param atHour      逻辑日的分界点（0~23）。刻意是 {@code Integer} 而不是 {@code int}：
 *                    配置缺失时 {@code int} 会拿到 0，而 0 是一个合法值（每天零点换段），
 *                    于是"没配"与"配了 0 点"就无法区分，默认值 4 永远用不上。
 * @param idleMinutes 多久没动静算结束。OpenClaw 的 {@code idleMinutes} 默认也是 120。
 */
@ConfigurationProperties("claw4j.session.reset")
public record SessionResetProperties(String mode, Integer atHour, long idleMinutes) {

    /** 跨天或空闲，谁先到算谁。 */
    public static final String MODE_DAILY_AND_IDLE = "daily-and-idle";

    /** 只看空闲。 */
    public static final String MODE_IDLE = "idle";

    /** 永不结束：不查库、不加段。 */
    public static final String MODE_NONE = "none";

    public static final int DEFAULT_AT_HOUR = 4;

    public static final long DEFAULT_IDLE_MINUTES = 120L;

    public SessionResetProperties {
        // 空与认不出都落到默认：见类注释最后一段，宁可"照常换段"也不要"静默不换"。
        mode = mode == null || mode.isBlank() ? MODE_DAILY_AND_IDLE : mode.trim();
        if (!MODE_IDLE.equals(mode) && !MODE_NONE.equals(mode)) {
            mode = MODE_DAILY_AND_IDLE;
        }
        // 越界的钟点同样归到默认：25 点不是一个可以解释的"每天一次"。
        atHour = atHour == null || atHour < 0 || atHour > 23 ? DEFAULT_AT_HOUR : atHour;
        idleMinutes = idleMinutes <= 0 ? DEFAULT_IDLE_MINUTES : idleMinutes;
    }

    /** 全部走默认值。 */
    public static SessionResetProperties defaults() {
        return new SessionResetProperties(null, null, 0L);
    }

    /** 要不要换段。{@code none} 时连库都不碰。 */
    public boolean rotates() {
        return !MODE_NONE.equals(mode);
    }

    /**
     * 日历这一段参不参与判定。
     *
     * <p>{@code idle} 模式下不算日子：用户说"这段对话跟一个项目走，隔天接着聊"，
     * 那就该由他隔了多久决定，而不是由日历决定。
     */
    public boolean followsDaily() {
        return MODE_DAILY_AND_IDLE.equals(mode);
    }

    /** 空闲窗口，毫秒。 */
    public long idleMillis() {
        return idleMinutes * 60_000L;
    }

    /**
     * 这一刻属于哪一个"逻辑日"。
     *
     * <p><b>不是 {@code LocalDate.now()}</b>：那用的是容器时区，而容器里通常是 UTC。
     * 差八小时的后果是东八区凌晨 0~8 点的行为全错，而 CI（跑在 UTC 上）是绿的——
     * 一个只在生产夜间出现的错，白天复现不了。
     *
     * <p><b>也不是"过了零点就换"</b>：{@code at-hour} 是给人留的缓冲。4 点换日的意思
     * 是"凌晨三点还在聊的那件事，还是昨天那件"。所以先按业务时区取时刻，再减掉
     * {@code atHour} 小时，取日期——减完之后落在零点之前的，自然算前一天。
     */
    public java.time.LocalDate logicalDayOf(Instant now, ZoneId zone) {
        return now.atZone(zone).minusHours(atHour).toLocalDate();
    }
}

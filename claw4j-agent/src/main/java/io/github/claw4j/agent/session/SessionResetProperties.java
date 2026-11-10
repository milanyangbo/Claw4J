package io.github.claw4j.agent.session;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Instant;
import java.time.ZoneId;

/**
 * 一段对话什么时候结束（ADR-0026 决定一）。键在 {@code claw4j.session.reset} 下。
 *
 * <p>这条规则要单独存在，是因为在此之前"会话什么时候结束"有四个答案，各有各的使用者——
 * 记忆认为永不结束、预算按 120 分钟空闲自己判、存储按 30 天删行、定时任务借用创建者那一份。
 * 四个答案各自都说得通，合起来就是"会话"这个词没有定义。{@link SessionEntries} 是这条规则的
 * 唯一执行点。
 *
 * <p>不判"用户这件事做完了"：判不了，判错的代价是把人卡住。能判的只有时间——OpenClaw 的会话
 * 也只有空闲重置（{@code idleMinutes}）、每日重置（{@code atHour}）与手动 {@code /new} 三种
 * 结束方式（{@code docs/concepts/session.md:267-283}），没有"任务完成"这一种。
 *
 * <p>三种模式：
 * <ul>
 *   <li>{@code none}（默认）：不自动结束。靠上下文压缩管长度，与它同默认（ADR-0029）；
 *   <li>{@code daily-and-idle}：跨了逻辑日，或者空闲超过阈值，谁先到算谁；
 *   <li>{@code idle}：只看空闲。适合"一段对话跟一个项目走"但在同一工作日内反复拾起的用法。
 * </ul>
 *
 * <p>三种模式下 {@code /new} 都能用：那一档配的是"不自动结束"，不是"不许结束"。
 *
 * <p>认不出的值**启动失败**：这一档的两侧是"合并上下文"与"分开上下文"，拼错之后悄悄按某一侧
 * 跑，现象是"记忆比预期长"或"记忆比预期短"，两种都不会有人去查一个拼写。与
 * {@code session.maintenance.mode} 同一个处理方式。
 *
 * @param mode        见上，缺省 {@link #MODE_NONE}
 * @param atHour      逻辑日的分界点（0~23）。是 {@code Integer} 而不是 {@code int}：配置缺失时
 *                    {@code int} 会拿到 0，而 0 是合法值（每天零点换段），于是"没配"与
 *                    "配了 0 点"无法区分，默认值 4 永远用不上
 * @param idleMinutes 多久没动静算结束
 */
@ConfigurationProperties("claw4j.session.reset")
public record SessionResetProperties(String mode, Integer atHour, long idleMinutes) {

    /** 跨天或空闲，谁先到算谁。 */
    public static final String MODE_DAILY_AND_IDLE = "daily-and-idle";

    /** 只看空闲。 */
    public static final String MODE_IDLE = "idle";

    /** 不自动结束；{@code /new} 仍然有效。默认。 */
    public static final String MODE_NONE = "none";

    public static final int DEFAULT_AT_HOUR = 4;

    public static final long DEFAULT_IDLE_MINUTES = 120L;

    public SessionResetProperties {
        // 缺省是"不自动结束"：与 OpenClaw 同默认。显式配了别的值就按它来，认不出就起不来。
        mode = mode == null || mode.isBlank() ? MODE_NONE : mode.trim();
        if (!MODE_IDLE.equals(mode) && !MODE_NONE.equals(mode) && !MODE_DAILY_AND_IDLE.equals(mode)) {
            throw new IllegalArgumentException("claw4j.session.reset.mode 只认 "
                    + MODE_NONE + " / " + MODE_DAILY_AND_IDLE + " / " + MODE_IDLE
                    + "，收到的是：" + mode);
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
     *
     * @return {@code daily-and-idle} 时为 true
     */
    public boolean followsDaily() {
        return MODE_DAILY_AND_IDLE.equals(mode);
    }

    /** 空闲窗口，毫秒。 */
    public long idleMillis() {
        return idleMinutes * 60_000L;
    }

    /**
     * 最近一个 at-hour 边界，返回时刻而不是日期（票 06）。
     *
     * <p>存结论的话（返回 {@code LocalDate}，判定写 {@code day_started <> today}），改配置之后
     * 旧行不会重算：今天 05:00 开始的段，{@code at-hour} 从 4 改成 9 之后它仍判新鲜，
     * 而边界已经过去了。存时刻的话，边界每次读都重算。
     *
     * <p>不用 {@code LocalDate.now()}：那用的是容器时区，而容器里通常是 UTC。差八小时的后果是
     * 东八区凌晨 0~8 点的行为全错，而 CI（跑在 UTC 上）是绿的——一个只在生产夜间出现的错。
     *
     * <p>也不是"过了零点就换"：{@code at-hour} 是给人留的缓冲。4 点换日的意思是"凌晨三点还在
     * 聊的那件事，还是昨天那件"。
     *
     * @param now  当前时刻
     * @param zone 业务时区，决定 at-hour 落在哪一刻
     * @return 最近一个 at-hour 边界对应的时刻
     */
    public java.time.Instant dailyResetAt(Instant now, ZoneId zone) {
        java.time.ZonedDateTime shifted = now.atZone(zone).minusHours(atHour);
        return shifted.toLocalDate().atStartOfDay(zone).plusHours(atHour).toInstant();
    }
}

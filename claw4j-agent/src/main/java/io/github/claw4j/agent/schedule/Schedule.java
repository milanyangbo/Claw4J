package io.github.claw4j.agent.schedule;

import io.github.claw4j.domain.NotifyPolicy;

import java.time.Instant;

/**
 * 一条定时任务现在的样子（P04）：谁建的、跑到哪儿、还启不启用、上一次为什么没跑成。
 *
 * <p><b>它不含"下一次跑什么"</b>：那由 {@code cron} 与 {@code zone} 决定，
 * 而 {@code nextRunAt} 只是"已经算好的那一个点"。两者都留着——
 * 只留 {@code nextRunAt} 的话，改 cron 之后没人知道旧的点是从哪来的；
 * 只留 cron 的话，每一轮扫描都要重新解析一遍所有任务。
 *
 * @param owner          归属键，与 run 的口径一致
 * @param channel        结果送回哪个通道；null 表示这个任务不推结果
 * @param channelRef     通道内的收件地址
 * @param goal           每次到点要问的那句话
 * @param cron           6 段 cron 原文
 * @param zone           时区；null 表示部署所在时区
 * @param notifyPolicy   通知策略。定时任务默认 {@link NotifyPolicy#SILENT}：
 *                       到点自己跑一趟的事每趟推一条，等于把自动化做成骚扰
 * @param lastFiredAt    上一次真的跑的时刻；从没跑过是 null
 * @param failures       连续失败次数（成功一次清零）
 * @param lastError      上一次失败的原因；成功之后是 null
 * @param disabledReason 停用原因。手动暂停时为空——"为什么没有下一次"有两种答案，
 *                       混成一句"已停用"的话，接到通知的人判断不了要不要处理
 */
public record Schedule(String id,
                       String owner,
                       String channel,
                       String channelRef,
                       String goal,
                       String cron,
                       String zone,
                       NotifyPolicy notifyPolicy,
                       Instant nextRunAt,
                       Instant lastFiredAt,
                       int failures,
                       String lastError,
                       boolean enabled,
                       String disabledReason) {
}

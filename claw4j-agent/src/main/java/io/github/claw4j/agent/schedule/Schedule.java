package io.github.claw4j.agent.schedule;

import io.github.claw4j.domain.NotifyPolicy;

import java.time.Instant;

/**
 * 一条定时任务的当前状态：谁建的、跑到哪儿、还启不启用、上一次为什么没跑成（P04）。
 *
 * <p>{@code cron} 与 {@code nextRunAt} 同时保留。只留 {@code nextRunAt}，改过 cron 之后
 * 就说不清这个时间点是怎么来的；只留 cron，每轮扫描都要把所有任务的表达式重新解析一遍。
 *
 * @param owner 归属键，与 run 的口径一致
 * @param channel 结果推回的通道；null 表示这个任务不推结果
 * @param channelRef 通道内的收件地址
 * @param goal 每次到点要问的那句话
 * @param cron 6 段 cron 原文
 * @param zone 时区；null 表示部署所在时区
 * @param notifyPolicy 通知策略。定时任务默认 {@link NotifyPolicy#SILENT}：无人值守的运行
 *     每趟推一条，只会把通知变成噪声
 * @param nextRunAt 预先算好的下一次触发时刻
 * @param lastFiredAt 上一次真正跑过的时刻；从未跑过为 null
 * @param failures 连续失败次数，成功一次清零
 * @param lastError 上一次失败的原因；成功之后为 null
 * @param disabledReason 停用原因。手动暂停时为空：停用有两种来源，合成一句「已停用」之后，
 *     收到通知的人判断不了要不要处理
 * @param enabled 是否启用
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

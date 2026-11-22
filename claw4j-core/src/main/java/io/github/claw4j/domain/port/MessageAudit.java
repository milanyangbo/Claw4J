package io.github.claw4j.domain.port;

import io.github.claw4j.domain.audit.MessageOutcome;

/**
 * 消息审计的落点（ADR-0037 票 04）。
 *
 * <p>为什么端口在 domain：入站派发器在 {@code claw4j.channel.kernel}（只依赖 core），而审计实现
 * 需要库、假名密钥与词表，那些在 agent。端口放 agent 的话，派发器那一侧就写不了那一行——
 * 而"重复投递被跳过"恰好只有它知道。
 *
 * <p>形状是<b>终态一条</b>：进来一条、出去一条，各自带一个结果。不做 queued / platform-started 那种
 * 进度行——OpenClaw 把它们放在一个惰性伴生存储里，不进 {@code audit.activity.list}
 * （{@code docs/gateway/protocol/ledgers.md:132-136}）。
 *
 * <p>失败姿势：实现必须吞掉自己的异常。审计缺一行不该让一条消息处理失败——那会让"记账"变成
 * "消息丢了"的原因。
 *
 * <p>{@link #NONE} 是裁剪部署与测试链路的空实现：没有审计时调用方不必写 if。
 */
public interface MessageAudit {

    /**
     * 一条入站消息处理完了。
     *
     * @param record 这一条的事实
     */
    void inbound(Inbound record);

    /**
     * 一条出站消息投递结束。
     *
     * @param record 这一条的事实
     */
    void outbound(Outbound record);

    /**
     * 什么都不做。没有审计设施时用它，调用方不必判空。
     *
     * <p>做成嵌套 record 而不是匿名类：{@code domain.port} 这个包有架构规则——只放接口与 record
     * （{@code CoreBoundariesTest}）。匿名类会在编译后多出一个 {@code MessageAudit$1}，
     * 而那个类名在这个包里是不合法的。
     */
    MessageAudit NONE = new None();

    /** 空实现。 */
    record None() implements MessageAudit {

        @Override
        public void inbound(Inbound record) {
        }

        @Override
        public void outbound(Outbound record) {
        }
    }

    /**
     * 一条入站消息。
     *
     * <p>{@code runId} 可空：入站消息在开跑之前就到了，而它可能根本没开跑（重复、没认出身分、
     * 被跳过）。能填的时候一定要填——"这条消息是被哪一轮答的"只有它能连起来。OpenClaw 的消息输入
     * 同样是 {@code runId} 可选（{@code src/audit/audit-event-types.ts:133}）。
     *
     * @param runId 这一轮的编号，可为 null
     * @param agentId 哪个 agent 处理的，可为 null（落 unknown）
     * @param channel 通道名
     * @param chatRef 通道内的会话地址（原文，实现负责假名化）
     * @param messageRef 这条消息在平台上的编号（原文）
     * @param actorId 发送者在平台上的标识（原文）
     * @param outcome 结果
     * @param reasonCode 原因码，没有时为 null
     * @param durationMs 从到达算起的毫秒数
     */
    record Inbound(String runId, String agentId, String channel, String chatRef, String messageRef,
                   String actorId, MessageOutcome outcome, String reasonCode, long durationMs) {
    }

    /**
     * 一条出站消息。
     *
     * @param runId 这一轮的编号，可为 null（定时任务、审批续跑没有入站那一轮）
     * @param agentId 哪个 agent 产出的，可为 null（落 unknown）
     * @param channel 通道名
     * @param chatRef 投递到哪段会话（原文）
     * @param targetRef 投递目标（原文），可为 null
     * @param outcome 结果
     * @param reasonCode 原因码，没有时为 null
     * @param failureStage 失败发生在哪一段，没有失败时为 null
     * @param deliveryKind 文本 / 媒体 / 其它
     * @param durationMs 从开始投递算起的毫秒数
     */
    record Outbound(String runId, String agentId, String channel, String chatRef, String targetRef,
                    MessageOutcome outcome, String reasonCode, String failureStage,
                    String deliveryKind, long durationMs) {
    }
}

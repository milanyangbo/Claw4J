package io.github.claw4j.channel.feishu;

import io.github.claw4j.channel.kernel.InboundEvent;

import java.time.Instant;

/**
 * 从飞书事件里解析出来的入站消息。
 *
 * <p>作用是把 SDK 类型挡在调度层之外：调度层的测试要能在没有凭据、没有网络、
 * 没有 {@code oapi-sdk} 的情况下跑，所以事件进调度层之前先变成这几个字符串。
 * 字段只保留通道自己需要的那部分，不做飞书事件的 Java 镜像。
 *
 * <p>它是 {@link InboundEvent} 在飞书侧的适配：编排层只认那几个通用字段，
 * {@code chat_id} 在这里映射成 {@code conversationId}，编排层里也就不出现 chatId 这种通道词。
 *
 * <p>不进 {@code channel.spi}：spi 是任何通道都要满足的契约，而这里的字段是飞书事件的形状
 * （{@code event_id}、{@code chat_id}、{@code open_id}）。
 *
 * @see io.github.claw4j.domain.port.InboundMessage 认出人之后往 Agent 送的那一份
 */
public interface FeishuInboundEvent extends InboundEvent {

    @Override
    default String conversationId() {
        return chatId();
    }

    /**
     * 平台侧的事件标识，去重按它。飞书 3 秒内没收到确认会重推，重推带的是同一个 {@code event_id}。
     *
     * <p>允许为 {@code null} 或空白：这时消息照常处理，只失去去重能力并记一条 WARN。
     * 宁可重复回答，也不因为少一个字段把用户的话丢掉。
     */
    String eventId();

    /**
     * 回哪条会话，对应飞书的 {@code chat_id}（群或单聊）。调度层不解析它，原样带回来。
     */
    String chatId();

    /**
     * 发消息人的 {@code open_id}，用来去角色表认人。认不出就拒绝：不放行，也不静默丢弃。
     */
    String senderId();

    /** 用户说的话。 */
    String text();

    /** 平台侧收到这条消息的时间，用于超时与日志，不参与去重判定。 */
    Instant receivedAt();
}

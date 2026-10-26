package io.github.claw4j.channel.feishu;

import java.time.Instant;

/**
 * 一条已经从飞书事件里解析出来的入站消息。
 *
 * <p>它存在的唯一理由是<b>把 SDK 的类型挡在外面</b>：调度层的测试要能在没有凭据、
 * 没有网络、没有 {@code oapi-sdk} 的情况下跑，所以事件进调度层之前必须先变成这几个字符串。
 * 因此这个接口刻意保持在"通道自己需要的最小信息"上——不是飞书事件的 Java 镜像。
 *
 * <p>它不进 {@code channel.spi}：spi 是"任何通道都要满足的契约"，而这里的字段是飞书事件的形状
 * （{@code event_id}、{@code chat_id}、{@code open_id}）。放进 spi 会让下一个通道被迫解释飞书的语义。
 *
 * @see io.github.claw4j.domain.port.InboundMessage 认出人之后往 Agent 送的那一份
 */
public interface FeishuInboundEvent {

    /**
     * 平台侧的事件标识，去重就按它。飞书 3 秒没确认会重推，重推送的是同一个 {@code event_id}。
     *
     * <p>允许为 {@code null}/空白：那种情况下这条消息<b>照常处理</b>，只是去重失效并记一条 WARN。
     * 宁可重复回答，也不能因为少了一个字段就把用户的话丢掉。
     */
    String eventId();

    /**
     * 回哪条会话。飞书是 {@code chat_id}（群或单聊），调度层不解析它，原样带回来。
     */
    String chatId();

    /**
     * 发消息的人的 {@code open_id}。用它去角色表里认人；认不出就拒绝，不放行也不静默丢弃。
     */
    String senderId();

    /** 用户说的话。 */
    String text();

    /** 平台侧收到这条消息的时间，用于超时与日志，不参与去重判定。 */
    Instant receivedAt();
}

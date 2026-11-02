package io.github.claw4j.domain.port;

import io.github.claw4j.domain.NotifyPolicy;
import io.github.claw4j.domain.principal.Principal;

import java.time.Instant;
import java.util.Objects;

/**
 * 一条入站消息：通道解析完用户身份之后交给 Agent 的东西。
 *
 * <p>通道的职责到"认出是谁、在哪段会话里、说了什么"为止，再往后的事与通道无关。
 * 这也是 ADR-0007 的边界：认人由通道负责，授权由 Agent 侧负责。
 *
 * @param principal  发起者，由通道从自身凭据解析而来
 * @param chatId     Chat ID，即通道侧对这条会话的标识（飞书是 chat_id + 用户，HTTP 是请求头）。
 *                   它只是 Session 的寻址方式之一，不等于 Session 本身
 * @param text       用户说的话
 * @param receivedAt 通道收到消息的时间，用于超时与去重
 * @param eventId    通道侧的事件标识，用于幂等。飞书超时重推会送两次同一个 eventId
 * @param runId      要接着做的那件事（A02）。null 表示"这是一件新事"；
 *                   非 null 表示接上这个 run，序号接着涨而不是另开一个。
 *                   <b>它不提权</b>：接不上（没有这个 run / 不属于这个人 /
 *                   它正在等审批）时落回"开一个新的"，绝不接别人的 run
 * @param channel    这条消息从哪个通道来（A05），与 {@code OutboundDelivery#channel()}
 *                   同一个口径。带了它，这件事的结果才知道"以后送回哪儿"；
 *                   null 表示"不登记收件地址"（老调用方与单测走这里），
 *                   它不影响回答，只影响"能不能被主动找回来"
 * @param notifyPolicy 这一件事的通知策略（A05）。交互式提问是 {@code done_only}，
 *                   定时任务传 {@code silent}——到点自己跑一趟的事不该每趟都推一条
 */
public record InboundMessage(Principal principal,
                             String chatId,
                             String text,
                             Instant receivedAt,
                             String eventId,
                             String runId,
                             String channel,
                             NotifyPolicy notifyPolicy) {

    public InboundMessage {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(chatId, "chatId");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(receivedAt, "receivedAt");
        // 不点名的调用方要的是"做完了说一次"。让 null 流进去会让判空散落各处，
        // 而散落的判空迟早少一处——少那一处的表现是"这件事忽然不通知了"。
        notifyPolicy = notifyPolicy == null ? NotifyPolicy.DEFAULT : notifyPolicy;
    }

    public InboundMessage(Principal principal, String chatId, String text, Instant receivedAt,
                          String eventId, String runId, String channel) {
        this(principal, chatId, text, receivedAt, eventId, runId, channel, NotifyPolicy.DEFAULT);
    }

    public InboundMessage(Principal principal, String chatId, String text, Instant receivedAt,
                          String eventId, String runId) {
        this(principal, chatId, text, receivedAt, eventId, runId, null, NotifyPolicy.DEFAULT);
    }

    public InboundMessage(Principal principal, String chatId, String text, Instant receivedAt, String eventId) {
        this(principal, chatId, text, receivedAt, eventId, null, null, NotifyPolicy.DEFAULT);
    }

    public InboundMessage(Principal principal, String chatId, String text) {
        this(principal, chatId, text, Instant.now(), null, null, null, NotifyPolicy.DEFAULT);
    }
}

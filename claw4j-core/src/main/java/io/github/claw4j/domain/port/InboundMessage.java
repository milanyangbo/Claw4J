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
 * @param channel    这条消息从哪个通道来（A05），与 {@code OutboundDelivery#channel()}
 *                   同一个口径。带了它，审批卡片与结果才知道"送回哪儿"；
 *                   null 表示这个通道没有推送能力（老调用方与单测走这里），
 *                   它不影响回答，只影响"有没有地方可推"
 * @param notifyPolicy 这一件事的通知策略（A05）。交互式提问是 {@code done_only}，
 *                   定时任务传 {@code silent}——到点自己跑一趟的事不该每趟都推一条
 * @param conversationId 这段对话的身份（ADR-0026）。null 表示"按 {@code chatId} 派生"，
 *                   也就是所有有人说话的通道；非 null 时以它为准，<b>投递仍用 {@code chatId}</b>。
 *                   定时的任务传 {@code cron:<taskId>:<触发时刻>}：它跑在自己的会话里，
 *                   不往用户那段对话里塞记录，也不吃用户那份预算
 * @param sessionId  整段会话的键，<b>用它取代原来的 {@code runId}</b>（ADR-0027）。
 *                   null 表示"按通道 + chatId + 当前段派生"，也就是所有有人说话的通道；
 *                   非 null 时以它为准，<b>不换段也不查指针</b>——审批批准之后走的就是这条：
 *                   回到发起请求的那一段对话里开新一轮。
 *                   <p><b>为什么不是 runId</b>：连续性靠会话，不靠"那件事"。同一段对话的上下文
 *                   与 goal 都在，模型看到的自然就是"刚才我要调这个工具、现在批准了"。
 */
public record InboundMessage(Principal principal,
                             String chatId,
                             String text,
                             Instant receivedAt,
                             String eventId,
                             String channel,
                             NotifyPolicy notifyPolicy,
                             String conversationId,
                             String sessionId) {

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
                          String eventId, String channel, NotifyPolicy notifyPolicy) {
        this(principal, chatId, text, receivedAt, eventId, channel, notifyPolicy, null, null);
    }

    public InboundMessage(Principal principal, String chatId, String text, Instant receivedAt,
                          String eventId, String channel) {
        this(principal, chatId, text, receivedAt, eventId, channel, NotifyPolicy.DEFAULT, null, null);
    }

    public InboundMessage(Principal principal, String chatId, String text, Instant receivedAt, String eventId) {
        this(principal, chatId, text, receivedAt, eventId, null, NotifyPolicy.DEFAULT, null, null);
    }

    public InboundMessage(Principal principal, String chatId, String text) {
        this(principal, chatId, text, Instant.now(), null, null, NotifyPolicy.DEFAULT, null, null);
    }
}

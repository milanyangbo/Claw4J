package io.github.claw4j.domain.port;

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
 * @param sessionKey 通道侧的会话标识（飞书是 chat_id + 用户，HTTP 是 header）。
 *                   它只是 Session 的寻址方式，不等于 Session 本身
 * @param text       用户说的话
 * @param receivedAt 通道收到消息的时间，用于超时与去重
 * @param eventId    通道侧的事件标识，用于幂等。飞书超时重推会送两次同一个 eventId
 */
public record InboundMessage(Principal principal,
                             String sessionKey,
                             String text,
                             Instant receivedAt,
                             String eventId) {

    public InboundMessage {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(sessionKey, "sessionKey");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(receivedAt, "receivedAt");
    }

    public InboundMessage(Principal principal, String sessionKey, String text) {
        this(principal, sessionKey, text, Instant.now(), null);
    }
}

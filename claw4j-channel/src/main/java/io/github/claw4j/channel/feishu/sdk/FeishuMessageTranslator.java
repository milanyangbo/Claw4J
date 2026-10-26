package io.github.claw4j.channel.feishu.sdk;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.lark.oapi.service.im.v1.model.EventMessage;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1Data;
import com.lark.oapi.service.im.v1.model.UserId;
import io.github.claw4j.channel.feishu.FeishuInboundEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 把飞书的事件对象翻成 {@link FeishuInboundEvent}——本层唯一有实质内容的工作。
 *
 * <p>为什么值得单独一个类：翻译里全是"飞书那边长什么样"的知识（{@code event_id} 在 header 里、
 * 正文是 JSON 字符串、@ 机器人会留一个 {@code @_user_1} 占位符），这些知识放进通道类里，
 * 通道类会被"怎么读飞书"淹没，而它在结构上只是一段生命周期 + 一个回调。
 *
 * <p>翻不出来时返回 {@code Optional#empty()}，让调用方回一句人话。翻不出来的三种情形：
 * 不是文字消息（图片、富文本 post）、只有 @ 没有正文、缺 chat_id 或发送者。
 * 不抛异常是刻意的——事件线程抛出去会让平台重推，而重推的还是一条翻不出来的消息。
 */
class FeishuMessageTranslator {

    private static final Logger log = LoggerFactory.getLogger(FeishuMessageTranslator.class);

    /**
     * 文本消息里 @ 机器人留下的占位符。飞书把 mention 从正文里抽走、换成一个 {@code @_user_1}，
     * 真正的人名在 {@code mentions} 数组里。不摘掉的话，模型看到的问题开头是一串无意义的 token。
     */
    private static final Pattern MENTION_PLACEHOLDER = Pattern.compile("@_user_\\d+");

    private static final String TEXT_MESSAGE = "text";

    private final ObjectMapper objectMapper = new ObjectMapper();

    Optional<FeishuInboundEvent> translate(P2MessageReceiveV1 event) {
        if (event == null || event.getEvent() == null) {
            return Optional.empty();
        }
        P2MessageReceiveV1Data data = event.getEvent();
        EventMessage message = data.getMessage();
        String openId = openIdOf(data);
        if (message == null || isBlank(message.getChatId()) || isBlank(openId)) {
            log.debug("飞书事件缺 chat_id 或发送者，跳过：messageId={}",
                    message == null ? null : message.getMessageId());
            return Optional.empty();
        }
        if (!TEXT_MESSAGE.equals(message.getMessageType())) {
            log.info("暂不处理这种消息类型：type={} chatId={}", message.getMessageType(), message.getChatId());
            return Optional.empty();
        }

        String text = stripMentions(textOf(message.getContent()));
        if (isBlank(text)) {
            return Optional.empty();
        }
        return Optional.of(new InboundEvent(eventIdOf(event), message.getChatId(), openId, text,
                receivedAtOf(message)));
    }

    /** 正文是 JSON 字符串，形如 {@code {"text":"..."}}。解析失败按"没有正文"处理，不猜。 */
    private String textOf(String content) {
        if (isBlank(content)) {
            return "";
        }
        try {
            JsonNode text = objectMapper.readTree(content).get("text");
            return text == null ? "" : text.asString("");
        } catch (RuntimeException malformed) {
            log.warn("飞书消息正文不是预期的 JSON，按没有正文处理：{}", content, malformed);
            return "";
        }
    }

    private static String stripMentions(String text) {
        return MENTION_PLACEHOLDER.matcher(text).replaceAll("").strip();
    }

    private static String eventIdOf(P2MessageReceiveV1 event) {
        return event.getHeader() == null ? null : event.getHeader().getEventId();
    }

    private static String openIdOf(P2MessageReceiveV1Data data) {
        UserId sender = data.getSender() == null ? null : data.getSender().getSenderId();
        return sender == null ? null : sender.getOpenId();
    }

    /**
     * 平台侧的发送时间。{@code create_time} 是毫秒字符串，解析不出来就用本机时间：
     * 这个字段只用于日志与超时观察，不值得为它让一整条消息走不通。
     */
    private static Instant receivedAtOf(EventMessage message) {
        String createTime = message.getCreateTime();
        if (isBlank(createTime)) {
            return Instant.now();
        }
        try {
            return Instant.ofEpochMilli(Long.parseLong(createTime.strip()));
        } catch (NumberFormatException notMillis) {
            return Instant.now();
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record InboundEvent(String eventId,
                                String chatId,
                                String senderId,
                                String text,
                                Instant receivedAt) implements FeishuInboundEvent {
    }
}

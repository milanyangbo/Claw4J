package io.github.claw4j.channel.wecom.client;

import io.github.claw4j.channel.wecom.WecomInboundEvent;

import java.time.Instant;
import java.util.Map;

/**
 * 把解出来的明文 XML 压成内核要的那几个字段，或者明确说这条不该回答。
 *
 * <p>三种结果对应三种处理，混成两种就会出错：
 *
 * <ul>
 *   <li>{@link Kind#TEXT}：正常的一句话，交给入站编排。</li>
 *   <li>{@link Kind#EVENT}：事件通知，例如成员第一次进入应用时的 {@code enter_agent}。
 *       它不是提问，必须静默——把「应用被打开」当成一句话回一句「把问题打成文字发给我」，
 *       用户每点一次应用就挨一句训。</li>
 *   <li>{@link Kind#UNSUPPORTED}：图片、语音、位置这些处理不了的类型，但说话的人在，
 *       所以要回一句「打成文字」——对话里的沉默分不清没收到和不会。</li>
 * </ul>
 *
 * <p>时间取 {@code CreateTime}（秒），取不到就用当前时间：它只用于日志与耗时，不参与去重判定，
 * 宁可不准也不要因此丢掉一条消息。
 */
class WecomMessageTranslator {

    /** 一条解密后的报文的去向。 */
    enum Kind {
        TEXT,
        EVENT,
        UNSUPPORTED
    }

    /**
     * 一条解密后报文的去向。
     *
     * @param kind 这条回调该往哪儿走
     * @param userId 发消息的成员，事件与不支持的类型也可能有；没有就是 null
     * @param event 只有 {@link Kind#TEXT} 时非空
     */
    record Parsed(Kind kind, String userId, WecomInboundEvent event) {
    }

    Parsed parse(String decryptedXml) {
        Map<String, String> fields = WecomXml.childrenOf(decryptedXml);
        String userId = fields.get("FromUserName");
        String type = fields.get("MsgType");
        if ("event".equalsIgnoreCase(type)) {
            return new Parsed(Kind.EVENT, userId, null);
        }
        if (!"text".equalsIgnoreCase(type)) {
            return new Parsed(Kind.UNSUPPORTED, userId, null);
        }
        String content = fields.get("Content");
        if (content == null || content.isBlank()) {
            // 有 MsgType=text 但正文是空的：多半是一条被清空的表情或粘贴失败。
            // 回"打成文字"比交给模型更诚实——模型会对着空气编一句回答。
            return new Parsed(Kind.UNSUPPORTED, userId, null);
        }
        if (userId == null || userId.isBlank()) {
            // 没有发送者就没法认人，也就没法回。当不支持处理：调用方会记日志并回空串。
            return new Parsed(Kind.UNSUPPORTED, null, null);
        }
        return new Parsed(Kind.TEXT, userId, new TextEvent(fields.get("MsgId"), userId, content,
                receivedAt(fields.get("CreateTime"))));
    }

    private static Instant receivedAt(String createTime) {
        if (createTime == null || createTime.isBlank()) {
            return Instant.now();
        }
        try {
            return Instant.ofEpochSecond(Long.parseLong(createTime.strip()));
        } catch (NumberFormatException notANumber) {
            return Instant.now();
        }
    }

    /**
     * 一条文本消息。
     *
     * <p>写成 record 而不是让调用方拼一个匿名类：它只有四个字段、没有行为，而少填一个字段
     * 在这件事上的表现是去重失效或回错了人，都很难查。
     */
    private record TextEvent(String eventId, String userId, String text, Instant receivedAt)
            implements WecomInboundEvent {

        @Override
        public String senderId() {
            return userId;
        }
    }
}

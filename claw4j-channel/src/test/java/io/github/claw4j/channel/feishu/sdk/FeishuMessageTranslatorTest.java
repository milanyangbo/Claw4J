package io.github.claw4j.channel.feishu.sdk;

import com.lark.oapi.event.model.Header;
import com.lark.oapi.service.im.v1.model.EventMessage;
import com.lark.oapi.service.im.v1.model.EventSender;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1Data;
import com.lark.oapi.service.im.v1.model.UserId;
import io.github.claw4j.channel.feishu.FeishuInboundEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 飞书事件到 {@link FeishuInboundEvent} 这一段翻译。
 *
 * <p>单独测它，因为这是这一层唯一有实质内容的工作，而错法全都不报错：取错 sender 字段会让
 * 所有人认成同一个人或者全都认不出，漏摘 {@code @_user_1} 会让模型看到一串无意义 token，
 * 翻不出来时抛异常会让平台一直重推同一条消息。
 *
 * <p>用例不需要凭据、不需要网络：事件对象是 SDK 的 POJO，直接 new 出来就行，
 * 而这些行是通道里最容易写错的部分。
 */
class FeishuMessageTranslatorTest {

    private static final String CHAT_ID = "oc_demo_chat";

    private static final String OPEN_ID = "ou_zhang";

    private static final String EVENT_ID = "evt-0001";

    /** 飞书的 create_time 是"这个时刻的毫秒字符串"，用同一个 Instant 反推，避免手算错。 */
    private static final long CREATE_TIME_MILLIS = Instant.parse("2026-10-26T12:00:00Z").toEpochMilli();

    private final FeishuMessageTranslator translator = new FeishuMessageTranslator();

    @Test
    @DisplayName("一条正常的文本消息：五个字段各自取对，eventId 来自 header 而不是正文")
    void translatesAPlainTextMessage() {
        P2MessageReceiveV1 event = event(textMessage("上周华东区成交额"));

        FeishuInboundEvent translated = translator.translate(event).orElseThrow();

        assertEquals(EVENT_ID, translated.eventId(), "eventId 不在 message 里，在 header 里");
        assertEquals(CHAT_ID, translated.chatId());
        assertEquals(OPEN_ID, translated.senderId());
        assertEquals("上周华东区成交额", translated.text());
        assertEquals(Instant.ofEpochMilli(CREATE_TIME_MILLIS), translated.receivedAt());
    }

    @Test
    @DisplayName("正文里的 @ 占位符被摘掉：模型不该看到 @_user_1 这种 token")
    void stripsMentionPlaceholders() {
        P2MessageReceiveV1 event = event(textMessage("@_user_1 上周华东区成交额 @_user_2 谢谢"));

        assertEquals("上周华东区成交额  谢谢", translator.translate(event).orElseThrow().text());
    }

    @Test
    @DisplayName("只有 @ 没有正文：翻不出来，返回空让调用方回一句人话")
    void mentionOnlyMessageIsNotTranslated() {
        assertTrue(translator.translate(event(textMessage("@_user_1"))).isEmpty());
    }

    @Test
    @DisplayName("不是文本消息（图片、富文本 post）：返回空，不猜")
    void nonTextMessageIsNotTranslated() {
        EventMessage image = EventMessage.newBuilder()
                .messageId("om_1").chatId(CHAT_ID).messageType("image")
                .content("{\"image_key\":\"img_1\"}").createTime(String.valueOf(CREATE_TIME_MILLIS))
                .build();

        assertTrue(translator.translate(event(image)).isEmpty());
    }

    @Test
    @DisplayName("缺 chat_id：翻不出来。少了它就没有回推的目标")
    void messageWithoutChatIdIsNotTranslated() {
        EventMessage noChat = EventMessage.newBuilder()
                .messageId("om_1").messageType("text").content("{\"text\":\"问题\"}")
                .createTime(String.valueOf(CREATE_TIME_MILLIS)).build();

        assertTrue(translator.translate(event(noChat)).isEmpty());
    }

    @Test
    @DisplayName("缺发送者 open_id：翻不出来。认不出人就不该往下走")
    void messageWithoutSenderIsNotTranslated() {
        P2MessageReceiveV1 event = event(textMessage("问题"));
        event.getEvent().setSender(null);

        assertTrue(translator.translate(event).isEmpty());
    }

    @Test
    @DisplayName("正文不是预期的 JSON：按没有正文处理，不抛异常（抛出去只会让平台重推同一条）")
    void malformedContentIsTreatedAsEmpty() {
        EventMessage malformed = EventMessage.newBuilder()
                .messageId("om_1").chatId(CHAT_ID).messageType("text")
                .content("这不是 JSON").createTime(String.valueOf(CREATE_TIME_MILLIS)).build();

        assertTrue(translator.translate(event(malformed)).isEmpty());
    }

    @Test
    @DisplayName("event 或 event.event 为空：返回空，不 NPE")
    void nullEventIsNotTranslated() {
        assertTrue(translator.translate(null).isEmpty());

        P2MessageReceiveV1 withoutEvent = new P2MessageReceiveV1();
        assertTrue(translator.translate(withoutEvent).isEmpty());
    }

    @Test
    @DisplayName("create_time 不是毫秒数：用本机时间兜底，不为一个日志字段让整条消息走不通")
    void unparseableCreateTimeFallsBackToNow() {
        EventMessage oddTime = EventMessage.newBuilder()
                .messageId("om_1").chatId(CHAT_ID).messageType("text")
                .content("{\"text\":\"问题\"}").createTime("昨天").build();

        Instant before = Instant.now().minus(Duration.ofSeconds(5));
        Instant receivedAt = translator.translate(event(oddTime)).orElseThrow().receivedAt();

        assertTrue(receivedAt.isAfter(before), "没有回落到当前时间：" + receivedAt);
    }

    @Test
    @DisplayName("header 缺失：eventId 为 null，消息照常翻译——去重失效比丢消息可接受")
    void missingHeaderLeavesEventIdNull() {
        P2MessageReceiveV1 event = event(textMessage("问题"));
        event.setHeader(null);

        Optional<FeishuInboundEvent> translated = translator.translate(event);

        assertTrue(translated.isPresent());
        assertNull(translated.orElseThrow().eventId());
    }

    // ---------------------------------------------------------------- 造一个飞书事件

    private static EventMessage textMessage(String text) {
        return EventMessage.newBuilder()
                .messageId("om_1")
                .chatId(CHAT_ID)
                .chatType("group")
                .messageType("text")
                .content("{\"text\":\"" + text + "\"}")
                .createTime(String.valueOf(CREATE_TIME_MILLIS))
                .build();
    }

    private static P2MessageReceiveV1 event(EventMessage message) {
        EventSender sender = new EventSender();
        sender.setSenderId(UserId.newBuilder().openId(OPEN_ID).build());

        P2MessageReceiveV1Data data = new P2MessageReceiveV1Data();
        data.setMessage(message);
        data.setSender(sender);

        Header header = new Header();
        header.setEventId(EVENT_ID);
        header.setEventType("im.message.receive_v1");

        P2MessageReceiveV1 event = new P2MessageReceiveV1();
        event.setEvent(data);
        event.setHeader(header);
        return event;
    }
}

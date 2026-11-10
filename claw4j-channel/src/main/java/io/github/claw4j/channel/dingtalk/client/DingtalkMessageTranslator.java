package io.github.claw4j.channel.dingtalk.client;

import io.github.claw4j.channel.dingtalk.DingtalkInboundEvent;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

/**
 * 把钉钉的明文 JSON 压成内核要的五个字段，或者明确地说"这条不该回答、或答不了"。
 *
 * <p>四种结果，对应四种处理，少一种就会出错：
 *
 * <ul>
 *   <li>{@link Kind#TEXT}：单聊里的一句话，交给入站编排。</li>
 *   <li>{@link Kind#GROUP}：群聊（{@code conversationType=2}）。第一版不作答，但要回一句
 *       "私聊我"——静默地把群里的提问当成单聊回，群里的人只会觉得机器人没反应。</li>
 *   <li>{@link Kind#UNSUPPORTED}：图片、语音、文件、富文本。回一句"打成文字"。</li>
 *   <li>{@link Kind#NO_IDENTITY}：钉钉没给 {@code senderStaffId}。机器人没发布上线时这个字段
 *       不返回，来自外部群的消息也不返回。它和"这个人没登记"在别的地方长得一样，
 *       但一个是配置问题、一个是登记问题，排查成本差很远。</li>
 * </ul>
 *
 * <p>判定顺序是先看有没有身份，再看是什么消息。反过来的话，一条没有 {@code senderStaffId}
 * 的图片消息会被判成"回一句打成文字"，而那句话发不出去（没有 userId 就没有收件人），
 * 于是它变成一条被吞掉的日志。
 */
class DingtalkMessageTranslator {

    /** 一条回调的去向。 */
    enum Kind {
        TEXT,
        GROUP,
        UNSUPPORTED,
        NO_IDENTITY
    }

    /**
     * 解析结果：去向，加随路数据（{@code userId}、{@code eventId}、{@code event}）。
     *
     * @param kind 这条回调该往哪儿走
     * @param userId 发消息的人；{@link Kind#NO_IDENTITY} 时是 null
     * @param eventId {@code msgId}；可能缺失，日志里用来对上一条具体消息
     * @param event 只有 {@link Kind#TEXT} 时非空
     */
    record Parsed(Kind kind, String userId, String eventId, DingtalkInboundEvent event) {
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    Parsed parse(String body) {
        JsonNode root = readTree(body);
        String userId = text(root, "senderStaffId");
        String eventId = text(root, "msgId");

        if (userId.isBlank()) {
            return new Parsed(Kind.NO_IDENTITY, null, eventId, null);
        }
        if (!"text".equalsIgnoreCase(text(root, "msgtype"))) {
            return new Parsed(Kind.UNSUPPORTED, userId, eventId, null);
        }
        String conversationType = text(root, "conversationType").strip();
        // conversationType 在文档里是字符串 "1" / "2"。写成 int 比较会一直不相等，
        // 于是群聊被当成单聊处理——症状是"群里问一句，答案私聊发给了提问的人"，
        // 看起来完全正常，直到有人问"为什么群里没有回答"。
        if ("2".equals(conversationType)) {
            return new Parsed(Kind.GROUP, userId, eventId, null);
        }
        String content = text(root, "text", "content");
        if (content.isBlank()) {
            // 有 msgtype=text 但正文是空的：多半是一条被清空的消息或粘贴失败。
            // 回"打成文字"比交给模型更诚实——模型会对着空气编一句回答。
            return new Parsed(Kind.UNSUPPORTED, userId, eventId, null);
        }
        return new Parsed(Kind.TEXT, userId, eventId,
                new TextEvent(eventId, userId, content, receivedAt(text(root, "createAt")),
                        !"2".equals(conversationType)));
    }

    private JsonNode readTree(String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalStateException("钉钉回调的报文是空的");
        }
        try {
            return objectMapper.readTree(body);
        } catch (RuntimeException malformed) {
            // 不在这里吞掉：调用方要记一条带原文片段的 ERROR，否则排查时只知道"解不开"，
            // 不知道对面到底发了什么（钉钉改过字段名这种事真的会发生）。
            throw new IllegalStateException("钉钉回调的报文不是合法 JSON："
                    + (body.length() > 200 ? body.substring(0, 200) + "…" : body), malformed);
        }
    }

    /**
     * {@code createAt} 是<b>毫秒</b>。
     *
     * <p>这一点必须单独写出来：企业微信的 {@code CreateTime} 是<b>秒</b>，两个数字差三个数量级。
     * 照抄那边的 {@code Instant.ofEpochSecond} 会得到一个五万年后的时间——
     * 它只用于日志与耗时，不会让任何判定出错，所以这个错会安静地活很久。
     *
     * <p>取不到就用当前时间：宁可不准，也不要因为少一个字段丢掉一条消息。
     */
    private static Instant receivedAt(String createAt) {
        if (createAt.isBlank()) {
            return Instant.now();
        }
        try {
            long millis = Long.parseLong(createAt.strip());
            // 10 位的按秒兜一下：文档说毫秒，但一条按秒给的时间不该把消息变成 1970 年
            return millis < 100_000_000_000L ? Instant.ofEpochSecond(millis) : Instant.ofEpochMilli(millis);
        } catch (NumberFormatException notANumber) {
            return Instant.now();
        }
    }

    private static String text(JsonNode node, String field) {
        return text(node, field, null);
    }

    /** 取一个字符串字段，{@code path} 非空时往下钻一层（{@code text.content}）。 */
    private static String text(JsonNode node, String field, String path) {
        JsonNode value = node.get(field);
        if (path != null) {
            value = value == null || value.isNull() ? null : value.get(path);
        }
        if (value == null || value.isNull()) {
            return "";
        }
        // asString：数字与布尔也能取到字面值。conversationType 与 createAt 在文档里都是字符串，
        // 但对面真发个数字过来的话，这里不该因此把整条消息判成"没有身份"。
        return value.asString("").strip();
    }

    /**
     * 一条文本消息。
     *
     * <p>写成 record 而不是让调用方拼一个匿名类：它只有五个字段、没有行为，
     * 而"少填一个字段"在这件事上的表现是"去重失效"或"回错了人"——都很难查。
     *
     * @param direct 单聊为 {@code true}。这个值是从 {@code conversationType} 算出来的，
     *               不是"反正只有单聊"的默认——见 {@link DingtalkInboundEvent#direct()}
     */
    private record TextEvent(String eventId, String userId, String text, Instant receivedAt,
                             boolean direct) implements DingtalkInboundEvent {

        @Override
        public String senderId() {
            return userId;
        }
    }
}

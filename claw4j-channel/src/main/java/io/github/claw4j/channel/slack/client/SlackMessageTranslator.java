package io.github.claw4j.channel.slack.client;

import io.github.claw4j.channel.slack.SlackInboundEvent;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * 把 Slack 的事件报文压成内核要的那五个字段——或者明确地说"这条不该回答、或答不了"。
 *
 * <p>四种结果，对应四种处理，<b>少一种就会出错</b>：
 *
 * <ul>
 *   <li>{@link Kind#TEXT}：一句话，交给入站编排。</li>
 *   <li>{@link Kind#HANDSHAKE}：配置请求地址时的那次握手，回 {@code challenge} 原文。它不是业务事件，
 *       但<b>是能不能把这个通道配起来的唯一一道关</b>。</li>
 *   <li>{@link Kind#EMPTY_QUESTION}：只 @ 了应用、没写问题。回一句"问点什么"，
 *       <b>不进模型</b>——空提问进模型会得到一段对着空气编的回答。</li>
 *   <li>{@link Kind#IGNORED}：其余一切。带一条人能读懂的理由，因为这一类最多、也最容易被误判。</li>
 * </ul>
 *
 * <p><b>判定顺序是有讲究的，而且这个顺序是本类最重要的一处</b>：
 *
 * <ol>
 *   <li>先看 {@code bot_id} 与 {@code subtype}，<b>在一切业务判断之前</b>。Slack 会把应用自己
 *       发出去的消息也推回来——不先挡这一层，一次提问就变成一问一答、答又问、问又答，
 *       不报错、只在账单上体现。前四个通道都没有这个问题。</li>
 *   <li>再问有没有 {@code user}：没有身份的消息连"没有权限"都发不出去。</li>
 *   <li>然后才是"这是哪一类提问"。</li>
 * </ol>
 *
 * <p><b>为什么只挡 {@code bot_id} 不够</b>：{@code message_changed}（用户编辑了消息）、
 * {@code channel_join}（某某加入频道）这些<b>不是机器人发的</b>，没有 {@code bot_id} 但有 {@code subtype}。
 * 前者连顶层 {@code text} 都没有（原文在 {@code event.message.text}，多嵌一层），
 * 照 {@code event.text} 取会拿到空串；后者的 {@code text} 是"某某加入了频道"这种句子——
 * 它会真的被当成提问送进模型。两条一起挡才是闭合的。
 *
 * <p>{@code event.type} 的不对称也要写清：订阅项叫 {@code app_mention} 与 {@code message.im}，
 * 但载荷里前者是 {@code app_mention}、后者是 {@code message}（配 {@code channel_type: "im"}）。
 * 按订阅名判断的话单聊永远匹配不到，而表现是"群里 @ 有反应、私聊没反应"，也不报错。
 */
class SlackMessageTranslator {

    /** 一条回调的去向。 */
    enum Kind {
        TEXT,
        HANDSHAKE,
        EMPTY_QUESTION,
        IGNORED
    }

    /**
     * @param kind      该往哪儿走
     * @param challenge 只有 {@link Kind#HANDSHAKE} 时非空
     * @param reason    只有 {@link Kind#IGNORED} 时非空，直接进日志
     * @param event     只有 {@link Kind#TEXT} 与 {@link Kind#EMPTY_QUESTION} 时非空
     */
    record Parsed(Kind kind, String challenge, String reason, SlackInboundEvent event) {
    }

    /** 配置请求地址时的那次握手。 */
    private static final String HANDSHAKE = "url_verification";

    /** 真正的业务事件都套在这一层信封里。 */
    private static final String EVENT_CALLBACK = "event_callback";

    private static final String APP_MENTION = "app_mention";

    private static final String MESSAGE = "message";

    /** 私聊。文档原话：判断私聊要看 {@code event.channel_type} 是不是 {@code im}。 */
    private static final String DIRECT_MESSAGE = "im";

    /** Slack 的 mention 有两种写法：{@code <@U123>} 与 {@code <@U123|显示名>}。两种都要剥。 */
    private static final Pattern MENTION = Pattern.compile("<@[^>]*>");

    private final ObjectMapper objectMapper = new ObjectMapper();

    Parsed parse(String body) {
        JsonNode root = readTree(body);
        String type = text(root, "type");

        if (HANDSHAKE.equals(type)) {
            String challenge = text(root, "challenge");
            if (challenge.isBlank()) {
                return ignored("握手报文里没有 challenge");
            }
            return new Parsed(Kind.HANDSHAKE, challenge, "", null);
        }
        if (!EVENT_CALLBACK.equals(type)) {
            return ignored("报文类型是 " + (type.isBlank() ? "（空）" : type) + "，不是业务事件");
        }

        JsonNode event = root.get("event");
        if (event == null || event.isNull()) {
            return ignored("event_callback 里没有 event");
        }

        String botId = text(event, "bot_id");
        if (!botId.isBlank()) {
            return ignored("这条是机器人或集成发的（bot_id=" + botId + "）：不挡就是自己回自己");
        }
        String subtype = text(event, "subtype");
        if (!subtype.isBlank()) {
            return ignored("subtype=" + subtype + "：编辑、删除、成员进出都不是一句人说的话");
        }

        String userId = text(event, "user");
        if (userId.isBlank()) {
            return ignored("事件里没有 user：没有收件人也认不出人");
        }

        String eventType = text(event, "type");
        if (APP_MENTION.equals(eventType)) {
            // 频道里 @ 应用：答案挂回这条消息下面的线程，不然频道会被回执与答案刷满
            return question(root, event, userId, text(event, "ts"));
        }
        if (MESSAGE.equals(eventType) && DIRECT_MESSAGE.equals(text(event, "channel_type"))) {
            // 私聊：发顶层。这里刻意不看 thread_ts——私聊里跟帖是少数，挂在原消息下反而绕
            return question(root, event, userId, "");
        }
        return ignored("事件类型是 " + eventType + "（channel_type="
                + text(event, "channel_type") + "），不是给我的提问");
    }

    /** 一句话的两种结局：有内容就交给编排，剥完是空就回一句"问点什么"。 */
    private Parsed question(JsonNode root, JsonNode event, String userId, String threadTs) {
        String channel = text(event, "channel");
        if (channel.isBlank()) {
            return ignored("事件里没有 channel：知道谁问的，但不知道回哪儿");
        }
        String question = stripMentions(text(event, "text"));
        TextEvent inbound = new TextEvent(text(root, "event_id"), userId, question, channel, threadTs,
                receivedAt(text(root, "event_time")));
        return question.isBlank()
                ? new Parsed(Kind.EMPTY_QUESTION, "", "", inbound)
                : new Parsed(Kind.TEXT, "", "", inbound);
    }

    /**
     * 剥掉 {@code <@U…>} 与 {@code <@U…|名字>}。
     *
     * <p>不剥的话模型收到的第一段是 {@code <@U0LAN0Z89>} 这串 id——它占 token，
     * 又和问题连在一起，写进 SQL 注释里还会让生成的语句看起来像被人动过。
     *
     * <p>只剥用户 mention：{@code <#C…|channel>} 与 {@code <https://…|文字>} 是频道引用和链接，
     * 剥掉会丢信息。所以正则限定在 {@code <@} 开头。
     */
    static String stripMentions(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return MENTION.matcher(text).replaceAll(" ").replaceAll("\\s+", " ").strip();
    }

    private JsonNode readTree(String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalStateException("Slack 回调的报文是空的");
        }
        try {
            return objectMapper.readTree(body);
        } catch (RuntimeException malformed) {
            // 不在这里吞掉：调用方要记一条带原文片段的 ERROR，否则排查时只知道"解不开"，
            // 不知道对面到底发了什么。
            throw new IllegalStateException("Slack 回调的报文不是合法 JSON："
                    + (body.length() > 200 ? body.substring(0, 200) + "…" : body), malformed);
        }
    }

    /**
     * {@code event_time} 是<b>秒</b>。
     *
     * <p>这一点必须单独写出来：钉钉的 {@code createAt} 是<b>毫秒</b>，两个数字差三个数量级。
     * 照抄钉钉的 {@code Instant.ofEpochMilli} 会得到 1970 年——它只用于日志与耗时，
     * 不会让任何判定出错，所以这个错会安静地活很久。
     *
     * <p>取不到就用当前时间：宁可不准，也不要因为少一个字段丢掉一条消息。
     */
    private static Instant receivedAt(String eventTime) {
        if (eventTime.isBlank()) {
            return Instant.now();
        }
        try {
            long value = Long.parseLong(eventTime.strip());
            // 13 位的按毫秒兜一下：文档说秒，但一条按毫秒给的时间不该把消息变成五万年后
            return value > 100_000_000_000L ? Instant.ofEpochMilli(value) : Instant.ofEpochSecond(value);
        } catch (NumberFormatException notANumber) {
            return Instant.now();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return "";
        }
        // asString：数字与布尔也能取到字面值（event_time 在文档里是数字）。
        return value.asString("").strip();
    }

    private static Parsed ignored(String reason) {
        return new Parsed(Kind.IGNORED, "", reason, null);
    }

    /**
     * 一条文本消息。
     *
     * <p>写成 record 而不是让调用方拼一个匿名类：它只有六个字段、没有行为，
     * 而"少填一个字段"在这件事上的表现是"去重失效"或"答案发到了别人的线程里"——都很难查。
     */
    private record TextEvent(String eventId, String userId, String text, String channel,
                             String threadTs, Instant receivedAt) implements SlackInboundEvent {

        @Override
        public String senderId() {
            return userId;
        }
    }
}

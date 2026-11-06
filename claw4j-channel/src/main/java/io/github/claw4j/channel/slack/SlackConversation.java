package io.github.claw4j.channel.slack;

/**
 * 把"频道 + 线程"压成一个字符串，以及反过来取。
 *
 * <p><b>为什么需要它</b>：{@code ReplySender#send(conversationId, text)} 只有两个参数，
 * 而 Slack 要三个——往哪个频道（{@code C…}）、发到哪条线程（{@code thread_ts}）、发什么。
 * 前四个通道都不需要这个：钉钉与企业微信的"会话"就是一个人，飞书的 {@code chat_id} 一个字段就够。
 * Slack 的群聊里，答案若不挂回原消息下面，三次提问就会在频道里留下六条独立消息
 * （三条回执、三条答案），而每条答案与它的提问在时间线上是分开的。
 *
 * <p>所以编码成 {@code C123456|1515449522.000016}：竖线左边是频道，右边是线程根。
 * 无线程时就是 {@code C123456}（私聊就发顶层）。
 *
 * <p><b>为什么敢这么编</b>：{@code conversationId} 在 Agent 侧没有消费点——它只做两件事，
 * 回推的目标，以及 A05 主动投递登记的收件地址。前者正是这里要的；后者登记下来的地址里
 * 带一个具体的 {@code ts}，作为线程根依然有效（代价是主动投递只能推回那个线程，
 * 要发私聊得先 {@code conversations.open}，第一版不做）。
 *
 * <p>两个字段里都不会出现竖线：Slack 的频道 id 是 {@code C}/{@code D}/{@code G} 加大写字母数字，
 * {@code ts} 是 {@code 1515449522.000016} 这种"秒.微秒"。所以按第一个竖线切就够了，
 * 不需要转义，也不需要长度前缀。
 */
public final class SlackConversation {

    /** 频道与线程根之间的分隔符。改它等于改一个对外约定：已经登记在库里的地址会全部失效。 */
    public static final char SEPARATOR = '|';

    private SlackConversation() {
    }

    /**
     * @param channel  频道或私聊会话的 id，{@code C…} / {@code D…} / {@code G…}
     * @param threadTs 线程根的 {@code ts}；空表示发顶层（私聊就是这么发的）
     */
    public static String of(String channel, String threadTs) {
        String normalizedChannel = channel == null ? "" : channel.strip();
        String normalizedThread = threadTs == null ? "" : threadTs.strip();
        return normalizedThread.isEmpty() ? normalizedChannel : normalizedChannel + SEPARATOR + normalizedThread;
    }

    /** @return 竖线左边那一段；拿不到就返回空串（调用方据此判"这条没地方可发"） */
    public static String channelOf(String conversationId) {
        if (conversationId == null) {
            return "";
        }
        int separator = conversationId.indexOf(SEPARATOR);
        return (separator < 0 ? conversationId : conversationId.substring(0, separator)).strip();
    }

    /** @return 竖线右边那一段；没有线程根就返回空串（空串是合法值，意思是"发顶层"） */
    public static String threadTsOf(String conversationId) {
        if (conversationId == null) {
            return "";
        }
        int separator = conversationId.indexOf(SEPARATOR);
        return separator < 0 ? "" : conversationId.substring(separator + 1).strip();
    }
}

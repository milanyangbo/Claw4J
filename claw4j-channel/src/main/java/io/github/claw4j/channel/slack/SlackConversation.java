package io.github.claw4j.channel.slack;

/**
 * {@code conversationId} 与"频道 + 线程根"之间的互转。
 *
 * <p>{@code ReplySender#send} 只有会话 id 与文本两个参数，Slack 要三样：往哪个频道
 * （{@code C…}）、发到哪条线程（{@code thread_ts}）、发什么。其他通道的会话就是一个人，
 * 一个 id 够用；Slack 的群聊里答案若不挂回原消息，三次提问会在频道里留下六条独立消息
 * （三条回执、三条答案），每条答案与它的提问在时间线上是分开的。
 *
 * <p>编码成 {@code C123456|1515449522.000016}：竖线左边是频道，右边是线程根；
 * 没有线程根时只写频道（私聊就发顶层，例如 {@code C123456}）。
 *
 * <p>按第一个竖线切即可，不需要转义或长度前缀：频道 id 是 {@code C}／{@code D}／{@code G}
 * 加大写字母数字，{@code ts} 是"秒.微秒"，两段都不含竖线。
 *
 * <p>代价：{@code conversationId} 同时是 A05 主动投递登记的收件地址，那里带着一个具体的
 * {@code ts}，所以主动投递只能推回那个线程。要发私聊得先用 {@code conversations.open}
 * 换一个 {@code D} 开头的会话 id，第一版不做。
 */
public final class SlackConversation {

    /** 频道与线程根之间的分隔符。改它等于改一个对外约定：已经登记在库里的地址会全部失效。 */
    public static final char SEPARATOR = '|';

    private SlackConversation() {
    }

    /**
     * 把频道与线程根拼成 {@code conversationId}，两个参数为 {@code null} 时按空串处理。
     *
     * @param channel 频道或私聊会话的 id，{@code C…} / {@code D…} / {@code G…}
     * @param threadTs 线程根的 {@code ts}；空表示发顶层（私聊就是这么发的）
     * @return 拼接结果；{@code threadTs} 为空时只有频道
     */
    public static String of(String channel, String threadTs) {
        String normalizedChannel = channel == null ? "" : channel.strip();
        String normalizedThread = threadTs == null ? "" : threadTs.strip();
        return normalizedThread.isEmpty() ? normalizedChannel : normalizedChannel + SEPARATOR + normalizedThread;
    }

    /**
     * 取 {@code conversationId} 里的频道部分。
     *
     * @param conversationId 频道，或"频道|线程根"；可为 {@code null}
     * @return 竖线左边那一段；拿不到就返回空串，调用方据此判"这条没地方可发"
     */
    public static String channelOf(String conversationId) {
        if (conversationId == null) {
            return "";
        }
        int separator = conversationId.indexOf(SEPARATOR);
        return (separator < 0 ? conversationId : conversationId.substring(0, separator)).strip();
    }

    /**
     * 取 {@code conversationId} 里的线程根。
     *
     * @param conversationId 频道，或"频道|线程根"；可为 {@code null}
     * @return 竖线右边那一段；没有线程根就返回空串（空串是合法值，意思是"发顶层"）
     */
    public static String threadTsOf(String conversationId) {
        if (conversationId == null) {
            return "";
        }
        int separator = conversationId.indexOf(SEPARATOR);
        return separator < 0 ? "" : conversationId.substring(separator + 1).strip();
    }
}

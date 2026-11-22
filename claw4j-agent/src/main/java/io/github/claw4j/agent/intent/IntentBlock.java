package io.github.claw4j.agent.intent;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 命中之后插进这一轮的那段有界提醒（ADR-0036 决定四）。
 *
 * <p>为什么必须有界：它是每轮都可能出现的文字，没有上限时一条写得很长的意图能把用户这句话
 * 挤出窗口，而表现是"模型答非所问"——和提示词预算要防的是同一类事故。
 *
 * <p>措辞刻意像"便签"而不是"系统指令"：它提醒的是用户自己留下的话，不是平台的规则。
 * 混成系统语气，模型会把它当成硬约束，之后每轮都想遵守。
 */
public final class IntentBlock {

    /** 整块最多多少字。 */
    public static final int MAX_CHARS = 600;

    /** 单条最多多少字。 */
    public static final int MAX_ITEM_CHARS = 160;

    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withZone(ZoneId.systemDefault());

    private IntentBlock() {
    }

    /**
     * 拼出这一段。
     *
     * @param fired 刚记上触发的意图；空则返回空串
     * @return 可直接拼在前缀里的文本
     */
    public static String of(List<StandingIntent> fired) {
        if (fired == null || fired.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("（你之前留下过几条提醒，正好对上了）\n");
        for (StandingIntent intent : fired) {
            String line = "- " + intent.createdAt().atZone(ZoneId.systemDefault()).format(DAY)
                    + " 留下的：" + shorten(intent.description()) + "\n";
            if (out.length() + line.length() > MAX_CHARS) {
                break;
            }
            out.append(line);
        }
        return out.toString();
    }

    /**
     * 单条截断，超了加省略号。
     *
     * @param text 原文
     * @return 截断后的文本
     */
    private static String shorten(String text) {
        String flat = text == null ? "" : text.replace('\n', ' ').strip();
        return flat.length() <= MAX_ITEM_CHARS ? flat : flat.substring(0, MAX_ITEM_CHARS) + "…";
    }
}

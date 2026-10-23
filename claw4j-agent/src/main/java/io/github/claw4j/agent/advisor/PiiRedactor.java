package io.github.claw4j.agent.advisor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 出站文本的 PII 打码（ADR-0007）。只做**出站**这一头：
 * 模型与工具拿到的还是原值，不然它没法判断"这个手机号是哪一单的"。
 *
 * <p>认两类：手机号与身份证号。行级与列级脱敏不做——那是数据的授权问题，
 * 由鉴权那一级的表白名单负责（ADR-0007）。
 *
 * <p>两侧都留了原文的头尾：全打码会让"这两个号码是不是同一个"都看不出来，
 * 而客服场景里"尾号 5678 那位"是要能说出口的。
 */
public final class PiiRedactor {

    /**
     * 手机号：1 开头 + 第二位 3-9 + 共 11 位。两侧用"非数字"界定——
     * 不加界定的话，一串 18 位数字会被当成手机号切出来打码，打出来的东西谁都不认识。
     */
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");

    /** 身份证：17 位数字 + 最后一位是数字或 X。同样两侧定界。 */
    private static final Pattern ID_CARD = Pattern.compile("(?<!\\d)\\d{17}[\\dXx](?![\\dXx])");

    private PiiRedactor() {
    }

    /** 手机号保留前 3 后 4，身份证保留前 6 后 4，中间打码。 */
    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String masked = PHONE.matcher(text).replaceAll(m -> m.group().substring(0, 3) + "****" + m.group().substring(7));
        return ID_CARD.matcher(masked).replaceAll(m -> m.group().substring(0, 6) + "********" + m.group().substring(14));
    }

    public static int countHits(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return countMatches(PHONE, text) + countMatches(ID_CARD, text);
    }

    /**
     * 流式下**可以安全吐出的原文前缀**：砍掉末尾那段"还可能是某个 PII 模式的开头"的字符。
     *
     * <p>为什么必须有这一步：分块边界会正好落在一个手机号中间（`138****` 与 `1234`），
     * 逐块各自脱敏两块都匹配不上，原始号码就整个漏出去了——而且漏得毫无规律，
     * 取决于模型那一批吐了多少字。所以宁可把尾巴扣在手里，等下一块来了再一起判。
     *
     * <p>扣的是**原文**的尾巴（不是打码后的），扣下来的部分原样留着继续攒。
     * 因为 PII 模式只由数字与 X 组成，尾巴只要还在"数字或 X"这一段里，
     * 就说明它有可能是一个号码的前半截；一旦出现别的字符，前面那些就已经定了。
     */
    public static String safePrefix(String pending) {
        int cut = pending.length();
        while (cut > 0 && isPatternChar(pending.charAt(cut - 1))) {
            cut--;
        }
        return pending.substring(0, cut);
    }

    /** 号码里可能出现的字符：数字，以及身份证末位的 X。 */
    private static boolean isPatternChar(char c) {
        return (c >= '0' && c <= '9') || c == 'X' || c == 'x';
    }

    private static int countMatches(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }
}

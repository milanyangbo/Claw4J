package io.github.claw4j.agent.intent;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 纯规则的意图匹配（ADR-0036 决定三）。
 *
 * <p>不调模型：一旦这里调模型，延迟压在这一轮上，而且"为什么这次提醒了"变得不可解释。
 *
 * <p>两种匹配方式，因为两种语言不同：
 *
 * <ul>
 *   <li>拉丁词按<b>整词</b>——"customer" 不该命中 "customers"；</li>
 *   <li>含 CJK 的词按<b>字面子串</b>——中文不靠空格分词，整词匹配在中文上等于永远不命中。</li>
 * </ul>
 *
 * <p>每轮最多扫 {@value #MAX_CANDIDATES} 个候选：触发词可以写得很吵，而回复路径不能被它拖住。
 */
public final class IntentMatcher {

    /** 每轮候选上限，与它一致。 */
    public static final int MAX_CANDIDATES = 256;

    /** 触发词的分隔符：英文逗号分号 + 中文逗号分号顿号。 */
    private static final Pattern SEPARATORS = Pattern.compile("[,;，；、]");

    /** 是否含 CJK（汉字、假名、谚文）。 */
    private static final Pattern CJK = Pattern.compile("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}]");

    private IntentMatcher() {
    }

    /**
     * 这一条意图是否被这句话命中。
     *
     * @param intent 候选
     * @param text   用户这一句
     * @return 命中即 true
     */
    public static boolean matches(StandingIntent intent, String text) {
        if (intent == null || text == null || text.isBlank()) {
            return false;
        }
        for (String keyword : split(intent.triggerKeywords())) {
            if (hit(keyword, text)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从候选里挑出命中的。
     *
     * @param candidates 候选（已按作用域筛过）
     * @param text       用户这一句
     * @return 命中的，保持候选顺序
     */
    public static List<StandingIntent> match(List<StandingIntent> candidates, String text) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<StandingIntent> hit = new ArrayList<>();
        int scanned = 0;
        for (StandingIntent candidate : candidates) {
            if (scanned++ >= MAX_CANDIDATES) {
                break;
            }
            if (matches(candidate, text)) {
                hit.add(candidate);
            }
        }
        return List.copyOf(hit);
    }

    /**
     * 一条触发词是否命中。
     *
     * @param keyword 触发词（已 trim）
     * @param text    文本
     * @return 命中即 true
     */
    private static boolean hit(String keyword, String text) {
        if (keyword.isEmpty()) {
            return false;
        }
        if (CJK.matcher(keyword).find()) {
            return text.contains(keyword);
        }
        Pattern word = Pattern.compile("(?i)(?<![\\p{L}\\p{N}_])"
                + Pattern.quote(keyword) + "(?![\\p{L}\\p{N}_])");
        Matcher m = word.matcher(text);
        return m.find();
    }

    /**
     * 把 {@code trigger_keywords} 拆成一条条，去掉空白项。
     *
     * @param keywords 原始串
     * @return 触发词
     */
    static List<String> split(String keywords) {
        if (keywords == null || keywords.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : SEPARATORS.split(keywords)) {
            String trimmed = part.strip();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }
}

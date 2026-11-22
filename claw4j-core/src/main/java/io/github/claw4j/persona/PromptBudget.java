package io.github.claw4j.persona;

import java.util.Objects;

/**
 * 提示词的字节预算（票 03）。
 *
 * <p>为什么要有上限：提示词里有几样东西会随部署增长——部门口径、人设正文、将来的技能说明。
 * 它们超长的表现不是报错，是<b>把用户这句话挤出窗口</b>，然后模型开始忘事、答非所问。
 * 这种事故没有日志，只能靠一个数字先拦住。
 *
 * <p>数值照 OpenClaw 的口径：单段 20000 字符、合计 60000 字符。截断时**一律附一句固定提示**，
 * 且故意不说截了哪一段——说了等于给模型一个"去读那个文件"的具体指令，而它未必有权读。
 *
 * <p>合计超限时先截 {@code variable}：稳定段是人设与口径，砍它等于换了个人；每轮那段只是
 * "今天几号"，砍掉的损失最小。
 */
public final class PromptBudget {

    /** 单段上限，字符数。 */
    public static final int DEFAULT_PART_MAX_CHARS = 20_000;

    /** 合计上限，字符数。 */
    public static final int DEFAULT_TOTAL_MAX_CHARS = 60_000;

    /** 截断时追加的固定提示。内置、不可配，且不说截了哪一段。 */
    public static final String TRUNCATED_NOTICE =
            "\n\n（部分内容超长已被截断；需要完整内容请直接读取原始文件。）";

    private final int partMax;

    private final int totalMax;

    /**
     * @param partMax  单段上限
     * @param totalMax 合计上限
     */
    public PromptBudget(int partMax, int totalMax) {
        if (partMax <= 0 || totalMax <= 0) {
            throw new IllegalArgumentException("上限必须是正数：" + partMax + " / " + totalMax);
        }
        this.partMax = partMax;
        this.totalMax = totalMax;
    }

    /**
     * 默认预算：20000 / 60000。
     *
     * @return 预算
     */
    public static PromptBudget defaults() {
        return new PromptBudget(DEFAULT_PART_MAX_CHARS, DEFAULT_TOTAL_MAX_CHARS);
    }

    /**
     * 按预算裁一份提示词。
     *
     * @param parts 原始两段
     * @return 结果（可能被截断）
     */
    public Result fit(PromptParts parts) {
        Objects.requireNonNull(parts, "parts");
        int original = parts.stable().length() + parts.variable().length();

        String stable = clip(parts.stable(), partMax);
        String variable = clip(parts.variable(), partMax);

        // 合计超了先砍每轮那段；砍完还不够再砍稳定段——顺序不能反。
        int room = totalMax - stable.length();
        if (variable.length() > room) {
            variable = room <= 0 ? "" : clip(variable, room);
        }
        if (stable.length() + variable.length() > totalMax) {
            stable = clip(stable, totalMax - variable.length());
        }
        PromptParts fitted = new PromptParts(stable, variable);
        return new Result(fitted, original, fitted.stable().length() + fitted.variable().length());
    }

    /**
     * 把一段裁到上限，超了加固定提示。
     *
     * @param text 原文
     * @param max  上限
     * @return 裁过的文本
     */
    private static String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        if (max <= 0) {
            return "";
        }
        if (text.length() <= max) {
            return text;
        }
        if (max <= TRUNCATED_NOTICE.length()) {
            return text.substring(0, max);
        }
        return text.substring(0, max - TRUNCATED_NOTICE.length()) + TRUNCATED_NOTICE;
    }

    /**
     * 裁完的结果。
     *
     * @param parts    两段
     * @param original 原始字符数
     * @param fitted   裁完的字符数
     */
    public record Result(PromptParts parts, int original, int fitted) {

        /**
         * @return 是否发生了截断
         */
        public boolean truncated() {
            return fitted < original;
        }
    }
}

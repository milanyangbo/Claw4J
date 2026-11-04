package io.github.claw4j.plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * 一段版本区间，写法就是人读得懂的那几种比较符（P16）。
 *
 * <pre>
 * &gt;=0.1.0 &lt;0.2.0      两个条件同时成立（空格或逗号分隔）
 * &gt;=0.1.0              只有下界
 * &lt;0.2.0               只有上界
 * 0.1.4                 裸版本＝精确匹配
 * *                     任意版本
 * </pre>
 *
 * <p><b>不支持</b> {@code ^0.1.0}、{@code ~0.1}、{@code ||}、Maven 的 {@code [1.0,2.0)}。
 * 两套语法并存时，"这个区间到底什么意思"要靠人记；而 {@code [0.1.0,0.2.0)} 与
 * {@code >=0.1.0 <0.2.0} 在 0.x 上等价，多看一行就能读懂的写法更值得留着。
 *
 * <p><b>看不懂的写法一律报错，不是当成"没条件"</b>。{@code =>0.1.0} 这种笔误如果被放过，
 * 这道闸就白装了——而且它不会以"校验失效"的形态出现，只会在某天以一个
 * {@code NoSuchMethodError} 出现，也就是这张票要消灭的那个形态。
 */
public final class CoreVersionRange {

    /** 任意版本。 */
    public static final CoreVersionRange ANY = new CoreVersionRange("*", List.of());

    private final String raw;

    private final List<Bound> bounds;

    private CoreVersionRange(String raw, List<Bound> bounds) {
        this.raw = raw;
        this.bounds = List.copyOf(bounds);
    }

    /**
     * 读一段区间。
     *
     * @throws IllegalArgumentException 空串、看不懂的比较符、或者某一段读不成版本号
     */
    public static CoreVersionRange parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("区间是空的：任意版本写成 *");
        }
        String stripped = text.strip();
        if ("*".equals(stripped)) {
            return ANY;
        }
        List<Bound> parsed = new ArrayList<>();
        for (String token : stripped.split("[,\\s]+")) {
            if (token.isEmpty()) {
                continue;
            }
            parsed.add(bound(token, stripped));
        }
        if (parsed.isEmpty()) {
            throw new IllegalArgumentException("区间 \"" + stripped + "\" 里没有条件");
        }
        return new CoreVersionRange(stripped, parsed);
    }

    private static Bound bound(String token, String raw) {
        Operator operator = Operator.leading(token);
        String version = operator == null ? token : token.substring(operator.symbol.length());
        if (version.isBlank()) {
            throw new IllegalArgumentException("区间 \"" + raw + "\" 里的 " + operator.symbol + " 后面没有版本号");
        }
        try {
            return new Bound(operator == null ? Operator.EQUAL : operator, CoreVersion.parse(version));
        } catch (IllegalArgumentException broken) {
            throw new IllegalArgumentException("区间 \"" + raw + "\" 里有一段读不成版本：" + version
                    + "（" + broken.getMessage() + "）", broken);
        }
    }

    /** 这个版本落不落在区间里。 */
    public boolean matches(CoreVersion version) {
        for (Bound bound : bounds) {
            if (!bound.admits(version)) {
                return false;
            }
        }
        return true;
    }

    /** 原文，报错时照抄，不重新拼一遍——重新拼会把人写的空格式样改掉，反而对不上配置文件。 */
    public String raw() {
        return raw;
    }

    @Override
    public String toString() {
        return raw;
    }

    private enum Operator {

        GREATER_OR_EQUAL(">="),
        LESS_OR_EQUAL("<="),
        GREATER(">"),
        LESS("<"),
        EQUAL("=");

        private final String symbol;

        Operator(String symbol) {
            this.symbol = symbol;
        }

        /** 按最长前缀认：{@code >=} 必须比 {@code >} 先匹配，否则 "{@code >=0.1.0}" 会被读成 ">{@code =0.1.0}"。 */
        static Operator leading(String token) {
            for (Operator operator : values()) {
                if (token.startsWith(operator.symbol)) {
                    return operator;
                }
            }
            return null;
        }
    }

    private record Bound(Operator operator, CoreVersion version) {

        boolean admits(CoreVersion candidate) {
            int order = candidate.compareTo(version);
            return switch (operator) {
                case GREATER_OR_EQUAL -> order >= 0;
                case LESS_OR_EQUAL -> order <= 0;
                case GREATER -> order > 0;
                case LESS -> order < 0;
                case EQUAL -> order == 0;
            };
        }
    }
}

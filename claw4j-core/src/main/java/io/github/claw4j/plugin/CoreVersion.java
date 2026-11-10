package io.github.claw4j.plugin;

/**
 * 一个三段数字的版本号：{@code major.minor.patch}（P16）。
 *
 * <p>限定符不参与比较，{@code 0.1.0-SNAPSHOT} 与 {@code 0.1.0} 等价。这一条是刻意的：按 Maven
 * 的排序 {@code -SNAPSHOT < 正式版}，照那个语义，同一个 reactor 里构建出来的插件声明
 * {@code >=0.1.0} 会当场校验不过，而它明明就是这一版核心编出来的。这套契约要回答的是"接口在
 * 不在"，不是"这是不是正式版"。
 *
 * <p>反过来，{@code 0.2.0-SNAPSHOT} 也按 {@code 0.2.0} 比，于是会被 {@code >=0.1.0 <0.2.0}
 * 拒掉——这正是要的方向：破坏性版本的预发布版不该被旧插件当成兼容。
 *
 * <p>缺的段按 {@code 0} 补（{@code 0.1} 等于 {@code 0.1.0}）。非数字一律报错，不做"尽力而为"
 * 的猜测：一个读错方向的版本比较，症状是"插件没被拦住"或"插件全被拦住"，两者都比启动失败更
 * 难查。
 */
public record CoreVersion(int major, int minor, int patch) implements Comparable<CoreVersion> {

    /**
     * 读一个版本号。
     *
     * @param text 形如 {@code 0.1.0}、{@code 0.1}、{@code 0.1.0-SNAPSHOT}
     * @return 解析出的版本
     * @throws IllegalArgumentException 空串、段数超过三段、或者某一段不是数字。报错带原文，
     *                                  因为调用方多半是在读一份别人写的配置
     */
    public static CoreVersion parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("版本号是空的");
        }
        String raw = text.strip();
        int dash = raw.indexOf('-');
        String numeric = dash < 0 ? raw : raw.substring(0, dash);
        String[] parts = numeric.split("\\.", -1);
        if (parts.length > 3) {
            throw new IllegalArgumentException("版本号 \"" + raw + "\" 超过三段数字");
        }
        int[] numbers = new int[3];
        for (int i = 0; i < parts.length; i++) {
            numbers[i] = segment(parts[i], raw);
        }
        return new CoreVersion(numbers[0], numbers[1], numbers[2]);
    }

    private static int segment(String part, String raw) {
        // 用 c >= '0' && c <= '9' 而不是 Character.isDigit：后者对全角数字（U+FF10 起）也返回 true，
        // 而 Integer.parseInt 认它——实测 parseInt("１２３") == 123。于是 "０.１.０" 会被静静读成
        // "0.1.0" 并放行：一份打成全角的声明文件不会报错，只会被当成另一个版本。
        // 这类"读进去了、读错了"比读不进去难查得多，所以在这里就把范围收窄到 ASCII。
        if (part.isEmpty() || !part.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw new IllegalArgumentException("版本号 \"" + raw + "\" 里有一段不是数字：" + part);
        }
        try {
            return Integer.parseInt(part);
        } catch (NumberFormatException overflow) {
            throw new IllegalArgumentException("版本号 \"" + raw + "\" 里的数字太大：" + part, overflow);
        }
    }

    @Override
    public int compareTo(CoreVersion other) {
        int byMajor = Integer.compare(major, other.major);
        if (byMajor != 0) {
            return byMajor;
        }
        int byMinor = Integer.compare(minor, other.minor);
        return byMinor != 0 ? byMinor : Integer.compare(patch, other.patch);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}

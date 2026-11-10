package io.github.claw4j.agent;

import org.springframework.ai.tool.toolsearch.index.regex.RegexToolIndex;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 能看懂中文整句的工具索引。
 *
 * <p>{@link RegexToolIndex} 按空白与标点切 query（Spring AI {@code RegexToolIndex}:169），中文整句
 * 没有空格，「回忆以前说过的话」于是成为一个 token，再当子串去描述里找，命中 0 个。不切词的代价
 * 不止命中率：要靠系统提示词让模型自己用空格分词，等于把实现的缺陷写进给模型的话里——一次没照做
 * 就空手而归，而另一句「看不到工具不代表没有这个能力」又不让它说没有，它只能硬凑一个工具。
 *
 * <p>继承而不自己实现 {@code ToolIndex}：接口只有四个方法，但父类已经写好会话隔离、清理、并发
 * （{@code ConcurrentHashMap} + {@code CopyOnWriteArrayList}）与打分排序，这些都不是这里要改的，
 * 只换「query 怎么变成正则」这一步。
 *
 * <p>二元组的代价是真实的：{@code 忆以}、{@code 过的} 这类跨词碎片在别的描述里也会出现，
 * 匹配是 OR，命中一个就算。压住它们靠排序——分数按命中次数累加，噪声命中 1 个排在真命中 4 个
 * 之后，而 {@code maxResults} 默认只有 5。在 pattern 层设阈值只能靠少生成，少生成要词典，
 * 那是另一个方案（ADR-0021 记的「不做」）。
 *
 * <p>同义词表是手写的，工具描述改了它不会跟着改，失败方式很安静：某个说法不再被扩展，命中率掉
 * 一点，没人发现。所以表要短，每一组都得能指到一条用例。
 *
 * <p>它仍然是词面匹配：「上次那个客户」里没有任何二元组出现在描述里，还是命中不了。这条不靠本类
 * 解决，靠评测里的失败回灌让模型换一种说法。
 */
public class ChineseToolIndex extends RegexToolIndex {

    /**
     * 一个永远不匹配的正则，代替父类在空 query 时返回的 {@code .*}。
     *
     * <p>{@code .*} 会把全部工具交出去：模型随便发个空查询就把五个工具全拿走，渐进式披露静默失效，
     * 而日志上一切正常。{@code (?!)}
     * 是一个永远失败的空先行断言，能编译、不匹配任何字符串。
     */
    static final String NEVER_MATCH = "(?!)";

    /** 与父类同一条上限：拼出来超过这个长度就从尾部删 token。 */
    private static final int MAX_PATTERN_LENGTH = 200;

    private static final String PATTERN_PREFIX = "(?i)(";

    /**
     * 同义说法扩展表：整句里出现左词时，把右词一起加进去。
     *
     * <p>它不解决「描述得模糊」，那要靠向量检索。这里只处理用户与工具描述在说同一件事、
     * 只是用词不同的情况。
     */
    private static final Map<String, List<String>> SYNONYMS = Map.of(
            "回忆", List.of("历史", "会话"),
            "想不起", List.of("历史", "会话"),
            "之前说过", List.of("历史", "会话"),
            "退货", List.of("无理由", "售后"),
            "退换", List.of("无理由", "售后"),
            "成交额", List.of("销售", "金额"),
            "卖了多少钱", List.of("销售", "金额"),
            "审批", List.of("权限", "负责人"),
            "谁批", List.of("权限", "负责人"));

    /**
     * 把 query 变成父类那一套形状的正则：{@code (?i)(token1|token2|...)}。
     *
     * <p>不含中文时原样交回父类，英文 query 的行为因此逐字不变——不是保证，是根本没走新代码。
     * 含中文时只把 ASCII 段交给父类处理（见 {@link #tokens(String)}），所以混合 query 里的
     * {@code recall} 也不会被打散成 {@code re|ca|ll}。
     *
     * @param query 用户或模型给出的检索词
     * @return 匹配用的正则；空 query 与切不出 token 时返回 {@link #NEVER_MATCH}
     */
    @Override
    protected String convertQueryToRegexPattern(String query) {
        if (query == null || query.isBlank()) {
            return NEVER_MATCH;
        }
        if (segments(query).stream().noneMatch(Segment::cjk)) {
            return super.convertQueryToRegexPattern(query);
        }
        List<String> tokens = tokens(query);
        if (tokens.isEmpty()) {
            return NEVER_MATCH;
        }
        String pattern = assemble(tokens);
        requireCompilable(pattern, query);
        return pattern;
    }

    /**
     * 把 query 切成 token，已转义，可直接拼进正则。
     *
     * <p>单独抽成可调用的方法，是为了让「切分对不对」能被单独断言：只看「搜出来的第一个是不是它」
     * 说不清变的是切分还是排序，换实现时也定位不到是哪一步变了。
     *
     * @param query 原始检索词
     * @return 去重后的 token，按首次出现顺序
     */
    List<String> tokens(String query) {
        List<String> tokens = new ArrayList<>();
        List<String> raw = new ArrayList<>();
        StringBuilder ascii = new StringBuilder();

        for (Segment segment : segments(query)) {
            if (segment.cjk()) {
                raw.add(segment.text());
                tokens.addAll(bigrams(segment.text()));
            } else {
                raw.add(segment.text().trim());
                ascii.append(' ').append(segment.text());
            }
        }

        // 非中文部分原样交给父类：停用词、长度过滤、转义三条规则还是框架那一份，
        // 不在本类里抄第二遍（抄了就一定会漂）。它返回 "(?i)(a|b)"，这里只取括号里的部分。
        // 结果是 ".*" 时不取：那是"这段里一个可用词都没有"，混进来会让整条 query 匹配全部工具。
        String asciiPattern = super.convertQueryToRegexPattern(ascii.toString());
        if (!".*".equals(asciiPattern) && !NEVER_MATCH.equals(asciiPattern)) {
            tokens.addAll(unwrap(asciiPattern));
        }

        for (Map.Entry<String, List<String>> synonym : SYNONYMS.entrySet()) {
            if (query.contains(synonym.getKey())) {
                synonym.getValue().forEach(add -> tokens.add(escape(add)));
            }
        }

        if (tokens.isEmpty()) {
            // 与父类同一条回落：全被切光时退回未加工的片段。
            // 中文单字 query 走的就是这里——单个汉字切不出二元组，但也不该变成"什么都没匹配到"。
            // 混合的（"退 退款"）仍然会丢掉单字那个，与父类行为一致，这是已知边界。
            raw.stream().filter(text -> !text.isBlank()).forEach(text -> tokens.add(escape(text)));
        }
        return new ArrayList<>(new LinkedHashSet<>(tokens));
    }

    /**
     * 相邻两个 CJK 字一组：{@code 回忆以前说过的话} →
     * {@code 回忆 / 忆以 / 以前 / 前说 / 说过 / 过的 / 的话}。
     *
     * <p>按 code point 切而不是 {@code char}：汉字扩展 B 区（如 U+20000）在 Java 里是代理对，
     * 按 char 切会切出半个字符，拼进正则就是两个私用区字符，命中率与正确性一起坏，且不报错。
     *
     * @param text 一段纯 CJK 文本
     * @return 二元组列表；不足两个字时为空
     */
    private static List<String> bigrams(String text) {
        int[] codePoints = text.codePoints().toArray();
        if (codePoints.length < 2) {
            return List.of();
        }
        List<String> bigrams = new ArrayList<>(codePoints.length - 1);
        for (int i = 0; i + 1 < codePoints.length; i++) {
            bigrams.add(new String(codePoints, i, 2));
        }
        return bigrams;
    }

    /**
     * 按 CJK / 非 CJK 切段。中文标点（{@code ，}、{@code ？}）的 script 是 COMMON，落在非 CJK 段里，
     * 于是它们天然成为词边界。
     *
     * @param query 原始检索词
     * @return 按出现顺序排列的段
     */
    private static List<Segment> segments(String query) {
        List<Segment> segments = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        Boolean current = null;
        for (int i = 0; i < query.length(); ) {
            int codePoint = query.codePointAt(i);
            boolean cjk = isCjk(codePoint);
            if (current != null && current != cjk) {
                segments.add(new Segment(current, buffer.toString()));
                buffer.setLength(0);
            }
            current = cjk;
            buffer.appendCodePoint(codePoint);
            i += Character.charCount(codePoint);
        }
        if (!buffer.isEmpty()) {
            segments.add(new Segment(Boolean.TRUE.equals(current), buffer.toString()));
        }
        return segments;
    }

    /**
     * 中日韩统一表意文字与假名、谚文都算 CJK。这一版面向中文，把日文、韩文一起处理只多三个分支，
     * 省掉一个「以后再说」。
     *
     * @param codePoint 一个 Unicode 码点
     * @return 属于上述任一 script 时为真
     */
    private static boolean isCjk(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }

    /** 从父类的 {@code (?i)(a|b)} 里取出 {@code [a, b]}。 */
    private static List<String> unwrap(String pattern) {
        if (!pattern.startsWith(PATTERN_PREFIX) || !pattern.endsWith(")")) {
            return List.of();
        }
        String inner = pattern.substring(PATTERN_PREFIX.length(), pattern.length() - 1);
        // 只切没被转义的竖线：父类把 token 自己的 | 转成了 \|
        return List.of(inner.split("(?<!\\\\)\\|", -1));
    }

    /** 拼成 {@code (?i)(a|b|c)}，超过上限就从尾部删 token，与父类同一条规则。 */
    private static String assemble(List<String> tokens) {
        List<String> kept = new ArrayList<>(tokens);
        String pattern = PATTERN_PREFIX + String.join("|", kept) + ")";
        while (pattern.length() > MAX_PATTERN_LENGTH && kept.size() > 1) {
            kept.remove(kept.size() - 1);
            pattern = PATTERN_PREFIX + String.join("|", kept) + ")";
        }
        return pattern;
    }

    /**
     * 拼出来先自己编译一次。
     *
     * <p>不这么做的话，拼错的表现是父类 {@code doSearch} 的 catch 把
     * {@code PatternSyntaxException} 变成一次空结果，只留一行 error 日志（Spring AI
     * {@code RegexToolIndex}:242），与「这个词真的没有对应工具」分不开。这里主动抛，
     * 坏掉的是调用方看得见的异常。
     *
     * @param pattern 待编译的正则
     * @param query 原始检索词，只用于异常消息
     * @throws IllegalStateException 拼出来的正则编译不过
     */
    private static void requireCompilable(String pattern, String query) {
        try {
            Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            throw new IllegalStateException("工具检索的查询拼不出合法正则：" + query, e);
        }
    }

    /**
     * 与父类那条逐字一致（Spring AI {@code RegexToolIndex}:220）。
     *
     * <p>中文、假名、谚文都不含正则元字符，所以它对二元组是恒等；它真正兜住的是用户直接敲进来的
     * 英文与符号——那些走的是回落路径，而父类在那条路径上也转义，两边必须同一条规则。
     *
     * @param token 一个待转义的片段
     * @return 可直接拼进正则的片段
     */
    private static String escape(String token) {
        return token.replaceAll("([\\\\.*+?^${}\\[\\]|()])", "\\\\$1");
    }

    /** 一段连续同类字符：{@code cjk} 为真表示这一整段都是中日韩文字。 */
    private record Segment(boolean cjk, String text) {
    }
}

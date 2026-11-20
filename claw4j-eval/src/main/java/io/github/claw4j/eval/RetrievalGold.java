package io.github.claw4j.eval;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一条检索评测的 gold：问什么、参考答案是什么、答案在哪份文档的哪几句话里。
 *
 * <p>证据是一组而不是一条：多跳题（"A2 买延保多少钱"要目录价与延保比例各一处）与跨文档题
 * 天然有两处。只标一处时，第二处缺了也算满分——那正是这类题要抓的失效。
 *
 * <p>绑句子不绑段号：段号随切块参数变，改一次 chunk-size 或切块算法，整份评估集就过期，
 * 而过期的评估集比没有更糟——它给出可信的假分数。
 *
 * @param id        kebab-case，报告与报错靠它定位
 * @param question  问给检索器的那句话
 * @param reference 参考答案，L2 的 LLM 指标要用；L1 不读它，但一起标好，免得以后再补一遍
 * @param spans     答案所在的原文片段，至少一处
 */
public record RetrievalGold(String id, String question, String reference, List<Span> spans) {

    /** yaml 里认得的顶层键。多一个就是写错了（宁可当场报错，也别让打错的键被静默忽略）。 */
    private static final Set<String> KEYS = Set.of("id", "question", "reference", "gold_spans");

    /** {@code gold_spans} 每一项里认得的键。 */
    private static final Set<String> SPAN_KEYS = Set.of("doc", "span");

    /**
     * span 归一化后的最短长度。
     *
     * <p>太短的片段没有区分度：一个"7 天"能命中几十段，算出来的 recall 是运气而不是检索能力。
     */
    private static final int MIN_SPAN_CHARS = 8;

    /**
     * 分片的最短长度。比整句那条松：分片只是"定位锚"，区分度靠 doc 过滤与上下文，
     * 而"计息不滚入本金"这类短句正好 7 个字，卡在 8 上面就等于把跨块那一半丢掉。
     */
    private static final int MIN_PIECE_CHARS = 6;

    /** 分片的标点：中文句读。切块在句中断开时，靠它把 span 拆成几片分别落段。 */
    private static final String PIECE_DELIMITERS = "，。；：、！？";

    /**
     * 一片要在一段里连续重合到多少比例才算命中。
     *
     * <p>0.3 是"有实质重叠"的下限：切块把词劈开时，边界两侧各能覆盖大半；
     * 而只是同主题的两段通常只有几个字的重合，达不到这个比例。
     */
    private static final double COVERAGE_THRESHOLD = 0.3;

    public RetrievalGold {
        id = requireText(id, "gold.id");
        question = requireText(question, "gold.question");
        reference = requireText(reference, "gold.reference");
        if (spans == null || spans.isEmpty()) {
            throw new IllegalArgumentException("gold " + id + " 的 gold_spans 是空的：一条题至少要有一处证据");
        }
        spans = List.copyOf(spans);
        for (Span one : spans) {
            int normalized = normalize(one.span()).length();
            if (normalized < MIN_SPAN_CHARS) {
                throw new IllegalArgumentException("gold " + id + " 的 span 归一化后只有 " + normalized
                        + " 个字符，区分度不够（至少 " + MIN_SPAN_CHARS + "）：" + one.span());
            }
        }
    }

    /**
     * 从一行已解析的 YAML 造一条 gold。
     *
     * <p>{@code gold_spans} 必须是列表，只有一处证据也写成列表：允许"单对象或列表"两种形状，
     * 下一个人就会两种都用，而读代码的人每次都得判断两者有没有区别。
     *
     * @param raw YAML 解析出来的一个条目
     * @return 校验过的 gold
     * @throws IllegalArgumentException 缺字段、键写错、spans 不是列表、span 太短
     */
    public static RetrievalGold from(Map<String, Object> raw) {
        if (raw == null) {
            throw new IllegalArgumentException("gold 是一个空条目");
        }
        String where = "gold " + raw.get("id");
        requireNoUnknown(raw.keySet(), KEYS, where);
        Object value = raw.get("gold_spans");
        if (value == null) {
            throw new IllegalArgumentException(where + " 缺少 gold_spans");
        }
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException(where + " 的 gold_spans 应当是非空列表（只有一处证据也写成列表）："
                    + value);
        }
        List<Span> spans = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            spans.add(span(list.get(i), where + " 的 gold_spans[" + i + "]"));
        }
        return new RetrievalGold(text(raw, "id"), text(raw, "question"), text(raw, "reference"), spans);
    }

    /**
     * 把每处证据落到库里的段上，返回它们的并集。
     *
     * <p>每处证据按中文句读切成几片，每片各落一段：切块会在句中断开（长文档里很常见），
     * 整句 contains 必然落空，而"答案跨块"恰恰是要验的一类。
     *
     * <p>任何一片落不到都抛，不返回部分结果：少一处证据会让 recall 悄悄从 1.0 变成 0.5，
     * 看起来像"检索没召回到"，实际是"gold 抄错了"。两种错要分开，否则评估集会带着错的锚点跑下去。
     *
     * @param chunks 索引全集的段
     * @return 命中的段 id，按证据与片的顺序去重
     * @throws IllegalStateException 有片段一段都没命中
     */
    public List<String> resolveIds(List<Chunk> chunks) {
        Set<String> ids = new LinkedHashSet<>();
        for (Span one : spans) {
            for (String piece : pieces(one.span())) {
                List<String> hits = hitsOf(piece, one.doc(), chunks);
                if (hits.isEmpty()) {
                    throw new IllegalStateException("gold " + id + " 的片段没有落到任何一段：doc=" + one.doc()
                            + "，片段=" + piece + "。切块参数改过，或者 span 抄错了（比对会去掉 Markdown 标记与空白）。"
                            + nearby(one.doc(), chunks));
                }
                ids.addAll(hits);
            }
        }
        return List.copyOf(ids);
    }

    /**
     * 一片能落到哪几段：按"连续重合的字数"判，不要求整片连续出现。
     *
     * <p>为什么不要求整片：切块按 token 硬切，不保证词边界。实测 settlement.md 被切成
     * "…每日万" 与 "分之五计息…"，"万分之五"被劈成两半，整片 contains 在两段上都为假。
     * 要求重合到一定比例，才既能定位到块，又不会因为一次硬切就让整条 gold 崩掉。
     *
     * @param piece  一个分片
     * @param doc    这片应当落在哪份文档
     * @param chunks 索引全集的段
     * @return 命中的段 id
     */
    private List<String> hitsOf(String piece, String doc, List<Chunk> chunks) {
        String needle = normalize(piece);
        int needed = Math.max(MIN_PIECE_CHARS, (int) Math.ceil(needle.length() * COVERAGE_THRESHOLD));
        return chunks.stream()
                .filter(chunk -> doc.equals(chunk.doc()))
                .filter(chunk -> longestCommonRun(needle, normalize(chunk.text())) >= needed)
                .map(Chunk::id)
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * 两个串的最长公共子串长度。
     *
     * <p>用滚动数组的经典 DP：只留上一行，空间是 O(较短串)。
     * 语料里一段 800 字、一片 20 来字，这点开销比"读不懂为什么落不到"便宜得多。
     *
     * @param left  一个串
     * @param right 另一个串
     * @return 最长连续重合的字数
     */
    static int longestCommonRun(String left, String right) {
        if (left.isEmpty() || right.isEmpty()) {
            return 0;
        }
        int[] previous = new int[right.length() + 1];
        int best = 0;
        for (int i = 1; i <= left.length(); i++) {
            int[] current = new int[right.length() + 1];
            char c = left.charAt(i - 1);
            for (int j = 1; j <= right.length(); j++) {
                if (c == right.charAt(j - 1)) {
                    current[j] = previous[j - 1] + 1;
                    best = Math.max(best, current[j]);
                }
            }
            previous = current;
        }
        return best;
    }

    /**
     * 把 span 按中文句读切成若干片。
     *
     * <p>没有标点、或者切出来的片都太短时退回整句：单句 gold 走的就是这条路。
     *
     * @param span 原句片段
     * @return 至少一片
     */
    static List<String> pieces(String span) {
        List<String> pieces = new ArrayList<>(2);
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < span.length(); i++) {
            char c = span.charAt(i);
            if (PIECE_DELIMITERS.indexOf(c) >= 0) {
                addPiece(pieces, current);
                current.setLength(0);
            }
            else {
                current.append(c);
            }
        }
        addPiece(pieces, current);
        if (pieces.isEmpty()) {
            pieces.add(span);
        }
        return pieces;
    }

    private static void addPiece(List<String> pieces, StringBuilder current) {
        String piece = current.toString().strip();
        if (normalize(piece).length() >= MIN_PIECE_CHARS) {
            pieces.add(piece);
        }
    }

    /**
     * 报错时附上该文档每段的边界。
     *
     * <p>片段落不到只有两种原因：span 抄错了，或者块边界正好切在这句话中间。
     * 两种都得看到实际切块结果才能区分，所以把每段的开头与结尾一起报出来。
     *
     * @param doc    落不到的那份文档
     * @param chunks 索引全集的段
     * @return 给人看的诊断，接在异常消息后面
     */
    private static String nearby(String doc, List<Chunk> chunks) {
        List<Chunk> sameDoc = chunks.stream().filter(chunk -> doc.equals(chunk.doc())).toList();
        if (sameDoc.isEmpty()) {
            return "\n（索引里没有 " + doc + " 这份文档）";
        }
        StringBuilder out = new StringBuilder("\n该文档共 ").append(sameDoc.size()).append(" 段：");
        for (Chunk chunk : sameDoc) {
            out.append("\n  #").append(chunk.index())
                    .append(" 长度 ").append(chunk.text().length())
                    .append(" 开头「").append(clip(chunk.text(), false))
                    .append("」结尾「").append(clip(chunk.text(), true)).append("」");
        }
        return out.toString();
    }

    private static String clip(String text, boolean fromEnd) {
        int window = 40;
        if (text.length() <= window) {
            return text;
        }
        return fromEnd ? text.substring(text.length() - window) : text.substring(0, window);
    }

    /**
     * 比对用的归一化：去掉 Markdown 强调标记与所有空白。
     *
     * <p>只做这两件。大小写与全半角不动：动多了会把 {@code 100mm} 与 {@code １００ｍｍ} 判成同一段，
     * 而语料里只有前者，多做的归一化只会让"看起来匹配"变得不可解释。
     *
     * @param text 原文，可以为 null
     * @return 去掉标记与空白后的文本；null 当空串
     */
    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '*' || c == '_' || c == '`' || Character.isWhitespace(c)) {
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    private static void requireNoUnknown(Set<String> actual, Set<String> known, String where) {
        Set<String> unknown = new LinkedHashSet<>(actual);
        unknown.removeAll(known);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(where + " 有认不出的键：" + unknown + "（认得的是 " + known + "）");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少 " + field);
        }
        return value.strip();
    }

    @SuppressWarnings("unchecked")
    private static Span span(Object item, String where) {
        if (!(item instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(where + " 应当是 doc/span 两个键：" + item);
        }
        Map<String, Object> block = (Map<String, Object>) map;
        requireNoUnknown(block.keySet(), SPAN_KEYS, where);
        return Span.of(text(block, "doc"), text(block, "span"));
    }

    private static String text(Map<String, Object> raw, String key) {
        Object value = raw.get(key);
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 一处证据：哪份文档的哪句原话。
     *
     * <p>{@code doc} 不只是出处，它参与匹配：同一句话在两份文档里出现时（现行版与归档版），
     * 靠它决定命中的是哪一份，否则归档版会排在前面把现行版顶掉。
     *
     * @param doc  文档相对路径
     * @param span 答案所在的原句片段，比对时做归一化
     */
    public record Span(String doc, String span) {

        public Span {
            doc = requireText(doc, "gold_spans.doc");
            span = requireText(span, "gold_spans.span");
        }

        /**
         * 按文档与原句造一处证据。
         *
         * @param doc  文档相对路径
         * @param span 原句片段
         * @return 校验过非空的一处证据
         */
        public static Span of(String doc, String span) {
            return new Span(doc, span);
        }
    }

    /**
     * 库里的一段。
     *
     * @param id     {@code 文档路径#块号}，Ragas 的 id 型指标认这个；不用向量表的 uuid，
     *               它重建索引就换一批，而"这段来自哪份文档的第几块"必须稳定
     * @param doc    相对文档目录的路径
     * @param index  文档内块号，从 1 开始
     * @param text   段落正文
     */
    public record Chunk(String id, String doc, int index, String text) {

        public Chunk {
            id = requireText(id, "chunk.id");
            doc = requireText(doc, "chunk.doc");
            text = text == null ? "" : text;
        }

        /**
         * 按文档与块号造一段。
         *
         * @param doc   相对路径
         * @param index 块号，从 1 开始
         * @param text  正文
         * @return id 为 {@code doc#index} 的段
         */
        public static Chunk of(String doc, int index, String text) {
            return new Chunk(doc + "#" + index, doc, index, text);
        }
    }
}

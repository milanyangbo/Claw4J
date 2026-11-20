package io.github.claw4j.eval;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一条检索评测的 gold：问什么、参考答案是什么、答案在哪份文档的哪句话里。
 *
 * <p>绑句子不绑段号：段号随切块参数变，改一次 chunk-size 或切块算法，整份评估集就过期，
 * 而过期的评估集比没有更糟——它给出可信的假分数。比对前把 Markdown 标记与空白归一化掉，
 * 因为文档里的关键句常带 {@code **} 强调，而 gold 在 yaml 里不会写星号。
 *
 * <p>解析时拒掉不认识的键：{@code gold_span} 敲成 {@code goldspan} 时，宽松的解析器会把这条
 * 用例变成什么都不判，而它跑起来是绿的。
 *
 * @param id        kebab-case，报告与报错靠它定位
 * @param question  问给检索器的那句话
 * @param reference 参考答案，L2 的 LLM 指标要用；L1 不读它，但一起标好，免得以后再补一遍
 * @param doc       文档相对路径，用于"同一句话在两份文档出现"时消歧
 * @param span      答案所在的原句片段，比对时做归一化
 */
public record RetrievalGold(String id, String question, String reference, String doc, String span) {

    /** yaml 里认得的顶层键。多一个就是写错了（宁可当场报错，也别让打错的键被静默忽略）。 */
    private static final Set<String> KEYS = Set.of("id", "question", "reference", "gold_span");

    /** {@code gold_span} 里认得的键。 */
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
        doc = requireText(doc, "gold.gold_span.doc");
        span = requireText(span, "gold.gold_span.span");
        int normalized = normalize(span).length();
        if (normalized < MIN_SPAN_CHARS) {
            throw new IllegalArgumentException("gold " + id + " 的 span 归一化后只有 " + normalized
                    + " 个字符，区分度不够（至少 " + MIN_SPAN_CHARS + "）：" + span);
        }
    }

    /**
     * 从一行已解析的 YAML 造一条 gold。
     *
     * @param raw YAML 解析出来的一个条目
     * @return 校验过的 gold
     * @throws IllegalArgumentException 缺字段、键写错、span 太短
     */
    public static RetrievalGold from(Map<String, Object> raw) {
        if (raw == null) {
            throw new IllegalArgumentException("gold 是一个空条目");
        }
        String where = "gold " + raw.get("id");
        requireNoUnknown(raw.keySet(), KEYS, where);
        Map<String, Object> block = block(raw, "gold_span", where);
        requireNoUnknown(block.keySet(), SPAN_KEYS, where + " 的 gold_span");
        return new RetrievalGold(
                text(raw, "id"),
                text(raw, "question"),
                text(raw, "reference"),
                text(block, "doc"),
                text(block, "span"));
    }

    /**
     * 把 span 落到库里的段上，返回它们的 id。
     *
     * <p>span 按中文句读切成几片，每片各落一段：切块会在句中断开（长文档里很常见），
     * 整句 contains 必然落空，而"答案跨块"恰恰是要验的一类。并集就是这条 gold 的 reference ids。
     *
     * <p>任何一片落不到都抛，不返回空列表：空 id 会让 Ragas 把这一条算成 recall 0，
     * 看起来像"检索没召回到"，实际是"gold 抄错了"。两种错要分开，否则评估集会带着错的锚点跑下去。
     *
     * @param chunks 索引全集的段
     * @return 命中的段 id，按片顺序去重
     * @throws IllegalStateException 有片段一段都没命中
     */
    public List<String> resolveIds(List<Chunk> chunks) {
        Set<String> ids = new LinkedHashSet<>();
        for (String piece : pieces(span)) {
            List<String> hits = hitsOf(piece, chunks);
            if (hits.isEmpty()) {
                throw new IllegalStateException("gold " + id + " 的片段没有落到任何一段：doc=" + doc
                        + "，片段=" + piece + "。切块参数改过，或者 span 抄错了（比对会去掉 Markdown 标记与空白）。"
                        + nearby(chunks));
            }
            ids.addAll(hits);
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
     * @param chunks 索引全集的段
     * @return 命中的段 id
     */
    private List<String> hitsOf(String piece, List<Chunk> chunks) {
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
     * @param chunks 索引全集的段
     * @return 给人看的诊断，接在异常消息后面
     */
    private String nearby(List<Chunk> chunks) {
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
    private static Map<String, Object> block(Map<String, Object> raw, String key, String where) {
        Object value = raw.get(key);
        if (value == null) {
            throw new IllegalArgumentException(where + " 缺少 " + key);
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(where + " 的 " + key + " 应当是 doc/span 两个键：" + value);
        }
        return (Map<String, Object>) map;
    }

    private static String text(Map<String, Object> raw, String key) {
        Object value = raw.get(key);
        return value == null ? null : String.valueOf(value);
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

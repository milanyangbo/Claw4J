package io.github.claw4j.text;

import java.util.ArrayList;
import java.util.List;

/**
 * 中文的"分词"：切成 2-gram，交给 Postgres 的 {@code simple} 分词器。
 *
 * <p><b>为什么是自己切，而不是让库去切</b>：Postgres 的默认分词器对中文失效。
 * 实测（{@code pgvector/pgvector:pg16}）：
 *
 * <pre>
 * select to_tsvector('simple', '华东区的退货政策怎么规定的');
 * -- '华东区的退货政策怎么规定的':1        ← 整句一个 token
 * </pre>
 *
 * 于是 {@code @@ to_tsquery('华东')} 永远为假。而扩展那几条路都不通：镜像里没有
 * {@code pg_bigm} / {@code zhparser}（要自建镜像），{@code pg_trgm} 的 {@code similarity}
 * 对一段长文档只有 0.083（分母随文档变长而变大，阈值没法跨长度统一）。
 *
 * <p>{@code simple} 分词器的规则简单到没有歧义：按非词字符切，中文汉字算词字符。
 * 所以这里把文本切成空格分隔的 2-gram，它就能得到一堆正确的 token——实测 12 个。
 *
 * <p><b>为什么不引中文分词库</b>（本地仓库里 {@code jieba-analysis} 与 {@code hanlp} 都在）：
 * 关键词通道的价值在**精确匹配**——{@code SKU-2024}、{@code 华东一区}、人名、单号。
 * 分词器对词典里没有的词会切成单字或丢掉，而那正是最需要字面命中时。bigram 不判断
 * "这是不是一个词"，只保证任何 2 字以上的连续片段都能被匹配到。
 *
 * <p><b>召回的边界</b>：bigram 的召回是分词召回的超集（任何 2 字以上的词都由 bigram 组成），
 * 多出来的是跨词边界的碎片（"的退"、"策怎"）。碎片由两件事挡住：查询侧的 AND 语义
 * （每个 bigram 都要出现）与向量通道。精度靠融合，不靠切分。
 *
 * <p><b>中英混排</b>：连续的 ASCII 字母数字整段作为一个 token（{@code SKU-2024} 切成
 * {@code sku} 与 {@code 2024}），不切 bigram——把 {@code SKU} 切成 {@code sk} / {@code ku}
 * 会让它和"思考"(sk) 之类的噪声混在一起。中文段落里夹一个英文词是常见写法，两条规则各管一段。
 */
public final class ChineseText {

    private ChineseText() {
    }

    /**
     * 切出来的 token 列表。
     *
     * <p>顺序是原文顺序，重复**不去重**：{@code to_tsvector} 自己会记位置与词频，
     * 去重会把它变成"一段话说两遍"和"说一遍"一样。
     */
    public static List<String> tokens(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return tokens;
        }
        StringBuilder cjk = new StringBuilder();
        StringBuilder word = new StringBuilder();
        int index = 0;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            int width = Character.charCount(codePoint);
            if (isCjk(codePoint)) {
                flushWord(word, tokens);
                cjk.appendCodePoint(codePoint);
            }
            else if (Character.isLetterOrDigit(codePoint)) {
                flushCjk(cjk, tokens);
                word.appendCodePoint(codePoint);
            }
            else {
                // 标点、空白、符号都是分隔符：它们既不是中文也不是"词"。
                flushCjk(cjk, tokens);
                flushWord(word, tokens);
            }
            index += width;
        }
        flushCjk(cjk, tokens);
        flushWord(word, tokens);
        return tokens;
    }

    /**
     * 存进 {@code bigrams} 列的形状：空格分隔。
     *
     * <p>空格是必须的——它正是 {@code simple} 分词器的分隔符，也是整件事的接缝所在。
     */
    public static String bigrams(String text) {
        return String.join(" ", tokens(text));
    }

    /**
     * 查询串：{@code to_tsquery('simple', ?)} 的入参。**空串要由调用方处理**。
     *
     * <p>两处与"存"不同：
     * <ul>
     *   <li><b>单字加 {@code :*}</b>。一个汉字切不出 bigram，于是它一条都命中不了；
     *       而空 tsquery 的行为是"一条都不命中"（实测 {@code @@} 为 {@code false}），
     *       不是报错——"搜一个字"与"知识库没这段"在结果上完全一样，那是最难查的一种失败。</li>
     *   <li><b>用 {@code &} 连接</b>：多词必须同时出现。{@code plainto_tsquery} 也是这个语义，
     *       但这里要拼前缀匹配，只能走 {@code to_tsquery} 的语法。</li>
     * </ul>
     *
     * <p>token 只可能含字母数字与汉字（构造时如此），所以不需要再转义 {@code & | ! ( ) : *}——
     * 那些字符在 {@link #tokens} 里已经是分隔符，不会出现在 token 内部。
     *
     * @return 形如 {@code 退货 & 货政 & 政策}；文本里没有任何可检索内容时返回空串
     */
    public static String tsQuery(String query) {
        List<String> tokens = tokens(query);
        if (tokens.isEmpty()) {
            return "";
        }
        StringBuilder expression = new StringBuilder();
        for (String token : tokens) {
            if (!expression.isEmpty()) {
                expression.append(" & ");
            }
            expression.append(token);
            if (token.codePointCount(0, token.length()) == 1) {
                expression.append(":*");
            }
        }
        return expression.toString();
    }

    /** 是否属于中日韩表意文字（含扩展 A 与兼容区）。假名不在这里：它按"词"整段处理。 */
    private static boolean isCjk(int codePoint) {
        return (codePoint >= 0x4E00 && codePoint <= 0x9FFF)
                || (codePoint >= 0x3400 && codePoint <= 0x4DBF)
                || (codePoint >= 0xF900 && codePoint <= 0xFAFF);
    }

    /**
     * 把一段连续的汉字按 2 个字符滑窗切完。
     *
     * <p>单字段保留该字本身：它命中不了任何 bigram，但至少能进索引，
     * 被前缀查询（{@code 退:*}）找到。
     */
    private static void flushCjk(StringBuilder run, List<String> tokens) {
        if (run.isEmpty()) {
            return;
        }
        String text = run.toString();
        run.setLength(0);
        int[] codePoints = text.codePoints().toArray();
        if (codePoints.length == 1) {
            tokens.add(new String(codePoints, 0, 1));
            return;
        }
        for (int i = 0; i + 1 < codePoints.length; i++) {
            tokens.add(new String(codePoints, i, 2));
        }
    }

    /** 连续的非 CJK 字母数字整段作为一个 token，小写化。 */
    private static void flushWord(StringBuilder run, List<String> tokens) {
        if (run.isEmpty()) {
            return;
        }
        tokens.add(run.toString().toLowerCase(java.util.Locale.ROOT));
        run.setLength(0);
    }
}

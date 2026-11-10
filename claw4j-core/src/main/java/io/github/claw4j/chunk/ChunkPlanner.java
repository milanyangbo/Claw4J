package io.github.claw4j.chunk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 语义切块的纯函数部分：切句、分组、找断点、成块。
 *
 * <p>它放在 {@code claw4j-core} 是因为算法只吃字符串与向量、只吐下标，不依赖 Spring AI，
 * 也不依赖任何 tokenizer。放在这里意味着能被单独测、单独拿走用；框架那一层
 * （{@code SemanticTextSplitter}）只负责算向量与超长兜底。
 *
 * <p>算法来自 LangChain 的 {@code SemanticChunker}（{@code langchain_experimental.text_splitter}，
 * 原始出处是 Greg Kamradt 的 notebook，LlamaIndex 的 {@code SemanticSplitterNodeParser} 同款）。
 * 三个参数照它的默认值：{@link #BUFFER_SIZE}（{@code buffer_size=1}）、
 * {@link #BREAKPOINT_PERCENTILE}（{@code percentile} 95），以及 {@code min_chunk_size} 缺省为
 * {@code None}，也就是不合并。
 *
 * <p>与它不同的只有两处。切句正则：它默认 {@code (?<=[.?!])\s+}，要求句末标点后面还有空白，
 * 中文没有词间空格，整段切不开，这里换成中文句末标点加换行。句首转折词：开源实现没有这条规则，
 * 它是产品要求，只认句首（句中引用"然而"不触发），词表见 {@link #TRANSITIONS}。
 *
 * <p>数值口径：距离 = 1 - 余弦相似度；分位数用线性插值（与 numpy 的 {@code percentile} 一致）；
 * 断点是距离严格大于阈值。最后这条很关键：整篇距离都一样时阈值等于距离本身，于是一个断点都
 * 没有——这正是"同主题文档不要硬切"想要的行为。
 */
public final class ChunkPlanner {

    /**
     * 句子组的窗口：每句与前后各 {@value} 句拼成一组再算向量（LangChain 的
     * {@code buffer_size}）。
     */
    public static final int BUFFER_SIZE = 1;

    /** 断点阈值：相邻组距离的百分位（LangChain 的 {@code percentile} 缺省 95）。 */
    public static final double BREAKPOINT_PERCENTILE = 95.0;

    /**
     * 短于这个字符数的片段并回前一句。
     *
     * <p>挡的是"然而。"这种被句号切出来的碎句——它单独成句会让转折词判定与向量都失去意义。
     * 这不是按字数切块：它是切句这一步的碎片处理，块边界仍然只看语义。
     */
    private static final int MIN_SENTENCE_CHARS = 8;

    /**
     * 句首转折 / 转场词。
     *
     * <p>按少切配：只放明确的转折与转场，不放大批"第一步/第二步"这种次序词。
     * 已知误判：句首"同时"在"同时满足三个条件"里是并列条件而不是转场；误切的代价是块变碎，
     * 比漏切（语义混块）轻。
     */
    private static final List<String> TRANSITIONS = List.of(
            "然而", "但是", "不过", "可是", "另一方面", "反过来说", "相比之下", "与此相反",
            "此外", "另外", "同时", "与此同时", "接下来", "最后", "总之", "综上", "因此", "所以");

    /**
     * 句末：中文/全角标点，或换行。英文的 {@code .} 不在里面——{@code v2.0}、{@code 3.5%}
     * 不该被切成三句；英文句子靠换行与 {@code !?} 断开即可。
     */
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[。！？；…!?;])|\\n+");

    /** 句首要去掉的装饰：列表符号、标题井号、引用、引号、括号。 */
    private static final Pattern LEADING_DECORATION = Pattern.compile("^[\\s\\-*+•#>\"'“”‘’（(【\\[]+");

    private ChunkPlanner() {
    }

    /**
     * 切句：句末标点或换行断开，去掉空白片段，短于 {@value #MIN_SENTENCE_CHARS} 字的并回前一句。
     *
     * @param text 待切的全文，可以为 {@code null}
     * @return 句子列表；整篇没有可切内容时返回空列表（调用方据此产出 0 块）
     */
    public static List<String> sentences(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> sentences = new ArrayList<>();
        for (String piece : SENTENCE_END.split(text)) {
            String sentence = piece.strip();
            if (sentence.isEmpty()) {
                continue;
            }
            if (!sentences.isEmpty() && sentence.codePointCount(0, sentence.length()) < MIN_SENTENCE_CHARS) {
                int last = sentences.size() - 1;
                sentences.set(last, sentences.get(last) + sentence);
                continue;
            }
            sentences.add(sentence);
        }
        return List.copyOf(sentences);
    }

    /**
     * 句子组：第 i 组 = 第 {@code i-buffer} 到 {@code i+buffer} 句拼起来。
     *
     * <p>分组的理由：单句向量对噪声敏感（"此外"这种短句几乎不携带主题），拼上邻居再算距离，
     * 断点才落在话题真的变了的位置。组数与句数相同，所以组间距离可以一一对应到"第几句之后断"。
     *
     * @param sentences  {@link #sentences} 的返回值
     * @param bufferSize 前后各取几句；负数按 0 处理
     * @return 与 {@code sentences} 等长的组列表
     */
    public static List<String> groups(List<String> sentences, int bufferSize) {
        int buffer = Math.max(0, bufferSize);
        List<String> groups = new ArrayList<>(sentences.size());
        for (int i = 0; i < sentences.size(); i++) {
            int from = Math.max(0, i - buffer);
            int to = Math.min(sentences.size(), i + buffer + 1);
            groups.add(String.join("", sentences.subList(from, to)));
        }
        return List.copyOf(groups);
    }

    /**
     * 距离断点：相邻两组余弦距离严格大于"距离的第 P 百分位"处断开。
     *
     * @param groupVectors 每组的向量，顺序与 {@link #groups} 一致
     * @param percentile   0~100，超出范围按端点截断
     * @return 新块起始句的下标（升序）；没有断点时返回空列表
     */
    public static List<Integer> distanceBreakpoints(List<float[]> groupVectors, double percentile) {
        if (groupVectors == null || groupVectors.size() < 2) {
            return List.of();
        }
        double[] distances = new double[groupVectors.size() - 1];
        for (int i = 0; i < distances.length; i++) {
            distances[i] = 1 - cosine(groupVectors.get(i), groupVectors.get(i + 1));
        }
        double threshold = percentile(distances, percentile);
        List<Integer> breaks = new ArrayList<>();
        for (int i = 0; i < distances.length; i++) {
            if (distances[i] > threshold) {
                breaks.add(i + 1);
            }
        }
        return List.copyOf(breaks);
    }

    /**
     * 叠加"句首转折词"断点：第 i 句以转折 / 转场词开头时，i 就是一个新块的起点。
     *
     * <p>典型调用是 {@code withTransitionBreaks(sentences, distanceBreakpoints(...))}；
     * 向量算不出来时传空列表，规则照常生效。
     *
     * @param sentences 句子列表，下标要与 {@code breaks} 同源
     * @param breaks    已有的断点，可以为 {@code null}
     * @return 合并后的断点，升序且不含 0
     */
    public static List<Integer> withTransitionBreaks(List<String> sentences, List<Integer> breaks) {
        Set<Integer> merged = new LinkedHashSet<>();
        if (breaks != null) {
            merged.addAll(breaks);
        }
        for (int i = 1; i < sentences.size(); i++) {
            if (startsWithTransition(sentences.get(i))) {
                merged.add(i);
            }
        }
        merged.remove(0);
        return List.copyOf(new java.util.TreeSet<>(merged));
    }

    /**
     * 按断点切片，返回每块的句子下标区间 {@code [start, end)}。
     *
     * <p>不做最小块合并：LangChain 的 {@code min_chunk_size} 缺省是 {@code None}，也就是不合并，
     * 这里照默认——加一个"多短算短"的字符阈值，正好是这一层想去掉的东西。代价是连续转折会切出
     * 很短的块，那是规则的结果。
     *
     * @param sentences 句子列表
     * @param breaks    断点，可以为 {@code null}；越界或非递增的项被忽略
     * @return 首尾相接、覆盖全部句子的区间
     */
    public static List<int[]> blocks(List<String> sentences, List<Integer> breaks) {
        if (sentences.isEmpty()) {
            return List.of();
        }
        List<int[]> blocks = new ArrayList<>();
        int start = 0;
        for (Integer point : new java.util.TreeSet<>(breaks == null ? List.<Integer>of() : breaks)) {
            if (point <= start || point >= sentences.size()) {
                continue;
            }
            blocks.add(new int[] {start, point});
            start = point;
        }
        blocks.add(new int[] {start, sentences.size()});
        return List.copyOf(blocks);
    }

    /**
     * 这一句是不是以转折 / 转场词开头。
     *
     * <p>只认句首：句中出现的"然而"（引用、举例、否定句）不算。把转折词当子串匹配，
     * 会把"政策里提到的『然而』并不存在"这类句子一起切碎。
     *
     * @param sentence 一句文本，可以为 {@code null}
     * @return 去掉列表符号、引号等前置装饰后，以 {@link #TRANSITIONS} 里的词开头时为 {@code true}
     */
    public static boolean startsWithTransition(String sentence) {
        if (sentence == null) {
            return false;
        }
        String stripped = LEADING_DECORATION.matcher(sentence).replaceFirst("");
        for (String transition : TRANSITIONS) {
            if (stripped.startsWith(transition)) {
                return true;
            }
        }
        return false;
    }

    /** 线性插值的百分位（与 numpy 的 {@code percentile} 同口径）。空数组返回 {@link Double#NaN}。 */
    static double percentile(double[] values, double percentile) {
        if (values.length == 0) {
            return Double.NaN;
        }
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        double rank = Math.min(100.0, Math.max(0.0, percentile)) / 100.0 * (sorted.length - 1);
        int lower = (int) Math.floor(rank);
        int upper = (int) Math.ceil(rank);
        if (lower == upper) {
            return sorted[lower];
        }
        return sorted[lower] + (sorted[upper] - sorted[lower]) * (rank - lower);
    }

    private static double cosine(float[] left, float[] right) {
        if (left == null || right == null || left.length != right.length || left.length == 0) {
            return 0;
        }
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        if (leftNorm == 0 || rightNorm == 0) {
            // 零向量没有方向可言：当成"完全不像"，让它成为断点而不是悄悄算成 1。
            return 0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }
}

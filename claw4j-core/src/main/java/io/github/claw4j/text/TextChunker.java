package io.github.claw4j.text;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 把一份文档切成若干块，每块是检索与出处的最小单位。
 *
 * <p><b>按 markdown 段落切，不做语义切块</b>：段落是作者自己划的语义边界，比任何
 * 按字符数的启发式都准。累加到上限就断开——一块太大时，向量会把好几件事平均成一个点，
 * 于是"哪件事都不太像"。
 *
 * <p><b>不做重叠窗口</b>：重叠的唯一收益是"一句话被切在两块中间"时两边都能命中。
 * 代价是同一段文字在库里存两份，于是检索结果里会出现两条几乎一样的内容，而出处指向两个
 * 不同的块——"这段到底在哪"变得更难回答。这一版选清楚的出处，代价是跨界的那一句要靠模型
 * 再检索一次（出处指回原文，它能自己接上）。
 *
 * <p><b>硬切只在单段超长时发生</b>：一个 3000 字的段落没有内部空行，只能按长度切。
 * 切的时候尽量不切断代理对（emoji 这类）——按 code point 数而不是 char 数。
 */
public final class TextChunker {

    /** 块的最大字符数。默认值写在 {@code RagProperties} 上，这里只要一个能跑的默认。 */
    public static final int DEFAULT_MAX_CHARS = 800;

    /** 空行分段的判据：一行里只有空白也算空行。 */
    private static final Pattern PARAGRAPH_BREAK = Pattern.compile("\\n\\s*\\n");

    private TextChunker() {
    }

    /**
     * 一块文档。
     *
     * @param ordinal 文档内第几块，**从 1 开始**（出处的第二段就是这个数，人读的时候从 1 数）
     * @param text    原文片段，已去掉首尾空白
     */
    public record Chunk(int ordinal, String text) {
    }

    public static List<Chunk> split(String text) {
        return split(text, DEFAULT_MAX_CHARS);
    }

    public static List<Chunk> split(String text, int maxChars) {
        if (maxChars <= 0) {
            throw new IllegalArgumentException("块长度必须为正：" + maxChars);
        }
        List<Chunk> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }
        StringBuilder current = new StringBuilder();
        for (String paragraph : PARAGRAPH_BREAK.split(text.strip())) {
            String piece = paragraph.strip();
            if (piece.isEmpty()) {
                continue;
            }
            // 单段自己就超长：先把攒着的吐出去，再把这个段按长度硬切。
            if (piece.codePointCount(0, piece.length()) > maxChars) {
                flush(current, chunks);
                for (String slice : hardSplit(piece, maxChars)) {
                    chunks.add(new Chunk(chunks.size() + 1, slice));
                }
                continue;
            }
            // 加上这一段会超：先吐出去，再从这一段重新开始。
            if (current.codePointCount(0, current.length()) + piece.codePointCount(0, piece.length())
                    + 1 > maxChars) {
                flush(current, chunks);
            }
            if (!current.isEmpty()) {
                current.append('\n');
            }
            current.append(piece);
        }
        flush(current, chunks);
        return chunks;
    }

    private static void flush(StringBuilder current, List<Chunk> chunks) {
        String text = current.toString().strip();
        current.setLength(0);
        if (!text.isEmpty()) {
            chunks.add(new Chunk(chunks.size() + 1, text));
        }
    }

    /**
     * 按 code point 数硬切。
     *
     * <p>不按 {@code char} 数：中文在基本区是 1 个 char，但 emoji 与扩展区汉字是 2 个，
     * 按 char 切会把它们劈成半个代理对——那是一个在库里静默变成问号的字符。
     */
    private static List<String> hardSplit(String piece, int maxChars) {
        List<String> slices = new ArrayList<>();
        int start = 0;
        while (start < piece.length()) {
            int end = piece.offsetByCodePoints(start,
                    Math.min(maxChars, piece.codePointCount(start, piece.length())));
            slices.add(piece.substring(start, end).strip());
            start = end;
        }
        return slices;
    }
}

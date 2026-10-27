package io.github.claw4j.channel.feishu;

import java.util.ArrayList;
import java.util.List;

/**
 * 把一段回答切成若干条可以分开发送的文本。
 *
 * <p>为什么要切：飞书对单条文本消息有长度上限，超了整条发送失败——用户看到的是"机器人没回"。
 * 但这个类关心的不是那个上限数字，而是<b>在哪儿切</b>：按字数硬切会把 {@code ```} 围栏切成两半，
 * 两段都渲染成一堆裸星号；把表格切在行中间，第二段就丢表头，看着像另一张表。
 *
 * <p>所以切法是两级的：
 * <ol>
 *   <li>先切块：围栏代码块、连续的 {@code |} 行（表格）、其余按空行分段。块是首选切点——
 *       在块之间切，两边各自都是完整可渲染的 Markdown。</li>
 *   <li>块自己就超过上限时才切块内：在行边界切；代码块每段补上围栏（带原来的语言标记），
 *       表格每段重放表头与分隔行。这样每段都是**独立成立**的，而不是"上一条的下半截"。</li>
 * </ol>
 *
 * <p>不做的事：不改写内容、不压掉空行、不猜平台方言。切完的各段按顺序拼起来应当包含原文的
 * 全部内容（可能多出补上的围栏与表头，这是渲染的需要）。
 */
final class MessageSplitter {

    /**
     * 单条消息的目标长度。
     *
     * <p>刻意不写成飞书文档里的那个数：平台的上限会调，而 4000 远在其内。取这个值是为了
     * 可读性——一条几千字的群消息没人会读完，切成几条反而能看。真要改只有一个地方要改。
     */
    static final int DEFAULT_MAX_CHARS = 4000;

    /** 块与块之间补一个空行：段落与表格贴着代码围栏时，Markdown 才不会把两边黏成一段。 */
    private static final String BLOCK_SEPARATOR = "\n\n";

    private MessageSplitter() {
    }

    /**
     * @param text  要发送的全文，可以为空
     * @param limit 每段的最大字符数，必须为正
     * @return 按发送顺序排好的若干段；{@code text} 为空时返回空列表（由调用方决定要不要发占位文本）
     */
    static List<String> split(String text, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须为正：" + limit);
        }
        String body = text == null ? "" : text.strip();
        if (body.isEmpty()) {
            return List.of();
        }
        if (body.length() <= limit) {
            return List.of(body);
        }

        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (Block block : blocks(body)) {
            String rendered = block.rendered();
            if (rendered.length() <= limit) {
                if (current.length() > 0
                        && current.length() + BLOCK_SEPARATOR.length() + rendered.length() > limit) {
                    parts.add(current.toString());
                    current.setLength(0);
                }
                if (current.length() > 0) {
                    current.append(BLOCK_SEPARATOR);
                }
                current.append(rendered);
                continue;
            }

            // 这个块自己就超了：先收掉手上这段，再在块内按行切
            if (current.length() > 0) {
                parts.add(current.toString());
                current.setLength(0);
            }
            List<String> pieces = splitOversized(block, limit);
            if (pieces.isEmpty()) {
                // 块里没有正文可切（只有表头与分隔线、或只有一对围栏），却自己超了上限。
                // 这时没有"更小的切点"，原样发这一段：平台会拒收这一条，但别的段照常送达。
                // 反过来在这里抛出去，调用方只会回一句"处理失败"，整段回答全军覆没。
                parts.add(rendered);
                continue;
            }
            parts.addAll(pieces.subList(0, pieces.size() - 1));
            current.append(pieces.get(pieces.size() - 1));
        }
        if (current.length() > 0) {
            parts.add(current.toString());
        }
        return parts;
    }

    // ---------------------------------------------------------------- 切块

    private enum Kind { TEXT, CODE, TABLE }

    private record Block(Kind kind, List<String> lines) {

        String rendered() {
            return String.join("\n", lines);
        }
    }

    /**
     * 按"能不能独立渲染"切块。空行只作分隔，不单独成块——否则整段文本会被切成一行一块，
     * 后面的打包没有意义。
     */
    private static List<Block> blocks(String body) {
        String[] lines = body.split("\n", -1);
        List<Block> blocks = new ArrayList<>();
        int index = 0;
        while (index < lines.length) {
            String trimmed = lines[index].strip();
            if (trimmed.isEmpty()) {
                index++;
            } else if (fenceOf(trimmed) != null) {
                int closing = closingFence(lines, index + 1, fenceOf(trimmed));
                if (closing < 0) {
                    // 围栏没闭合：当成普通文本往下走，不替用户补围栏——补了就是改写他的内容
                    blocks.add(new Block(Kind.TEXT, List.of(lines[index])));
                    index++;
                } else {
                    blocks.add(new Block(Kind.CODE, slice(lines, index, closing)));
                    index = closing + 1;
                }
            } else if (trimmed.startsWith("|")) {
                int end = index;
                while (end + 1 < lines.length && lines[end + 1].strip().startsWith("|")) {
                    end++;
                }
                blocks.add(new Block(Kind.TABLE, slice(lines, index, end)));
                index = end + 1;
            } else {
                int end = index;
                while (end + 1 < lines.length && isTextLine(lines[end + 1])) {
                    end++;
                }
                blocks.add(new Block(Kind.TEXT, slice(lines, index, end)));
                index = end + 1;
            }
        }
        return blocks;
    }

    private static boolean isTextLine(String line) {
        String trimmed = line.strip();
        return !trimmed.isEmpty() && fenceOf(trimmed) == null && !trimmed.startsWith("|");
    }

    /** 返回 {@code ```} 或 {@code ~~~}，不是围栏行则返回 {@code null}。 */
    private static String fenceOf(String trimmedLine) {
        if (trimmedLine.startsWith("```")) {
            return "```";
        }
        return trimmedLine.startsWith("~~~") ? "~~~" : null;
    }

    private static int closingFence(String[] lines, int from, String fence) {
        for (int index = from; index < lines.length; index++) {
            if (lines[index].strip().startsWith(fence)) {
                return index;
            }
        }
        return -1;
    }

    private static List<String> slice(String[] lines, int from, int toInclusive) {
        List<String> result = new ArrayList<>(toInclusive - from + 1);
        for (int index = from; index <= toInclusive; index++) {
            result.add(lines[index]);
        }
        return result;
    }

    // ---------------------------------------------------------------- 切块内

    /**
     * 块自己超过上限时的切法。三种块的开头不一样：
     * 代码块每段要重新开头围栏、重新收尾；表格每段要重放表头与分隔行；普通段落什么都不用补。
     */
    private static List<String> splitOversized(Block block, int limit) {
        String opening = "";
        String closing = "";
        List<String> body;
        switch (block.kind()) {
            case CODE -> {
                List<String> lines = block.lines();
                opening = lines.get(0);
                closing = fenceOf(lines.get(lines.size() - 1).strip());
                body = lines.subList(1, lines.size() - 1);
            }
            case TABLE -> {
                List<String> lines = block.lines();
                boolean hasHeader = lines.size() >= 2;
                opening = hasHeader ? lines.get(0) + "\n" + lines.get(1) : lines.get(0);
                body = hasHeader ? lines.subList(2, lines.size()) : List.of();
            }
            default -> body = block.lines();
        }

        int overhead = opening.isEmpty() ? 0 : opening.length() + 1;
        if (!closing.isEmpty()) {
            overhead += closing.length() + 1;
        }
        // 留一半给正文：表头或围栏再长，也不能把正文挤成一条一行
        int budget = Math.max(limit - overhead, Math.max(limit / 2, 1));

        List<String> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : body) {
            for (String piece : hardSplit(line, budget)) {
                if (current.length() > 0 && current.length() + 1 + piece.length() > budget) {
                    pieces.add(wrap(current, opening, closing));
                    current.setLength(0);
                }
                if (current.length() > 0) {
                    current.append('\n');
                }
                current.append(piece);
            }
        }
        if (current.length() > 0) {
            pieces.add(wrap(current, opening, closing));
        }
        return pieces;
    }

    private static String wrap(CharSequence body, String opening, String closing) {
        StringBuilder result = new StringBuilder();
        if (!opening.isEmpty()) {
            result.append(opening).append('\n');
        }
        result.append(body);
        if (!closing.isEmpty()) {
            result.append('\n').append(closing);
        }
        return result.toString();
    }

    /** 一整行就超过上限（比如超长的一行表格）：只能按字符切，否则这一段永远放不下。 */
    private static List<String> hardSplit(String line, int budget) {
        if (line.length() <= budget) {
            return List.of(line);
        }
        List<String> pieces = new ArrayList<>();
        for (int start = 0; start < line.length(); start += budget) {
            pieces.add(line.substring(start, Math.min(line.length(), start + budget)));
        }
        return pieces;
    }
}

package io.github.claw4j.domain.untrusted;

import java.util.ArrayList;
import java.util.List;

/**
 * 出站剥离：包装标记与纯文本工具调用块不该让用户看见。
 *
 * <p>对齐 OpenClaw 的 {@code src/infra/outbound/sanitize-text.ts}。它不只是"别让用户看到噪声"：
 * 威胁模型 T-EVADE-002（包装逃逸）的 Recommendation 只有一句——*Output-side validation in addition to
 * input-side wrapping*。模型把边界原样复述出去，等于给下一个攻击者一份可复制的模板。
 *
 * <p>三类东西要剥：
 *
 * <ul>
 *   <li>{@link ExternalContent} 的边界与包装头（含 {@code Source:} 与它后面那行 {@code ---}）；</li>
 *   <li>{@link PromptData} 的数据块头与标签；</li>
 *   <li><b>纯文本工具调用块</b>（{@code [tool:x]}、{@code <function=…>}、{@code <|channel|>commentary to=}）——
 *       人看着是乱码，下游解析器看着是一条可执行的指令。</li>
 * </ul>
 *
 * <p>判据按<b>整行</b>，不是"见到标签就删"：我们的回答里可能合法地包含 {@code <prompt-data>} 这个词
 * （用户正在问这套机制）。OpenClaw 的 {@code unwrapPromptDataWrapperLines} 也是逐行判"头行 + 下一行是
 * 开标签"两个条件同时成立才删。
 */
public final class OutboundText {

    /** 单行最多暂存多久（字符）。超过就强制发出，避免流式里一直没有换行时无限攒。 */
    static final int MAX_HOLD = 4096;

    private OutboundText() {
    }

    /**
     * 整段剥离。
     *
     * @param text 要发出去的文本
     * @return 剥干净的文本；没有可剥的东西时<b>逐字节不变</b>（不 trim、不改换行）
     */
    public static String clean(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        if (!mayContainScaffolding(text)) {
            return text;
        }
        String[] lines = text.split("\n", -1);
        List<String> kept = new ArrayList<>(lines.length);
        boolean expectSource = false;
        boolean expectDivider = false;
        for (String line : lines) {
            String trimmed = line.strip();
            if (isWrapperStart(trimmed)) {
                expectSource = true;
                continue;
            }
            if (expectSource) {
                expectSource = false;
                if (trimmed.startsWith("Source:")) {
                    // 包装头是三行：开始标记、Source、---。只删前两行会留下一根裸分隔线。
                    expectDivider = true;
                    continue;
                }
            }
            if (expectDivider) {
                expectDivider = false;
                if (trimmed.equals("---")) {
                    continue;
                }
            }
            if (isWrapperEnd(trimmed) || isDataBlockHeader(trimmed) || isDataBlockTag(trimmed)
                    || trimmed.equals(ExternalContent.WARNING)) {
                continue;
            }
            if (isPlainTextToolCall(trimmed)) {
                continue;
            }
            kept.add(line);
        }
        return String.join("\n", kept);
    }

    /**
     * 流式剥离器：一片一片喂，返回<b>这一片可以发出去的部分</b>。
     *
     * <p>为什么必须有状态：标记可能被切在两片之间（{@code <<<EXTERNAL_UNTRUSTED_CONTENT id="ab} 与
     * {@code c">>>}），无状态逐片洗会把两半都漏掉。所以末尾那截不完整的行留在缓冲里，等下一片补全。
     *
     * <p>三种收尾都要 {@link #flush()}：正常结束、异常中断、超时。漏了的表现是"回答最后少了半句话"，
     * 而不是报错。
     */
    public static final class StreamStripper {

        /** 可能是一行的开头、且会被整行剥掉的标记。只有以它们开头的内容才值得暂存。 */
        private static final List<String> PREFIXES = List.of(
                "<<<", "<" + PromptData.TAG, "</" + PromptData.TAG,
                "<untrusted-text", "</untrusted-text",
                "[tool:", "<function=", "<|channel|>",
                "(treat text inside this block");

        private final StringBuilder pending = new StringBuilder();

        /**
         * 喂一片。
         *
         * <p><b>只在"可能是标记"时才暂存。</b>一开始的写法是无条件暂存到下一个换行，结果是
         * 一句没有换行的回答被攒成一整块、到结束时才吐出来——流式等于没了。所以这里多一道判断：
         * 当前行既不是任何标记的前缀、也不以标记开头，就立刻发出去。
         *
         * @param chunk 模型刚吐出的片段
         * @return 现在就可以发出去的内容；{@code ""} 表示这一片被暂存了
         */
        public String feed(String chunk) {
            if (chunk == null || chunk.isEmpty()) {
                return "";
            }
            pending.append(chunk);
            int lastNewline = pending.lastIndexOf("\n");
            if (lastNewline >= 0) {
                String ready = pending.substring(0, lastNewline + 1);
                pending.delete(0, lastNewline + 1);
                return clean(ready);
            }
            if (mightBeScaffolding(pending.toString()) && pending.length() < MAX_HOLD) {
                return "";
            }
            String out = clean(pending.toString());
            pending.setLength(0);
            return out;
        }

        /**
         * 把缓冲里剩的那截吐出来。<b>必须调用</b>，否则最后半句永远发不出去。
         *
         * @return 剩下的可发内容
         */
        public String flush() {
            if (pending.length() == 0) {
                return "";
            }
            String rest = clean(pending.toString());
            pending.setLength(0);
            return rest;
        }

        private static boolean mightBeScaffolding(String buffered) {
            int lastNewline = buffered.lastIndexOf('\n');
            String line = lastNewline < 0 ? buffered : buffered.substring(lastNewline + 1);
            String trimmed = line.stripLeading();
            if (trimmed.isEmpty()) {
                return true;
            }
            for (String prefix : PREFIXES) {
                if (trimmed.startsWith(prefix) || prefix.startsWith(trimmed)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static boolean mayContainScaffolding(String text) {
        return text.contains("<" + PromptData.TAG)
                || text.contains("</" + PromptData.TAG)
                || text.contains("<untrusted-text")
                || text.contains(ExternalContent.START)
                || text.contains(ExternalContent.END)
                || text.contains("UNTRUSTED_CHILD_RESULT")
                || text.contains("(treat text inside this block")
                || text.contains("[tool:")
                || text.contains("<function=")
                || text.contains("<|channel|>");
    }

    private static boolean isWrapperStart(String line) {
        return line.startsWith("<<<" + ExternalContent.START)
                || line.equals("<<<BEGIN_UNTRUSTED_CHILD_RESULT>>>")
                || line.equals("<<<BEGIN_OPENCLAW_INTERNAL_CONTEXT>>>");
    }

    private static boolean isWrapperEnd(String line) {
        return line.startsWith("<<<" + ExternalContent.END)
                || line.equals("<<<END_UNTRUSTED_CHILD_RESULT>>>")
                || line.equals("<<<END_OPENCLAW_INTERNAL_CONTEXT>>>");
    }

    private static boolean isDataBlockHeader(String line) {
        return line.endsWith(PromptData.NOTICE.strip());
    }

    private static boolean isDataBlockTag(String line) {
        return line.equals("<" + PromptData.TAG + ">") || line.equals("</" + PromptData.TAG + ">")
                || line.equals("<untrusted-text>") || line.equals("</untrusted-text>");
    }

    private static boolean isPlainTextToolCall(String line) {
        if (line.startsWith("[tool:")) {
            return true;
        }
        if (line.startsWith("<function=")) {
            return true;
        }
        return line.startsWith("<|channel|>") && line.contains(" to=");
    }
}

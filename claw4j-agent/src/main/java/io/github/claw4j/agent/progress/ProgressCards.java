package io.github.claw4j.agent.progress;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 进度卡：一份计划，每段会话一张，整卡替换。
 *
 * <p>形状对齐 OpenClaw 的 {@code session_progress_cards}：一行、整卡替换、带 {@code revision}
 * 做乐观并发、清空时保留墓碑行。它是 <b>给人看的投影</b>，不是给模型看的上下文——这条边界由
 * ADR-0024 定下，它的卡也一样（Gateway 侧状态，投递面读它）。
 *
 * <p>限额照抄它的判据（它的 DDL 里没有 CHECK，全在代码里判，我们也一样）：
 *
 * <ul>
 *   <li>最多 {@value #MAX_STEPS} 步；</li>
 *   <li>单步最多 {@value #MAX_STEP_BYTES} 个 UTF-8 <b>字节</b>（不是字符）；</li>
 *   <li>同一时刻最多 {@value #MAX_IN_PROGRESS} 步是"在做"。</li>
 * </ul>
 *
 * <p>超限<b>不收</b>，把原因回给模型让它改。它没写明是拒收还是截断，我们选拒收并记在这里：
 * 截断会让模型以为计划被记住了，而人看到的是残的。
 */
public interface ProgressCards {

    /** 最多几笔。 */
    int MAX_STEPS = 50;

    /** 单笔最多多少 UTF-8 字节。 */
    int MAX_STEP_BYTES = 512;

    /** 同时最多几笔"在做"。 */
    int MAX_IN_PROGRESS = 1;

    /**
     * 写一份卡。
     *
     * <p>两个字段都空 = 清空（置 null 并 +1，不删行）。这与它的
     * "retain revision tombstones" 一致。
     *
     * @param sessionKey 会话 id（含段）
     * @param write      这一份
     * @return 三态之一
     * @throws Rejected 超限或内容不合法
     */
    Outcome save(String sessionKey, Write write);

    /**
     * 读回这一段当前的卡。
     *
     * @param sessionKey 会话 id
     * @return 没有卡（或已清空）时为空
     */
    Optional<Card> of(String sessionKey);

    /** 一步的状态。词表与工具一致。 */
    enum Status {
        PENDING,
        IN_PROGRESS,
        COMPLETED
    }

    /**
     * 一步。只有两个字段，与它的 {@code ProgressCardStep} 一致。
     *
     * <p>工具入参里的 {@code activeForm} 不进卡：那是给模型看的措辞，不是进度。
     *
     * @param step   做什么
     * @param status 到哪了
     */
    record Step(String step, Status status) {
    }

    /**
     * 一次写入。
     *
     * @param markdown         备注；当前恒为 null（工具入参里没有它），留着对齐形状
     * @param steps            计划；空表示清空
     * @param expectedRevision 期望的版本；null 表示不做并发校验
     */
    record Write(String markdown, List<Step> steps, Long expectedRevision) {
    }

    /**
     * 一张卡。
     *
     * @param sessionKey 属于哪一段
     * @param revision   版本，从 1 开始，每次写 +1
     * @param markdown   备注，可为 null
     * @param steps      计划，可为空
     * @param createdAt  第一次写入的时间
     * @param updatedAt  最后一次写入的时间
     */
    record Card(String sessionKey, long revision, String markdown, List<Step> steps,
                Instant createdAt, Instant updatedAt) {
    }

    /** 写入结果。三态，与它的 {@code {card} | {cleared} | 版本不符} 一致。 */
    sealed interface Outcome {

        /**
         * 写进去了。
         *
         * @param card 写完之后的样子
         */
        record Saved(Card card) implements Outcome {
        }

        /** 清掉了（行保留，内容为 null）。 */
        record Cleared() implements Outcome {
        }

        /**
         * 版本不符，没写。
         *
         * @param current 库里当前那一份
         */
        record Stale(Card current) implements Outcome {
        }
    }

    /**
     * 不收这份写入，原因给模型看。
     *
     * <p>用异常而不是返回 null：静默丢弃的表现是"模型以为记下了、人看不到"，最难查。
     */
    class Rejected extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /**
         * @param message 对模型可读的原因，会作为工具结果回给它
         */
        public Rejected(String message) {
            super(message);
        }
    }

    /**
     * 校验并清洗一份计划，超过限额就拒收。
     *
     * <p>清洗的是不可见字符与双向控制字符：它们能把"看起来一样"的两步排成不同顺序，
     * 而投递面是按顺序渲染的。
     *
     * @param steps 模型交上来的计划
     * @return 清洗后的副本；空列表表示清空
     * @throws Rejected 超限
     */
    static List<Step> checked(List<Step> steps) {
        if (steps == null) {
            return List.of();
        }
        if (steps.size() > MAX_STEPS) {
            throw new Rejected("一次最多 " + MAX_STEPS + " 步，这次交了 " + steps.size()
                    + " 步。请合并成关键步骤再交。");
        }
        List<Step> cleaned = new ArrayList<>(steps.size());
        int inProgress = 0;
        for (Step step : steps) {
            String text = clean(step.step());
            if (text.isEmpty()) {
                throw new Rejected("每一步都要有内容。");
            }
            int bytes = text.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > MAX_STEP_BYTES) {
                throw new Rejected("单步最多 " + MAX_STEP_BYTES + " 个字节（UTF-8），这一步有 "
                        + bytes + " 个。请写短一点。");
            }
            Status status = step.status() == null ? Status.PENDING : step.status();
            if (status == Status.IN_PROGRESS) {
                inProgress++;
            }
            cleaned.add(new Step(text, status));
        }
        if (inProgress > MAX_IN_PROGRESS) {
            throw new Rejected("同时最多 " + MAX_IN_PROGRESS + " 步是「在做」，这次有 "
                    + inProgress + " 步。其余请标成待办。");
        }
        return List.copyOf(cleaned);
    }

    /**
     * 去掉不可见字符与双向控制字符，换行压成空格。
     *
     * @param text 原文，可为 null
     * @return 清洗后的文本，绝不为 null
     */
    private static String clean(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> {
            // 先处理空白：换行与制表要变成空格，不能当成"不可见字符"删掉——
            // 删掉会把两步的文字粘成一句（"核对表结构然后交差"），意思就变了。
            if (cp == '\n' || cp == '\r' || cp == '\t') {
                out.append(' ');
                return;
            }
            int type = Character.getType(cp);
            boolean bidi = cp == 0x200E || cp == 0x200F || cp == 0x202A || cp == 0x202B
                    || cp == 0x202C || cp == 0x202D || cp == 0x202E
                    || cp == 0x2066 || cp == 0x2067 || cp == 0x2068 || cp == 0x2069;
            boolean invisible = type == Character.FORMAT || type == Character.CONTROL
                    || type == Character.UNASSIGNED || type == Character.SURROGATE;
            if (bidi || invisible) {
                return;
            }
            out.appendCodePoint(cp);
        });
        return out.toString().strip().replaceAll(" {2,}", " ");
    }
}

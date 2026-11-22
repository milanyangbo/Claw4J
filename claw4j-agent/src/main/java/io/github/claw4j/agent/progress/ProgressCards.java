package io.github.claw4j.agent.progress;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 进度卡：一份计划，每段会话一张，整卡替换。
 *
 * <p>它是 <b>给人看的投影</b>，不是给模型看的上下文。这条边界由 ADR-0024 定下（把计划钉进
 * 上下文实测找不到生效场景），OpenClaw 的 {@code progress_card} 也一样——它是 Gateway 侧的
 * 会话状态，投递面读它，模型那一侧只拿到工具描述里的一句提醒。
 *
 * <p>限额照抄它的判据，不自己发明：
 *
 * <ul>
 *   <li>最多 {@value #MAX_STEPS} 步——再多就不是"一眼可见的进度"了；</li>
 *   <li>单步最多 {@value #MAX_STEP_BYTES} 个 UTF-8 <b>字节</b>（不是字符）——中文一步约 170 字；</li>
 *   <li>同一时刻最多 {@value #MAX_IN_PROGRESS} 步是"在做"——两件同时在做，说明这不是一份计划。</li>
 * </ul>
 *
 * <p>超限时<b>不收</b>，把原因回给模型让它改。这一点与它的文档一致但文档没写清"拒收还是截断"，
 * 我们选拒收并记在这里：截断会让模型以为自己写的计划被记住了，而人看到的是残的。
 */
public interface ProgressCards {

    /** 最多几笔。 */
    int MAX_STEPS = 50;

    /** 单笔最多多少 UTF-8 字节。 */
    int MAX_STEP_BYTES = 512;

    /** 同时最多几笔"在做"。 */
    int MAX_IN_PROGRESS = 1;

    /**
     * 整卡替换这一段的计划。
     *
     * @param sessionId 会话 id（含段）
     * @param steps     新的一份；空列表表示清空
     * @throws Rejected 超限或内容为空
     */
    void save(String sessionId, List<Step> steps);

    /**
     * 读回这一段当前的卡。
     *
     * @param sessionId 会话 id
     * @return 没有卡时为空
     */
    Optional<Card> of(String sessionId);

    /** 一步的状态。词表与工具一致，不另立。 */
    enum Status {
        PENDING,
        IN_PROGRESS,
        COMPLETED
    }

    /**
     * 一步。
     *
     * @param content    做什么
     * @param status     到哪了
     * @param activeForm 在做的时候怎么描述它（"正在核对表结构"），给人看
     */
    record Step(String content, Status status, String activeForm) {
    }

    /**
     * 一张卡。
     *
     * @param sessionId 属于哪一段
     * @param steps     计划
     * @param updatedAt 最后一次整卡替换的时间
     */
    record Card(String sessionId, List<Step> steps, Instant updatedAt) {
    }

    /**
     * 不收这份计划，原因给模型看。
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
            throw new Rejected("计划不能为空；要清空就交一个空列表。");
        }
        if (steps.size() > MAX_STEPS) {
            throw new Rejected("一次最多 " + MAX_STEPS + " 步，这次交了 " + steps.size()
                    + " 步。请合并成关键步骤再交。");
        }
        List<Step> cleaned = new ArrayList<>(steps.size());
        int inProgress = 0;
        for (Step step : steps) {
            String content = clean(step.content());
            if (content.isEmpty()) {
                throw new Rejected("每一步都要有内容。");
            }
            int bytes = content.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > MAX_STEP_BYTES) {
                throw new Rejected("单步最多 " + MAX_STEP_BYTES + " 个字节（UTF-8），这一步有 "
                        + bytes + " 个。请写短一点。");
            }
            Status status = step.status() == null ? Status.PENDING : step.status();
            if (status == Status.IN_PROGRESS) {
                inProgress++;
            }
            cleaned.add(new Step(content, status, clean(step.activeForm())));
        }
        if (inProgress > MAX_IN_PROGRESS) {
            throw new Rejected("同时最多 " + MAX_IN_PROGRESS + " 步是「在做」，这次有 "
                    + inProgress + " 步。其余请标成待办。");
        }
        return List.copyOf(cleaned);
    }

    /**
     * 去掉不可见字符与双向控制字符，并把换行压成空格。
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
            int type = Character.getType(cp);
            boolean bidi = cp == 0x200E || cp == 0x200F || cp == 0x202A || cp == 0x202B
                    || cp == 0x202C || cp == 0x202D || cp == 0x202E
                    || cp == 0x2066 || cp == 0x2067 || cp == 0x2068 || cp == 0x2069;
            boolean invisible = type == Character.FORMAT || type == Character.CONTROL
                    || type == Character.UNASSIGNED || type == Character.SURROGATE;
            if (bidi || invisible) {
                return;
            }
            out.appendCodePoint(cp == '\n' || cp == '\r' || cp == '\t' ? ' ' : cp);
        });
        return out.toString().strip().replaceAll(" {2,}", " ");
    }
}

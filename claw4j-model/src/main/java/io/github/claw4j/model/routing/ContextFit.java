package io.github.claw4j.model.routing;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 上下文溢出之后：谁还装得下这次请求。
 *
 * <p>照 OpenClaw 的 {@code preferContextFitFallbackCandidates}（`model-fallback-candidates.ts:58-96`）：
 * 只重排<b>失败位置之后</b>的候选，"够得着"的稳定排在前面；<b>不丢候选</b>——注释原话是
 * <i>a larger window is a preference, not a guarantee of fit</i>（容量大是偏好，不是保证）。
 *
 * <p>需求下界只要两个来源：错误文本里的实际 token 数，或"失败模型的容量 + 1"。
 * <b>不引入估算器</b>：估出来的数会把"这次装不下"变成"这次大概装不下"，而重排只看偏好。
 */
public final class ContextFit {

    /** 供应商在错误里报的"你要了 N 个 token"。 */
    private static final Pattern REQUESTED = Pattern.compile(
            "(?:requested|prompt|request)[^0-9]{0,24}(\\d{2,9})\\s*tokens?");

    /** "maximum context length is N"。 */
    private static final Pattern CAPACITY = Pattern.compile(
            "(?:maximum context length|context length)[^0-9]{0,24}(\\d{2,9})");

    private ContextFit() {
    }

    /**
     * 这次请求至少要多少 token。
     *
     * @param failureMessage 失败信息（可能带实际 token 数）
     * @param failedCapacity 失败那个模型的有效容量
     * @return 需求下界；两个来源都没有时返回失败容量 + 1
     */
    public static int requiredTokens(String failureMessage, int failedCapacity) {
        if (failureMessage != null) {
            Matcher requested = REQUESTED.matcher(failureMessage);
            if (requested.find()) {
                return Integer.parseInt(requested.group(1));
            }
            Matcher capacity = CAPACITY.matcher(failureMessage);
            if (capacity.find()) {
                // 供应商说"最多 N"：这次要的比 N 多，取下界 N+1
                return Integer.parseInt(capacity.group(1)) + 1;
            }
        }
        return Math.max(1, failedCapacity + 1);
    }

    /**
     * 按"够得着在前"重排。
     *
     * <p>稳定分区：同组内保持原顺序，所以配置里的偏好还在。容量未知的（0）排到后面，但仍在链上。
     *
     * @param refs       候选（只含失败位置之后的那些）
     * @param required   需求下界
     * @param capacities 每个候选的有效容量；查不到按 0
     * @param <T>        候选类型
     * @return 新的顺序，长度与入参相同
     */
    public static <T> java.util.List<T> preferFitting(java.util.List<T> refs, int required,
                                                      java.util.function.Function<T, Integer> capacities) {
        java.util.List<T> fits = new java.util.ArrayList<>();
        java.util.List<T> tooSmall = new java.util.ArrayList<>();
        for (T ref : refs) {
            Integer capacity = capacities.apply(ref);
            if (capacity != null && capacity >= required) {
                fits.add(ref);
            }
            else {
                tooSmall.add(ref);
            }
        }
        fits.addAll(tooSmall);
        return fits;
    }
}

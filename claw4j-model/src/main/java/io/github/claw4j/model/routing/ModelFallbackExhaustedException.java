package io.github.claw4j.model.routing;

import io.github.claw4j.model.failover.FailoverReason;

import java.time.Instant;
import java.util.List;

/**
 * 一条候选都没走通。
 *
 * <p>比"把最后一个异常抛出去"多带两样东西：**每一跳的原因**与**最早什么时候能再试**。
 * 没有它们，运维看到的只是一句"所有模型都失败"，而真正要回答的是"是限流吗、什么时候能好"。
 * 照 OpenClaw 的耗尽摘要（`docs/concepts/model-failover.md:149`）。
 */
public class ModelFallbackExhaustedException extends RuntimeException {

    /**
     * 一次尝试：正常试过、被冷却跳过、被闸跳过都算。
     *
     * @param ref    失败/跳过的模型
     * @param reason 原因；被跳过时是跳过理由（冷却里记的那个）
     * @param status {@code failed} 或 {@code skipped}
     */
    public record Attempt(String ref, FailoverReason reason, String status) {
    }

    private final List<Attempt> attempts;

    private final Instant soonestRecovery;

    /**
     * 只有一句话的形态（错误面映射、测试用）。
     *
     * @param message 可读摘要
     */
    public ModelFallbackExhaustedException(String message) {
        this(message, List.of(), null, null);
    }

    /**
     * @param message        可读摘要
     * @param attempts       每一跳
     * @param soonestRecovery 最早恢复时刻，没有时传 null
     * @param cause          最后一个真实异常
     */
    public ModelFallbackExhaustedException(String message, List<Attempt> attempts,
                                           Instant soonestRecovery, Throwable cause) {
        super(message, cause);
        this.attempts = List.copyOf(attempts);
        this.soonestRecovery = soonestRecovery;
    }

    /**
     * 每一跳。
     *
     * @return 不可变列表
     */
    public List<Attempt> attempts() {
        return attempts;
    }

    /**
     * 最早什么时候能再试。
     *
     * @return 时刻；全部是真实失败（没有冷却）时为空
     */
    public java.util.Optional<Instant> soonestRecovery() {
        return java.util.Optional.ofNullable(soonestRecovery);
    }
}

package io.github.claw4j.agent.session;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.compaction.CompactionResult;
import org.springframework.ai.session.compaction.CompactionStrategy;
import org.springframework.ai.session.compaction.CompactionTrigger;

import java.util.Objects;

/**
 * 进模型之前把历史收拾一遍：够了就压成摘要，不够就什么也不做。
 *
 * <p><b>为什么需要这个类，而不是把 trigger 与 strategy 交给 {@code SessionMemoryAdvisor}</b>：
 * 它的 Builder 上确实有 {@code compactionTrigger} / {@code compactionStrategy} 两个参数，
 * 但 0.10.0 的 {@code SessionMemoryAdvisor} 字节码里<b>一次都没调用过
 * {@code SessionService.compact}</b>。配上去之后两个字段只是躺在对象里，
 * 压缩永远不会发生——这是"配了、不报错、也没生效"的那一类，比缺功能更难发现。
 *
 * <p>所以触发点只能自己找。选在 {@code ChatClientAgentPort} 进模型**之前**：
 * 那里是唯一一个"这一轮马上要拼提示词"的位置，压完紧接着就被读到。
 *
 * <p><b>会话还不存在时要闭嘴</b>：第一轮对话还没有会话，{@code compact} 会抛
 * {@code IllegalArgumentException}。那不是故障，是时序——用户第一次提问本来就没有历史可压。
 * 把它也记成失败，会让 {@code compaction.failures} 从第一天起就不是 0，从此没人看这个数。
 */
public class SessionCompactor {

    private static final Logger log = LoggerFactory.getLogger(SessionCompactor.class);

    public static final String ARCHIVED = "claw4j.session.compaction.archived";
    public static final String TOKENS_SAVED = "claw4j.session.compaction.tokens_saved";
    public static final String FAILURES = "claw4j.session.compaction.failures";

    private final SessionService sessions;
    private final CompactionTrigger trigger;
    private final CompactionStrategy strategy;
    private final MeterRegistry registry;

    public SessionCompactor(SessionService sessions,
                            CompactionTrigger trigger,
                            CompactionStrategy strategy,
                            MeterRegistry registry) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.trigger = Objects.requireNonNull(trigger, "trigger");
        this.strategy = Objects.requireNonNull(strategy, "strategy");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * 压不压由 {@code trigger} 决定，{@code SessionService.compact} 内部会先问它一次，
     * 不满足就原样返回。所以这里每轮都调是安全的——绝大多数时候它是一次判断加一次查询。
     */
    public void compactIfNeeded(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        CompactionResult result;
        try {
            result = sessions.compact(sessionId, trigger, strategy);
        }
        catch (IllegalArgumentException notCreatedYet) {
            // 第一轮：会话还没建出来。这不是失败。
            log.debug("会话 {} 还不存在，本轮不压", sessionId);
            return;
        }
        catch (RuntimeException failure) {
            // 存储层那两类异常已经由 FaultTolerantSessionService 吞掉了，能到这里的是别的：
            // 摘要模型挂了、模板拼错、策略自己抛。压不成不影响这一轮回答，但要留痕。
            Counter.builder(FAILURES)
                    .description("会话压缩失败次数")
                    .register(registry)
                    .increment();
            log.warn("会话 {} 压缩失败，这一轮照常回答：{}", sessionId, failure.toString());
            return;
        }
        if (result == null || result.archivedEventCount() <= 0) {
            return;
        }
        Counter.builder(ARCHIVED)
                .description("被压成摘要的会话事件条数")
                .register(registry)
                .increment(result.archivedEventCount());
        Counter.builder(TOKENS_SAVED)
                .description("压缩估计省下的 token 数（策略的估算值，不是账单口径）")
                .register(registry)
                .increment(result.tokensEstimatedSaved());
        log.info("会话 {} 压掉 {} 条事件，估计省下 {} token（估算值）",
                sessionId, result.archivedEventCount(), result.tokensEstimatedSaved());
    }
}

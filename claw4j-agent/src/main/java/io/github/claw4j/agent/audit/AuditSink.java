package io.github.claw4j.agent.audit;

import java.util.Optional;

/**
 * 审计事件的落点（ADR-0027 决定二）。
 *
 * <p><b>为什么是一个接口而不是直接依赖 {@link AuditLedger}</b>：账本需要数据库，
 * 而"什么时候该记一条什么事件"这条规则不需要。把两者分开，规则就能在没有库的地方被验证——
 * 一处一次性的断言，不必为一个方法起一个容器。
 *
 * <p>实现只有一个（{@link AuditLedger}）。这个接口存在的理由不是"将来可能换实现"，
 * 而是让测试能看见真正产生的事件，而不是"某个方法被调用了"。
 */
@FunctionalInterface
public interface AuditSink {

    /**
     * 写一条事件。
     *
     * @return 落下来的账本序号；空表示没写进去
     */
    Optional<Long> record(AuditEvent event);
}

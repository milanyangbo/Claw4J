package io.github.claw4j.agent.audit;

/**
 * 审计事件的落点（ADR-0027 决定二）。
 *
 * <p>为什么是接口而不是直接依赖 {@link AuditLedger}：账本需要数据库，而「什么时候该记一条什么
 * 事件」这条规则不需要。分开之后，规则可以在没有库的地方被验证——一处断言，不必为一个方法起一个
 * 容器。
 *
 * <p>实现只有一个。接口存在的理由不是「将来可能换实现」，而是让测试能看见真正产生的事件，而不是
 * 「某个方法被调用了」。
 *
 * <p>没有返回值：账本序号是账本自己的排序键（翻页与上限淘汰用），不是调用方要的东西。OpenClaw 的
 * 写入是后台 writer 尽力而为（{@code src/config/schema.help.core.ts:60}），调用方不知道写到了第几
 * 行；这里的写入是同步的，同样不把序号交出去——一旦有人拿它做关联，它就又变成一个实体了。
 */
@FunctionalInterface
public interface AuditSink {

    /**
     * 写一条事件。
     *
     * <p>写不进去由实现自己记账（计数 + 一次 WARN），不抛、也不返回成败：记账不是回答的前置条件，
     * 调用方没有「根据写没写成功改变行为」的余地。
     *
     * @param event 要落库的事件
     */
    void record(AuditEvent event);
}

package io.github.claw4j.agent.audit;

import java.time.Duration;

/**
 * 审批与执行之间的交接（ADR-0037 票 05）。
 *
 * <p>它替掉的是原来那个 {@code ToolCallAudit} 里的进程内 Map：那个 Map 有 256 条上限、
 * 10 分钟 TTL，超了整份清掉，只在单进程成立——审计里的 {@code approval_id} 于是会成批、
 * 静默地消失。OpenClaw 把同一件事落在持久事实里（{@code execution_decision_facts}，
 * 见 {@code docs/gateway/protocol/ledgers.md:123-131}），重启与多实例都还在。
 *
 * <p>键是「这一轮 + 工具名 + 参数指纹」：与 {@code args_digest} 同一个函数算出来，
 * 所以「这次执行是哪次批准的」能直接对上，而参数原文不进库。
 */
public interface DecisionFacts {

    /**
     * 记下一条刚兑现的批准。
     *
     * @param executionId 这一轮的编号
     * @param toolName 工具名
     * @param argsDigest 参数指纹
     * @param approvalId 审批编号
     */
    void grant(String executionId, String toolName, String argsDigest, String approvalId);

    /**
     * 取走那条批准。取一次就没了——同一次批准不该为第二次执行放行。
     *
     * @param executionId 这一轮的编号
     * @param toolName 工具名
     * @param argsDigest 参数指纹
     * @return 审批编号；没有或已过期时为 null
     */
    String take(String executionId, String toolName, String argsDigest);

    /**
     * 清掉过期的交接。默认与审批有效期同一个量级，由调用方的定时任务顺手做。
     *
     * @param age 超过多久算过期
     * @return 清掉几条
     */
    int prune(Duration age);
}

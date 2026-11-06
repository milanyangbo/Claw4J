package io.github.claw4j.agent.approval;

import io.github.claw4j.domain.port.ApprovalLedger;

import java.util.Optional;

/**
 * 鉴权 Advisor 眼里"审批"这一层的全部内容（P02）：问一句有没有批准，没有就登记一条。
 *
 * <p><b>为什么是接口而不是直接用 {@link ApprovalLedger}</b>：Advisor 判定完还要做两件事——
 * 把这一步落成 awaiting、把这件事挂起。这两件事属于 run 域，而 Advisor 不该认识
 * 一个大而全的存储接口（它只会用到其中两个，多出来的每一个都是"以后顺手在这里改点别的"
 * 的入口）。收成两个方法之后，"判定"与"落状态"的分界就写在类型上了。
 *
 * <p><b>没有审批设施时用 {@link #denyAll()}</b>：一律没有批准、也登记不了请求。
 * 标了要审批的动作照样被拦，只是没人能批——那就是"这个部署没开审批"应有的样子，
 * 而不是悄悄退化成普通动作。
 */
public interface ApprovalGate {

    /** 兑现一条同人、同工具、同参数的批准。空表示没有。 */
    Optional<ApprovalLedger.Grant> redeem(String owner, String toolName, String argsDigest);

    /**
     * 登记一条待批请求，并把这件事停在这一步。
     *
     * @return 审批编号。空表示没记下来——调用方仍然要拦下这次调用，
     *         只是这一轮没有编号可以回给用户
     */
    Optional<String> ask(ApprovalLedger.Ask ask);

    /** 没有审批设施时的那一档：问不到、也记不下。 */
    static ApprovalGate denyAll() {
        return new ApprovalGate() {

            @Override
            public Optional<ApprovalLedger.Grant> redeem(String owner, String toolName, String argsDigest) {
                return Optional.empty();
            }

            @Override
            public Optional<String> ask(ApprovalLedger.Ask ask) {
                return Optional.empty();
            }
        };
    }
}

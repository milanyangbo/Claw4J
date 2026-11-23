package io.github.claw4j.channel.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.claw4j.domain.port.AuditRunInspect;

import java.util.List;

/**
 * 一次 run 全景的**报文形状**（ADR-0037 票 14）：照它的 {@code AuditRunInspectResultSchema}。
 *
 * <p>翻译只在这一层：端口给的是"这一跑批了什么"，这里把它翻成它那份闭对象
 * （{@code audit-run.ts:313-330}）。三个要点：
 *
 * <ul>
 *   <li>{@code identity} 恒为 {@code unsupported}：我们没有采集执行身份（它那条
 *       {@code logging.audit.executionIdentity} 的对应物），而它的 schema **本来就为这种情况留了状态**
 *       （{@code audit-run.ts:251-255}）。写 {@code present} 才是错的——那等于声称我们知道
 *       "谁发起的、代表谁"。</li>
 *   <li>{@code coverage} 由这一页回执推：有 {@code enforced} 就是它，只有 {@code attribution-only}
 *       就是它，一条回执都没有就 {@code unknown}。</li>
 *   <li>三个数组（{@code decisionDisplays} / {@code missingEvidence} / {@code remediation}）**恒在**：
 *       它们在 schema 里是必填数组，空也要空成 {@code []}，不能因为空就不出现。</li>
 * </ul>
 *
 * @param schemaVersion 恒为 1
 * @param run 这一轮本身
 * @param identity 执行身份：我们没有，照实报 {@code unsupported}
 * @param decisionDisplays 回执，按时间倒序
 * @param coverage 这批回执的覆盖度
 * @param nextDecisionCursor 下一页游标；没有更多时不存在
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record AuditRunView(int schemaVersion, RunView run, IdentityView identity,
                    List<Display> decisionDisplays, CoverageView coverage,
                    String nextDecisionCursor) {

    /** 身份状态："我们要采集但没采到"与"我们压根不采集"是两件事。 */
    private static final String IDENTITY_UNSUPPORTED = "unsupported";

    /** 为什么是不支持的：一句话说得清，且能指向缺的那一层。 */
    private static final String IDENTITY_REASON = "execution_identity_not_collected";

    /** 缺的那块证据：它的 {@code missingEvidence} 是引用数组，这里写采集层的名字。 */
    private static final List<String> IDENTITY_MISSING = List.of("execution.identity.context");

    /**
     * 把端口的一次查询翻成报文。
     *
     * @param runId 这一轮的编号
     * @param report 查出来的全景
     * @return 报文形状
     */
    static AuditRunView of(String runId, AuditRunInspect.Report report) {
        List<Display> displays = report.decisions().stream().map(Display::of).toList();
        return new AuditRunView(1,
                new RunView(runId, report.runKnown() ? "known" : "unknown"),
                new IdentityView(IDENTITY_UNSUPPORTED, IDENTITY_REASON, IDENTITY_MISSING, List.of()),
                displays,
                new CoverageView(coverageOf(report.decisions()), List.of()),
                report.nextCursor());
    }

    /**
     * 这批回执的覆盖度：有真闸就是 {@code enforced}，只有记录就是 {@code attribution-only}。
     *
     * <p>顺序是有意的：一条 {@code enforced} 就足以说明"这一跑里至少有一次决定真的改了结果"，
     * 而反过来（有记录就降级）会让一条被批准的调用看起来像"只是留了条记录"。
     *
     * @param decisions 这一页回执
     * @return 覆盖度状态
     */
    private static String coverageOf(List<AuditRunInspect.Decision> decisions) {
        if (decisions.isEmpty()) {
            return "unknown";
        }
        boolean enforced = decisions.stream()
                .anyMatch(decision -> "enforced".equals(decision.coverageState()));
        return enforced ? "enforced" : "attribution-only";
    }

    /**
     * 这一轮本身。
     *
     * @param runId 这一轮的编号
     * @param status {@code known} / {@code unknown}
     */
    record RunView(String runId, String status) {
    }

    /**
     * 执行身份。
     *
     * @param state {@code unsupported}（我们没有这一层）
     * @param reasonCode 为什么
     * @param missingEvidence 缺哪块证据
     * @param remediation 怎么补；没有可补的路径时是空数组
     */
    record IdentityView(String state, String reasonCode, List<String> missingEvidence,
                        List<Remediation> remediation) {
    }

    /**
     * 一条回执的展示投影。
     *
     * @param schemaVersion 恒为 1
     * @param selectorId 不透明选择器，也是游标
     * @param occurredAt 发生时刻，Unix 毫秒
     * @param action 做了什么
     * @param decision 结论
     * @param enforcement 这道决定改没改结果
     * @param provenance 谁产的这条回执
     * @param missingEvidence 缺哪块证据（这里恒空）
     * @param remediation 怎么补（这里恒空）
     */
    record Display(int schemaVersion, String selectorId, long occurredAt, ActionView action,
                   DecisionView decision, EnforcementView enforcement, ProvenanceView provenance,
                   List<String> missingEvidence, List<Remediation> remediation) {

        static Display of(AuditRunInspect.Decision decision) {
            return new Display(1, decision.selectorId(), decision.occurredAt().toEpochMilli(),
                    new ActionView(decision.family(), decision.operation()),
                    new DecisionView(decision.outcome(), decision.reasonCode()),
                    new EnforcementView(decision.coverageState(),
                            "enforced".equals(decision.coverageState()) ? 1 : 0,
                            "enforced".equals(decision.coverageState()) ? 1 : 0, List.of()),
                    decision.producer() == null
                            ? new ProvenanceView("unverified", null)
                            : new ProvenanceView("verified", decision.producer()),
                    List.of(), List.of());
        }
    }

    /**
     * 做了什么。
     *
     * @param family {@code approval} / {@code message} / {@code generic}
     * @param operation 工具名或 {@code delivery}
     */
    record ActionView(String family, String operation) {
    }

    /**
     * 结论。
     *
     * @param outcome {@code allowed} / {@code denied} / {@code not-applicable} / {@code unknown}
     * @param reasonCode 为什么
     */
    record DecisionView(String outcome, String reasonCode) {
    }

    /**
     * 这道决定改没改结果。
     *
     * @param coverageState {@code enforced} / {@code attribution-only} / …
     * @param policyCount 用到的策略条数
     * @param grantCount 用到的授权条数
     * @param contextFieldsUsed 用到的上下文字段
     */
    record EnforcementView(String coverageState, int policyCount, int grantCount,
                           List<String> contextFieldsUsed) {
    }

    /**
     * 谁产的这条回执。
     *
     * @param state {@code verified}（闭集里的生产者）/ {@code unverified}（拿不到）
     * @param producer {@code operator-approval} / {@code message-delivery} / …；{@code unverified} 时不存在
     */
    record ProvenanceView(String state, String producer) {
    }

    /**
     * 这批回执的覆盖度。
     *
     * @param state {@code enforced} / {@code attribution-only} / {@code unknown}
     * @param missingEvidence 缺哪块证据（这里恒空）
     */
    record CoverageView(String state, List<String> missingEvidence) {
    }

    /**
     * 补法。
     *
     * @param code 机器认的码
     * @param text 人话
     */
    record Remediation(String code, String text) {
    }
}

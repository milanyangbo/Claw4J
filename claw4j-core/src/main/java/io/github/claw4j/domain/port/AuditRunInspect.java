package io.github.claw4j.domain.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 一次 run 的全景（ADR-0037 票 14）：照 OpenClaw 的 {@code audit.run.inspect}。
 *
 * <p>它与 {@link AuditQuery} 的区别是轴不同：那个是**时间线**（"最近发生了什么"，跨 run），
 * 这个是**单次全景**（"这一跑谁发起的、批了什么、为什么放行或拒绝、还缺哪块证据"）。
 * 两者读的是同一批表，但回答的问题不同，所以是两个端口而不是一个带开关的方法。
 *
 * <p>回执的合并顺序照它固定下来：admission → approval → message → generic → cron
 * （{@code docs/cli/audit.md:359-362}）。我们只有其中三类有生产者（approval / message / generic），
 * 顺序仍然照它排——将来补上另外两类时不用改这个契约。
 *
 * @see <a href="https://github.com/openclaw/openclaw/blob/main/packages/gateway-protocol/src/schema/audit-run.ts">
 *     audit-run.ts</a>
 */
public interface AuditRunInspect {

    /** 一次回执最多几条：与它的 {@code decisionLimit} 上界同一个数（{@code audit-run.ts:289}）。 */
    int MAX_LIMIT = 100;

    /**
     * 没给 {@code decisionLimit} 时取几条。
     *
     * <p>它只规定了 1–100 的边界、没写默认值；取 50 是为了让"一次审批流程"（通常几条）一页看得完，
     * 同时不至于把一百条回执一次吐出去。要改只动这一个常量。
     */
    int DEFAULT_LIMIT = 50;

    /**
     * 查一次 run 的全景。
     *
     * @param runId 这一轮的编号
     * @param decisionLimit 回执条数，1–{@link #MAX_LIMIT}
     * @param decisionCursor 上一页的 {@link Report#nextCursor()}；null 表示从头
     * @return 全景；**空表示游标不认识**（那一行已经过期或根本不是这里发的）——调用方据此回 400，
     *     不要当成"从头查"：那种静默重来会让客户端把同一页看成新的一页
     */
    Optional<Report> inspect(String runId, int decisionLimit, String decisionCursor);

    /**
     * 一次 run 的全景。
     *
     * @param runKnown 这一轮在库里找不找得到（它自己的 {@code run.status}）：找不到也回，
     *     但回执是空的——与它"unknown 不等于允许"同一个态度
     * @param decisions 这一页回执，按时间倒序
     * @param nextCursor 下一页游标；没有更多时为 null
     */
    record Report(boolean runKnown, List<Decision> decisions, String nextCursor) {
    }

    /**
     * 一条回执（它的 {@code DecisionReceiptDisplayV1}）。
     *
     * <p>**只是展示用的投影**：原文（owner 的 receipt、resolution、event id）不进这里，
     * 与它"Raw owner receipts and a {@code decisions} key never cross the Gateway boundary"
     * 同一句话（{@code docs/cli/audit.md:349-351}）。
     *
     * @param selectorId 不透明选择器：{@code approval-decision:<id>} / {@code message-decision:<seq>} /
     *     {@code generic-decision:<tool>}。游标就是它——用序号做游标的话，别人的一行新增就会让
     *     下一页错位
     * @param occurredAt 这条决定发生的时刻
     * @param family 哪一类：{@code approval} / {@code message} / {@code generic}
     * @param operation 具体动作（审批是工具名，投递是 {@code delivery}）
     * @param outcome {@code allowed} / {@code denied} / {@code not-applicable} / {@code unknown}
     * @param reasonCode 为什么是这个结论
     * @param coverageState {@code enforced}（真改了结果的闸）/ {@code attribution-only}（只留了记录）/
     *     {@code unattributed} / {@code unknown} / {@code unsupported}
     * @param producer 它的 {@code producer} 闭集里的一个；null 表示拿不到"谁产的"，报文里落
     *     {@code {state:"unverified"}}
     */
    record Decision(String selectorId, Instant occurredAt, String family, String operation,
                    String outcome, String reasonCode, String coverageState, String producer) {
    }
}

package io.github.claw4j.agent.audit;

import io.github.claw4j.agent.approval.CallFingerprint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 审计的规则层（ADR-0027 决定二）：把「什么时候该记一条什么事件」收在一处，落库交给
 * {@link AuditLedger}。
 *
 * <p>它记的是事件，不是「某次调用的行」：一次工具调用产生两条（{@code tool.action.started} 与
 * {@code tool.action.finished}），一次运行两条（{@code agent.run.started} 与
 * {@code agent.run.finished}）。这是 OpenClaw 的形状（{@code packages/gateway-protocol/src/schema/audit.ts:10}），
 * 也是审计能回答「跑了多久」的前提——只有结束那一条时，两次调用的先后只能靠时间戳猜。
 *
 * <p>等审批不写审计：那件事的真相在 {@code approval_request} 一张表里（谁提的、批没批、谁批的、
 * 什么时候）。同一个事实写两遍，第一个代价是「今天有几条被拒」要 union 两张表，第二个代价是两张表
 * 迟早对不上。
 *
 * <p>只写元数据：参数原文、结果、异常消息都不进来。指纹用 {@link CallFingerprint}，与审批那一侧
 * 同一个函数，于是「这次执行是哪次批准的」能直接对上，而参数本身不进库。
 *
 * <p>唯一一处靠进程内状态的地方：{@link #granted} 把刚兑现的那条批准记下来，等执行那一下取走。
 * 兑现发生在 Advisor 里、执行发生在工具回调里，两处拿不到同一份上下文。代价写在字段上：只在单进程内
 * 成立、兑现了没执行会留下一个不会被取走的条目、键里带 runId + 工具名 + 指纹三样。
 */
public class ToolCallAudit {

    /**
     * 兑现与执行之间的那条线能活多久。取十分钟，与默认的审批有效期同一个量级。
     */
    private static final Duration HANDOFF_TTL = Duration.ofMinutes(10);

    /**
     * 这条线最多记多少条。它只是线索，不是账本——超了整份清掉，
     * 比"一直涨到内存告警"诚实：清掉的表现是那几行审计少了审批编号。
     */
    private static final int HANDOFF_LIMIT = 256;

    private static final Logger log = LoggerFactory.getLogger(ToolCallAudit.class);

    /** 为 null 表示这个部署没有账本：审计无处可写，于是什么都不写（而不是造一个假账本）。 */
    private final AuditSink ledger;

    /** 兑现过的批准，等着被执行那一下取走。键是 runId + 工具名 + 指纹。 */
    private final Map<String, Pending> granted = new ConcurrentHashMap<>();

    /**
     * 规则层，落库交给 {@code ledger}。
     *
     * @param ledger 审计落点，为 null 时什么都不写
     */
    public ToolCallAudit(AuditSink ledger) {
        this.ledger = ledger;
    }

    /** 一条兑现过的批准，带着它的到期点（过了就不再被取走）。 */
    private record Pending(String approvalId, Instant expiresAt) {
    }

    /**
     * 一轮开始。
     *
     * @param scope 这一轮的身份与位置
     */
    public void runStarted(AuditEvent.Scope scope) {
        write(AuditEvent.runStarted(scope));
    }

    /**
     * 一轮结束。
     *
     * @param scope 这一轮的身份与位置
     * @param status 这一轮的结局
     * @param errorCode 归一化错误码，成功时为 null
     */
    public void runFinished(AuditEvent.Scope scope, AuditStatus status, AuditErrorCode errorCode) {
        write(AuditEvent.runFinished(scope, status, errorCode));
    }

    /**
     * 一次工具调用开始。
     *
     * <p>被策略挡住的调用不该调它：那种调用根本没有开始，只有一条 {@code finished / blocked}。
     * 两条都写的话，配额统计会把「想调但没调成」算成「调过」。
     *
     * @param scope 这一轮的身份与位置
     * @param toolName 工具名，空白时不写
     * @param argsJson 工具参数原文，只用来算指纹
     */
    public void toolStarted(AuditEvent.Scope scope, String toolName, String argsJson) {
        if (isBlank(toolName)) {
            return;
        }
        write(AuditEvent.toolStarted(scope, toolName, fingerprint(toolName, argsJson)));
    }

    /**
     * 一次工具调用结束（成功或抛异常）。
     *
     * @param scope 这一轮的身份与位置
     * @param toolName 工具名，空白时不写
     * @param argsJson 工具参数原文，只用来算指纹
     * @param failure 工具抛出的异常类型名；null 表示正常返回。只用来判「成没成」，类型名本身不落库
     *     ——那一列是归一化错误码，而类型名是开放的。要查是哪个异常，trace 的 span 上有
     */
    public void toolFinished(AuditEvent.Scope scope, String toolName, String argsJson,
                             String failure) {
        if (isBlank(toolName)) {
            return;
        }
        String digest = fingerprint(toolName, argsJson);
        write(AuditEvent.toolFinished(scope, toolName, digest,
                failure == null ? AuditStatus.SUCCEEDED : AuditStatus.FAILED,
                failure == null ? null : AuditErrorCode.TOOL_FAILED,
                take(scope == null ? null : scope.runId(), toolName, digest)));
    }

    /**
     * 被拦下来了，而且再等也不会通过：没权限、或者这个工具的权限根本没登记。
     *
     * <p>只写一条 {@code finished / blocked}：动作没有开始，就没有开始那一条。回给模型的那句话
     * （「缺 tools 权限」）不进来——审计记的是归一化错误码，那句话的语气会随文案改动而变，把它写进
     * 「为什么没成」那一格，三个月后按它做统计就废了。
     *
     * @param scope 这一轮的身份与位置
     * @param toolName 工具名，空白时不写
     * @param argsJson 工具参数原文，只用来算指纹
     */
    public void rejected(AuditEvent.Scope scope, String toolName, String argsJson) {
        if (isBlank(toolName)) {
            return;
        }
        write(AuditEvent.toolFinished(scope, toolName, fingerprint(toolName, argsJson),
                AuditStatus.BLOCKED, AuditErrorCode.TOOL_BLOCKED, null));
    }

    /**
     * 记下「这一条批准刚刚被兑现了」，等执行那一下把它写进结束事件。
     *
     * <p>没有 runId 时不记：不在一个 run 里的调用本来也不会落审计，记下来只占一个永远不会被取走的槽。
     *
     * @param runId 这一轮的编号
     * @param toolName 工具名
     * @param argsDigest 参数指纹
     * @param approvalId 审批编号
     */
    public void granted(String runId, String toolName, String argsDigest, String approvalId) {
        if (isBlank(runId) || isBlank(toolName) || isBlank(argsDigest) || isBlank(approvalId)) {
            return;
        }
        if (granted.size() >= HANDOFF_LIMIT) {
            granted.values().removeIf(pending -> pending.expiresAt().isBefore(Instant.now()));
            if (granted.size() >= HANDOFF_LIMIT) {
                // 清空而不是乱丢一条：这条线索没有"更重要"的那一条，而留一半只会让人以为它是完整的。
                granted.clear();
                log.info("审批与执行之间的待配对记录超过 {} 条，整份清掉；之后的审计事件不会带审批编号",
                        HANDOFF_LIMIT);
            }
        }
        granted.put(key(runId, toolName, argsDigest),
                new Pending(approvalId, Instant.now().plus(HANDOFF_TTL)));
    }

    /** 写一条。写不进去只记一条 debug：审计缺一行不该把一次调用打成失败。 */
    private void write(AuditEvent event) {
        if (ledger == null) {
            return;
        }
        try {
            ledger.record(event);
        } catch (RuntimeException failure) {
            // 账本自己已经吃掉了存储异常；能走到这里的是更意外的东西，仍然不该影响这一次调用。
            log.debug("写审计事件时出错：{}", event.action().column(), failure);
        }
    }

    /** 取走那条"刚兑现的批准"。过期的当作没有：它已经不在有效期附近了，配上去只会误导。 */
    private String take(String runId, String toolName, String digest) {
        if (isBlank(runId) || isBlank(digest)) {
            return null;
        }
        Pending pending = granted.remove(key(runId, toolName, digest));
        if (pending == null) {
            return null;
        }
        return pending.expiresAt().isBefore(Instant.now()) ? null : pending.approvalId();
    }

    private static String key(String runId, String toolName, String argsDigest) {
        return runId + '\n' + toolName + '\n' + argsDigest;
    }

    /**
     * 参数指纹，与审批那条同一个函数。参数还没收全（流式下被拦在授权那一道）时为空——
     * 那时连"这一次是什么"都还说不清，记一个算出来的半截指纹比留空更糟。
     */
    static String fingerprint(String toolName, String argsJson) {
        return CallFingerprint.of(toolName, argsJson).orElse(null);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

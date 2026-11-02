package io.github.claw4j.agent.audit;

import io.github.claw4j.agent.approval.CallFingerprint;
import io.github.claw4j.agent.advisor.PiiRedactor;
import io.github.claw4j.agent.run.RunStore;
import io.github.claw4j.agent.run.StepAudit;
import io.github.claw4j.agent.run.StepInput;
import io.github.claw4j.agent.run.StepKind;
import io.github.claw4j.agent.run.StepStatus;
import io.github.claw4j.domain.principal.Principal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一次工具调用的审计行：谁来、以什么角色、调了什么工具、参数摘要与指纹、谁批的、什么决定（P07）。
 *
 * <p><b>它是 P07 唯一的写入口</b>，两个接缝都调它：鉴权 Advisor 在被拒与等审批时调
 * {@link #rejected} / {@link #awaitingApproval}，工具回调用 {@link #executed}。
 * 同一件事只有一个类在写，才有资格说"审计与执行是同一份真相"（ADR-0023）。
 *
 * <p><b>它不建表</b>：写的是一行 {@code agent_step}（kind = {@code tool_calls}），
 * 加上 {@link StepAudit} 那几个字段。读的时候走 {@code claw4j_run.audit_trail} 这个视图——
 * 视图把 step、run、approval 接在一起，于是"这次调用是哪次批准的、谁批的、结论是什么"
 * 一条 select 就能答。
 *
 * <p><b>只落摘要与指纹，不落原文</b>：参数原文里可能有凭据与个人信息（ADR-0016 同一条纪律），
 * 审计存的是 {@code sha256(工具名 + 规范化参数)} 与前 200 字符的脱敏摘要。
 * 摘要不是为了给人读全文，是为了让审批人与审计人认得出"这是一次查表"。
 *
 * <p><b>唯一一处靠进程内状态的地方</b>：{@link #granted} 把"刚兑现的那条批准"
 * 记下来，等执行那一下由 {@link #executed} 取走——因为兑现发生在 Advisor 里，
 * 而执行发生在工具回调里，这两处拿不到同一份上下文（ToolContext 是每次执行现造的，
 * Advisor 的 param 进不去）。三条代价，写在这里而不是留给下一个人猜：
 *
 * <ul>
 *   <li>只在单进程内成立。多副本部署时兑现与执行可能落在不同进程上，
 *       那一行的 {@code approval_id} 会是空的——审计仍然有这一行，只是少了那条线。</li>
 *   <li>兑现了但没执行（流被掐断、进程重启）会留下一个不会被取走的条目。
 *       所以有 TTL 与容量上限：过期的先清，超上限整份清掉。丢了只影响那一条线。</li>
 *   <li>键里带 runId、工具名与指纹三样。只带工具名的话，同一轮里两个同工具不同参数的调用
 *       会互相顶掉；带上指纹之后，"同一次调用"这个判定与审批那一侧是同一个函数。</li>
 * </ul>
 *
 * <p><b>写不进去不抛</b>：审计是记账，不是回答的前置条件（与 {@code JdbcRunStore} 同一个口径）。
 * 降级的后果是"这一行没记上"，而不是让一次正常的工具调用失败。
 */
public class ToolCallAudit {

    /** 参数摘要的字符上限。与库里那一列（varchar(256)）留出余量的同一口径。 */
    public static final int SUMMARY_CHARS = 200;

    /**
     * 兑现与执行之间的那条线能活多久。取十分钟，与默认的审批有效期同一个量级：
     * 一次兑现之后还没执行的调用，本来也不该在很久之后才出现。
     */
    private static final Duration HANDOFF_TTL = Duration.ofMinutes(10);

    /**
     * 这条线最多记多少条。它只是线索，不是账本——超了整份清掉，
     * 比"一直涨到内存告警"诚实：清掉的表现是那几行审计少了审批编号。
     */
    private static final int HANDOFF_LIMIT = 256;

    private static final Logger log = LoggerFactory.getLogger(ToolCallAudit.class);

    /** 为 null 表示这个部署没有 run 存储：审计无处可写，于是什么都不写（而不是造一个假存储）。 */
    private final RunStore runs;

    /** 兑现过的批准，等着被执行那一下取走。键是 runId + 工具名 + 指纹。 */
    private final Map<String, Pending> granted = new ConcurrentHashMap<>();

    public ToolCallAudit(RunStore runs) {
        this.runs = runs;
    }

    /** 一条兑现过的批准，带着它的到期点（过了就不再被取走）。 */
    private record Pending(String approvalId, Instant expiresAt) {
    }

    /**
     * 记下"这一条批准刚刚被兑现了"，等执行那一下把它写进审计行。
     *
     * <p>没有 runId 时不记：不在一个 run 里的调用（子 Agent 那一层还没有 runId）本来就不会
     * 落审计行，记下来也只会占一个永远不会被取走的槽。
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
                log.info("审批与执行之间的待配对记录超过 {} 条，整份清掉；之后的审计行不会带审批编号",
                        HANDOFF_LIMIT);
            }
        }
        granted.put(key(runId, toolName, argsDigest),
                new Pending(approvalId, Instant.now().plus(HANDOFF_TTL)));
    }

    /**
     * 真跑了一次（工具自己抛了异常也算"跑过"：越权与报错是两件事，混在一起就分不出该改配置还是该修工具）。
     *
     * @param failure 工具抛出的异常类型名；null 表示正常返回。
     *                只记类型不记 message：message 里常有数据（一条贴了 SQL 的报错就够搬走一张表）
     */
    public void executed(String runId, Principal principal, String toolName, String argsJson,
                         String failure) {
        if (isBlank(runId) || isBlank(toolName)) {
            return;
        }
        String digest = digestOf(toolName, argsJson);
        write(new StepInput(runId, StepKind.TOOL_CALLS, null, toolName,
                failure == null ? null : "工具抛出 " + failure,
                failure == null ? StepStatus.DONE : StepStatus.FAILED,
                StepAudit.executed(userOf(principal), roleOf(principal), digest,
                        summaryOf(argsJson), take(runId, toolName, digest))));
    }

    /**
     * 被拦下来了，而且再等也不会通过：没权限、或者这个工具的权限根本没登记。
     *
     * @param reason 回给模型的那句话。它是我们自己的话（哪条不满足），不是业务数据，
     *               所以可以整句进库——审计里那句"为什么"正是最常被查的一格
     */
    public void rejected(String runId, Principal principal, String toolName, String argsJson,
                         String reason) {
        String digest = digestOf(toolName, argsJson);
        writeRefusal(runId, toolName, reason, StepAudit.rejected(
                userOf(principal), roleOf(principal), digest, summaryOf(argsJson)));
    }

    /**
     * 停在等人点头：这一行带着审批编号，批准之后它会被执行那一行接上。
     *
     * <p>status 记 {@link StepStatus#FAILED} 而不是 {@code awaiting}：
     * "等审批"这个状态属于另一类 step（{@code approval}），而那一行会随着结论被改掉；
     * 这一行记的是"这次调用没有发生"，它不该随着谁点了按钮而变。
     */
    public void awaitingApproval(String runId, Principal principal, String toolName, String argsJson,
                                 String approvalId, String reason) {
        String digest = digestOf(toolName, argsJson);
        writeRefusal(runId, toolName, reason, StepAudit.awaiting(
                userOf(principal), roleOf(principal), digest, summaryOf(argsJson), approvalId));
    }

    private void writeRefusal(String runId, String toolName, String reason, StepAudit audit) {
        if (isBlank(runId) || isBlank(toolName)) {
            return;
        }
        write(new StepInput(runId, StepKind.TOOL_CALLS, null, toolName, reason,
                StepStatus.FAILED, audit));
    }

    /** 落一行。写不进去只记一条 debug：审计缺一行不该把一次调用打成失败。 */
    private void write(StepInput input) {
        if (runs == null) {
            return;
        }
        try {
            if (runs.append(input).isEmpty()) {
                log.debug("审计这一行没落下去：run={} tool={}", input.runId(), input.toolNames());
            }
        }
        catch (RuntimeException failure) {
            // 存储降级（库没迁、连接断了）在 store 那一层已经消化成空值；
            // 能走到这里的是更意外的东西，仍然不该影响这一次工具调用。
            log.debug("写审计行时出错：run={} tool={}", input.runId(), input.toolNames(), failure);
        }
    }

    /** 取走那条"刚兑现的批准"。过期的当作没有：它已经不在有效期附近了，配上去只会误导。 */
    private String take(String runId, String toolName, String digest) {
        if (isBlank(digest)) {
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
    static String digestOf(String toolName, String argsJson) {
        return CallFingerprint.of(toolName, argsJson).orElse(null);
    }

    /**
     * 给人看的参数摘要：脱敏之后截断。
     *
     * <p>顺序与审批那一侧一致（先脱敏再截断）：反过来的话，一个手机号被截成两半，
     * 两半都不再匹配号码规则，于是掩码失效、前半截原样进库。
     */
    static String summaryOf(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) {
            return null;
        }
        String masked = PiiRedactor.mask(argsJson).strip();
        return masked.length() <= SUMMARY_CHARS ? masked : masked.substring(0, SUMMARY_CHARS) + "…";
    }

    private static String userOf(Principal principal) {
        return principal == null ? null : principal.userId();
    }

    private static String roleOf(Principal principal) {
        return principal == null ? null : principal.role();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

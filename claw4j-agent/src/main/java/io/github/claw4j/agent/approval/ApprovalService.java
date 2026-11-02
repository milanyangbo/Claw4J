package io.github.claw4j.agent.approval;

import io.github.claw4j.agent.run.RunResumer;
import io.github.claw4j.agent.run.RunStore;
import io.github.claw4j.agent.run.StepStatus;
import io.github.claw4j.domain.ApprovalVerdict;
import io.github.claw4j.domain.ApproverRoster;
import io.github.claw4j.domain.port.ApprovalDecider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 一次审批决定的落点（P02、P03）：看这个人有没有资格批、定结论、把那一步从 awaiting 改掉、
 * 然后叫醒停在原地的 run。
 *
 * <p><b>资格先判，结论后落</b>（P03）：不在名单里的人、以及提出这条请求的人，都<b>不落结论</b>——
 * 那条请求仍然挂着等别人，而不是被点废。先判资格还有一个顺序上的便宜：资格判定要读一次行
 * （拿 {@code owner}），而"结论落不落得下来"那条 update 必须在读懂之后，
 * 否则自批的那一下已经写进去了，再想撤回来就得写一条补偿语句。
 *
 * <p><b>三步的顺序不是随意的</b>：
 * <ol>
 *   <li><b>先落结论</b>——续跑跑起来之后，工具那一层紧接着会问"有没有一条有效批准"，
 *       而它读的正是这次 update。先叫醒再落结论的话，模型会在一个还没有批准的世界里
 *       重新发起同一个调用，于是它立刻又被拦一次（而人会以为自己已经批过了）。</li>
 *   <li><b>再改 step</b>——recap 是续跑时模型唯一能看到的进度，那一步还写着"等审批"的话，
 *       它会先把"我在等人批"再说一遍。</li>
 *   <li><b>最后续跑</b>——前两步都只改了库，没有它们也能"成功"返回；
 *       而续跑一旦开始就花 token、调模型、执行工具，是这一步里唯一不可逆的动作。</li>
 * </ol>
 *
 * <p><b>一次结论只能落一次</b>：判定与写入是一条语句（{@code where status = 'pending'}），
 * 并且带 {@code expires_at > now()}。两个审批人同时点、或者同一个人点了两次，
 * 第二次拿到的是 {@link ApprovalVerdict#ALREADY_DECIDED}，而不是一次新的续跑——
 * 否则那件事会被同一个批准唤醒两遍。
 */
public class ApprovalService implements ApprovalDecider {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

    /**
     * 落结论，并且只在"还挂着、还没过期"的那一行上落得下来。
     *
     * <p>把 {@code expires_at > now()} 放进 where 而不是先查后判：先查后判会留下一个
     * 窗口——查的时候还在有效期内，写的时候已经过了。那个窗口里落下的 granted，
     * 是一条"批得太晚但还是生效了"的记录。
     */
    private static final String CLAIM_SQL =
            "update claw4j_run.approval_request set status = ?, decided_at = now(),"
                    + " decided_by = ?, reason = ?"
                    + " where id = ? and status = 'pending' and expires_at > now()";

    /**
     * 读一条请求现在的样子：资格判定要 {@code owner}，落不下去时要拿状态与过期与否解释。
     * 一次读全——落结论那条 update 仍然是唯一的判定点。
     */
    private static final String ROW_SQL =
            "select status, expires_at <= now() as expired, run_id, owner, tool_name"
                    + " from claw4j_run.approval_request where id = ?";

    private final JdbcTemplate jdbc;

    private final RunStore runs;

    /**
     * 续跑那条腿。可以为 null——没有 Agent 的部署（只装配了 run 存储）里，批准照样落得下来，
     * 只是没人接着做。那时如实说出来，而不是假装续上了。
     */
    private final RunResumer resumer;

    /** 谁能批（P03）。名单为空时谁都批不了——装配时在这里喊一次，别让它表现成"请求默默过期"。 */
    private final ApproverRoster roster;

    public ApprovalService(JdbcTemplate jdbc, RunStore runs, RunResumer resumer, ApproverRoster roster) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.resumer = resumer;
        this.roster = Objects.requireNonNull(roster, "roster");
        if (roster.nobody()) {
            log.warn("claw4j.approval.approvers 一个人都没有：待批请求会一直挂着，直到过期。"
                    + "要么把审批人填进去（飞书填 open_id，HTTP 填用户名），要么别把任何动作标成“要审批”。");
        }
    }

    @Override
    public Result decide(String approvalId, String approver, boolean approved, String reason) {
        if (approvalId == null || approvalId.isBlank()) {
            return new Result(ApprovalVerdict.NOT_FOUND, "没有审批编号，不知道要批哪一条。");
        }
        Optional<Row> found = look(approvalId);
        if (found.isEmpty()) {
            return new Result(ApprovalVerdict.NOT_FOUND, "没有这个审批编号。");
        }
        Row row = found.get();
        // 资格这一关在写之前：不合格时那一行仍然 pending，别人还能批。
        Optional<ApprovalVerdict> refused = roster.refuse(approver, row.owner());
        if (refused.isPresent()) {
            return new Result(refused.get(), refusalText(refused.get()));
        }
        if (claim(approvalId, approver, approved, reason) == 0) {
            // 读的时候还在等，写的时候落不下来：已经有结论 / 刚好过期 / 另一个人刚落完。
            return explanationOf(row);
        }
        String note = note(row.toolName(), approvalId, approved, reason);
        if (row.runId() == null || row.runId().isBlank()) {
            // 不在一个 run 里（子 Agent，A10）：结论落下了，但没有"接着做"这回事。
            return new Result(verdictOf(approved), approved ? "已批准。" : "已拒绝。");
        }
        runs.settleApprovalStep(row.runId(), approved ? StepStatus.DONE : StepStatus.FAILED);
        return resume(row, note, approved);
    }

    /** 落结论。返回 0 表示那一行已经不是"等着且没过期"了。 */
    private int claim(String approvalId, String approver, boolean approved, String reason) {
        try {
            return jdbc.update(CLAIM_SQL, approved ? "granted" : "denied", approver, reason, approvalId);
        } catch (RuntimeException failure) {
            // 落不下来时不能假装批了：调用方（审批卡片）要告诉点按钮的人"没生效"，让他再点一次。
            log.error("审批结论落不下来 approval={}", approvalId, failure);
            return 0;
        }
    }

    private Result resume(Row row, String note, boolean approved) {
        if (resumer == null) {
            return new Result(verdictOf(approved),
                    approved
                            ? "已批准，但这个部署没有 Agent 在跑，这件事不会自动接着做。"
                            : "已拒绝，这个部署没有 Agent 在跑，理由没能回灌给模型。");
        }
        Optional<String> answer = resumer.resume(row.runId(), note);
        if (answer.isEmpty()) {
            // 结论确实落下了，所以 verdict 不改；但"接着做"没发生，必须说出来——
            // 说"已批准"而不提这一句，用户会一直等一个不会来的回答。
            return new Result(verdictOf(approved),
                    (approved ? "已批准" : "已拒绝")
                            + "，但这件事实在没能自动接着做（它不在等待状态，或者读不到发起人）。"
                            + "让提这个请求的人再说一句“继续”。");
        }
        return new Result(verdictOf(approved),
                approved ? "已批准，这件事已经接着往下做了。" : "已拒绝，理由已经交给模型。");
    }

    private static ApprovalVerdict verdictOf(boolean approved) {
        return approved ? ApprovalVerdict.GRANTED : ApprovalVerdict.DENIED;
    }

    /**
     * 资格不合格那句话。两档分开说：一个是"找别人"，一个是"让提请求的人找别人"——
     * 他可能就在名单里，说"你不是审批人"会让他去翻自己名字有没有拼错。
     */
    private static String refusalText(ApprovalVerdict refused) {
        if (refused == ApprovalVerdict.SELF_APPROVAL) {
            return "这条请求是你自己提的，不能自己批——自行批准等于没有审批。找名单里的另一个人点。";
        }
        return "你不在审批人名单里（claw4j.approval.approvers），这条请求得由名单里的人点。";
    }

    /**
     * 读一条请求现在的样子。
     *
     * <p>读不出来（库连不上、表没迁）按"没有这一条"处理：这一侧是<b>做决定</b>的那一侧，
     * 读不到就什么都不能落——与 {@code JdbcApprovalLedger} 里"读不到按没有批准处理"同一个方向。
     */
    private Optional<Row> look(String approvalId) {
        List<Row> rows;
        try {
            rows = jdbc.query(ROW_SQL, this::rowOf, approvalId);
        } catch (RuntimeException failure) {
            log.warn("审批表读不出来 approval={}", approvalId, failure);
            return Optional.empty();
        }
        return rows.stream().findFirst();
    }

    /**
     * 落不下来时，究竟是哪一种落不下来。
     *
     * <p>三种答案分开，因为它们对应三句完全不同的话：已经定过（别点了，看结果）、
     * 过期了（让他重新发起）、以及读的时候还在等但写的时候被人抢先了。
     */
    private static Result explanationOf(Row row) {
        if (!"pending".equals(row.status())) {
            return new Result(ApprovalVerdict.ALREADY_DECIDED,
                    "这条审批已经有结论了（" + row.status() + "），再点一次不会改变它。");
        }
        if (row.expired()) {
            return new Result(ApprovalVerdict.EXPIRED,
                    "这条审批已经过期了。让提这个请求的人重新发起一次——过期的批准不能补点。");
        }
        // 还挂着、也没过期，却更新不到：只可能是并发（另一个人刚落下结论）。
        return new Result(ApprovalVerdict.ALREADY_DECIDED, "这条审批刚刚被别人定过结论了。");
    }

    /**
     * 回灌给模型的那句话。
     *
     * <p><b>拒绝也要给理由，而且要说"别再发一次"</b>：模型看不到审批系统，
     * 只看到"上一轮被拦了"。不说清为什么，它下一轮会把同一个请求原样再来一遍；
     * 而说清了理由之后，它至少知道该换个参数、换条路，或者直接告诉用户不行。
     */
    private static String note(String toolName, String approvalId, boolean approved, String reason) {
        if (approved) {
            return "工具 " + toolName + " 的审批已经通过（编号 " + approvalId
                    + "）。下一步就是执行它，不要再问用户要不要做。";
        }
        StringBuilder text = new StringBuilder("工具 ").append(toolName)
                .append(" 的审批被拒绝了（编号 ").append(approvalId).append("）");
        if (reason != null && !reason.isBlank()) {
            text.append("，理由：").append(reason.strip());
        }
        return text.append("。不要再请求同一个操作：同一个请求再发一次也不会被批准，")
                .append("换个做法，或者直接告诉用户这条路走不通。").toString();
    }

    /**
     * 读一条请求的当前样子（审批卡片/接口渲染用它）。
     *
     * <p>{@code expired} 在 SQL 里算：{@code expires_at <= now()} 用的是库的时钟，
     * 与写下那一行时同一个来源。拿 JVM 时钟去比，会在两者有偏差的部署里让卡片显示
     * "还有 3 分钟"而点下去直接失败——那种不一致只能靠翻日志解释。
     */
    @Override
    public Optional<Pending> find(String approvalId) {
        if (approvalId == null || approvalId.isBlank()) {
            return Optional.empty();
        }
        try {
            return jdbc.query(
                    "select id, run_id, owner, tool_name, args_summary, status,"
                            + " expires_at <= now() as expired, expires_at"
                            + " from claw4j_run.approval_request where id = ?",
                    this::pendingOf, approvalId).stream().findFirst();
        } catch (RuntimeException failure) {
            log.warn("读审批请求失败 approval={}", approvalId, failure);
            return Optional.empty();
        }
    }

    private Pending pendingOf(ResultSet row, int index) throws SQLException {
        return new Pending(row.getString("id"), row.getString("run_id"), row.getString("owner"),
                row.getString("tool_name"), row.getString("args_summary"), row.getString("status"),
                row.getBoolean("expired"), row.getObject("expires_at", java.time.OffsetDateTime.class)
                        .toInstant());
    }

    private Row rowOf(ResultSet row, int index) throws SQLException {
        return new Row(row.getString("status"), row.getBoolean("expired"),
                row.getString("run_id"), row.getString("owner"), row.getString("tool_name"));
    }

    private record Row(String status, boolean expired, String runId, String owner, String toolName) {
    }
}

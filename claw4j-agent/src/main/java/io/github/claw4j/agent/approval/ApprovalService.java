package io.github.claw4j.agent.approval;

import io.github.claw4j.agent.approval.PrincipalSnapshot;
import io.github.claw4j.domain.ApprovalVerdict;
import io.github.claw4j.domain.ApproverRoster;
import io.github.claw4j.domain.NotifyPolicy;
import io.github.claw4j.domain.port.AgentPort;
import io.github.claw4j.domain.port.ApprovalDecider;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.principal.Principal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 一次审批决定的落点（P02、P03）：看这个人有没有资格批、定结论，然后按结论
 * 在那段对话里说一声。
 *
 * <p><b>结论落在审批行上，"接着做"落在会话上</b>（ADR-0027 决定一）。照 OpenClaw 原文：
 *
 * <blockquote>When an approval can be delivered, ordinary agent runs wait for the decision and
 * receive the exec result in the same turn. […] An operator denial returns a denied tool result
 * without running the command.<br>
 * For main-agent async approvals with an originating session, OpenClaw posts the denial back
 * into that session as an internal followup so the agent can stop waiting on the async command
 * and avoid a missing-result repair.</blockquote>
 *
 * <p><b>我们走的是后一支（异步后续）</b>：工具被拦下的那一刻这一轮就结束了（回给用户"要先审批"），
 * 没有一条线程在原地等。那一支对两种结论都要回灌：批准说"它被放行了"，
 * 拒绝说"它没有执行，不要再重试"——否则模型一直以为还在等，下一次用户随便说句什么，
 * 它答的是"还在等审批"。
 *
 * <p><b>这里曾经写反过一次</b>：把"不会以<b>拒绝系统事件</b>唤醒会话"读成了"拒绝什么都不说"。
 * OpenClaw 分得很清楚：system event（`Exec denied`）不发，但 <b>internal followup</b> 要发——
 * 那句原文的理由就写在括号里：让 agent 停止等待、免得它去修一个不存在的缺失结果。
 *
 * <p><b>为什么"同一段对话"就够了，不用把做到哪一步抄给模型</b>：上下文与 goal 都在那段对话里
 * （ADR-0025、ADR-0027 决定三）。模型看到的自然就是"刚才我要调这个工具、现在有结论了"。
 * 以前要从 step 拼一段 recap，是因为 run 挂起之后上下文被认为丢了——那个前提不成立。
 *
 * <p><b>资格先判，结论后落</b>（P03）：不在名单里的人、以及提出这条请求的人，都<b>不落结论</b>——
 * 那条请求仍然挂着等别人，而不是被点废。先判资格还有一个顺序上的便宜：资格判定要读一次行
 * （拿 {@code owner}），而"结论落不落得下来"那条 update 必须在读懂之后，
 * 否则自批的那一下已经写进去了，再想撤回来就得写一条补偿语句。
 *
 * <p><b>先落结论，再开新一轮</b>：新一轮跑起来之后，工具那一层紧接着会问"有没有一条有效批准"，
 * 而它读的正是这次 update。先叫醒再落结论的话，模型会在一个还没有批准的世界里
 * 重新发起同一个调用，于是它立刻又被拦一次（而人会以为自己已经批过了）。
 *
 * <p><b>一次结论只能落一次</b>：判定与写入是一条语句（{@code where status = 'pending'}），
 * 并且带 {@code expires_at > now()}。两个审批人同时点、或者同一个人点了两次，
 * 第二次拿到的是 {@link ApprovalVerdict#ALREADY_DECIDED}，而不是第二轮模型调用——
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
     * 读一条请求现在的样子：资格判定要 {@code owner}，落不下去时要拿状态与过期与否解释，
     * 批准时要拿来处（哪段对话、哪个通道、发起人是谁）。一次读全——落结论那条 update
     * 仍然是唯一的判定点。
     */
    private static final String ROW_SQL =
            "select status, expires_at <= now() as expired, turn_id, owner, tool_name,"
                    + " session_key, channel, chat_ref, principal_snapshot"
                    + " from claw4j_run.approval_request where id = ?";

    private final JdbcTemplate jdbc;

    /**
     * 谁有权批（P03）。名单为空时谁都批不了——装配时在这里喊一次，
     * 别让它表现成"请求默默过期"。
     */
    private final ApproverRoster roster;

    /**
     * 批准之后在那段对话里开新一轮。可以为 null——没有 Agent 的部署（只装配了审批表）里，
     * 批准照样落得下来，只是没人接着做。那时如实说出来，而不是假装续上了。
     */
    private final AgentPort agent;

    public ApprovalService(JdbcTemplate jdbc, ApproverRoster roster, AgentPort agent) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.roster = Objects.requireNonNull(roster, "roster");
        this.agent = agent;
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
        if (!approved) {
            // 拒绝：命令不执行（终态），但那一段对话要被告知——否则模型一直以为还在等。
            Reached reached = wake(row, deniedNote(row.toolName(), reason));
            return new Result(ApprovalVerdict.DENIED,
                    deniedText(row.toolName(), reason) + denialTrailer(reached));
        }
        Reached reached = wake(row, grantedNote(row.toolName(), approvalId));
        return new Result(ApprovalVerdict.GRANTED, grantedTrailer(reached));
    }

    /**
     * 结论再说到了哪一步。四种结果对应四句完全不同的话——把"结论落了"与"说到了"混成
     * 一句"已处理"，点按钮的人会以为那段对话知道，而它可能一无所知。
     */
    private enum Reached {
        /** 真的在那段对话里开了一轮。 */
        DELIVERED,
        /** 这个部署没有 Agent：结论落得下来，没人接着做，也没人可告知。 */
        NO_AGENT,
        /** 请求没留下完整的来处（哪段对话、发起人是谁）：猜一个地址比不说更糟。 */
        NO_ORIGIN,
        /** 那一轮没跑起来（模型侧报错）。结论已落库，但对话不知道。 */
        FAILED
    }

    /**
     * 回到发起请求的那段对话，开新一轮，把结论交给模型。
     *
     * <p><b>身份用发起人的快照，不用审批人的</b>（V11）：用审批人的身份跑等于"我批了
     * 就能用我的权限干"——那不是审批，是授权变更。而只存 {@code owner} 反推不出角色与
     * 可访问表，所以这一行上的 {@code principal_snapshot} 是整份身份。
     *
     * <p><b>会话键原样带过去，不换段</b>：换段就等于把上下文与 goal 丢掉，
     * 那正好是"接着做"需要的两样东西。
     *
     * <p><b>没有来处就不跑</b>：读不到发起人或会话键时如实说"没法自动接着做"。
     * 猜一个身份或猜一段对话去跑，比让人再说一句更糟——前者是提权，后者是回答落到别人那里。
     * 这一支也正好对应 OpenClaw 的"没有会话、或者会话唤不起来时，只把结论报给操作者"。
     */
    private Reached wake(Row row, String note) {
        if (agent == null) {
            return Reached.NO_AGENT;
        }
        Optional<Principal> principal = PrincipalSnapshot.decode(row.principalSnapshot());
        if (principal.isEmpty() || blank(row.sessionKey()) || blank(row.chatRef())) {
            return Reached.NO_ORIGIN;
        }
        InboundMessage message = new InboundMessage(
                principal.get(),
                row.chatRef(),
                note,
                Instant.now(),
                null,
                row.channel(),
                NotifyPolicy.DONE_ONLY,
                null,
                row.sessionKey());
        try {
            agent.reply(message);
        }
        catch (RuntimeException failure) {
            log.error("审批之后那一轮没跑起来 session={}", row.sessionKey(), failure);
            return Reached.FAILED;
        }
        return Reached.DELIVERED;
    }

    /** 落结论。返回 0 表示那一行已经不是"等着且没过期"了。 */
    private int claim(String approvalId, String approver, boolean approved, String reason) {
        try {
            return jdbc.update(CLAIM_SQL, approved ? "granted" : "denied", approver, reason, approvalId);
        }
        catch (RuntimeException failure) {
            // 落不下来时不能假装批了：调用方（审批卡片）要告诉点按钮的人"没生效"，让他再点一次。
            log.error("审批结论落不下来 approval={}", approvalId, failure);
            return 0;
        }
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
        }
        catch (RuntimeException failure) {
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
     * 批准之后交给模型的这句话。
     *
     * <p><b>要说"下一步就是执行它"</b>：模型看不到审批系统，只看到"上一轮被拦了"。
     * 不点明这一句，它下一轮会再问一遍用户要不要做——而那个按钮已经有人按过了。
     */
    private static String grantedNote(String toolName, String approvalId) {
        return "[审批通过] 工具 " + toolName + " 的审批已经通过（编号 " + approvalId
                + "）。下一步就是执行它，不要再问用户要不要做。";
    }

    /**
     * 拒绝之后交给模型的这句话。
     *
     * <p>三段都要有，逐条对着 OpenClaw 的 {@code buildExecDeniedFollowupPrompt}：
     * <b>没有执行</b>、<b>不要重试</b>、<b>没有新输出</b>，最后一句是"向用户说明为什么"。
     * 少了中间那两句，模型会把同一个调用原样再发一遍——而它已经被拒过，
     * 再来一次只是让同一个人再点一次拒绝。
     */
    private static String deniedNote(String toolName, String reason) {
        StringBuilder text = new StringBuilder("[审批被拒] 工具 ").append(toolName)
                .append(" 的这次调用没有执行，也不会有新的输出。不要再重试同一个调用，")
                .append("也不要引用早先任何一次执行的输出。");
        if (reason != null && !reason.isBlank()) {
            text.append("理由：").append(reason.strip()).append('。');
        }
        return text.append("向用户说明这次调用没有执行、以及为什么；不要声称拿到了新的结果。").toString();
    }

    /** 拒绝之后回给操作者的那一句（结论已经落了，还差"说到了哪儿"那句尾巴）。 */
    private static String deniedText(String toolName, String reason) {
        StringBuilder text = new StringBuilder("已拒绝，工具 ").append(toolName).append(" 的这次操作不会执行。");
        if (reason != null && !reason.isBlank()) {
            text.append("理由：").append(reason.strip()).append('。');
        }
        return text.toString();
    }

    /** 批下来的尾巴：说清"接着做了"还是"没人接着做"。 */
    private static String grantedTrailer(Reached reached) {
        return switch (reached) {
            case DELIVERED -> "这件事已经接着往下做了。";
            case NO_AGENT -> "但这个部署没有 Agent 在跑，这件事不会自动接着做。";
            case NO_ORIGIN -> "但这条请求没有留下完整的来处（哪段对话、发起人是谁），没法自动接着做。"
                    + "让提这个请求的人再说一句“继续”。";
            case FAILED -> "但接着做的那一轮出错了（见日志），结果可能还没送到。"
                    + "让提这个请求的人再说一句“继续”。";
        };
    }

    /** 拒掉的尾巴：说清"那段对话知道了"还是"没人可告知"。 */
    private static String denialTrailer(Reached reached) {
        return switch (reached) {
            case DELIVERED -> "那段对话已经收到这条结论。";
            case NO_AGENT -> "但这个部署没有 Agent 在跑，那段对话不会被告知。";
            case NO_ORIGIN -> "但这条请求没有留下完整的来处，那段对话不会被告知——"
                    + "让提这个请求的人知道这件事没做。";
            case FAILED -> "结论已经落库，但那段对话没被通知到（见日志）。";
        };
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
                    "select id, turn_id, owner, tool_name, args_summary, status,"
                            + " expires_at <= now() as expired, expires_at"
                            + " from claw4j_run.approval_request where id = ?",
                    this::pendingOf, approvalId).stream().findFirst();
        }
        catch (RuntimeException failure) {
            log.warn("读审批请求失败 approval={}", approvalId, failure);
            return Optional.empty();
        }
    }

    private Pending pendingOf(ResultSet row, int index) throws SQLException {
        return new Pending(row.getString("id"), row.getString("turn_id"), row.getString("owner"),
                row.getString("tool_name"), row.getString("args_summary"), row.getString("status"),
                row.getBoolean("expired"), row.getObject("expires_at", java.time.OffsetDateTime.class)
                .toInstant());
    }

    private Row rowOf(ResultSet row, int index) throws SQLException {
        return new Row(row.getString("status"), row.getBoolean("expired"),
                row.getString("owner"), row.getString("tool_name"),
                row.getString("session_key"), row.getString("channel"), row.getString("chat_ref"),
                row.getString("principal_snapshot"));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * @param sessionKey        这条请求来自哪一段对话（含段）
     * @param channel           卡片与结果的投递通道，可空
     * @param chatRef           通道内的会话地址
     * @param principalSnapshot 发起人的身份快照（JSON），可空
     */
    private record Row(String status, boolean expired, String owner, String toolName,
                       String sessionKey, String channel, String chatRef, String principalSnapshot) {
    }
}

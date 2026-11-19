package io.github.claw4j.agent.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.claw4j.agent.advisor.PiiRedactor;
import io.github.claw4j.agent.session.SessionKeys;
import io.github.claw4j.capability.exec.ExecProperties;
import io.github.claw4j.domain.ApprovalChoice;
import io.github.claw4j.domain.ApprovalVerdict;
import io.github.claw4j.domain.ApproverRoster;
import io.github.claw4j.domain.NotifyPolicy;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.port.AgentPort;
import io.github.claw4j.domain.port.ApprovalCards;
import io.github.claw4j.domain.port.Approvals;
import io.github.claw4j.domain.port.InboundMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 审批的唯一实现：请求落在 {@code claw4j_run.approval_request}，裁决落终态并回那段对话，
 * 记录（一次性兑现、过期判定）也在这一处。
 *
 * <p>三个概念共享同一张表和同一个"还挂着且没过期"的判据，所以放在一处：登记、裁决、兑现
 * 对同一行的先后顺序是这一块的全部要点。"推卡片"是 {@link #ask} 的最后一步，失败只记日志
 * ——登记成了就算成了。
 *
 * <p>顺序上的两条硬约束：
 *
 * <ul>
 *   <li><b>资格先判，结论后落</b>：不在名单里的人、以及提出这条请求的人，都不落结论——那条请求
 *       仍然挂着等别人。先落 update 再撤回来要多写一条语句。</li>
 *   <li><b>先落结论，再开新一轮</b>：新一轮里工具会问"有没有一条有效批准"，读的正是这次
 *       update。反过来模型会在一个还没有批准的世界里重发同一个调用，立刻又被拦一次。</li>
 * </ul>
 *
 * <p>一次结论只能落一次：判定与写入是一条语句（{@code where status = 'pending'}），并且带
 * {@code expires_at > now()}——两个审批人同时点，第二个拿到 {@link ApprovalVerdict#ALREADY_DECIDED}。
 */
public class JdbcApprovals implements Approvals {

    private static final Logger log = LoggerFactory.getLogger(JdbcApprovals.class);

    /** 摘要的长度上限，与 {@code args_summary varchar(512)} 对齐；先脱敏再截断，顺序不能反。 */
    private static final int SUMMARY_LIMIT = 480;

    /**
     * 落结论，只在「还挂着、还没过期」的那一行上落得下来。
     *
     * <p>{@code expires_at > now()} 放进 where 而不是先查后判：先查后判留下一个窗口——查的时候
     * 还在有效期内，写的时候已经过了，那个窗口里落下的 allowed 是一条"批得太晚但还是生效了"的记录。
     */
    private static final String CLAIM_SQL =
            "update claw4j_run.approval_request set status = ?, decision = ?,"
                    + " terminal_reason = 'user', resolved_at = now(), resolver_kind = ?,"
                    + " resolver_id = ?, reason = ?"
                    + " where id = ? and status = 'pending' and expires_at > now()";

    /** 把「还挂着但已经过点」的行落成终态：只靠 expires_at 答不了"这条最后怎么了"。 */
    private static final String EXPIRE_SQL =
            "update claw4j_run.approval_request"
                    + " set status = 'expired', decision = 'deny', terminal_reason = 'timeout',"
                    + " resolved_at = now(), resolver_kind = 'system'"
                    + " where status = 'pending' and expires_at <= now()";

    /** 用户点了一张已经过期的卡片时用：不等到清扫那一分钟。 */
    private static final String EXPIRE_ONE_SQL =
            "update claw4j_run.approval_request"
                    + " set status = 'expired', decision = 'deny', terminal_reason = 'timeout',"
                    + " resolved_at = now(), resolver_kind = 'system'"
                    + " where id = ? and status = 'pending' and expires_at <= now()";

    /** 结论是人在卡片上点的：OpenClaw 四档（device / channel / runtime / system）里我们只有这一档。 */
    private static final String RESOLVER_KIND_CHANNEL = "channel";

    /** 资格判定要 owner，落不下去时要状态与过期与否，批准时要拿来处。一次读全。 */
    private static final String ROW_SQL =
            "select status, expires_at <= now() as expired, run_id, owner, tool_name,"
                    + " session_key, channel, chat_ref, principal_snapshot, frozen_plan"
                    + " from claw4j_run.approval_request where id = ?";

    /**
     * 兑现一条批准：一条语句完成"找到 + 标成用掉"。
     *
     * <p>分成两步时，两个并发的同参数调用会同时查到同一条批准，同一次点头放行了两次副作用。
     * {@code for update skip locked} 让第二个请求直接看不到这条，于是它重新发起一次审批。
     *
     * <p>有效期在 SQL 里比：{@code now()} 与 {@code expires_at} 同一个时钟来源。
     */
    private static final String REDEEM_SQL =
            "update claw4j_run.approval_request set consumed_at = now(), consumed_by = ? where id = ("
                    + " select id from claw4j_run.approval_request"
                    + " where owner = ? and tool_name = ? and args_digest = ? and status = 'allowed'"
                    + " and consumed_at is null and expires_at > now()"
                    + " order by expires_at desc limit 1 for update skip locked)"
                    + " returning id, expires_at, frozen_plan";

    /**
     * 登记一条待批请求。
     *
     * <p>过期点用 {@code now() + 毫秒} 算，不传 JVM 算好的绝对时刻：判定那一侧用的是库的时钟。
     * 冻结件与请求一起落——批准的对象是那一条命令，执行前要拿它与现在要跑的逐字符比。
     */
    private static final String ASK_SQL =
            "insert into claw4j_run.approval_request"
                    + " (id, run_id, owner, tool_name, args_digest, args_summary, status,"
                    + " session_key, channel, chat_ref, principal_snapshot, frozen_plan,"
                    + " requested_at, expires_at)"
                    + " values (?, ?, ?, ?, ?, ?, 'pending', ?, ?, ?, ?, ?::jsonb, now(),"
                    + " now() + (? * interval '1 millisecond'))";

    private static final String PENDING_SQL =
            "select id, run_id, owner, tool_name, args_summary, status, frozen_plan,"
                    + " expires_at <= now() as expired, expires_at"
                    + " from claw4j_run.approval_request where id = ?";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbc;

    /** 谁有权批（P03）。名单为空时谁都批不了——装配时在构造里喊一次。 */
    private final ApproverRoster roster;

    /** 批准之后在那段对话里开新一轮。可以为 null：只装配了审批表的部署里，批准照样落得下来。 */
    private final AgentPort agent;

    /** {@code allow-always} 的落点。可以为 null：没装 exec 的部署里选不了那一档。 */
    private final JdbcExecAllowlist allowlist;

    /** exec 配置，只用来判"接不接受以后都放行"。可以为 null。 */
    private final ExecProperties exec;

    /** 各通道的卡片能力。空表示这个部署没有卡片，编号与 HTTP 接口照旧能用。 */
    private final List<ApprovalCards> cards;

    /** 一条请求等多久。 */
    private final long ttlMillis;

    /** 库读不出来的日志只打一次：每次工具调用都会走到这里。 */
    private volatile boolean degraded;

    /**
     * @param jdbc 主连接池，必填
     * @param roster 审批人名单，必填
     * @param agent 批准之后续跑用的门，可为 null
     * @param allowlist 持久放行的落点，可为 null
     * @param exec exec 配置，可为 null
     * @param cards 卡片能力，可为 null
     * @param ttlMillis 一条请求等多久，毫秒
     */
    public JdbcApprovals(JdbcTemplate jdbc, ApproverRoster roster, AgentPort agent,
                         JdbcExecAllowlist allowlist, ExecProperties exec,
                         List<ApprovalCards> cards, long ttlMillis) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.roster = Objects.requireNonNull(roster, "roster");
        this.agent = agent;
        this.allowlist = allowlist;
        this.exec = exec;
        this.cards = cards == null ? List.of() : List.copyOf(cards);
        this.ttlMillis = ttlMillis;
        if (roster.nobody()) {
            log.warn("claw4j.approval.approvers 一个人都没有：待批请求会一直挂着，直到过期。"
                    + "要么把审批人填进去（飞书填 open_id，HTTP 填用户名），要么别把任何动作标成“要审批”。");
        }
    }

    /**
     * 兑现一条批准，顺带把"这个表能读"这件事恢复回来。
     *
     * @param owner 审批人
     * @param toolName 工具名
     * @param argsDigest 参数指纹
     * @return 批准的凭据；读不出来时为 empty（按"没有批准"处理，出错时收紧才是安全的那一侧）
     */
    @Override
    public Optional<Grant> redeem(String owner, String toolName, String argsDigest) {
        if (blank(owner) || blank(toolName) || blank(argsDigest)) {
            // 问不出答案的那种输入：按"没有批准"走，而不是拼一个 "%" 去撞运气。
            return Optional.empty();
        }
        try {
            Optional<Grant> granted = jdbc.query(REDEEM_SQL, this::grantOf, owner, owner, toolName, argsDigest)
                    .stream().findFirst();
            if (degraded) {
                degraded = false;
                log.info("审批账本已恢复可读");
            }
            return granted;
        }
        catch (RuntimeException failure) {
            warnOnce("审批账本读不出来，标了要审批的动作这一轮一律按“没有批准”处理", failure);
            return Optional.empty();
        }
    }

    /**
     * 登记一条请求，然后试着把卡片推出去。
     *
     * <p>推不出去不影响这一轮：登记成了、编号也回给调用方了，缺的只是"更方便的那个按钮"。
     *
     * @param ask 待批请求
     * @return 审批编号；记不下来时为 empty（调用方仍然要拦下这次调用）
     */
    @Override
    public Optional<String> ask(Ask ask) {
        if (ask == null || blank(ask.owner()) || blank(ask.toolName()) || blank(ask.argsDigest())) {
            return Optional.empty();
        }
        String id = UUID.randomUUID().toString();
        Origin origin = ask.origin();
        try {
            jdbc.update(ASK_SQL, id, ask.runId(), ask.owner(), ask.toolName(), ask.argsDigest(),
                    summary(ask.argsText()),
                    origin == null ? null : origin.sessionKey(),
                    origin == null ? null : origin.channel(),
                    origin == null ? null : origin.chatRef(),
                    origin == null || origin.principal() == null ? null : encodePrincipal(origin.principal()),
                    ask.frozenPlan(),
                    ttlMillis);
            announce(ask, id);
            return Optional.of(id);
        }
        catch (RuntimeException failure) {
            warnOnce("登记审批请求失败：这一轮仍然按“没有批准”拦下，但用户拿不到审批编号", failure);
            return Optional.empty();
        }
    }

    /**
     * 落一个审批结论，并把结论投回发起请求的那段对话。
     *
     * <p>顺序固定：读行、判资格、落结论、再叫醒。{@link ApprovalChoice#ALLOW_ALWAYS} 多做一步：
     * 按命令段往 {@code exec_allowlist} 里落条目。落不进去只记日志——结论已经生效，缺的是
     * "下次不用再问"，不是"这次没放行"。
     *
     * @param approvalId 审批编号
     * @param approver 点按钮的人的 {@code userId}
     * @param choice 结论档位
     * @param reason 拒绝理由，可为 null
     * @return 结论与回给操作者的那句话
     */
    @Override
    public Result decide(String approvalId, String approver, ApprovalChoice choice, String reason) {
        if (blank(approvalId)) {
            return new Result(ApprovalVerdict.NOT_FOUND, "没有审批编号，不知道要批哪一条。");
        }
        ApprovalChoice decision = choice == null ? ApprovalChoice.DENY : choice;
        Optional<Row> found = look(approvalId);
        if (found.isEmpty()) {
            return new Result(ApprovalVerdict.NOT_FOUND, "没有这个审批编号。");
        }
        Row row = found.get();
        Optional<ApprovalVerdict> refused = roster.refuse(approver, row.owner());
        if (refused.isPresent()) {
            return new Result(refused.get(), refusalText(refused.get()));
        }
        if (decision == ApprovalChoice.ALLOW_ALWAYS && !allowsAlways(row.frozenPlan())) {
            // 这个请求不接受长期放行（ask=always，或不是 exec 的命令，或这条命令落不进库）。
            // 什么都不改：那条请求仍然挂着，等他选"批准"或"拒绝"。
            return new Result(ApprovalVerdict.NOT_APPLICABLE,
                    "这一条不接受“以后都放行”：当前档位要求每次都由人看一眼，或者它没有可持久的地方。"
                            + "点“批准”只放行这一次。");
        }
        if (claim(approvalId, approver, decision, reason) == 0) {
            // 读的时候还在等，写的时候落不下来：刚好过期 / 已经有结论 / 另一个人刚落完。
            if (expireOne(approvalId)) {
                return new Result(ApprovalVerdict.EXPIRED,
                        "这条审批已经过期了。让提这个请求的人重新发起一次——过期的批准不能补点。");
            }
            return explanationOf(row);
        }
        if (decision == ApprovalChoice.DENY) {
            if (silent(row.sessionKey())) {
                return new Result(ApprovalVerdict.DENIED,
                        deniedText(row.toolName(), reason) + denialTrailer(Reached.SILENT));
            }
            Reached reached = wake(row, deniedNote(row.toolName(), reason));
            return new Result(ApprovalVerdict.DENIED,
                    deniedText(row.toolName(), reason) + denialTrailer(reached));
        }
        int written = decision == ApprovalChoice.ALLOW_ALWAYS ? remember(row) : 0;
        Reached reached = wake(row, grantedNote(row.toolName(), approvalId, decision, written));
        return new Result(ApprovalVerdict.GRANTED, grantedTrailer(reached, decision, written));
    }

    /**
     * 读一条请求现在的样子，供审批卡片与接口渲染。
     *
     * <p>{@code expired} 在 SQL 里算：{@code expires_at <= now()} 用的是库的时钟，与写下那一行时
     * 同一个来源。拿 JVM 时钟去比，会在两者有偏差的部署里让卡片显示"还有 3 分钟"而点下去直接失败。
     *
     * @param approvalId 审批编号
     * @return 待批视图；编号为空或读不出来时为 empty
     */
    @Override
    public Optional<Pending> find(String approvalId) {
        if (blank(approvalId)) {
            return Optional.empty();
        }
        try {
            return jdbc.query(PENDING_SQL, this::pendingOf, approvalId).stream().findFirst();
        }
        catch (RuntimeException failure) {
            log.warn("读审批请求失败 approval={}", approvalId, failure);
            return Optional.empty();
        }
    }

    /**
     * 把到点的请求落成 expired。由容器按 {@code claw4j.approval.sweep-interval} 调（默认一分钟）。
     *
     * <p>失败只记 debug：清扫是后台维护，库连不上时下一轮再来。
     *
     * @return 这一轮落成终态的行数
     */
    @Scheduled(fixedDelayString = "${claw4j.approval.sweep-interval:PT1M}")
    public int expireDue() {
        try {
            int expired = jdbc.update(EXPIRE_SQL);
            if (expired > 0) {
                log.info("审批清扫：{} 条超时未处理，已落成 expired", expired);
            }
            return expired;
        }
        catch (RuntimeException failure) {
            log.debug("审批清扫这一轮没跑成", failure);
            return 0;
        }
    }

    /**
     * 把这条请求推给「它自己那段对话」。
     *
     * <p>推给会话而不是名单上的每个人：那需要一张"人 → 会话"的地址表，而飞书那边还得知
     * {@code chat_id}。卡片落回那段对话之后，名单里的人点得动，不在名单里的人点了会被拒。
     *
     * @param ask 刚登记的那条请求
     * @param approvalId 审批编号
     */
    private void announce(Ask ask, String approvalId) {
        if (cards.isEmpty()) {
            return;
        }
        Origin origin = ask.origin();
        if (origin == null || origin.channel() == null || origin.chatRef() == null) {
            return;
        }
        Pending card = new Pending(approvalId, ask.runId(), ask.owner(), ask.toolName(),
                summary(ask.argsText()), "pending", false, null, ask.allowAlways());
        for (ApprovalCards sender : cards) {
            if (!sender.channel().equals(origin.channel())) {
                continue;
            }
            try {
                sender.announce(origin.chatRef(), card);
            }
            catch (RuntimeException failure) {
                log.warn("审批卡片没发出去：通道 {} 地址 {} 审批 {}",
                        origin.channel(), origin.chatRef(), approvalId, failure);
            }
        }
    }

    /**
     * 这条请求接不接受"以后都放行"。
     *
     * <p>两个条件，对着 OpenClaw 的 {@code resolveExecApprovalAllowedDecisions}：
     * {@code ask=always} 时不接受（含义是每条都要人看）；这一次落不进库时也不接受
     * （给了按钮点下去写零条、却回一句"以后都放行"）。
     *
     * <p>判据不看工具名：只有 exec 会写冻结件，所以"有没有冻结件"就是"它有没有持久放行的落点"。
     * 拿工具名比字符串的话，库那边改一次名字，这里就静默退化成 {@code not_applicable}。
     *
     * @param frozenPlan 那一行冻结的计划，可空
     * @return 接受时为 {@code true}
     */
    private boolean allowsAlways(String frozenPlan) {
        return AgentExecApprovalPort.allowsAlways(exec, FrozenPlanJson.decode(frozenPlan));
    }

    /**
     * 把这一条批准变成一条持久放行，返回真正写下去的条数。
     *
     * <p>从审批行上的冻结件重建，不从内存里的请求对象：结论可能在另一个进程、另一天落的。
     *
     * @param row 审批行
     * @return 写下去的条目数；一段表达不出来、或没有放行表时为 0
     */
    private int remember(Row row) {
        if (allowlist == null) {
            log.warn("选了 allow-always，但这个部署没有装配 exec 的放行表：这一条只生效一次（tool={}）",
                    row.toolName());
            return 0;
        }
        return allowlist.remember(FrozenPlanJson.decode(row.frozenPlan()));
    }

    /**
     * 落结论。
     *
     * @param approvalId 审批编号
     * @param approver 审批人
     * @param choice 结论档位
     * @param reason 拒绝理由，可为 null
     * @return 影响的行数，0 表示那一行已经不是「等着且没过期」了
     */
    private int claim(String approvalId, String approver, ApprovalChoice choice, String reason) {
        boolean allowed = choice != ApprovalChoice.DENY;
        try {
            return jdbc.update(CLAIM_SQL,
                    allowed ? "allowed" : "denied",
                    switch (choice) {
                        case ALLOW_ONCE -> "allow-once";
                        case ALLOW_ALWAYS -> "allow-always";
                        case DENY -> "deny";
                    },
                    RESOLVER_KIND_CHANNEL,
                    approver,
                    reason,
                    approvalId);
        }
        catch (RuntimeException failure) {
            log.error("审批结论落不下来 approval={}", approvalId, failure);
            return 0;
        }
    }

    /**
     * 只落这一条到期行。
     *
     * @param approvalId 审批编号
     * @return true 表示它确实是「还挂着且已过期」，刚刚被落成了 expired
     */
    private boolean expireOne(String approvalId) {
        try {
            return jdbc.update(EXPIRE_ONE_SQL, approvalId) > 0;
        }
        catch (RuntimeException failure) {
            log.debug("这条审批没能落成 expired approval={}", approvalId, failure);
            return false;
        }
    }

    /**
     * 回到发起请求的那段对话，开新一轮，把结论交给模型。
     *
     * <p>身份用发起人的快照，不用审批人的（基线）：用审批人的身份跑等于"我批了就能用我的权限干"。
     * 没有来处就不跑——猜一个身份是提权，猜一段对话是回答落到别人那里。
     *
     * @param row 审批行
     * @param note 要交给模型的那句话
     * @return 说到了哪一步
     */
    private Reached wake(Row row, String note) {
        if (agent == null) {
            return Reached.NO_AGENT;
        }
        Optional<Principal> principal = decodePrincipal(row.principalSnapshot());
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
                row.sessionKey(),
                true);
        try {
            agent.reply(message);
        }
        catch (RuntimeException failure) {
            log.error("审批之后那一轮没跑起来 session={}", row.sessionKey(), failure);
            return Reached.FAILED;
        }
        return Reached.DELIVERED;
    }

    /**
     * 这段会话要不要跳过投递。
     *
     * <p>照 OpenClaw 的 {@code isSubagentSessionKey || isCronSessionKey}，<b>只对拒绝生效</b>：
     * 子 Agent 拿到的拒绝已经是它那一轮的工具结果；定时任务没有人正在读它。批准照常投，
     * 那是"接着做"的入口。
     *
     * @param sessionKey 会话键，可以为 {@code null}
     * @return 跳过投递时为 {@code true}
     */
    private static boolean silent(String sessionKey) {
        return sessionKey != null
                && (sessionKey.contains("subagent:") || sessionKey.contains(SessionKeys.CRON_PREFIX));
    }

    /**
     * 落不下来时，究竟是哪一种落不下来。
     *
     * @param row 审批行
     * @return 结论与解释
     */
    private static Result explanationOf(Row row) {
        if ("expired".equals(row.status()) || ("pending".equals(row.status()) && row.expired())) {
            return new Result(ApprovalVerdict.EXPIRED,
                    "这条审批已经过期了。让提这个请求的人重新发起一次——过期的批准不能补点。");
        }
        if (!"pending".equals(row.status())) {
            return new Result(ApprovalVerdict.ALREADY_DECIDED,
                    "这条审批已经有结论了（" + row.status() + "），再点一次不会改变它。");
        }
        return new Result(ApprovalVerdict.ALREADY_DECIDED, "这条审批刚刚被别人定过结论了。");
    }

    /**
     * 资格不合格那句话。两档分开说，因为下一步不同。
     *
     * @param refused 拒绝的原因
     * @return 回给操作者的话
     */
    private static String refusalText(ApprovalVerdict refused) {
        if (refused == ApprovalVerdict.SELF_APPROVAL) {
            return "这条请求是你自己提的，不能自己批——自行批准等于没有审批。找名单里的另一个人点。";
        }
        return "你不在审批人名单里（claw4j.approval.approvers），这条请求得由名单里的人点。";
    }

    /**
     * 批准之后交给模型的这句话。
     *
     * <p>要说"下一步就是执行它"：模型看不到审批系统，只看到上一轮被拦了。不点明这一句，它下一轮
     * 会再问一遍用户要不要做。
     *
     * @param toolName 工具名
     * @param approvalId 审批编号
     * @param choice 结论档位
     * @param written 真正写进放行表的条数
     * @return 交给模型的文本
     */
    private static String grantedNote(String toolName, String approvalId, ApprovalChoice choice, int written) {
        StringBuilder text = new StringBuilder("[审批通过] 工具 ").append(toolName)
                .append(" 的审批已经通过（编号 ").append(approvalId).append("）。");
        if (choice == ApprovalChoice.ALLOW_ALWAYS && written > 0) {
            text.append("这次选的是“以后都放行”：同一条命令段不再需要审批。");
        }
        return text.append("下一步就是执行它，不要再问用户要不要做；现在还没有任何输出。").toString();
    }

    /**
     * 拒绝之后交给模型的这句话。
     *
     * <p>四句对着 OpenClaw 的 {@code buildExecDeniedFollowupPrompt}：没有执行、不要重试、没有新输出、
     * 不引用早先的输出，最后是向用户说明为什么。
     *
     * @param toolName 工具名
     * @param reason 拒绝理由，可为 null
     * @return 交给模型的文本
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

    /**
     * 拒绝之后回给操作者的那一句。
     *
     * @param toolName 工具名
     * @param reason 拒绝理由，可为 null
     * @return 回给操作者的话
     */
    private static String deniedText(String toolName, String reason) {
        StringBuilder text = new StringBuilder("已拒绝，工具 ").append(toolName).append(" 的这次操作不会执行。");
        if (reason != null && !reason.isBlank()) {
            text.append("理由：").append(reason.strip()).append('。');
        }
        return text.toString();
    }

    /**
     * 批下来的尾巴：说清落在哪里、有没有接着做。
     *
     * <p>{@code allow-always} 只有真的写进放行表才能说"下次不再问"——写零条却这么说，是在
     * 让点按钮的人以为一件事发生了而它没有。
     *
     * @param reached 说到了哪一步
     * @param choice 结论档位
     * @param written 真正写进放行表的条数
     * @return 追加在结论后面的那句
     */
    private static String grantedTrailer(Reached reached, ApprovalChoice choice, int written) {
        String head = "";
        if (choice == ApprovalChoice.ALLOW_ALWAYS) {
            head = written > 0
                    ? "这条命令段已经持久放行，下次不再问。"
                    : "这一次已经放行，但“以后都放行”没写进放行表（见日志），下次还会问。";
        }
        return head + switch (reached) {
            case DELIVERED -> "这件事已经接着往下做了。";
            case NO_AGENT -> "但这个部署没有 Agent 在跑，这件事不会自动接着做。";
            case NO_ORIGIN -> "但这条请求没有留下完整的来处（哪段对话、发起人是谁），没法自动接着做。"
                    + "让提这个请求的人再说一句“继续”。";
            case FAILED -> "但接着做的那一轮出错了（见日志），结果可能还没送到。"
                    + "让提这个请求的人再说一句“继续”。";
            case SILENT -> "但那一段是子 Agent 或定时任务，没有可接着做的对话。";
        };
    }

    /**
     * 拒掉的尾巴：说清「那段对话知道了」还是「没人可告知」。
     *
     * @param reached 说到了哪一步
     * @return 追加在结论后面的那句
     */
    private static String denialTrailer(Reached reached) {
        return switch (reached) {
            case DELIVERED -> "那段对话已经收到这条结论。";
            case NO_AGENT -> "但这个部署没有 Agent 在跑，那段对话不会被告知。";
            case NO_ORIGIN -> "但这条请求没有留下完整的来处，那段对话不会被告知——"
                    + "让提这个请求的人知道这件事没做。";
            case FAILED -> "结论已经落库，但那段对话没被通知到（见日志）。";
            case SILENT -> "那一段是子 Agent 或定时任务，按约定不把拒绝投回去。";
        };
    }

    /**
     * 读一条请求现在的样子。
     *
     * <p>读不出来按「没有这一条」处理：这一侧是做决定的那一侧，读不到就什么都不能落。
     *
     * @param approvalId 审批编号
     * @return 那一行；读不出来时为 empty
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

    private Grant grantOf(ResultSet row, int index) throws SQLException {
        var expiresAt = row.getObject("expires_at", java.time.OffsetDateTime.class);
        return new Grant(row.getString("id"),
                expiresAt == null ? Instant.EPOCH : expiresAt.toInstant(),
                row.getString("frozen_plan"));
    }

    private Pending pendingOf(ResultSet row, int index) throws SQLException {
        return new Pending(row.getString("id"), row.getString("run_id"), row.getString("owner"),
                row.getString("tool_name"), row.getString("args_summary"), row.getString("status"),
                row.getBoolean("expired"),
                row.getObject("expires_at", java.time.OffsetDateTime.class).toInstant(),
                allowsAlways(row.getString("frozen_plan")));
    }

    private Row rowOf(ResultSet row, int index) throws SQLException {
        return new Row(row.getString("status"), row.getBoolean("expired"),
                row.getString("owner"), row.getString("tool_name"),
                row.getString("session_key"), row.getString("channel"), row.getString("chat_ref"),
                row.getString("principal_snapshot"), row.getString("frozen_plan"));
    }

    /**
     * 给人看的摘要：先脱敏，再截断。
     *
     * <p>顺序不能反。反过来的话，一个手机号会被截成两半，两半都不再匹配号码规则，于是掩码失效、
     * 前半截原样进库。
     *
     * @param raw 原始文本，可以为 {@code null}
     * @return 摘要；{@code null} 入参返回空串
     */
    private static String summary(String raw) {
        String masked = PiiRedactor.mask(raw == null ? "" : raw);
        return masked.length() <= SUMMARY_LIMIT ? masked : masked.substring(0, SUMMARY_LIMIT - 1) + "…";
    }

    private static String encodePrincipal(Principal principal) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("userId", principal.userId());
        return node.toString();
    }

    /**
     * 读回一份身份。
     *
     * <p>缺 {@code userId} 就当成读不出来，不补默认值：补出来的身份会让续跑以一个没人授予过的
     * 身份发生。旧记录里多出来的字段直接忽略。
     *
     * @param json 快照 JSON，可以为 {@code null}
     * @return 身份；读不出来时为 empty
     */
    private static Optional<Principal> decodePrincipal(String json) {
        if (blank(json)) {
            return Optional.empty();
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            JsonNode userId = node == null ? null : node.get("userId");
            if (userId == null || userId.isNull() || userId.asText().isBlank()) {
                log.warn("发起人快照缺少 userId，这件事不续跑");
                return Optional.empty();
            }
            return Optional.of(new Principal(userId.asText()));
        }
        catch (Exception broken) {
            log.warn("发起人快照读不出来，这件事不续跑", broken);
            return Optional.empty();
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private void warnOnce(String message, RuntimeException failure) {
        if (!degraded) {
            degraded = true;
            log.warn(message + "（之后同类失败不再逐条打日志）", failure);
        }
    }

    /**
     * 结论再说到了哪一步。四种结果对应四句完全不同的话——把「结论落了」与「说到了」混成一句
     * 「已处理」，点按钮的人会以为那段对话知道，而它可能一无所知。
     */
    private enum Reached {
        /** 真的在那段对话里开了一轮。 */
        DELIVERED,
        /** 这个部署没有 Agent：结论落得下来，没人接着做，也没人可告知。 */
        NO_AGENT,
        /** 请求没留下完整的来处：猜一个地址比不说更糟。 */
        NO_ORIGIN,
        /** 那一轮没跑起来（模型侧报错）。结论已落库，但对话不知道。 */
        FAILED,
        /** 那段会话是子 Agent 或定时任务：按约定不投递拒绝。 */
        SILENT
    }

    /**
     * 审批行。
     *
     * @param sessionKey 这条请求来自哪一段对话（含段）
     * @param channel 卡片与结果的投递通道，可空
     * @param chatRef 通道内的会话地址
     * @param principalSnapshot 发起人的身份快照（JSON），可空
     * @param frozenPlan 批准那一刻冻结的执行计划（JSON），可空；只有 exec 会写
     */
    private record Row(String status, boolean expired, String owner, String toolName,
                       String sessionKey, String channel, String chatRef, String principalSnapshot,
                       String frozenPlan) {
    }
}

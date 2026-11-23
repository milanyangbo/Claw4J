package io.github.claw4j.agent.approval;

import io.github.claw4j.agent.audit.ToolCallAudit;
import io.github.claw4j.capability.exec.ExecApprovalPort;
import io.github.claw4j.capability.exec.ExecCallContext;
import io.github.claw4j.capability.exec.ExecProperties;
import io.github.claw4j.capability.exec.FrozenPlan;
import io.github.claw4j.domain.port.Approvals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * exec 那一侧的审批端口：把"问人"交给 {@link Approvals}（票 05、10）。
 *
 * <p>为什么实现放在 agent：审批要落库（{@code approval_request}）、要推卡片、要在结论之后
 * 回那段对话开新一轮——三样都在这一侧。capability 只留接口，依赖方向才不倒。
 *
 * <p><b>这一轮不原地等人</b>：没有就地回答的界面（卡片与 HTTP 都是异步的），所以每次询问都是
 * "登记一条、回一个没有结论"，命令这一轮不跑。人在卡片上点了之后，审批服务在那段对话里开新一轮，
 * 模型重新调这个工具，那时 {@link #decide} 先兑现那条批准，才真的跑。
 *
 * <p>兑现按"同人、同工具、同冻结计划"查（{@code args_digest} = {@link FrozenPlan#digest()}）：
 * 换了人、换了命令都要重新批。批准的对象是那一条命令，不是"这个工具"。
 *
 * <p>兑现成功之后往审计里递一次"这次执行是哪条批准放行的"。递的是审计自己那套指纹
 * （{@link ToolCallAudit#digest}）——执行那一行是按它写的，用别的键配不上，而且不报错。
 */
public class AgentExecApprovalPort implements ExecApprovalPort {

    private static final Logger log = LoggerFactory.getLogger(AgentExecApprovalPort.class);

    /**
     * 上下文里没有工具名时的兜底。
     *
     * <p>正常路径由 {@code ExecContextToolCallback} 带上真实工具名（库给的 {@code Bash}）；
     * 这个常量只服务"只带了身份"的调用方（单测、以及将来别的入口），不让审批行记一个空工具名。
     */
    public static final String TOOL_NAME = "Bash";

    private final Approvals approvals;

    private final JdbcExecAllowlist allowlist;

    /**
     * exec 的配置，用来判"这个请求接不接受以后都放行"。可以为 null：没有装配 exec 的部署里，
     * 这个端口本来也不会被问到。
     */
    private final ExecProperties properties;

    /** 审计落点，可以为 null：不带审计的链路（单测）里只跳过那一次配对。 */
    private final ToolCallAudit audit;

    /**
     * @param approvals 审批（登记 + 兑现），必填
     * @param allowlist allow-always 的落点，必填
     * @param properties exec 配置，可为 null
     * @param audit 审计落点，可为 null
     */
    public AgentExecApprovalPort(Approvals approvals, JdbcExecAllowlist allowlist,
                                 ExecProperties properties, ToolCallAudit audit) {
        this.approvals = Objects.requireNonNull(approvals, "approvals");
        this.allowlist = Objects.requireNonNull(allowlist, "allowlist");
        this.properties = properties;
        this.audit = audit;
    }

    /**
     * 这条请求接不接受"以后都放行"。
     *
     * <p>两个条件，逐条对着 OpenClaw 的 {@code resolveExecApprovalAllowedDecisions}：
     *
     * <ul>
     *   <li>{@code ask=always} 时<b>不接受</b>：那一档的含义是"每条都要人看"，落一条持久放行等于把它
     *       悄悄改成 {@code on-miss}。它在那里也只返回 {@code allow-once} 与 {@code deny}。</li>
     *   <li><b>本次只能一次性持久化时也不接受</b>（{@code ./run.sh}、{@code /tmp/x} 这类取不出可执行名
     *       的段）：给了按钮，点下去写零条、却回一句"以后都放行"，下一次照旧弹。</li>
     * </ul>
     *
     * @param properties exec 配置，可以为 {@code null}
     * @param plan       这一条请求冻结的计划，可以为 {@code null}
     * @return 接受时为 {@code true}；没有配置或落不了库时按不接受
     */
    public static boolean allowsAlways(ExecProperties properties, FrozenPlan plan) {
        return properties != null
                && properties.settings().ask() != ExecProperties.Ask.ALWAYS
                && JdbcExecAllowlist.remembers(plan);
    }

    @Override
    public boolean alreadyApproved(List<String> segments) {
        return allowlist.hasAll(segments);
    }

    /**
     * 拿这一次的结论：先兑现一条已有的批准，没有就登记一条。
     *
     * <p>顺序不能反：先登记再兑现的话，一次已经批过的调用会多出一条没人看的待批请求。
     *
     * @param request 待批的这条命令
     * @return 结论；拿不到结论时是 {@link Decision.Kind#UNDECIDED}
     */
    @Override
    public Decision decide(Request request) {
        ExecCallContext context = ExecCallContext.current();
        if (context == null) {
            // 没有身份就没有"批给谁"，这条命令不该以任何形式被放行。
            return Decision.deny("这次调用没有携带身份（工具上下文里没有发起人），exec 一律不放行");
        }
        String toolName = toolName(context);
        FrozenPlan plan = request.plan();
        Optional<Approvals.Grant> granted = approvals.redeem(context.owner(), toolName, plan.digest());
        if (granted.isPresent()) {
            handoff(context, toolName, granted.get());
            return redeemed(granted.get(), plan);
        }
        Optional<String> approvalId = approvals.ask(new Approvals.Ask(
                context.executionId(), context.owner(), toolName, plan.digest(), plan.summary(),
                originOf(context), FrozenPlanJson.encode(plan), allowsAlways(properties, plan)));
        if (approvalId.isEmpty()) {
            return Decision.undecided(null,
                    "没能登记成一条审批请求（这个部署没有审批设施，或者库写不进去）");
        }
        log.info("exec 需要人批 approval={} owner={} command={}",
                approvalId.get(), context.owner(), plan.command());
        return Decision.undecided(approvalId.get(), "这条命令要由人批准，已经登记成一条审批请求");
    }

    /**
     * 兑现到一条批准之后，交回给后端。
     *
     * <p>冻结件不一致就拒：指纹本来就是计划算出来的，正常路径上这里永远相等，所以这一句是
     * <b>断言</b>而不是判据——它挡的是将来有人改了指纹口径之后，一条批准能跨命令复用。
     *
     * @param grant 兑现到的批准
     * @param plan  这次要跑的计划
     * @return 放行；不一致时为拒
     */
    private static Decision redeemed(Approvals.Grant grant, FrozenPlan plan) {
        FrozenPlan approved = FrozenPlanJson.decode(grant.frozenPlan());
        if (approved == null || !approved.sameAs(plan)) {
            return Decision.deny("批准的是另一条命令（approval mismatch），旧的批准不能用在新的命令上");
        }
        return Decision.allowOnce(plan);
    }

    /**
     * 把"这次执行是哪条批准放行的"递给审计。
     *
     * <p>只在这一处合得上：兑现发生在端口里，写审计要的那一轮编号与参数原文在调用上下文里。
     * 分开写就会有人只改一边，而结果是一行没有 {@code approval_id} 的审计——查的时候才发现。
     *
     * <p>缺任何一样就跳过：审计那一行退化成没有审批编号，比写一个配不上的编号好。
     *
     * @param context 这次调用的上下文
     * @param toolName 工具名
     * @param grant 刚兑现的批准
     */
    private void handoff(ExecCallContext context, String toolName, Approvals.Grant grant) {
        if (audit == null || context.argsJson() == null || context.executionId() == null) {
            return;
        }
        ToolCallAudit.digest(toolName, context.argsJson())
                .ifPresent(digest -> audit.granted(context.executionId(), toolName, digest, grant.id()));
    }

    @Override
    public void remember(Request request) {
        allowlist.remember(request.plan());
    }

    /**
     * 这条请求从哪段对话来。没有会话键时为 {@code null}——那时结论落得下来，但没有"接着做"。
     *
     * @param context 这次调用的上下文
     * @return 来处
     */
    private static Approvals.Origin originOf(ExecCallContext context) {
        if (context.sessionKey() == null) {
            return null;
        }
        return new Approvals.Origin(context.sessionKey(), context.channel(),
                context.chatRef(), context.principal());
    }

    private static String toolName(ExecCallContext context) {
        return context.toolName() == null || context.toolName().isBlank()
                ? TOOL_NAME : context.toolName();
    }
}

package io.github.claw4j.agent.approval;

import io.github.claw4j.agent.advisor.PiiRedactor;
import io.github.claw4j.capability.exec.ExecApprovalPort;
import io.github.claw4j.capability.exec.ExecCallContext;
import io.github.claw4j.capability.exec.ExecProperties;
import io.github.claw4j.capability.exec.FrozenPlan;
import io.github.claw4j.domain.port.ApprovalLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * exec 那一侧的审批端口实现在这里：把"问人"这件事交给审批账本与卡片（票 05）。
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
 */
public class AgentExecApprovalPort implements ExecApprovalPort {

    private static final Logger log = LoggerFactory.getLogger(AgentExecApprovalPort.class);

    /**
     * 审批行上记的工具名。
     *
     * <p>用库的工具名 {@code Bash}，不是动作名 {@code exec_command}：动作是权限的口径，
     * 批准的对象是"那一次会跑起来的那个工具"（V6 的列注释）。
     */
    public static final String TOOL_NAME = "Bash";

    /**
     * 卡片上那句命令摘要的长度上限，与 {@code args_summary varchar(512)} 对齐。
     *
     * <p>超过它就会被库拒掉——而拒绝的方式是"审批请求没登记上"，人看到的是"没人能批"。
     * 截断在写之前做，比让库去挡清楚。
     */
    private static final int SUMMARY_CHARS = 480;

    private final ApprovalGate gate;

    private final JdbcExecAllowlist allowlist;

    /**
     * exec 的配置，用来判"这个请求接不接受以后都放行"。可以为 null：没有装配 exec 的部署里，
     * 这个端口本来也不会被问到。
     */
    private final ExecProperties properties;

    /**
     * @param gate 审批门（登记 + 兑现），必填
     * @param allowlist allow-always 的落点，必填
     * @param properties exec 配置，可为 null
     */
    public AgentExecApprovalPort(ApprovalGate gate, JdbcExecAllowlist allowlist,
                                 ExecProperties properties) {
        this.gate = Objects.requireNonNull(gate, "gate");
        this.allowlist = Objects.requireNonNull(allowlist, "allowlist");
        this.properties = properties;
    }

    /**
     * 这一档接不接受"以后都放行"。
     *
     * <p>{@code ask=always} 时**不接受**：那一档的含义是"每条都要人看"，落一条持久放行等于把它
     * 悄悄改成 {@code on-miss}。照 OpenClaw 的 {@code resolveExecApprovalAllowedDecisions}——
     * 它在那里也只返回 {@code allow-once} 与 {@code deny}。
     *
     * @param properties exec 配置，可以为 {@code null}
     * @return 接受时为 {@code true}；没有配置时按不接受（没有可持久的东西）
     */
    public static boolean allowsAlways(ExecProperties properties) {
        return properties != null && properties.settings().ask() != ExecProperties.Ask.ALWAYS;
    }

    @Override
    public boolean alreadyApproved(List<String> segments) {
        return allowlist.hasAll(segments);
    }

    /**
     * 拿这一次的结论：先兑现一条已有的批准，没有就登记一条。
     *
     * <p>顺序不能反：先登记再兑现的话，一次已经批过的调用会多出一条没人看的待批请求，
     * 而审批人看到的是一个"已经在跑的命令还要不要批"的问题。
     */
    @Override
    public Decision decide(Request request) {
        ExecCallContext context = ExecCallContext.current();
        if (context == null) {
            // 没有身份就没有"批给谁"，这条命令不该以任何形式被放行。
            return Decision.deny("这次调用没有携带身份（工具上下文里没有发起人），exec 一律不放行");
        }
        FrozenPlan plan = request.plan();
        Optional<ApprovalLedger.Grant> granted = gate.redeem(context.owner(), TOOL_NAME, plan.digest());
        if (granted.isPresent()) {
            return redeemed(granted.get(), plan);
        }
        Optional<String> approvalId = gate.ask(new ApprovalLedger.Ask(
                context.runId(), context.owner(), TOOL_NAME, plan.digest(), summaryOf(plan),
                originOf(context), FrozenPlanJson.encode(plan), allowsAlways(properties)));
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
     * **断言**而不是判据——它挡的是将来有人改了指纹口径（比如只算命令名）之后，一条批准能跨命令
     * 复用。那种改动不会让任何一条用例红，而后果是"批了 A 也等于批了 B"。
     *
     * @param grant 兑现到的批准
     * @param plan  这次要跑的计划
     * @return 放行；不一致时为拒
     */
    private static Decision redeemed(ApprovalLedger.Grant grant, FrozenPlan plan) {
        FrozenPlan approved = FrozenPlanJson.decode(grant.frozenPlan());
        if (approved == null || !approved.sameAs(plan)) {
            return Decision.deny("批准的是另一条命令（approval mismatch），旧的批准不能用在新的命令上");
        }
        return Decision.allowOnce(plan);
    }

    @Override
    public void remember(Request request) {
        allowlist.remember(request.plan());
    }

    /**
     * 卡片上给人看的那句话：在哪个目录里跑什么，脱敏并截断。
     *
     * <p>脱敏在截断之前：反过来的话，一个手机号会被截成两半，两半都不再匹配号码规则，
     * 于是掩码失效、前半截原样进库。
     *
     * @param plan 冻结件
     * @return 摘要
     */
    private static String summaryOf(FrozenPlan plan) {
        String masked = PiiRedactor.mask(plan.summary());
        return masked.length() <= SUMMARY_CHARS
                ? masked : masked.substring(0, SUMMARY_CHARS - 1) + "…";
    }

    /**
     * 这条请求从哪段对话来。没有会话键时为 {@code null}——那时结论落得下来，但没有"接着做"。
     *
     * @param context 这次调用的上下文
     * @return 来处
     */
    private static ApprovalLedger.Origin originOf(ExecCallContext context) {
        if (context.sessionKey() == null) {
            return null;
        }
        return new ApprovalLedger.Origin(context.sessionKey(), context.channel(),
                context.chatRef(), context.principal());
    }
}

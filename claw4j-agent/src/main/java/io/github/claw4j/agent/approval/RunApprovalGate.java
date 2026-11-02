package io.github.claw4j.agent.approval;

import io.github.claw4j.agent.run.DeliveryTarget;
import io.github.claw4j.agent.run.RunStore;
import io.github.claw4j.agent.run.StepInput;
import io.github.claw4j.agent.run.StepKind;
import io.github.claw4j.agent.run.StepStatus;
import io.github.claw4j.domain.port.ApprovalCards;
import io.github.claw4j.domain.port.ApprovalDecider;
import io.github.claw4j.domain.port.ApprovalLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * 审批门：账本那一条记不下任何请求时，这里什么都做。
 *
 * <p><b>"停在这一步"要落在两个地方</b>：{@code agent_run.status = suspended} 说这件事停了，
 * {@code agent_step} 上一行 awaiting 说停在哪个动作上。只落前一半的话，
 * "这件事卡过几次审批、每次卡在什么工具上"就只能靠读摘要文本回答；
 * 只落后一半的话，没有人知道这件事不该被"继续"唤醒。
 *
 * <p><b>顺序是：先登记，再挂起，最后把卡片推出去</b>（P03）。反过来的话会出现一段
 * "run 已经 suspended、但审批表里还没有那一行"的窗口——这时候如果有人去点批准，
 * 他点的是一个不存在的编号。卡片排在最后：它只是"让人看见"的一次尝试，
 * 而点得动它的前提是前两步已经落库。
 *
 * <p><b>卡片推不出去不影响这一轮</b>：登记与挂起都成了，对话里也带着审批编号，
 * 人照样能去接口批。反过来把卡片失败往上抛，这一轮会变成 500——那会把
 * "通知没发出去"升级成"这次调用炸了"，而工具本来就没执行、什么也没坏。
 */
public class RunApprovalGate implements ApprovalGate {

    private static final Logger log = LoggerFactory.getLogger(RunApprovalGate.class);

    private final ApprovalLedger ledger;

    private final RunStore runs;

    /** 各通道的卡片能力。空表示这个部署没有卡片（对话里的编号与 HTTP 接口照旧能用）。 */
    private final List<ApprovalCards> cards;

    /** 这件事登记过哪些收件地址（{@code agent_run_delivery}）。卡片推到那些会话里去。 */
    private final Function<String, List<DeliveryTarget>> targetsOf;

    public RunApprovalGate(ApprovalLedger ledger, RunStore runs,
                           List<ApprovalCards> cards,
                           Function<String, List<DeliveryTarget>> targetsOf) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.cards = cards == null ? List.of() : List.copyOf(cards);
        this.targetsOf = Objects.requireNonNull(targetsOf, "targetsOf");
    }

    @Override
    public Optional<ApprovalLedger.Grant> redeem(String owner, String toolName, String argsDigest) {
        return ledger.redeem(owner, toolName, argsDigest);
    }

    @Override
    public Optional<String> ask(ApprovalLedger.Ask ask) {
        Optional<String> id = ledger.ask(ask);
        if (id.isEmpty()) {
            return Optional.empty();
        }
        if (ask.runId() == null || ask.runId().isBlank()) {
            // 没有 run 可挂（子 Agent 那一层，A10）：这一轮照样被拦，只是没有"停在哪儿"这回事。
            return id;
        }
        runs.append(new StepInput(ask.runId(), StepKind.APPROVAL,
                        "工具 " + ask.toolName() + " 等审批（编号 " + id.get() + "）",
                        ask.toolName(), ask.argsSummary(), StepStatus.AWAITING))
                .ifPresentOrElse(step -> runs.suspend(ask.runId()),
                        () -> log.warn("run {} 的审批步骤没落下来，这件事不会停在等待状态", ask.runId()));
        announce(ask, id.get());
        return id;
    }

    /**
     * 把这条请求推给"这件事登记过的那些会话"（P03）。
     *
     * <p><b>推给会话而不是推给审批人名单上的每个人</b>：那需要一张"人 → 会话"的地址表，
     * 而飞书那一侧的身份是 {@code open_id}、要给它发消息还得知 {@code chat_id}；
     * 这套东西本来就有——{@code agent_run_delivery} 记着"这件事在哪些会话里被问过"。
     * 卡片落回那些会话之后，名单里的人点得动，不在名单里的人点了会被
     * {@code ApprovalDecider} 拒掉（资格判定不在这一层，见那边的说明）。
     *
     * <p>推不出去的每一种情形都只记日志：这件事已经停住了，编号也在对话里，
     * 缺的只是"更方便的那个按钮"。
     */
    private void announce(ApprovalLedger.Ask ask, String approvalId) {
        if (cards.isEmpty()) {
            return;
        }
        ApprovalDecider.Pending card = new ApprovalDecider.Pending(approvalId, ask.runId(), ask.owner(),
                ask.toolName(), ask.argsSummary(), "pending", false, null);
        List<DeliveryTarget> targets;
        try {
            targets = targetsOf.apply(ask.runId());
        } catch (RuntimeException failure) {
            log.warn("读不到 run {} 的收件地址，审批卡片没发出去（对话里仍然带着编号）", ask.runId(), failure);
            return;
        }
        for (DeliveryTarget target : targets) {
            for (ApprovalCards sender : cards) {
                if (!sender.channel().equals(target.channel())) {
                    continue;
                }
                try {
                    sender.announce(target.channelRef(), card);
                } catch (RuntimeException failure) {
                    log.warn("审批卡片没发出去：通道 {} 地址 {} 审批 {}",
                            target.channel(), target.channelRef(), approvalId, failure);
                }
            }
        }
    }
}

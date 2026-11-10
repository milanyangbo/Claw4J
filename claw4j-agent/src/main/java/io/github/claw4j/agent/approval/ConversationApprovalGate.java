package io.github.claw4j.agent.approval;

import io.github.claw4j.domain.port.ApprovalCards;
import io.github.claw4j.domain.port.ApprovalDecider;
import io.github.claw4j.domain.port.ApprovalLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 审批门：登记请求、推卡片，等待结论的活儿不在这里。
 *
 * <p>它只做两件事：登记一条请求，把卡片推到那段对话里。
 *
 * <p>它不做两件事：不往步骤表落一行 awaiting，也不把某一轮的状态改成 suspended。那两件事服务的是
 * 「这件事停在这里，等批准之后接着做」，而 OpenClaw 没有这种东西——它的审批是运行内等待，或
 * 「approval-pending + 系统事件唤醒会话」。唤醒的是会话，不是那一次运行（ADR-0027）。
 *
 * <p>这一轮因此直接以 done 收尾：它确实结束了，回给用户的那句话是「我需要你批准」。批准之后发生的是
 * 同一段对话里的下一轮——上下文与 goal 都在，模型看到的自然是「刚才我要调这个工具、现在批准了」。
 *
 * <p>卡片推到哪：请求自己身上的 {@code channel + chat_ref}（V21）。一条请求只可能来自一段对话，地址
 * 就写在它身上，不需要按 {@code runId} 去另一张表查「这件事登记过哪些地址」。
 *
 * <p>推不出去不影响这一轮：登记成了，对话里也带着审批编号，人照样能去接口批。反过来把卡片失败往上
 * 抛，这一轮会变成 500——那会把「通知没发出去」升级成「这次调用炸了」，而工具本来就没执行、什么也
 * 没坏。
 */
public class ConversationApprovalGate implements ApprovalGate {

    private static final Logger log = LoggerFactory.getLogger(ConversationApprovalGate.class);

    private final ApprovalLedger ledger;

    /** 各通道的卡片能力。空表示这个部署没有卡片（对话里的编号与 HTTP 接口照旧能用）。 */
    private final List<ApprovalCards> cards;

    /**
     * 审批门。
     *
     * @param ledger 审批账本，必填
     * @param cards 容器里的卡片能力，为 null 时按空处理
     */
    public ConversationApprovalGate(ApprovalLedger ledger, List<ApprovalCards> cards) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.cards = cards == null ? List.of() : List.copyOf(cards);
    }

    @Override
    public Optional<ApprovalLedger.Grant> redeem(String owner, String toolName, String argsDigest) {
        return ledger.redeem(owner, toolName, argsDigest);
    }

    @Override
    public Optional<String> ask(ApprovalLedger.Ask ask) {
        Optional<String> id = ledger.ask(ask);
        id.ifPresent(approvalId -> announce(ask, approvalId));
        return id;
    }

    /**
     * 把这条请求推给「它自己那段对话」。
     *
     * <p>推给会话而不是推给审批人名单上的每个人：那需要一张「人 → 会话」的地址表，而飞书那一侧的身份
     * 是 {@code open_id}、要给它发消息还得知 {@code chat_id}。卡片落回那段对话之后，名单里的人点得
     * 动，不在名单里的人点了会被 {@code ApprovalDecider} 拒掉（资格判定不在这一层）。
     *
     * <p>推不出去的每一种情形都只记日志：这件事已经登记住了，编号也在对话里，缺的只是「更方便的那个
     * 按钮」。
     *
     * @param ask 刚登记的那条请求
     * @param approvalId 审批编号
     */
    private void announce(ApprovalLedger.Ask ask, String approvalId) {
        if (cards.isEmpty()) {
            return;
        }
        ApprovalLedger.Origin origin = ask.origin();
        if (origin == null || origin.channel() == null || origin.chatRef() == null) {
            // 没有来处（子 Agent 那一层）：没有"哪段对话"可推。编号仍然回给调用方。
            return;
        }
        ApprovalDecider.Pending card = new ApprovalDecider.Pending(approvalId, ask.runId(), ask.owner(),
                ask.toolName(), ask.argsSummary(), "pending", false, null);
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
}

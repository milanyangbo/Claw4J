package io.github.claw4j.channel.feishu.sdk;

import io.github.claw4j.channel.feishu.FeishuApprovalCard;
import io.github.claw4j.domain.ApprovalVerdict;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.port.ApprovalDecider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * 有人在卡片上点了按钮之后发生什么（P03）。
 *
 * <p>这一层不碰 SDK：收一个「谁点了哪一条、批还是拒」，回一句提示与新卡片。
 * 事件对象到这四个值的转换、结果到 toast 与卡片的转换，都留在
 * {@link FeishuLongConnectionChannel} 的适配方法里，只有那里认识 SDK 类型。
 * 分开之后，判定链路（认人 → 决定 → 读回现状）能在本地用假货跑，
 * 只剩「飞书事件长什么样」需要真凭据来验。
 *
 * <p>谁点的看 {@code operator}，不看按钮：卡片会被转发、被截图，按钮里只写哪一条加批还是拒。
 * 认出来的身份还要再过 {@code claw4j.channel.feishu.roles}，没登记的人点不动任何东西，
 * 与群里发消息是同一条规矩（ADR-0007：认不出就拒绝，没有默认角色）。
 *
 * <p>提示语直接用 {@code decide} 返回的那句话：结论与话术在一处，卡片 toast、HTTP 的
 * {@code detail}、对话里的回答于是说的是同一件事。这里只在外面加一层「什么颜色的提示」。
 */
class FeishuApprovalActions {

    private static final Logger log = LoggerFactory.getLogger(FeishuApprovalActions.class);

    private final ApprovalDecider decider;

    private final Function<String, Optional<Principal>> principals;

    FeishuApprovalActions(ApprovalDecider decider, Function<String, Optional<Principal>> principals) {
        this.decider = decider;
        this.principals = principals;
    }

    /**
     * 一次按钮点击带来的三个值。
     *
     * @param operatorOpenId 点按钮的人（事件里的 {@code operator.open_id}）
     * @param approvalId 按钮带来的编号
     * @param decision 按钮带来的决定：{@code approve} 或 {@code deny}
     */
    record Click(String operatorOpenId, String approvalId, String decision) {
    }

    /**
     * 一次点击要回给飞书的东西。
     *
     * @param toastType 飞书提示的档位：success / info / warning / error
     * @param toastText 一句话
     * @param card 更新之后的卡片；null 表示这张卡片不改，因为这次点击没有改变任何东西
     */
    record Outcome(String toastType, String toastText, Map<String, Object> card) {

        static Outcome of(String toastType, String toastText) {
            return new Outcome(toastType, toastText, null);
        }
    }

    Outcome on(Click click) {
        if (click.approvalId() == null || click.approvalId().isBlank()) {
            return Outcome.of("error", "这张卡片的按钮里没有编号（可能是旧版本发的），让提这个请求的人重新发起。");
        }
        Optional<Principal> who = principals.apply(click.operatorOpenId());
        if (who.isEmpty()) {
            return Outcome.of("error", "你还没有在飞书通道登记（claw4j.channel.feishu.roles），"
                    + "所以点不了任何一条。让管理员把你的 open_id 加进去。");
        }
        Boolean approved = approvedOf(click.decision());
        if (approved == null) {
            return Outcome.of("error", "这个按钮我不认识（" + click.decision() + "），请用卡片上的批准/拒绝。");
        }
        ApprovalDecider.Result result = decider.decide(click.approvalId(), who.get().userId(), approved, null);
        log.info("飞书卡片审批：approval={} approver={} verdict={}",
                click.approvalId(), who.get().userId(), result.verdict());
        return new Outcome(toastTypeOf(result), result.detail(),
                settles(result.verdict()) ? cardAfter(click.approvalId()) : null);
    }

    /**
     * 这次点击有没有让那条请求的结论落定。
     *
     * <p>只有落定、已定过、已过期才换卡片。没资格的那几种（不是审批人、自批）什么都不改：
     * 换成待批的样子是白换，换成别的样子与实际状态不符——那条请求还在等人批。
     */
    private static boolean settles(ApprovalVerdict verdict) {
        return switch (verdict) {
            case GRANTED, DENIED, EXPIRED, ALREADY_DECIDED -> true;
            case NOT_FOUND, NOT_APPROVER, SELF_APPROVAL -> false;
        };
    }

    /** 读回那条请求的现状并渲染成新卡片；读不回来就返回 null，让卡片保持原样。 */
    private Map<String, Object> cardAfter(String approvalId) {
        try {
            return decider.find(approvalId).map(FeishuApprovalCard::of).orElse(null);
        } catch (RuntimeException failure) {
            log.warn("审批卡片读不回现状，这一张保持原样：approval={}", approvalId, failure);
            return null;
        }
    }

    private static Boolean approvedOf(String decision) {
        if (FeishuApprovalCard.DECISION_APPROVE.equals(decision)) {
            return Boolean.TRUE;
        }
        if (FeishuApprovalCard.DECISION_DENY.equals(decision)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private static String toastTypeOf(ApprovalDecider.Result result) {
        return switch (result.verdict()) {
            case GRANTED -> "success";
            case DENIED -> "info";
            case EXPIRED, ALREADY_DECIDED -> "warning";
            default -> "error";
        };
    }
}

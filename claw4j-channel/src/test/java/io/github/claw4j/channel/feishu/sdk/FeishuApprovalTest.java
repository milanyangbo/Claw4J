package io.github.claw4j.channel.feishu.sdk;

import io.github.claw4j.channel.feishu.FeishuApprovalCard;
import io.github.claw4j.domain.ApprovalVerdict;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.port.ApprovalDecider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审批卡片这一半（P03）：卡片长什么样、点完之后回什么。
 *
 * <p><b>为什么这两件事写在一份里</b>：它们是同一个动作的两端——发出去的那张卡片上按钮带着什么，
 * 就决定了点回来时能读到什么。分成两份的话，"按钮里写的是编号还是工具名"这种改动
 * 在两边各红一次，而真正的错（两边对不上）两边都是绿的。
 *
 * <p>真连上飞书之后的样子不在这里验（要真凭据、要真人点）：这一份断的是
 * 结构（有没有按钮、按钮带什么）、以及"点的人是谁、能不能批"这条判定链。
 */
class FeishuApprovalTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String APPROVAL_ID = "ap-7";

    @Test
    @DisplayName("待批卡片：工具名、参数摘要、发起人都在，按钮带着编号与决定")
    void aPendingCardCarriesTheThreeThingsAndTwoButtons() {
        Map<String, Object> card = FeishuApprovalCard.of(pending(false));

        assertTrue(FeishuApprovalCard.actionable(pending(false)));
        String json = JSON.writeValueAsString(card);
        assertTrue(json.contains("需要有人批准"), json);
        assertTrue(json.contains("sendEmail"), "没有工具名，审批人不知道自己批的是哪一类动作");
        assertTrue(json.contains("to=张三"), "没有参数摘要，他分不清这一次和以后每一次");
        assertTrue(json.contains("u-9"), "没有发起人，他不知道该去找谁问");
        assertTrue(json.contains("\"批准\"") && json.contains("\"拒绝\""), json);
        assertTrue(json.contains(APPROVAL_ID), "按钮上得带着是哪一条");
        assertTrue(json.contains(FeishuApprovalCard.DECISION_APPROVE)
                && json.contains(FeishuApprovalCard.DECISION_DENY), json);
        assertFalse(json.contains("\"schema\""), "卡片 1.0 的结构，不写 schema 键");
    }

    @Test
    @DisplayName("过期卡片：按钮不再出现，并明确说「重新发起」——点下去必然失败的按钮比没有更迷惑")
    void anExpiredCardHasNoButtonsAndSaysWhatToDo() {
        Map<String, Object> card = FeishuApprovalCard.of(pending(true));

        assertFalse(FeishuApprovalCard.actionable(pending(true)));
        String json = JSON.writeValueAsString(card);
        assertFalse(json.contains("\"action\""), "过期的卡片不该留下点得动的按钮：" + json);
        assertTrue(json.contains("过期"), json);
        assertTrue(json.contains("重新发起"), json);
        assertTrue(json.contains(APPROVAL_ID), "卡片留着，那是这次请求的记录");
    }

    @Test
    @DisplayName("已经有结论的卡片：说清结论，也没有按钮")
    void aDecidedCardTellsTheOutcome() {
        ApprovalDecider.Pending granted = new ApprovalDecider.Pending(APPROVAL_ID, "run-7", "u-9",
                "sendEmail", "to=张三", "granted", false, Instant.parse("2026-11-02T13:00:00Z"));

        assertFalse(FeishuApprovalCard.actionable(granted));
        String json = JSON.writeValueAsString(FeishuApprovalCard.of(granted));
        assertTrue(json.contains("已经批准"), json);
        assertTrue(json.contains("green"), "批了给绿的：群里扫一眼就知道这条不用管了");
        assertFalse(json.contains("\"action\""), json);
    }

    @Test
    @DisplayName("没有参数的单据也写一行「（没有参数）」，不省掉那行：省掉之后读者会以为是漏渲染")
    void aCardWithoutArgumentsStillShowsTheRow() {
        ApprovalDecider.Pending bare = new ApprovalDecider.Pending(APPROVAL_ID, "run-7", "u-9",
                "systemStatus", null, "pending", false, Instant.parse("2026-11-02T13:00:00Z"));

        assertTrue(JSON.writeValueAsString(FeishuApprovalCard.of(bare)).contains("（没有参数）"));
    }

    @Test
    @DisplayName("登记过的人点批准：成功提示，卡片换成「已经批准」，决定里带的是他的 userId")
    void aRegisteredApproverGrantsAndTheCardTurnsGreen() {
        FixedDecider decider = new FixedDecider();
        FeishuApprovalActions.Outcome outcome = actions(decider).on(
                new FeishuApprovalActions.Click("ou_ethan", APPROVAL_ID, FeishuApprovalCard.DECISION_APPROVE));

        assertEquals("success", outcome.toastType());
        assertEquals("已批准，这件事已经接着往下做了。", outcome.toastText(), "提示语就是判定那句，不另写一份");
        assertNotNull(outcome.card(), "结论落定了，卡片要换一张");
        assertEquals("ou_ethan", decider.seenApprover);
        assertTrue(decider.seenApproved);
        assertNull(decider.seenReason, "卡片上没有输入框：拒绝理由留空，要写理由走接口");
        assertFalse(JSON.writeValueAsString(outcome.card()).contains("\"action\""),
                "批过的卡片不该再留按钮：第二个人点下去只会拿到「已经有结论」");
    }

    @Test
    @DisplayName("没登记的人点不动：错误提示，卡片不改，判定一次都没发生")
    void anUnregisteredClickerCannotDoAnything() {
        FixedDecider decider = new FixedDecider();
        FeishuApprovalActions.Outcome outcome = actions(decider).on(
                new FeishuApprovalActions.Click("ou_stranger", APPROVAL_ID, FeishuApprovalCard.DECISION_APPROVE));

        assertEquals("error", outcome.toastType());
        assertTrue(outcome.toastText().contains("飞书通道登记"), outcome.toastText());
        assertNull(outcome.card(), "什么都没变，卡片就不该死灰");
        assertEquals(0, decider.calls);
    }

    @Test
    @DisplayName("批自己的请求：错误提示 + 卡片保持原样（那条请求还挂着，等别人批）")
    void selfApprovalKeepsTheCardPending() {
        FixedDecider decider = new FixedDecider();
        decider.verdict = ApprovalVerdict.SELF_APPROVAL;

        FeishuApprovalActions.Outcome outcome = actions(decider).on(
                new FeishuApprovalActions.Click("ou_ethan", APPROVAL_ID, FeishuApprovalCard.DECISION_APPROVE));

        assertEquals("error", outcome.toastType());
        assertTrue(outcome.toastText().contains("不能自己批"), outcome.toastText());
        assertNull(outcome.card());
        assertEquals(1, decider.calls, "判定要真的发生——资格由服务端判，不由这一层猜");
    }

    @Test
    @DisplayName("认不出的按钮值、以及按钮里没有编号：都是错误提示，不去碰判定")
    void unknownButtonsAreRejectedBeforeAnyDecision() {
        FixedDecider decider = new FixedDecider();
        FeishuApprovalActions actions = actions(decider);

        FeishuApprovalActions.Outcome unknown = actions.on(
                new FeishuApprovalActions.Click("ou_ethan", APPROVAL_ID, "maybe"));
        assertEquals("error", unknown.toastType());
        assertNull(unknown.card());

        FeishuApprovalActions.Outcome noId = actions.on(
                new FeishuApprovalActions.Click("ou_ethan", " ", FeishuApprovalCard.DECISION_APPROVE));
        assertEquals("error", noId.toastType());
        assertTrue(noId.toastText().contains("没有编号"), noId.toastText());
        assertEquals(0, decider.calls);
    }

    @Test
    @DisplayName("已经被别人点过：提示是警告档，卡片换成真实结论（不是这次点击以为的样子）")
    void aSecondClickShowsTheRealOutcome() {
        FixedDecider decider = new FixedDecider();
        decider.verdict = ApprovalVerdict.ALREADY_DECIDED;

        FeishuApprovalActions.Outcome outcome = actions(decider).on(
                new FeishuApprovalActions.Click("ou_ethan", APPROVAL_ID, FeishuApprovalCard.DECISION_DENY));

        assertEquals("warning", outcome.toastType());
        assertNotNull(outcome.card());
        assertTrue(JSON.writeValueAsString(outcome.card()).contains("需要有人批准"),
                "卡片按读回来的那一行渲染（这份替身说它还在等），而不是按这次点击猜");
    }

    // ---------------------------------------------------------------- 装配

    private static FeishuApprovalActions actions(ApprovalDecider decider) {
        return new FeishuApprovalActions(decider, openId -> "ou_ethan".equals(openId)
                ? Optional.of(Principal.of("ou_ethan", "admin", Set.of("*"), Set.of("*")))
                : Optional.empty());
    }

    private static ApprovalDecider.Pending pending(boolean expired) {
        return new ApprovalDecider.Pending(APPROVAL_ID, "run-7", "u-9", "sendEmail", "to=张三",
                "pending", expired, Instant.parse("2026-11-02T13:00:00Z"));
    }

    /** 只回答"结论是什么、现在的样子是什么"；名单与自批在 {@code ApprovalService} 那里。 */
    private static final class FixedDecider implements ApprovalDecider {

        private ApprovalVerdict verdict = ApprovalVerdict.GRANTED;

        private int calls;

        private String seenApprover;

        private boolean seenApproved;

        private String seenReason;

        @Override
        public Result decide(String approvalId, String approver, boolean approved, String reason) {
            calls++;
            seenApprover = approver;
            seenApproved = approved;
            seenReason = reason;
            return new Result(verdict, verdict == ApprovalVerdict.GRANTED
                    ? "已批准，这件事已经接着往下做了。"
                    : "这条请求是你自己提的，不能自己批——自行批准等于没有审批。找名单里的另一个人点。");
        }

        /** 读回来的样子跟着结论走：批过的那一行在库里是 {@code granted}，不是 {@code pending}。 */
        @Override
        public Optional<Pending> find(String approvalId) {
            String status = switch (verdict) {
                case GRANTED -> "granted";
                case DENIED -> "denied";
                default -> "pending";
            };
            return Optional.of(new Pending(APPROVAL_ID, "run-7", "u-9", "sendEmail", "to=张三",
                    status, false, Instant.parse("2026-11-02T13:00:00Z")));
        }
    }
}

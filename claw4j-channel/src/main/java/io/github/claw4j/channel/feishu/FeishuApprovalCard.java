package io.github.claw4j.channel.feishu;

import io.github.claw4j.domain.port.ApprovalDecider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一张审批卡片长什么样（P03）：工具名、参数摘要、发起人、两个按钮。
 *
 * <p><b>为什么是纯函数</b>：卡片的形状是这个通道最容易被改坏、又最难在本地验证的东西——
 * 发出去要真凭据、点一下要真人。把"拼 JSON"从"调接口"里分出来，形状就能被断言钉住；
 * 剩下那点 SDK 调用只是把拼好的结构塞进消息体。
 *
 * <p><b>三样东西缺一不可</b>：没有工具名，审批人不知道自己批的是哪一类动作；没有参数摘要，
 * 他不知道自己批的是"这一封邮件"还是"以后每一封邮件"——摘要为空时卡片会写"（没有参数）"
 * 而不是省掉这一行，省掉之后读者会以为这行是漏渲染；没有发起人，他不知道该去找谁
 * 问"你为什么要这么干"。
 *
 * <p><b>过期不是删掉按钮，是换成一句话</b>：卡片留着（那是这次请求的记录），
 * 只是不再有点得动的按钮，并且明确说"重新发起"——点下去必然失败的按钮，
 * 比没有按钮更让人迷惑。
 *
 * <p>按钮携带的值只有两件事：哪一条、批还是拒。谁点的由事件里的 operator 决定，
 * <b>不放在按钮里</b>：放在里面等于让卡片自称是谁，而卡片是可以被转发、被截图复制的。
 */
public final class FeishuApprovalCard {

    /** 按钮携带的键名。带前缀是为了不和同一张卡片上别的东西撞（表单、链接、自增 id）。 */
    public static final String VALUE_APPROVAL = "claw4j_approval";

    /** 按钮携带的决定：{@link #DECISION_APPROVE} 或 {@link #DECISION_DENY}。 */
    public static final String VALUE_DECISION = "claw4j_decision";

    public static final String DECISION_APPROVE = "approve";

    public static final String DECISION_DENY = "deny";

    /** 库里 {@code approval_request.status} 的三个取值之一：这条还没人定结论。 */
    private static final String PENDING = "pending";

    private FeishuApprovalCard() {
    }

    /**
     * 卡片结构（飞书卡片 1.0 的对象形态）。
     *
     * @param card 这条请求现在的样子。{@code status} 与 {@code expired} 决定它还能不能被点
     */
    public static Map<String, Object> of(ApprovalDecider.Pending card) {
        Map<String, Object> head = new LinkedHashMap<>();
        head.put("template", templateOf(card));
        head.put("title", Map.of("tag", "plain_text", "content", titleOf(card)));

        List<Object> elements = new ArrayList<>();
        elements.add(fieldsOf(card));
        elements.add(Map.of("tag", "div",
                "text", Map.of("tag", "lark_md", "content", bodyOf(card))));
        if (actionable(card)) {
            elements.add(Map.of("tag", "action", "actions", List.of(
                    button("批准", "primary", card.id(), DECISION_APPROVE),
                    button("拒绝", "danger", card.id(), DECISION_DENY))));
        }
        elements.add(Map.of("tag", "note", "elements", List.of(
                Map.of("tag", "plain_text", "content", footerOf(card)))));

        Map<String, Object> card_ = new LinkedHashMap<>();
        card_.put("config", Map.of("wide_screen_mode", true));
        card_.put("header", head);
        card_.put("elements", elements);
        return card_;
    }

    /** 还能点：还挂着、也没过期。别的组合一律只展示。 */
    public static boolean actionable(ApprovalDecider.Pending card) {
        return PENDING.equals(card.status()) && !card.expired();
    }

    private static String templateOf(ApprovalDecider.Pending card) {
        if (card.expired()) {
            return "grey";
        }
        return switch (card.status()) {
            case "granted" -> "green";
            case "denied" -> "red";
            default -> "orange";
        };
    }

    private static String titleOf(ApprovalDecider.Pending card) {
        if (card.expired()) {
            return "这条审批已经过期";
        }
        return switch (card.status()) {
            case "granted" -> "已经批准";
            case "denied" -> "已经拒绝";
            default -> "需要有人批准";
        };
    }

    private static Map<String, Object> fieldsOf(ApprovalDecider.Pending card) {
        List<Object> fields = new ArrayList<>();
        fields.add(field("工具", "`" + orNone(card.toolName()) + "`", true));
        fields.add(field("发起人", "`" + orNone(card.owner()) + "`", true));
        fields.add(field("参数", "`" + (blank(card.argsSummary()) ? "（没有参数）" : card.argsSummary()) + "`",
                false));
        return Map.of("tag", "div", "fields", fields);
    }

    private static Map<String, Object> field(String label, String value, boolean shortField) {
        return Map.of("is_short", shortField,
                "text", Map.of("tag", "lark_md", "content", "**" + label + "**\n" + value));
    }

    private static String bodyOf(ApprovalDecider.Pending card) {
        String id = "审批编号 `" + card.id() + "`。";
        if (card.expired()) {
            return id + "它已经过了有效期，**按钮不再作数**——让提这个请求的人重新发起一次。";
        }
        return switch (card.status()) {
            case "granted" -> id + "这件事已经批过了，会（或者已经）接着往下做。";
            case "denied" -> id + "这件事被拒绝了，理由已经交给模型。";
            default -> id + "批准之后这件事会自动接着做，不用重新说明背景。";
        };
    }

    private static String footerOf(ApprovalDecider.Pending card) {
        if (card.expired()) {
            return "过期的批准不能补点：这不是点错了，是这条请求已经不作数";
        }
        if (actionable(card)) {
            return "批准与拒绝都记账：谁点的、什么时候点的，事后查得到";
        }
        return "这条请求已经有结论，按钮不再出现";
    }

    private static Map<String, Object> button(String text, String type, String approvalId, String decision) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put(VALUE_APPROVAL, approvalId);
        value.put(VALUE_DECISION, decision);
        return Map.of("tag", "button",
                "text", Map.of("tag", "plain_text", "content", text),
                "type", type,
                "value", value);
    }

    private static String orNone(String value) {
        return blank(value) ? "（没有）" : value;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}

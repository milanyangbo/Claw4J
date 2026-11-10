package io.github.claw4j.channel.feishu;

import io.github.claw4j.domain.port.ApprovalDecider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把一条待决审批渲染成飞书卡片 1.0 的对象树（P03）：工具名、参数摘要、发起人、两个按钮。
 *
 * <p>渲染与发送分开：卡片形状最容易被改坏，却最难在本地验证，因为发出去要真凭据、
 * 点一下要真人。拆开之后形状能用断言钉住，剩下的 SDK 调用只负责把拼好的结构塞进消息体。
 *
 * <p>三个字段缺一不可。没有工具名，审批人不知道批的是哪类动作；没有参数摘要，
 * 分不清批的是这一封邮件还是以后每一封，所以摘要为空时写「（没有参数）」，
 * 而不是省掉整行，省掉会被读成渲染漏了；没有发起人，事后不知道找谁。
 *
 * <p>过期不删按钮，改成一句话：卡片留着当这次请求的记录，按钮换文案并提示重新发起。
 * 留一个点下去必然失败的按钮，比没有按钮更容易让人误判。
 *
 * <p>按钮里只放两条信息：哪一条、批还是拒。谁点的取自事件里的 operator，
 * 不写进按钮——卡片会被转发、被截图复制，让它自称身份不可信。
 */
public final class FeishuApprovalCard {

    /** 按钮携带的键名。带前缀是为了不和同一张卡片上别的东西撞（表单、链接、自增 id）。 */
    public static final String VALUE_APPROVAL = "claw4j_approval";

    /** 按钮携带的决定：{@link #DECISION_APPROVE} 或 {@link #DECISION_DENY}。 */
    public static final String VALUE_DECISION = "claw4j_decision";

    /** 决定的取值：批准。 */
    public static final String DECISION_APPROVE = "approve";

    /** 决定的取值：拒绝。 */
    public static final String DECISION_DENY = "deny";

    /** 库里 {@code approval_request.status} 的三个取值之一：这条还没人定结论。 */
    private static final String PENDING = "pending";

    private FeishuApprovalCard() {
    }

    /**
     * 把一条审批渲染成卡片对象树。
     *
     * <p>{@code status} 与 {@code expired} 决定卡片颜色、标题、正文，以及带不带按钮。
     *
     * @param card 这条请求现在的样子
     * @return 可以直接作为消息体发出的卡片 1.0 对象树
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

    /** 是否还点得动：状态仍是 pending，并且没过期。别的组合一律只展示。 */
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

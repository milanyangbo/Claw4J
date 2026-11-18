package io.github.claw4j.agent.approval;

import io.github.claw4j.agent.advisor.PiiRedactor;
import io.github.claw4j.agent.audit.ToolCallAudit;
import io.github.claw4j.domain.ContextKeys;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.port.ApprovalLedger;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.Optional;

/**
 * 给"声明了要人批"的工具套一层闸：执行前问一次，没批准就**回一条工具结果**（票 05）。
 *
 * <p>为什么不在 Advisor 层做：Advisor 只能改写模型的回答，或者把整轮拦掉。前者会让模型以为
 * "这件事我做了"（它拿到了拒绝文本，却看不见自己那条工具调用被换掉），后者会让这一轮彻底结束，
 * 用户还得再问一遍。工具回调上的闸没有这两个问题：拒绝就是一次"调用了、没执行、返回了理由"的
 * 工具结果，模型在同一回合里拿到它，接着往下说。
 *
 * <p>为什么只挂在声明了要审批的工具上：读、检索这类工具每读一次弹一次审批，人两天就学会闭眼点
 * 批准。它那边的对应物也一样——{@code write} / {@code edit} 走沙箱与策略，不走审批。
 *
 * <p>"哪些工具有这道闸"由 {@link ApprovalRequiredTools} 决定，不看权限登记表。后者在 ADR-0030
 * 的阶段 4 整个删掉（权限只在清单级），这份名单是它消失之后"哪些要人批"唯一的落点。
 */
public class ApprovalRequiredToolCallback implements ToolCallback {

    /** 没带身份时那句话的前缀。判据是「这一轮说不清是谁」，与「等他批」不是同一件事。 */
    public static final String DENIAL_PREFIX = "没有权限";

    /**
     * 等审批那句话的前缀。
     *
     * <p>与 {@link #DENIAL_PREFIX} 分开：这两件事的下一步动作不同——「没有权限」要找管理员，
     * 「等你批」要找审批人点一下。它们原来都住在鉴权 Advisor 上，那个类在
     * ADR-0030 里整块下线之后，这句话跟着唯一还会用到它的地方搬到这里。
     */
    public static final String APPROVAL_PREFIX = "这个动作要先审批";

    /** 审批行上给人看的参数摘要上限，与 {@code args_summary varchar(512)} 对齐。 */
    private static final int SUMMARY_CHARS = 480;

    private final ToolCallback delegate;

    private final ApprovalGate gate;

    private final ToolCallAudit audit;

    /**
     * 包一层。
     *
     * @param delegate 被包的工具，必填
     * @param gate 审批门（兑现 + 登记），必填
     */
    public ApprovalRequiredToolCallback(ToolCallback delegate, ApprovalGate gate) {
        this(delegate, gate, null);
    }

    /**
     * 包一层，并把"这次执行是哪条批准放行的"交给审计。
     *
     * <p>这个配对只有这一处知道：兑现发生在闸里，兑出来的是哪条批准，而写审计要的是
     * 这一轮的 runId——两样都在这里合得上。曾经住在鉴权 Advisor 上，那个类在 ADR-0030
     * 里下线时这一段跟着不见了，结果是审计里那些行再也没带过 {@code approval_id}
     * （"这次执行是哪次批准的"整条链断在中间，且不报错）。
     *
     * @param delegate 被包的工具，必填
     * @param gate 审批门（兑现 + 登记），必填
     * @param audit 审计落点，可以为 {@code null}（不带审计的测试链路）
     */
    public ApprovalRequiredToolCallback(ToolCallback delegate, ApprovalGate gate, ToolCallAudit audit) {
        this.delegate = java.util.Objects.requireNonNull(delegate, "delegate");
        this.gate = java.util.Objects.requireNonNull(gate, "gate");
        this.audit = audit;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    /**
     * 先问一句有没有批准，再决定跑不跑。
     *
     * <p>没有身份时直接回一条拒绝结果，而不是抛异常：抛出去在框架那一侧会被包成工具错误，
     * 模型看到的只是一句"出错了"，而真正的原因是"这次调用不知道是谁发的"。
     *
     * @param toolInput 模型给的参数，必填
     * @param toolContext 工具上下文，身份与会话从这里取，可以为 {@code null}
     * @return 工具结果；没批准时是一条带理由的拒绝结果
     */
    @Override
    public String call(String toolInput, ToolContext toolContext) {
        String toolName = getToolDefinition().name();
        Principal principal = principalOf(toolContext);
        if (principal == null) {
            return DENIAL_PREFIX
                    + "：这次调用没有携带身份，声明了要审批的工具一律不放行。";
        }
        Optional<String> digest = CallFingerprint.of(toolName, toolInput);
        if (digest.isEmpty()) {
            return "这个工具的参数不是一段完整的 JSON 对象，没有执行：" + toolName;
        }
        // 只兑现一次：redeem 会把那条批准标成用掉，第二次调用拿到的必然是空。
        Optional<ApprovalLedger.Grant> redeemed =
                gate.redeem(principal.userId(), toolName, digest.get());
        if (redeemed.isPresent()) {
            // 把"这条批准放行了这次执行"交给审计，紧挨着执行那一下。分开写就会有人只改一边。
            if (audit != null) {
                audit.granted(text(toolContext, ContextKeys.RUN_ID), toolName, digest.get(),
                        redeemed.get().id());
            }
            return delegate.call(toolInput, toolContext);
        }
        Optional<String> approvalId = gate.ask(new ApprovalLedger.Ask(
                text(toolContext, ContextKeys.RUN_ID), principal.userId(), toolName, digest.get(),
                summaryOf(toolInput), originOf(toolContext, principal)));
        return denial(approvalId.orElse(null));
    }

    /**
     * 没批准时那条工具结果。
     *
     * <p>四句都要说清：没有执行、不要重试同一个调用、没有新的输出、向用户说明原因。少一句，
     * 模型就会把同一个调用原样再发一遍——而那既不会变成批准，又会把真正的进展挤掉。
     *
     * @param approvalId 审批编号；没登记上时为 {@code null}
     * @return 工具结果文本
     */
    private static String denial(String approvalId) {
        StringBuilder text = new StringBuilder(APPROVAL_PREFIX)
                .append("：这个动作要先审批，所以它没有执行，也没有新的输出。");
        if (approvalId == null) {
            text.append("这一次也没能登记成审批请求（没有审批设施，或者库写不进去），")
                    .append("所以在修好之前它不会被放行。");
        } else {
            text.append("审批编号 ").append(approvalId)
                    .append("，批准之后会自动接着做，不用重新说明背景。");
        }
        return text.append("不要重试同一个调用：再发一次既不会变成批准，也不会生成新的审批；")
                .append("向用户说明这个动作在等谁批准。").toString();
    }

    /**
     * 给人看的参数摘要：脱敏之后截断。
     *
     * <p>脱敏在截断之前：反过来的话，一个手机号被截成两半，两半都不再匹配号码规则，于是掩码失效。
     *
     * @param toolInput 参数原文
     * @return 摘要
     */
    private static String summaryOf(String toolInput) {
        String masked = PiiRedactor.mask(toolInput == null ? "" : toolInput);
        return masked.length() <= SUMMARY_CHARS
                ? masked : masked.substring(0, SUMMARY_CHARS - 1) + "…";
    }

    /**
     * 这条请求从哪段对话来。没有会话键时为 {@code null}——那时结论落得下来，但没有"接着做"。
     *
     * @param toolContext 工具上下文，可以为 {@code null}
     * @param principal 发起人
     * @return 来处
     */
    private static ApprovalLedger.Origin originOf(ToolContext toolContext, Principal principal) {
        String sessionKey = text(toolContext, ChatMemory.CONVERSATION_ID);
        if (sessionKey == null) {
            return null;
        }
        return new ApprovalLedger.Origin(sessionKey, text(toolContext, ContextKeys.CHANNEL),
                text(toolContext, ContextKeys.CHAT_REF), principal);
    }

    private static Principal principalOf(ToolContext toolContext) {
        if (toolContext == null) {
            return null;
        }
        Object value = toolContext.getContext().get(ContextKeys.PRINCIPAL);
        return value instanceof Principal principal ? principal : null;
    }

    private static String text(ToolContext toolContext, String key) {
        if (toolContext == null) {
            return null;
        }
        Object value = toolContext.getContext().get(key);
        return value instanceof String text && !text.isBlank() ? text : null;
    }
}

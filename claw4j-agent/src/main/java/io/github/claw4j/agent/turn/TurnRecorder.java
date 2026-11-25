package io.github.claw4j.agent.turn;

import io.github.claw4j.agent.audit.AuditErrorCode;
import io.github.claw4j.agent.audit.AuditEvent;
import io.github.claw4j.agent.audit.AuditStatus;
import io.github.claw4j.agent.audit.ToolCallAudit;
import io.github.claw4j.agent.channel.ConversationDelivery;
import io.github.claw4j.agent.channel.DeliveryTarget;
import io.github.claw4j.agent.observability.SpanTracer;
import io.github.claw4j.domain.NotifyPolicy;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.principal.Principal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatResponse;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一轮的记账与收尾：span 的属性、审计的两条事件、目标的额度、结果的投递。
 *
 * <p>从门面里抽出来，是因为这些事<b>与「怎么跑这一轮」无关</b>：它们只关心「跑完了、花了多少、
 * 成没成、要不要推出去」。留在门面里时，读一遍主路径要先跳过四十行 span 属性名。
 *
 * <p>四样东西都可能缺席（没开观测、没开审计、没开目标存储、没配推送），缺席时的行为统一是
 * <b>不做这件事</b>，不造空实现：造一个空实现会让「审计到底生效没有」没有答案。
 */
final class TurnRecorder {

    private static final Logger log = LoggerFactory.getLogger(TurnRecorder.class);

    /**
     * 根 span 的名字：一轮的执行体（P14）。
     *
     * <p>不是「一次请求」：五个通道都把 {@code agent.reply} 丢进线程池，请求的 trace 在入池那一刻
     * 就断了，而模型轮与工具调用都发生在入池之后。根放在这一轮上，trace 的边界才与审计里那两条
     * {@code agent_run} 事件之间是同一段。
     *
     * <p>编号只活一轮（ADR-0027）：{@code executionId} 是这一轮的身份，不是能接上的把手，下一轮是新编号，
     * 同一段对话靠 {@code sessionKey} 连起来。
     */
    static final String EXECUTION_SPAN = "execution";

    /** 一轮动作的 span：包住 {@code call()} 与收尾。工具 span 都挂在它下面。 */
    static final String TURN_SPAN = "model.turn";

    /**
     * 这一轮是哪一轮。trace 与审计之间只有这一把键：拿它去 {@code audit_event.execution_id} 查，
     * 这一轮的全部事件都在。
     *
     * <p>名字与工具上下文里那把（{@link io.github.claw4j.domain.ContextKeys#EXECUTION_ID}）同一个词：
     * 同一个编号在 trace、审计、工具参数三处出现时，不该有两个名字——读的人会以为它们指两件事。
     */
    private static final String ATTR_EXECUTION_ID = "claw4j.execution.id";

    /**
     * 谁的这件事——只放权限指纹，不放 {@code userId}。
     *
     * <p>trace 会进第三方后端，而「哪个员工问了什么」本身是内部信息。指纹能回答「这两个 span
     * 是同一个人吗」（那正是排查时要问的），却反推不出是谁。与审计表里存 userId 的区别正是
     * 「审计归自己、trace 可能出去」。
     */
    private static final String ATTR_OWNER = "claw4j.owner";

    /** 这一轮花掉的 token。拿不到时不写——写 0 会被读成「没花钱」。 */
    private static final String ATTR_TOKENS = "claw4j.tokens";

    /** 这一轮是哪个模型答的。取响应里的 model id，不是配置里的路由名（路由在这一层看不到）。 */
    private static final String ATTR_MODEL = "gen_ai.response.model";

    /**
     * trace（P14）。默认是空实现：没开观测时每次 {@code open} 都是一次会被内联掉的调用，埋点代码
     * 与开着时逐字相同。关着不该是另一条代码路径，否则那条路径平时不跑，等开了观测才第一次执行。
     */
    private final SpanTracer tracer;

    /**
     * 审计规则层（ADR-0027）：这一轮开始与结束各一条事件。
     *
     * <p>为 null 时一行都不写。它不是可选优化——没开审计的部署本来就该零写入，造一个空实现去假装
     * 它在，会让「审计到底有没有生效」没有答案。
     */
    private final ToolCallAudit audit;

    /**
     * 结果投递与回收（A05）。可以为 null：没有 run 存储就没有「送到哪儿」这回事，那是能力不在场，
     * 同样不造空实现。
     */
    private final ConversationDelivery delivery;

    /**
     * @param tracer 埋点入口，为 null 时用 {@link SpanTracer#NONE}
     * @param audit 审计接缝，可以为 null
     * @param delivery 结果投递，可以为 null
     */
    TurnRecorder(SpanTracer tracer, ToolCallAudit audit, ConversationDelivery delivery) {
        this.tracer = tracer == null ? SpanTracer.NONE : tracer;
        this.audit = audit;
        this.delivery = delivery;
    }

    /** 埋点入口，门面开 span 时用；关着时是空实现而不是 null。 */
    SpanTracer tracer() {
        return tracer;
    }

    /**
     * 审计的「这一轮开始」。
     *
     * <p>与结束那条成对：只有结束的话，跑了多久、两条并发的先后都只能靠时间戳猜。
     *
     * @param scope 审计作用域
     */
    void runStarted(AuditEvent.Scope scope) {
        if (audit != null) {
            audit.runStarted(scope);
        }
    }

    /**
     * 收尾那条审计事件。没开审计时什么都不写——账本序号没有任何人需要。
     *
     * @param scope 审计作用域
     * @param status 这一轮的结局
     * @param errorCode 失败时的错误码，成功时为 null
     */
    void runFinished(AuditEvent.Scope scope, AuditStatus status, AuditErrorCode errorCode) {
        if (audit != null) {
            audit.runFinished(scope, status, errorCode);
        }
    }

    /**
     * 记下这一轮花掉的 token，按策略投递，然后写收尾那条审计（A04）。
     *
     * <p>这里只记模型轮，工具调用不归它记：这一层看不到工具循环里调了什么，猜一个填上去比留空更糟。
     * 工具调用那一项由 {@code MeteredToolCallback} 在调用发生的地方扣额度（A04），并在同一处落一行
     * 审计（P07）。要回答「这一轮调了哪些工具」，读 step 表里的 {@code StepKind#TOOL_CALLS} 行，
     * 不要读这里。
     *
     * <p>这一轮花掉的 token 不再记账（2026-11-25，ADR-0025 作废）：额度原本挂在目标上，而目标与
     * 会话语义层一起下线。现在这一层只负责收尾——脱敏后的回答落日志、按策略投递、写审计终态。
     * 要恢复用量统计，得在新的一层上重建，不是把 {@code tokens} 参数加回来。
     *
     * @param scope 审计作用域
     * @param answer 这一轮的回答全文
     * @param pushTo 要推的地址；null 表示不推
     * @param policy 通知策略
     */
    void closeTurn(AuditEvent.Scope scope, String answer,
                   DeliveryTarget pushTo, NotifyPolicy policy) {
        if (delivery != null && pushTo != null) {
            delivery.announce(pushTo, policy, answer);
        }
        runFinished(scope, AuditStatus.SUCCEEDED, null);
    }

    /**
     * 这一轮要不要推、推到哪儿。
     *
     * <p>同步请求（HTTP、飞书消息）的回答已经从响应里返回了，再推一次就是同一条内容出现两遍，
     * 那时返回 null。定时任务与审批续跑没有人等结果，于是推到这段对话（ADR-0027 决定四：结果只回到
     * 它开始的地方，不回退到「最近用过的通道」）。
     *
     * @param answeredAt 已经就地回答过的地址
     * @param message 入站消息，作为推送地址的来源
     * @return 要推的地址；不推时为 null
     */
    static DeliveryTarget pushTargetOf(DeliveryTarget answeredAt, InboundMessage message) {
        return answeredAt == null ? addressOf(message) : null;
    }

    /**
     * 这一轮来自哪段对话。没有通道名时是 null（老调用方与单测）。
     *
     * @param message 入站消息
     * @return 推送地址；通道名为空时为 null
     */
    static DeliveryTarget addressOf(InboundMessage message) {
        String channel = message.channel();
        if (channel == null || channel.isBlank()) {
            return null;
        }
        return new DeliveryTarget(channel, message.chatId(), message.direct());
    }

    /**
     * 把这一轮的结论写到 {@code model.turn} 上（P14）：花掉的 token、哪个模型答的。
     *
     * <p>两个值都「没有就不写」：token 拿不到时写 0 与「这轮没花钱」分不开，模型名同理。空属性是
     * 诚实的，补一个看起来合理的默认值才是——后者的代价是某天有人按这个数字判断成本。
     *
     * @param turn 这一轮的 span
     * @param response 模型响应，可为 null（流式那一侧拿不到汇总 usage）
     */
    static void stampTurn(SpanTracer.SpanScope turn, ChatResponse response) {
        Long tokens = tokensOf(response);
        if (tokens != null && tokens > 0) {
            turn.attribute(ATTR_TOKENS, Long.toString(tokens));
        }
        String model = modelOf(response);
        if (model != null && !model.isBlank()) {
            turn.attribute(ATTR_MODEL, model);
        }
    }

    /**
     * 这一轮花掉的 token。拿不到是 null，不是 0——不拿估算值冒充真实值（与
     * {@code MeteredChatModel} 同一个口径）。
     *
     * <p>两者必须分开：0 是「这一轮没花钱」，null 是「这一轮没有数」。目标记账拿 null 时不重算用量
     * （{@code JdbcSessionGoalStore#account}），拿 0 会把「没有数」记成「没花费」，而预算因此永远
     * 差一截，并且没有任何一处报错。
     *
     * @param response 模型响应，可为 null
     * @return 总 token 数；拿不到时为 null
     */
    static Long tokensOf(ChatResponse response) {
        if (response == null || response.getMetadata() == null || response.getMetadata().getUsage() == null) {
            return null;
        }
        Integer total = response.getMetadata().getUsage().getTotalTokens();
        return total == null ? null : total.longValue();
    }

    /**
     * 响应里报的模型名。不是配置里的路由名——路由在 {@code MeteredChatModel} 那一层，这里看不到。
     *
     * @param response 模型响应，可为 null
     * @return 模型名；拿不到时为 null
     */
    private static String modelOf(ChatResponse response) {
        return response == null || response.getMetadata() == null
                ? null : response.getMetadata().getModel();
    }

    /**
     * 根 span 的属性：哪一轮、谁的。
     *
     * <p>为空时不写这一格：写一个空串会让「有编号」与「没有」在图上长得一样。
     *
     * @param executionId 这一轮的编号，可为 null
     * @param principal 这一轮的身份，可为 null
     * @return 按插入顺序排列的属性表
     */
    static Map<String, String> runAttributes(String executionId, Principal principal) {
        Map<String, String> attributes = new LinkedHashMap<>();
        if (executionId != null) {
            attributes.put(ATTR_EXECUTION_ID, executionId);
        }
        if (principal != null) {
            attributes.put(ATTR_OWNER, principal.userId());
        }
        return attributes;
    }
}

package io.github.claw4j.agent.observability;

import io.github.claw4j.agent.audit.AuditEvent;
import io.github.claw4j.agent.audit.ToolCallAudit;
import io.github.claw4j.domain.ContextKeys;
import io.github.claw4j.domain.principal.Principal;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.tool.observation.ToolCallingObservationContext;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 给一个工具加计量、一段 span 与两条审计事件：被调了几次、成没成、花了多久（P07 / P14）。
 *
 * <p>包在 {@code ToolCallback} 上，不包在 {@code ToolCallingManager} 上：一次
 * {@code executeToolCalls} 可能执行零个、一个或多个工具调用（模型一次要三个是常事），计在 manager
 * 上数的是「轮次」而不是「工具调用」。而「哪个工具被问得多」正是按工具算才有用，它决定下一个该优化
 * 谁、哪个该收权限。span 同理：一段 {@code tool.<名字>} 的 duration 只有挂在这里才是一次真实调用的
 * 耗时。
 *
 * <p>三件事放在一个类里，因为事实同源：计数的前提是「这次调用真的发生了」，审计要落的
 * （{@link ToolCallAudit}，P07）与 trace 要画的（{@link SpanTracer}，P14）也是同一件事。分成几个
 * 包装类会多几层对象，还会让「只包了计量那层」的路径悄悄漏掉另外几件——工具清单只收 provider、不收
 * 单个 callback，就是这样漏的。
 *
 * <p>扣额度不在这里（ADR-0027 决定五）：额度是目标的 token budget，按轮记账
 * （{@code ChatClientAgentPort#closeTurn}），而「这一轮绕了太多次」的止损由 Spring AI 的工具循环
 * 执行。把成本控制与失控保护合成一个数，两者就只能由同一个人处理。
 *
 * <p>没有 registry 时照样包：计数与审计都与有没有装 actuator 无关，装不装只影响指标报不报得出去。
 * {@link SpanTracer#NONE} 是零开销的空实现，埋点代码只有一种写法。
 *
 * <p>这里看不到 {@code denied}：被拦下的调用根本没被执行，走不到这个 {@code call} 里——看不见的
 * 工具进不了清单，要人批的工具在触发前就被审批闸拦下（ADR-0030 之后，拒绝只剩审批那一处）。
 *
 * <p>两个 {@code call} 重载都覆写：带 {@code ToolContext} 的那个在 Spring AI 里是 default 方法，
 * 但它未必转调单参那个（不同实现不一样）。只包一个的话，走另一个重载的调用就漏计了。
 */
public class MeteredToolCallback implements ToolCallback {

    public static final String TOOL_CALLS = "claw4j.tool.calls";

    /** span 上的工具名。与 {@code claw4j.tool.calls} 的 {@code tool} 标签同一个口径。 */
    public static final String ATTR_TOOL = "claw4j.tool";

    /** 结果档位。取值与计数器那个 {@code outcome} 标签逐字一致——两处各说一套的话，对不上号。 */
    public static final String ATTR_OUTCOME = "claw4j.outcome";

    /** 失败时的异常类型名。只记类型不记 message（trace 会进第三方后端）。 */
    public static final String ATTR_ERROR_TYPE = "claw4j.error.type";

    private final ToolCallback delegate;
    private final String toolName;
    private final MeterRegistry registry;

    /**
     * 审计（P07）。可以为 null：没有 run 存储时就没有可写的落点。
     *
     * <p>审计写在工具回调上，是因为这里是「这次调用真的发生了」唯一的落点。鉴权 Advisor 那一侧只
     * 知道「允许执行」，而允许与发生之间还隔着一次真实的调用（它可能抛异常、也可能根本没被调起来）。
     */
    private final ToolCallAudit audit;

    /**
     * trace（P14）。默认是空实现：没开观测时埋点照样走同一条代码路径，只是 {@code open} 返回的是一段
     * 什么都不做的 scope。
     */
    private final SpanTracer tracer;

    /**
     * 观测注册表，只用来读「当前这次调用是哪个 {@code toolCallId}」（票 06）。
     *
     * <p>它必须是<b>框架那个</b>注册表：{@code DefaultToolCallingManager} 调工具之前会先建
     * {@code ToolCallingObservationContext} 并把它推进 scope（Spring AI 2.0.2 `:313-328`），
     * 换一个注册表就读不到。为 {@link ObservationRegistry#NOOP} 时等于关闭这个能力。
     */
    private final ObservationRegistry observations;

    /**
     * 只加计量的包装。
     *
     * @param delegate 被包装的工具，必填
     * @param registry 指标注册表；为 null 表示调用方没装 actuator，照常记录调用，只是没有指标可报
     */
    public MeteredToolCallback(ToolCallback delegate, MeterRegistry registry) {
        this(delegate, registry, null, SpanTracer.NONE, ObservationRegistry.NOOP);
    }

    /**
     * 加上审计的包装。
     *
     * @param delegate 被包装的工具，必填
     * @param registry 指标注册表，可为 null
     * @param audit 为 null 表示没装配审计：计量照常，只是这一次调用不会留下一行审计
     */
    public MeteredToolCallback(ToolCallback delegate, MeterRegistry registry, ToolCallAudit audit) {
        this(delegate, registry, audit, SpanTracer.NONE, ObservationRegistry.NOOP);
    }

    /**
     * 完整包装。
     *
     * @param delegate 被包装的工具，必填
     * @param registry 指标注册表，可为 null
     * @param audit 审计接缝，可为 null
     * @param tracer 为 null 时按空实现处理：埋点不该因为少一个参数就把调用打断
     */
    public MeteredToolCallback(ToolCallback delegate, MeterRegistry registry,
                               ToolCallAudit audit, SpanTracer tracer) {
        this(delegate, registry, audit, tracer, ObservationRegistry.NOOP);
    }

    /**
     * 完整包装，并且能读到工具调用编号。
     *
     * @param delegate 被包装的工具，必填
     * @param registry 指标注册表，可为 null
     * @param audit 审计接缝，可为 null
     * @param tracer 为 null 时按空实现处理
     * @param observations 框架那个观测注册表；为 null 按
     *     {@link ObservationRegistry#NOOP} 处理——少传一个参数不该让调用失败，只是拿不到编号
     */
    public MeteredToolCallback(ToolCallback delegate, MeterRegistry registry,
                               ToolCallAudit audit, SpanTracer tracer, ObservationRegistry observations) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.registry = registry;
        this.audit = audit;
        this.tracer = tracer == null ? SpanTracer.NONE : tracer;
        this.observations = observations == null ? ObservationRegistry.NOOP : observations;
        this.toolName = delegate.getToolDefinition().name();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    /**
     * 单参重载：这一层拿不到 {@code executionId}，于是不会有审计行。span 照开——它记的是这个工具被调了多久，
     * 与有没有 run 无关。
     *
     * @param toolInput 模型给出的工具参数
     * @return 工具的返回内容
     */
    @Override
    public String call(String toolInput) {
        try (SpanTracer.SpanScope span = openSpan()) {
            try {
                String result = delegate.call(toolInput);
                span.attribute(ATTR_OUTCOME, "ok");
                count("ok");
                return result;
            }
            catch (RuntimeException failure) {
                span.attribute(ATTR_OUTCOME, "error");
                span.attribute(ATTR_ERROR_TYPE, failure.getClass().getSimpleName());
                span.failed(failure);
                count("error");
                throw failure;
            }
        }
    }

    /**
     * 带上下文的那一个重载：{@code executionId} 与审计的 scope 只在它这里拿得到。
     *
     * <p>这一层没有「额度不够就不执行」的分支（ADR-0027 决定五）：成本上限在目标的 token 预算上，
     * 按轮记账；这一轮绕了太多次由工具循环在上限处截断。工具回调只回答「这次调用发生了什么」。
     *
     * <p>span 上不挂审计行号：审计与 trace 是两条独立的管道，共同的键是 {@code claw4j.run.id}——拿它
     * 去库里查，一次运行的每条事件都在。挂一个「第几行」既不比它多回答什么，又把两条本可以各自演进的
     * 管道钉在一起。OpenClaw 也是这个分法：run span 的属性只有 outcome / channel / provider / model
     * 与 agent，出错时多一个 errorCategory（{@code docs/gateway/opentelemetry/spans-and-events.md:37}），
     * 账本序号留在账本里，给排序、翻页与上限淘汰用。
     *
     * @param toolInput 模型给出的工具参数
     * @param toolContext 工具上下文，{@code executionId} 与身份从这里取
     * @return 工具的返回内容
     */
    @Override
    public String call(String toolInput, ToolContext toolContext) {
        // 编号必须在 openSpan() 之前读：span 一开，当前观测就换成它自己了（见 toolCallId 的注释）。
        String toolCallId = toolCallId();
        ToolContext context = withToolCallId(toolContext, toolCallId);
        try (SpanTracer.SpanScope span = openSpan()) {
            // 开始那一条先写，结束那一条在两条出口各写一次：只有结束的话，
            // 「这个工具跑了多久」和「两个并发调用谁先开始」都只能靠时间戳猜。
            AuditEvent.Scope scope = scopeOf(context);
            toolStarted(scope, toolInput, toolCallId);
            try {
                String result = delegate.call(toolInput, context);
                toolFinished(scope, toolInput, null, toolCallId);
                span.attribute(ATTR_OUTCOME, "ok");
                count("ok");
                return result;
            }
            catch (RuntimeException failure) {
                // 抛异常也是一次「发生过的调用」：审计里它是 finished + failed，
                // 与被拒绝（根本没执行）是两件事——修法与改配置完全不同。
                toolFinished(scope, toolInput, failure.getClass().getSimpleName(), toolCallId);
                span.attribute(ATTR_OUTCOME, "error");
                span.attribute(ATTR_ERROR_TYPE, failure.getClass().getSimpleName());
                span.failed(failure);
                count("error");
                throw failure;
            }
        }
    }

    /**
     * 一行 {@code tool.<工具名>} 的 span。属性在开始时就把工具名写死，后面只补结果。
     *
     * @return 这段调用的 span
     */
    private SpanTracer.SpanScope openSpan() {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(ATTR_TOOL, toolName);
        return tracer.open("tool." + toolName, attributes);
    }

    /**
     * 写开始那条审计事件。写不写得上与这次调用无关——审计缺一行不该把工具打成失败。
     *
     * @param scope 审计作用域
     * @param toolInput 工具参数原文，只用来算指纹
     * @param toolCallId 这次调用的编号，可为 null
     */
    private void toolStarted(AuditEvent.Scope scope, String toolInput, String toolCallId) {
        if (audit != null) {
            audit.toolStarted(scope, toolName, toolInput, toolCallId);
        }
    }

    /**
     * 写结束那条审计事件。
     *
     * @param scope 审计作用域
     * @param toolInput 工具参数原文，只用来算指纹
     * @param failure 异常类型名；null 表示正常返回
     * @param toolCallId 这次调用的编号，可为 null
     */
    private void toolFinished(AuditEvent.Scope scope, String toolInput, String failure,
                              String toolCallId) {
        if (audit != null) {
            audit.toolFinished(scope, toolName, toolInput, failure, toolCallId);
        }
    }

    /**
     * 这一条事件的身份与位置。
     *
     * <p>身份从工具上下文里取：入口把 principal 与 {@code executionId} 一起放进去了，而这里拿不到 Advisor
     * 的上下文。取不到 principal 时照样写（那一格没人）；取不到 {@code executionId} 时这一行落不下去——
     * 没有轮次的事件不属于任何一次运行，与 OpenClaw 的必填同口径。
     *
     * <p>通道留空：工具上下文里没有它，而为了填这一格去加一个 key，会让审计与投递两条本来无关的线互相
     * 拉扯。按 {@code executionId} 与 {@code sessionKey} 关联已经够查。
     *
     * <p>{@code agentId} 从工具上下文读（ADR-0037 票 01）：入口把部署级 agent 名放进去了。读不到时
     * 落 {@code unknown}——那一格空着的话，「这条是哪个 agent 干的」在多 agent 部署里就永远答不了。
     *
     * <p>公开而不是私有：审批闸那一侧拦下调用时也要组同一份作用域（{@code ApprovalRequiredToolCallback}
     * 的 {@code blocked} 事件）。两处各写一份的话，同一个工具事件在两条路径上长得不一样，而这种不一致
     * 不会报错，只会让按 agent 过滤时少几行。
     *
     * @param toolContext 工具上下文，可为 null
     * @return 事件作用域
     */
    public static AuditEvent.Scope scopeOf(ToolContext toolContext) {
        if (toolContext == null) {
            return AuditEvent.Scope.empty();
        }
        // 两把会话键（ADR-0037 第三批）：session_key 是会话键（不含段），session_id 是那一段。
        // 工具上下文里两个都在（PromptComposer 塞的 CONVERSATION 与框架的 CONVERSATION_ID）；
        // 子链只塞了后者，那时退回它——它至少能唯一定位这一轮。
        String sessionId = textOf(toolContext, ChatMemory.CONVERSATION_ID);
        String sessionKey = textOf(toolContext, ContextKeys.CONVERSATION);
        return AuditEvent.Scope.of(executionIdOf(toolContext), sessionKey == null ? sessionId : sessionKey,
                sessionId, agentIdOf(toolContext), principalOf(toolContext), channelOf(toolContext));
    }

    /** 从工具上下文取一格非空白字符串。 */
    private static String textOf(ToolContext toolContext, String key) {
        Object value = toolContext.getContext().get(key);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private static String agentIdOf(ToolContext toolContext) {
        Object value = toolContext.getContext().get(ContextKeys.AGENT_ID);
        return value instanceof String agentId && !agentId.isBlank() ? agentId : null;
    }

    private static String channelOf(ToolContext toolContext) {
        Object value = toolContext.getContext().get(ContextKeys.CHANNEL);
        return value instanceof String channel && !channel.isBlank() ? channel : null;
    }

    /**
     * 从工具上下文读这次调用的编号（票 06）。
     *
     * <p>公开是因为审批闸那一侧也要用（它拦下调用时写 {@code blocked} 事件）。取不到返回 null，
     * 而那在今天是常态：这个键由本类写进上下文，而审批闸在计量层<b>之外</b>——被拦下的调用走到
     * 那里时键还不存在。这是包装顺序决定的，不是漏传；不为了填这一格去改顺序——把计量提到最外
     * 会让「被审批拒掉」也算进「被调了几次」。
     *
     * @param toolContext 工具上下文，可为 null
     * @return 调用编号；没有时返回 null
     */
    public static String toolCallIdOf(ToolContext toolContext) {
        if (toolContext == null) {
            return null;
        }
        Object value = toolContext.getContext().get(ContextKeys.TOOL_CALL_ID);
        return value instanceof String toolCallId && !toolCallId.isBlank() ? toolCallId : null;
    }

    private static Principal principalOf(ToolContext toolContext) {
        if (toolContext == null) {
            return null;
        }
        Object value = toolContext.getContext().get(ContextKeys.PRINCIPAL);
        return value instanceof Principal principal ? principal : null;
    }

    /** 与计划工具那个读法一致：executionId 由入口放进工具上下文，模型点名不了别人的 run。 */
    private static String executionIdOf(ToolContext toolContext) {
        if (toolContext == null) {
            return null;
        }
        Object value = toolContext.getContext().get(ContextKeys.EXECUTION_ID);
        return value instanceof String executionId && !executionId.isBlank() ? executionId : null;
    }

    /**
     * 这次调用在框架里的编号（票 06）。
     *
     * <p>从<b>当前观测</b>取，不从参数、不从上下文取：Spring AI 2.0.2 的
     * {@code DefaultToolCallingManager} 建 {@code ToolCallingObservationContext} 时把
     * {@code toolCall.id()} 放了进去（`:313-316`），然后在它的 {@code observe(...)} 里调工具
     * （`:321-328`）。所以回调执行时，那一条就在 scope 上——它是框架已经给出的东西，我们只读。
     *
     * <p>走这条而不是去包装 {@code ToolCallingManager}：包装 manager 得把「逐条解析工具调用」
     * 自己写一遍（找 callback、限流、组返回值、拼会话历史），那正是 ADR-0037 要去的方向的反面。
     *
     * <p>取不到就返回 {@code null}，**不编一个 UUID**：编出来的 id 会让「同参数两次调用」看起来
     * 可分，实际每次都不一样，任何按它做的统计都是假数。取不到的情形有三种——部署没开观测
     * （NOOP）、provider 没给 id、回调不是从框架的工具循环里进来的。
     *
     * <p>必须在 {@link #openSpan()} <b>之前</b>调：这段 span 一旦开起来，当前观测就换成了它自己，
     * 再读只能读到自己——那种错不报错，只是编号永远为空。
     *
     * @return 调用编号；读不到时为 null
     */
    private String toolCallId() {
        Observation current = observations.getCurrentObservation();
        // 编号在 context 上，不在 Observation 对象上：框架建的是 ToolCallingObservationContext。
        if (current == null || !(current.getContext() instanceof ToolCallingObservationContext toolCall)) {
            return null;
        }
        String id = toolCall.getToolCallId();
        return id == null || id.isBlank() ? null : id;
    }

    /**
     * 把编号放进上下文再往内传。
     *
     * <p>必须新建一个 {@link ToolContext}：它的 map 在构造时就被 {@code unmodifiableMap} 包住了
     * （Spring AI `ToolContext:52-54`），往原实例里 put 会抛。
     *
     * <p>取不到编号时返回<b>同一个实例</b>：不新建一层、也不留一个值为 null 的键——那会让
     * 「没有这个能力」与「这次调用没有编号」在下游看起来一样。
     *
     * @param toolContext 框架传进来的上下文，可为 null（单参路径不会走到这里）
     * @param toolCallId 调用编号，可为 null
     * @return 带编号的上下文
     */
    private static ToolContext withToolCallId(ToolContext toolContext, String toolCallId) {
        if (toolCallId == null) {
            return toolContext;
        }
        Map<String, Object> merged = new HashMap<>(
                toolContext == null ? Map.of() : toolContext.getContext());
        merged.put(ContextKeys.TOOL_CALL_ID, toolCallId);
        return new ToolContext(merged);
    }

    private void count(String outcome) {
        if (registry == null) {
            return;
        }
        Counter.builder(TOOL_CALLS)
                .description("工具调用次数")
                .tag("tool", toolName)
                .tag("outcome", outcome)
                .register(registry)
                .increment();
    }
}

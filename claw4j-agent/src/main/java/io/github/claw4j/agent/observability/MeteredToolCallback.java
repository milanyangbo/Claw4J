package io.github.claw4j.agent.observability;

import io.github.claw4j.agent.ToolCallLog;
import io.github.claw4j.agent.audit.AuditEvent;
import io.github.claw4j.agent.audit.ToolCallAudit;
import io.github.claw4j.domain.ContextKeys;
import io.github.claw4j.domain.principal.Principal;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 给一个工具加计量与一段 span：被调了几次、成没成、花了多久（P14 起还进 trace）；
 * 顺带记下"这一轮调用过它"。
 *
 * <p><b>为什么在 {@code ToolCallback} 上，而不是在 {@code ToolCallingManager} 上</b>：
 * 一次 {@code executeToolCalls} 可能执行零个、一个或多个工具调用（模型一次要三个是常事），
 * 计在 manager 上数的是"轮次"，不是"工具调用"。
 * 而"哪个工具被问得多"恰恰是按工具算才有用——它决定下一个该优化谁、哪个该收权限。
 * span 同理：一段 {@code tool.<名字>} 的 duration 只有挂在这里才是一次真实调用的耗时。
 *
 * <p><b>它做四件事，因为这四个事实同源</b>：计数的前提是"这次调用真的发生了"，
 * {@link ToolCallLog} 要记的、审计要落的（{@link ToolCallAudit}，P07）、
 * trace 要画的（{@link SpanTracer}，P14）也都是同一件事。分成几个包装类会多几层对象，
 * 还会让"只包了计量那层"的路径悄悄漏掉另外几件——
 * 这个仓库已经因为"某条路径没被包上"出过一次问题（工具清单只收 provider 不收单个 callback）。
 *
 * <p><b>扣额度不在这里了</b>（ADR-0027 决定五）：额度是目标的 token budget，
 * 按"轮"记账（{@code ChatClientAgentPort#closeTurn}）；而"这一轮绕了太多次"的止损由
 * Spring AI 的工具循环执行。这里曾经按会话累计工具调用次数与耗时——
 * 那是把"成本控制"与"失控保护"合成一个数，而两者该由不同的人处理。
 *
 * <p><b>没有 registry 时照样包</b>：记录"这一轮调过哪些工具"是语义缓存判断副作用的依据，
 * 与"有没有装 actuator"无关。装不装只影响计数报不报得出去。span 也一样：
 * {@link SpanTracer#NONE} 是零开销的空实现，埋点代码只有一种写法。
 *
 * <p><b>这里看不到 {@code denied}</b>：鉴权拒绝发生在 Advisor 那一层，工具根本没被执行，
 * 所以走不到这个 {@code call} 里。那个计数只能在鉴权 Advisor 上报（见
 * {@code AuthorizationAdvisor}）。两处各报一半，是因为各自是唯一知道真相的地方。
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
     * 审计（P07）。可以为 null——没有 run 存储时就没有可写的落点。
     *
     * <p><b>为什么审计写在工具回调上</b>：这里是"这次调用真的发生了"唯一的落点。
     * 鉴权 Advisor 那一侧只知道"允许执行"，而"允许"与"发生"之间还隔着一次真实的调用
     * （它可能抛异常、也可能根本没被调起来）。
     */
    private final ToolCallAudit audit;

    /**
     * trace（P14）。默认是空实现——没开观测时埋点照样走同一条代码路径，
     * 只是 {@code open} 返回的是一段什么都不做的 scope。
     */
    private final SpanTracer tracer;

    /** @param registry 为 null 表示调用方没装 actuator：照常记录调用，只是没有指标可报 */
    public MeteredToolCallback(ToolCallback delegate, MeterRegistry registry) {
        this(delegate, registry, null, SpanTracer.NONE);
    }

    /**
     * @param audit 为 null 表示没装配 run 存储：计量照常，只是这一次调用不会留下一行审计
     */
    public MeteredToolCallback(ToolCallback delegate, MeterRegistry registry, ToolCallAudit audit) {
        this(delegate, registry, audit, SpanTracer.NONE);
    }

    /**
     * @param tracer 为 null 时按空实现处理：埋点不该因为少一个参数就把调用打断
     */
    public MeteredToolCallback(ToolCallback delegate, MeterRegistry registry,
                               ToolCallAudit audit, SpanTracer tracer) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.registry = registry;
        this.audit = audit;
        this.tracer = tracer == null ? SpanTracer.NONE : tracer;
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
     * 单参重载：这一层拿不到 runId，于是不会有审计行、也就没有 seq 可写。
     * span 照开——它记的是"这个工具被调了多久"，与有没有 run 无关。
     */
    @Override
    public String call(String toolInput) {
        ToolCallLog.record(toolName);
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
     * 带上下文的那一个重载：runId 与审计的 scope 只在它这里拿得到。
     *
     * <p>这一层不再有"额度不够就不执行"的分支（ADR-0027 决定五）：
     * 成本上限在目标的 token 预算上，按轮记账；这一轮绕了太多次由工具循环在上限处截断。
     * 工具回调只回答"这次调用发生了什么"。
     *
     * <p><b>span 上不挂审计行号</b>：审计与 trace 是两条独立的管道，共同的那个键是
     * {@code claw4j.run.id}——拿它去库里查，一次运行的每条事件都在。挂一个"第几行"
     * 既不比 runId 多回答什么，又把两条本可以各自演进的管道钉在一起。
     * OpenClaw 也是这个分法：它的 span 属性只有 outcome / channel / provider / model / agent，
     * 账本序号留在账本里，给排序、翻页与上限淘汰用。
     */
    @Override
    public String call(String toolInput, ToolContext toolContext) {
        String runId = runIdOf(toolContext);
        ToolCallLog.record(toolName);
        try (SpanTracer.SpanScope span = openSpan()) {
            // 开始那一条先写，结束那一条在两条出口各写一次：只有结束的话，
            // "这个工具跑了多久"和"两个并发调用谁先开始"都只能靠时间戳猜。
            AuditEvent.Scope scope = scopeOf(toolContext);
            toolStarted(scope, toolInput);
            try {
                String result = delegate.call(toolInput, toolContext);
                toolFinished(scope, toolInput, null);
                span.attribute(ATTR_OUTCOME, "ok");
                count("ok");
                return result;
            }
            catch (RuntimeException failure) {
                // 抛异常也是一次"发生过的调用"：审计里它是 executed + failed，
                // 与被拒绝（根本没执行）是两件事——修法与改配置完全不同。
                toolFinished(scope, toolInput, failure.getClass().getSimpleName());
                span.attribute(ATTR_OUTCOME, "error");
                span.attribute(ATTR_ERROR_TYPE, failure.getClass().getSimpleName());
                span.failed(failure);
                count("error");
                throw failure;
            }
        }
    }

    /** 一行 {@code tool.<工具名>} 的 span。属性在开始时就把工具名写死，后面只补结果。 */
    private SpanTracer.SpanScope openSpan() {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(ATTR_TOOL, toolName);
        return tracer.open("tool." + toolName, attributes);
    }

    /** 写两条审计事件。写不写得上与这次调用无关——审计缺一行不该把工具打成失败。 */
    private void toolStarted(AuditEvent.Scope scope, String toolInput) {
        if (audit != null) {
            audit.toolStarted(scope, toolName, toolInput);
        }
    }

    private void toolFinished(AuditEvent.Scope scope, String toolInput, String failure) {
        if (audit != null) {
            audit.toolFinished(scope, toolName, toolInput, failure);
        }
    }

    /**
     * 这一条事件的身份与位置。
     *
     * <p>身份从工具上下文里取：入口把 principal 与 runId 一起放进去了，而这里拿不到 Advisor 的上下文。
     * 取不到 principal 时照样写（那一格没人）；**取不到 runId 时这一行落不下去**——
     * 没有轮次的事件不属于任何一次运行，与 OpenClaw 的必填同口径。
     *
     * <p>通道留空：工具上下文里没有它，而为了填这一格去加一个 key，会让"审计"与"投递"
     * 两条本来无关的线互相拉扯。按 runId / sessionKey 关联已经够查。
     */
    private static AuditEvent.Scope scopeOf(ToolContext toolContext) {
        if (toolContext == null) {
            return AuditEvent.Scope.empty();
        }
        Object session = toolContext.getContext().get(ChatMemory.CONVERSATION_ID);
        String sessionKey = session instanceof String value && !value.isBlank() ? value : null;
        return AuditEvent.Scope.of(runIdOf(toolContext), sessionKey, principalOf(toolContext), null);
    }

    private static Principal principalOf(ToolContext toolContext) {
        if (toolContext == null) {
            return null;
        }
        Object value = toolContext.getContext().get(ContextKeys.PRINCIPAL);
        return value instanceof Principal principal ? principal : null;
    }

    /** 与计划工具那个读法一致：runId 由入口放进工具上下文，模型点名不了别人的 run。 */
    private static String runIdOf(ToolContext toolContext) {
        if (toolContext == null) {
            return null;
        }
        Object value = toolContext.getContext().get(ContextKeys.RUN_ID);
        return value instanceof String runId && !runId.isBlank() ? runId : null;
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

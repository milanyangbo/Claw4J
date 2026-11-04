package io.github.claw4j.agent.observability;

import io.github.claw4j.agent.ToolCallLog;
import io.github.claw4j.agent.audit.ToolCallAudit;
import io.github.claw4j.agent.run.Budget;
import io.github.claw4j.agent.run.RunBudget;
import io.github.claw4j.agent.run.Step;
import io.github.claw4j.domain.ContextKeys;
import io.github.claw4j.domain.principal.Principal;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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
 * <p><b>它做五件事，因为这五个事实同源</b>：计数的前提是"这次调用真的发生了"，
 * {@link ToolCallLog} 要记的、预算要扣的（{@link RunBudget}，A04）、
 * 审计要落的（{@link ToolCallAudit}，P07）、trace 要画的（{@link RunTracer}，P14）
 * 也都是同一件事。分成几个包装类会多几层对象，还会让"只包了计量那层"的路径悄悄漏掉另外几件——
 * 这个仓库已经因为"某条路径没被包上"出过一次问题（工具清单只收 provider 不收单个 callback）。
 *
 * <p><b>没有 registry 时照样包</b>：记录"这一轮调过哪些工具"是语义缓存判断副作用的依据，
 * 与"有没有装 actuator"无关。装不装只影响计数报不报得出去。span 也一样：
 * {@link RunTracer#NONE} 是零开销的空实现，埋点代码只有一种写法。
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

    /**
     * 这次调用的审计行序号。**这是 trace 与库唯一能对上号的东西**：
     * 拿 {@code (claw4j.run.id, claw4j.step.seq)} 去 {@code RunStore#stepsOf} 里查，
     * 查到的就是这一行的审计。
     */
    public static final String ATTR_STEP_SEQ = "claw4j.step.seq";

    private final ToolCallback delegate;
    private final String toolName;
    private final MeterRegistry registry;

    /**
     * 这一轮所属 run 的预算。可以为 null——没有装配 run 存储时（{@code claw4j.run.enabled=false}）
     * 就没有任何一件"事"可记，也就没有可扣的额度。
     */
    private final RunBudget budget;

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
    private final RunTracer tracer;

    /** @param registry 为 null 表示调用方没装 actuator：照常记录调用，只是没有指标可报 */
    public MeteredToolCallback(ToolCallback delegate, MeterRegistry registry) {
        this(delegate, registry, null, null, RunTracer.NONE);
    }

    /**
     * @param budget 为 null 表示这一轮没有 run，工具调用照常执行、只是不扣额度
     */
    public MeteredToolCallback(ToolCallback delegate, MeterRegistry registry, RunBudget budget) {
        this(delegate, registry, budget, null, RunTracer.NONE);
    }

    /**
     * @param audit 为 null 表示没装配 run 存储：计量照常，只是这一次调用不会留下一行审计
     */
    public MeteredToolCallback(ToolCallback delegate, MeterRegistry registry, RunBudget budget,
                               ToolCallAudit audit) {
        this(delegate, registry, budget, audit, RunTracer.NONE);
    }

    /**
     * @param tracer 为 null 时按空实现处理：埋点不该因为少一个参数就把调用打断
     */
    public MeteredToolCallback(ToolCallback delegate, MeterRegistry registry, RunBudget budget,
                               ToolCallAudit audit, RunTracer tracer) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.registry = registry;
        this.budget = budget;
        this.audit = audit;
        this.tracer = tracer == null ? RunTracer.NONE : tracer;
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
        try (RunTracer.SpanScope span = openSpan()) {
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
     * 带上下文的那一个重载：runId 只在它这里，预算也只能在扣（A04），审计同理（P07），
     * step 序号也只在它这里拿得到（P14）。
     *
     * <p><b>顶不住时回一句话，而不是抛异常</b>：抛出去会被工具循环当成"这个工具坏了"，
     * 模型拿到的是一句错误，于是它多半会换一个工具再试一次——
     * 而那正是一次新的工具调用，预算却刚刚才说过"没有了"。
     *
     * <p><b>只扣真的执行了的那些次</b>：被拒的那一次不算用掉的量，否则
     * "还剩几次"会凭空少一次，而它正是下一次给不给的依据。审计也按同一个口径：
     * 因预算没跑的那一次不落审计行——它不是"被谁拒绝的调用"，是"根本没发生"。
     * span 照开一段，属性写着 {@code budget_refused}：trace 里"这里什么都没发生"
     * 与"这里本该有三个工具"是两件事，缺了这一段，看图上就是模型自己没调。
     */
    @Override
    public String call(String toolInput, ToolContext toolContext) {
        String runId = runIdOf(toolContext);
        if (budget != null && runId != null) {
            Optional<Budget> spent = budget.spent(runId);
            if (spent.isPresent() && budget.exhausted(spent.get())) {
                try (RunTracer.SpanScope span = openSpan()) {
                    span.attribute(ATTR_OUTCOME, "budget_refused");
                }
                count("budget_refused");
                return budget.refusalText(spent.get());
            }
        }
        ToolCallLog.record(toolName);
        try (RunTracer.SpanScope span = openSpan()) {
            try {
                String result = delegate.call(toolInput, toolContext);
                if (budget != null && runId != null) {
                    budget.chargeToolCall(runId);
                }
                stampStep(span, record(runId, toolContext, toolInput, null));
                span.attribute(ATTR_OUTCOME, "ok");
                count("ok");
                return result;
            }
            catch (RuntimeException failure) {
                // 抛异常也是一次"发生过的调用"：审计里它是 executed + failed，
                // 与被拒绝（根本没执行）是两件事——修法与改配置完全不同。
                stampStep(span, record(runId, toolContext, toolInput, failure.getClass().getSimpleName()));
                span.attribute(ATTR_OUTCOME, "error");
                span.attribute(ATTR_ERROR_TYPE, failure.getClass().getSimpleName());
                span.failed(failure);
                count("error");
                throw failure;
            }
        }
    }

    /** 一行 {@code tool.<工具名>} 的 span。属性在开始时就把工具名写死，后面只补结果。 */
    private RunTracer.SpanScope openSpan() {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(ATTR_TOOL, toolName);
        return tracer.open("tool." + toolName, attributes);
    }

    /**
     * 把审计行的序号写到 span 上。
     *
     * <p>写在 {@code close()} 之前是必须的：已经 end 的 span 上再设属性是静默的空操作，
     * 而它看起来与"设成功了"一模一样——这类失败只会在别人抱怨"trace 里查不到对应审计行"时
     * 才被发现，那时离写出这行代码已经很久了。
     */
    private static void stampStep(RunTracer.SpanScope span, Optional<Step> step) {
        step.ifPresent(written -> span.attribute(ATTR_STEP_SEQ, Integer.toString(written.seq())));
    }

    /**
     * 落一行审计（P07），并把它交回去。
     *
     * <p>身份从工具上下文里取：入口把它与 runId 一起放进去了，而这里拿不到 Advisor 的上下文。
     * 取不到身份时照样落一行——"有个工具跑了"本身就是审计要的事实，只是那一格没人。
     *
     * @param failure 工具抛出的异常类型名；null 表示正常返回。只记类型不记 message
     * @return 落下来的那一行；空表示没落（没装存储、降级、或读不到 runId）
     */
    private Optional<Step> record(String runId, ToolContext toolContext, String toolInput, String failure) {
        if (audit == null) {
            return Optional.empty();
        }
        return audit.executed(runId, principalOf(toolContext), toolName, toolInput, failure);
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

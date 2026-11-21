package io.github.claw4j.agent.subagent;

import io.github.claw4j.domain.ContextKeys;
import io.github.claw4j.domain.principal.Principal;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * 委派这个动作对父 Agent 呈现为工具 {@code Task}：<b>形状与分派由库提供，这里只补它拿不到的那一样——身份。</b>
 *
 * <p>工具名、输入 schema（{@code TaskCall}）、描述模板、按 {@code subagent_type} 找定义、
 * 后台任务与 {@code TaskOutput} 都在 {@code spring-ai-agent-utils} 的 {@code TaskTool} 里
 * （用户 2026-11-19 裁决：用官方标准库）。这个类不是第二份实现，它只做一件事：
 *
 * <p><b>为什么需要这一层。</b>库的 {@code TaskFunction} 是 {@code Function<TaskCall, String>}，
 * 走 {@code FunctionToolCallback}——拿不到 {@code ToolContext}。而我们的身份（{@code Principal}）
 * 只在工具上下文里。没有它，子链就说不清"这次委派是谁发起的"：按发送者收窄的那一层失效、
 * 审批找不到 owner。库把执行器留成扩展点（{@code SubagentType(resolver, executor)}），
 * 但那个扩展点的签名同样没有上下文，所以只能在"进库之前"把身份放进去、在"库里调执行器时"取出来。
 *
 * <p>与 ADR-0012 否决过的"把身份放进 ThreadLocal 传遍整条链"不是同一件事：这里的作用域是
 * <b>一次工具调用</b>（{@code call} 进、{@code call} 出），不跨流式分段、不跨异步。
 * 唯一会跨线程的是库的后台任务，那一段由 {@link SubagentTaskRepository} 在 {@code putTask}
 * 时把身份取出来、进后台线程前再装回去。
 *
 * <p>没带身份仍然一律拒绝（与换库之前一致）：拒绝发生在 {@link SubagentRunner#execute}，
 * 它拿不到身份时返回一句明确的「没有权限」，而不是照常派出去。
 */
public final class SubagentDelegation implements ToolCallback {

    /**
     * 工具名定为 {@code Task}。
     *
     * <p>库的 {@code TaskTool.Builder#build()} 里硬编码就是这个名；这里留一份引用，
     * 别处不写字面量——清单断言、画像登记、子链自己的工具过滤都要用到它。
     */
    public static final String TOOL_NAME = "Task";

    /**
     * 没带身份时那句话的前缀。测试靠它区分「明确的拒绝」与「查出来是空的」。
     *
     * <p>它原来住在鉴权 Advisor 上；那个类在 ADR-0030 里整块下线之后，委派是唯一还会因为
     * 「没有身份」而拒绝的地方，于是这句话跟着唯一还会用到它的地方搬到这里。
     */
    public static final String DENIAL_PREFIX = "没有权限";

    /**
     * 这一次工具调用期间的身份。
     *
     * <p>只在这个线程上、只在 {@link #call} 的进出之间有效。不用 {@code ScopedValue} 是因为
     * 它在 JDK 21 上还是 preview，为一个一次调用的作用域开 preview 编译不划算。
     */
    private static final ThreadLocal<Principal> CURRENT = new ThreadLocal<>();

    /** 只用来把库那份 JSON 化的字符串还原，不参与任何判定。 */
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private final ToolCallback delegate;

    /**
     * 包一层库的委派工具。
     *
     * @param delegate 库的 {@code TaskTool} 产出的回调，必填
     */
    public SubagentDelegation(ToolCallback delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /**
     * 这次调用是谁发起的。库里调执行器时读它；读不到就是 {@code null}，由执行器拒绝。
     *
     * @return 父这一轮的身份；不在委派调用期间时为 {@code null}
     */
    static Principal currentPrincipal() {
        return CURRENT.get();
    }

    /**
     * 带着给定的身份跑一段——只给后台任务用（另一个线程上 {@link ThreadLocal} 是空的）。
     *
     * @param principal 提交后台任务那一刻的身份
     * @param body 要在那个身份下跑的活
     * @param <T> 返回类型
     * @return {@code body} 的结果
     */
    static <T> T callAs(Principal principal, Supplier<T> body) {
        Principal previous = CURRENT.get();
        if (principal != null) {
            CURRENT.set(principal);
        }
        try {
            return body.get();
        }
        finally {
            if (previous == null) {
                CURRENT.remove();
            }
            else {
                CURRENT.set(previous);
            }
        }
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        Principal previous = CURRENT.get();
        Principal principal = principalOf(toolContext);
        if (principal != null) {
            CURRENT.set(principal);
        }
        try {
            return plainText(delegate.call(toolInput, toolContext));
        }
        finally {
            if (previous == null) {
                CURRENT.remove();
            }
            else {
                CURRENT.set(previous);
            }
        }
    }

    /**
     * 库的 {@code TaskTool} 走 {@code FunctionToolCallback}，它把返回值按 JSON 序列化——
     * 一段结论文本回来是 {@code "\"结论：128400\""}，里面的换行也变成字面的 {@code \\n}。
     * 模型看到的应该是那段文本本身，所以这里还原一层。解析不动就原样返回：宁可多两个引号，
     * 也不能把结论吞掉。
     *
     * @param result 库返回的原始值
     * @return 纯文本
     */
    private static String plainText(String result) {
        if (result == null || result.length() < 2 || !result.startsWith("\"")) {
            return result;
        }
        try {
            return MAPPER.readValue(result, String.class);
        }
        catch (Exception notJson) {
            return result;
        }
    }

    /** 身份只从工具上下文里取；取不到就是取不到，不 fallback 到匿名放行。 */
    private static Principal principalOf(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object value = toolContext.getContext().get(ContextKeys.PRINCIPAL);
        return value instanceof Principal principal ? principal : null;
    }
}

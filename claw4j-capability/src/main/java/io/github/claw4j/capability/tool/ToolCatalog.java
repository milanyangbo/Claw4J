package io.github.claw4j.capability.tool;

import io.github.claw4j.capability.untrusted.UntrustedToolCallback;
import io.github.claw4j.domain.principal.ToolPolicyContext;
import io.github.claw4j.domain.principal.ToolProfile;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 这个部署的工具清单：模型能用哪些工具，以及它是按哪一档筛出来的。
 *
 * <p>提成一个 bean，是因为清单原来只是 {@code AgentAutoConfiguration#chatClient} 里的局部变量，
 * 拼完交给 {@code ChatClient} 就没人见过。有了第二个出口（MCP server，P11）之后，两条路各自收一遍，
 * 档位、{@code MeteredToolCallback} 那层包装、权限登记表就会各漂一次，而且漂了不会报错。
 *
 * <p>两个出口吃同一份之后，判据可以写成一句可断言的话：外部客户端看到的工具是模型能用的工具的
 * 子集。子集而不是相等，是因为有些工具对零上下文的客户端没有意义。
 *
 * <p>它是活视图而不是快照（票 03）：{@link #tools()} 每次都问一次来源，而 {@code ChatClient} 通过
 * {@link ToolCallbackProvider} 接它，框架在每次请求时展开 provider，于是"这一轮模型看得见哪些工具"
 * 每轮重算一次（{@code ChatPipelineTest#aToolThatAppearsLaterIsVisibleAtTheNextTurn}）。快照的代价
 * 是可恢复的外部能力永远回不来：MCP server 恢复之后连接是新的、清单是旧的。
 *
 * <p>代价要记账：每轮都走一遍收集（档位筛选加计量包装），几十个工具是微秒级；收集过程里的日志
 * 只在结论变了时打（见 {@code AgentAutoConfiguration#toolCatalog}），否则默认档下一句话刷一行。
 *
 * <p>{@code profile} 跟着清单一起走：它回答"这份清单是哪一档筛出来的"。让消费方自己再算一遍，
 * 等于把"档位从哪来"复制到第二处，两处的分支条件将来一定会分开。
 */
public final class ToolCatalog implements ToolCallbackProvider {

    private final String agent;

    private final ToolProfile profile;

    /**
     * 按本轮上下文解析清单。
     *
     * <p>为什么是函数而不是 {@link Supplier}：清单不只是"来源变了要重算"，还随"谁在问、用哪个
     * 模型"变（ADR-0030 裁决二的各层）。让 catalog 自己认那些维度的话，它就要认识配置、认识身份，
     * 而它住在 capability，那边不该知道 agent 的配置形状。这里只把上下文透传出去。
     */
    private final Function<ToolPolicyContext, List<ToolCallback>> source;

    /**
     * 固定清单，用于工具来源在装配期就定死的场合（例子、用例、以及把自己暴露成 MCP server
     * 那条只采样一次的路径）。
     *
     * @param agent 人设名
     * @param profile 筛出这份清单用的档位与 allow / deny
     * @param tools 清单本体
     */
    public ToolCatalog(String agent, ToolProfile profile, List<ToolCallback> tools) {
        this(agent, profile, context -> tools);
    }

    /**
     * 活清单，每次 {@link #tools()} 都问一次来源。
     *
     * @param agent 人设名
     * @param profile 筛出这份清单用的档位与 allow / deny
     * @param source 每次解析时调用。它必须只读状态、不发起连接：清单解析发生在每一轮请求里，
     *               在这里握手等于把对端的延迟灌进对话
     */
    public ToolCatalog(String agent, ToolProfile profile, Supplier<List<ToolCallback>> source) {
        this(agent, profile, asFunction(source));
    }

    /**
     * 按上下文解析的清单。
     *
     * @param agent    人设名
     * @param profile  筛出这份清单用的画像与全局白/黑
     * @param resolver 每次解析时调用，入参是本轮上下文。它必须只读状态、不发起连接
     */
    public ToolCatalog(String agent, ToolProfile profile,
                       Function<ToolPolicyContext, List<ToolCallback>> resolver) {
        this.agent = Objects.requireNonNull(agent, "agent");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.source = Objects.requireNonNull(resolver, "resolver");
    }

    /**
     * 这份清单属于哪个 agent（人设名），只用于日志与 MCP 的 serverInfo。
     *
     * @return 人设名
     */
    public String agent() {
        return agent;
    }

    /**
     * 筛出这份清单用的档位与 allow / deny。
     *
     * @return 筛选用到的 profile
     */
    public ToolProfile profile() {
        return profile;
    }

    /**
     * 当下这一份：筛完之后、已包过计量与预算，每次调用都重新解析。
     *
     * @return 工具清单的只读快照
     */
    public List<ToolCallback> tools() {
        return toolsFor(ToolPolicyContext.anonymous());
    }

    /**
     * 这一轮该给模型看的清单。
     *
     * <p>上下文决定"按模型""按发送者""子 Agent 角色"那几层生不生效，所以它必须由<b>每轮的调用方</b>
     * 传进来，而不是在这里从某个静态状态里读——后者在流式路径上会读错线程。
     *
     * @param context 本轮上下文；{@link ToolPolicyContext#anonymous()} 表示只有全局层
     * @return 筛完、包过的清单
     */
    public List<ToolCallback> toolsFor(ToolPolicyContext context) {
        ToolPolicyContext ctx = context == null ? ToolPolicyContext.anonymous() : context;
        List<ToolCallback> tools = source.apply(ctx);
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }
        // 所有工具的返回值都过一遍特殊 token 净化。未包装的路径（Read / Grep / Bash / MCP）
        // 同样能带回 <|im_start|> 这类伪造"角色边界"的串，而它们按设计不包装（票 01）。
        // 已经包过的内容由净化器自己跳过：否则它会把人家的边界当伪造标记清掉。
        return tools.stream().map(UntrustedToolCallback::sanitizing).toList();
    }

    /**
     * 给 {@code ChatClient} 的 provider 机制用。
     *
     * <p>它拿不到上下文，所以按"只有全局层"解析——这是安全的默认（不会因为漏传就放宽）。
     * 要按身份或模型收窄的路径走 {@link #toolsFor(ToolPolicyContext)}，由调用方每轮显式传。
     *
     * @return 清单
     */
    @Override
    public ToolCallback[] getToolCallbacks() {
        return tools().toArray(new ToolCallback[0]);
    }

    private static Function<ToolPolicyContext, List<ToolCallback>> asFunction(
            Supplier<List<ToolCallback>> source) {
        Objects.requireNonNull(source, "source");
        return context -> source.get();
    }

    /**
     * 工具名集合。用 {@link LinkedHashSet} 保留顺序：清单的顺序就是启动日志里那一行的顺序，
     * 排查时能直接与 yml 对上。
     *
     * @return 按清单顺序排列的名字集合
     */
    public Set<String> names() {
        Set<String> names = new LinkedHashSet<>();
        for (ToolCallback tool : tools()) {
            names.add(tool.getToolDefinition().name());
        }
        return names;
    }

    /**
     * 按名字取工具。重名不在这里处理：两个来源导出同名工具是配置错误，该在清单断言上红，
     * 而不是在这里悄悄取第一个。
     *
     * @param name 工具名
     * @return 第一个同名的工具；没有则为空
     */
    public Optional<ToolCallback> byName(String name) {
        return tools().stream()
                .filter(tool -> tool.getToolDefinition().name().equals(name))
                .findFirst();
    }
}

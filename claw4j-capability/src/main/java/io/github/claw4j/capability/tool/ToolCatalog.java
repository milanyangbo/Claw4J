package io.github.claw4j.capability.tool;

import io.github.claw4j.domain.principal.ToolProfile;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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

    private final Supplier<List<ToolCallback>> source;

    /**
     * 固定清单，用于工具来源在装配期就定死的场合（例子、用例、以及把自己暴露成 MCP server
     * 那条只采样一次的路径）。
     *
     * @param agent 人设名
     * @param profile 筛出这份清单用的档位与 allow / deny
     * @param tools 清单本体
     */
    public ToolCatalog(String agent, ToolProfile profile, List<ToolCallback> tools) {
        this(agent, profile, () -> tools);
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
        this.agent = Objects.requireNonNull(agent, "agent");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.source = Objects.requireNonNull(source, "source");
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
        List<ToolCallback> tools = source.get();
        return tools == null ? List.of() : List.copyOf(tools);
    }

    /** 给 {@code ChatClient} 用：框架在每次请求时调它，于是清单按轮解析。 */
    @Override
    public ToolCallback[] getToolCallbacks() {
        return tools().toArray(new ToolCallback[0]);
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

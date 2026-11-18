package io.github.claw4j.capability.mcp;

import io.github.claw4j.domain.principal.ToolTier;
import io.github.claw4j.domain.principal.ToolTierRegistry;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * MCP 工具的画像登记：**活视图**，每次问 provider「你现在有哪些工具」。
 *
 * <p>为什么不能拍快照：MCP server 是别的进程，启动那一刻它可能还没起来。启动时 DOWN、
 * 十几秒后重连成功是很常见的形状（{@code McpServerRetryTest} 就专测它）。快照的问题不是
 * "少登记了几个"，而是**重连成功之后整轮对话直接失败**：清单组装处查不到那些新工具的画像，
 * 抛的是 {@code 工具 X 没有声明能力画像}——一个外部 server 的迟到会把 agent 打挂，
 * 而启动日志里一切正常。
 *
 * <p>两条并行来源（配置里的 server、代码里的 {@code McpServerEndpoint} bean）共用这一份实现：
 * 它们对画像的判断本来就没有区别，各写一遍只会让其中一处忘记改成活视图。
 */
final class LiveMcpTiers implements ToolTierRegistry {

    /** MCP 是外部来源：{@code coding} 与 {@code messaging} 都有，{@code minimal} 没有（ADR-0030 裁决一）。 */
    private static final Set<ToolTier> PROFILES = Set.of(ToolTier.CODING, ToolTier.MESSAGING);

    private final Predicate<String> knows;

    private final Supplier<Collection<String>> names;

    private LiveMcpTiers(Predicate<String> knows, Supplier<Collection<String>> names) {
        this.knows = knows;
        this.names = names;
    }

    /**
     * 一个 provider 的活视图。
     *
     * @param provider 工具来源，必填
     * @return 画像登记
     */
    static ToolTierRegistry of(McpToolProvider provider) {
        return new LiveMcpTiers(provider::knowsTool, provider::toolNames);
    }

    /**
     * 同样的判断，来源由调用方给。
     *
     * <p>单独留出来是为了能被单测钉住：{@code toolNames()} 事后变长时，画像也要跟着答得出来。
     *
     * @param knows 这个名字现在有没有
     * @param names 现在有哪些名字（{@code group:plugins} 靠它）
     * @return 画像登记
     */
    static ToolTierRegistry of(Predicate<String> knows, Supplier<Collection<String>> names) {
        return new LiveMcpTiers(knows, names);
    }

    @Override
    public Optional<Set<ToolTier>> profilesFor(String toolName) {
        return toolName != null && knows.test(toolName) ? Optional.of(PROFILES) : Optional.empty();
    }

    /** MCP 是"外部来源"里的一支：沙箱会话的 {@code bundle-mcp} 靠它把这一支单独点名。 */
    @Override
    public String source() {
        return "mcp";
    }

    @Override
    public Set<String> externalToolNames() {
        Collection<String> current = names.get();
        return current == null ? Set.of() : Set.copyOf(current);
    }
}

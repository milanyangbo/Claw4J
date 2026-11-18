package io.github.claw4j.agent;

import io.github.claw4j.agent.schedule.ScheduleTools;
import io.github.claw4j.capability.sandbox.SandboxProperties;
import io.github.claw4j.domain.principal.ToolPolicyContext;
import io.github.claw4j.domain.principal.ToolPolicyLayer;
import io.github.claw4j.domain.principal.ToolTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配置 + 本轮上下文 → 用哪个画像、还要跑哪几层（ADR-0030 裁决二）。
 *
 * <p>这里问的是"哪一层该生效"，不是"一层怎么算"——后者在 core 的
 * {@code ToolPolicyPipelineTest} 里。分开的理由：一条配置没生效，可能是没匹配上（这里），
 * 也可能是匹配上了但语义写错（那里），两处混在一起时排查会从错的地方开始。
 */
class ToolPolicyAssemblerTest {

    /**
     * 只配了 byProvider 的一份。
     *
     * <p>与下面那份分开是因为 toolsBySender 的 {@code *} 对"没有身份"的调用也生效——
     * 它不是"有身份但没单独配"，而是"其余所有情况"。混在一份配置里验 byProvider，
     * 会看到一条与命题无关的 sender 层。
     */
    private static ToolsProperties providerConfig() {
        return new ToolsProperties(ToolTier.FULL, List.of(), List.of(), List.of(),
                Map.of("deepseek/deepseek-chat",
                                new ToolsProperties.PolicyRule(ToolTier.MESSAGING, List.of(),
                                        List.of("Bash"), List.of()),
                        "deepseek",
                                new ToolsProperties.PolicyRule(null, List.of(), List.of("Skill"), List.of())),
                Map.of(), null);
    }

    /** 一条配置：全局 full，按发送者三条（通道键 / id 键 / 兜底）。 */
    private static ToolsProperties senderConfig() {
        return new ToolsProperties(ToolTier.FULL, List.of(), List.of(), List.of(), Map.of(),
                Map.of("id:alice", new ToolsProperties.PolicyRule(null, List.of("querySales"), List.of(), List.of()),
                        "channel:feishu:alice",
                                new ToolsProperties.PolicyRule(null, List.of(), List.of("group:runtime"), List.of()),
                        "*", new ToolsProperties.PolicyRule(null, List.of(), List.of("Skill"), List.of())),
                null);
    }

    /** 两种维度都配的一份：验"层的顺序"需要它们同时在场。 */
    private static ToolsProperties bothConfig() {
        return new ToolsProperties(ToolTier.FULL, List.of(), List.of(), List.of(),
                providerConfig().byProvider(), senderConfig().toolsBySender(), null);
    }

    @Test
    @DisplayName("没有命中的维度时只剩全局层：一张空配置不该凭空多出层")
    void anonymousContextHasNoExtraLayers() {
        ToolPolicyAssembler.Decision decision =
                ToolPolicyAssembler.decide(ToolsProperties.defaults(), ToolPolicyContext.anonymous());

        assertThat(decision.profile()).isEqualTo(ToolTier.FULL);
        assertThat(decision.layers()).isEmpty();
    }

    @Test
    @DisplayName("byProvider 两种键形都认：provider 与 provider/model，精确的更优先")
    void byProviderAcceptsBothKeyForms() {
        // 只有 provider 键：命中。
        ToolPolicyAssembler.Decision onlyProvider = ToolPolicyAssembler.decide(providerConfig(),
                ToolPolicyContext.anonymous().withProvider("deepseek"));
        assertThat(onlyProvider.layers()).extracting(ToolPolicyLayer::label)
                .containsExactly("byProvider:deepseek");
        assertThat(onlyProvider.profile()).as("短键没写 profile 时不换画像").isEqualTo(ToolTier.FULL);

        // 两种键都有：精确的那个赢，画像换成 messaging。
        ToolPolicyAssembler.Decision exact = ToolPolicyAssembler.decide(providerConfig(),
                ToolPolicyContext.anonymous().withProvider("deepseek/deepseek-chat"));
        assertThat(exact.profile()).isEqualTo(ToolTier.MESSAGING);
        assertThat(exact.layers()).extracting(ToolPolicyLayer::label)
                .containsExactly("byProvider:deepseek/deepseek-chat");
    }

    @Test
    @DisplayName("没配的 provider 不生效：否则一个只配了 deepseek 的部署会在 qwen 上也收紧")
    void unknownProviderHasNoLayer() {
        ToolPolicyAssembler.Decision decision = ToolPolicyAssembler.decide(providerConfig(),
                ToolPolicyContext.anonymous().withProvider("qwen/qwen-plus"));

        assertThat(decision.profile()).isEqualTo(ToolTier.FULL);
        assertThat(decision.layers()).isEmpty();
    }

    @Test
    @DisplayName("发送者：channel+id 先于 id，命中即用而不是把候选链合并")
    void senderChannelKeyBeatsBareId() {
        ToolPolicyAssembler.Decision decision = ToolPolicyAssembler.decide(senderConfig(),
                new ToolPolicyContext(null, null, "feishu", "alice", null, false, null, null));

        assertThat(decision.layers()).extracting(ToolPolicyLayer::label)
                .containsExactly("sender:channel:feishu:alice");
        // 命中的是通道那条（deny runtime），不是 id 那条（allow querySales）。
        assertThat(decision.layers().getFirst().allow()).isEmpty();
        assertThat(decision.layers().getFirst().deny()).containsExactly("group:runtime");
    }

    @Test
    @DisplayName("通道对不上时回落到 id，再回落到 *")
    void senderFallsBackToIdThenWildcard() {
        ToolPolicyAssembler.Decision byId = ToolPolicyAssembler.decide(senderConfig(),
                new ToolPolicyContext(null, null, "wecom", "alice", null, false, null, null));
        assertThat(byId.layers()).extracting(ToolPolicyLayer::label).containsExactly("sender:id:alice");

        ToolPolicyAssembler.Decision wildcard = ToolPolicyAssembler.decide(senderConfig(),
                new ToolPolicyContext(null, null, "wecom", "bob", null, false, null, null));
        assertThat(wildcard.layers()).extracting(ToolPolicyLayer::label).containsExactly("sender:*");
    }

    @Test
    @DisplayName("没配 toolsBySender 时清单不受发送者影响")
    void noSenderConfigMeansNoNarrowing() {
        ToolPolicyAssembler.Decision decision = ToolPolicyAssembler.decide(
                ToolsProperties.defaults(),
                new ToolPolicyContext(null, null, "feishu", "alice", null, false, null, null));

        assertThat(decision.layers()).isEmpty();
    }

    @Test
    @DisplayName("所有子 Agent 都禁定时任务：一次性的执行体不该往全局任务表里写")
    void everySubagentDeniesScheduledTasks() {
        ToolPolicyAssembler.Decision decision = ToolPolicyAssembler.decide(ToolsProperties.defaults(),
                ToolPolicyContext.anonymous().withSubagentRole("worker"));

        ToolPolicyLayer layer = decision.layers().getFirst();
        assertThat(layer.deny())
                .contains(ScheduleTools.CREATE, ScheduleTools.LIST, ScheduleTools.CANCEL);
        assertThat(layer.deny()).as("非 leaf 不该禁再委派").doesNotContain("Task");
    }

    @Test
    @DisplayName("leaf 额外禁再开一个执行体：否则派活可以无限套下去")
    void leafDeniesSpawningAgain() {
        ToolPolicyAssembler.Decision decision = ToolPolicyAssembler.decide(ToolsProperties.defaults(),
                ToolPolicyContext.anonymous().withSubagentRole("leaf"));

        assertThat(decision.layers().getFirst().deny()).contains("Task");
    }

    @Test
    @DisplayName("显式 allow 能压过角色清单：配置比角色的默认更懂这个部署")
    void explicitAllowOverridesRoleDeny() {
        ToolsProperties explicit = new ToolsProperties(ToolTier.FULL, List.of(), List.of(), List.of(),
                Map.of(), Map.of(),
                new ToolsProperties.SubagentTools(
                        List.of("Task", ScheduleTools.CREATE), List.of()));

        ToolPolicyAssembler.Decision decision = ToolPolicyAssembler.decide(explicit,
                ToolPolicyContext.anonymous().withSubagentRole("leaf"));

        ToolPolicyLayer layer = decision.layers().getFirst();
        assertThat(layer.deny()).as("点名的被摘掉").doesNotContain("Task", ScheduleTools.CREATE);
        assertThat(layer.deny()).as("没点名的仍在").contains(ScheduleTools.LIST);
        assertThat(layer.allow()).contains("Task");
    }

    @Test
    @DisplayName("继承层：父会话的策略整层带过来，排在最后继续收窄")
    void inheritedPolicyAppliesToChild() {
        ToolPolicyLayer parent = ToolPolicyLayer.denying("parent", Set.of("Bash"));

        ToolPolicyAssembler.Decision decision = ToolPolicyAssembler.decide(bothConfig(),
                new ToolPolicyContext(null, "deepseek/deepseek-chat", "feishu", "alice",
                        null, false, null, parent));

        assertThat(decision.layers()).extracting(ToolPolicyLayer::label)
                .containsExactly("byProvider:deepseek/deepseek-chat",
                        "sender:channel:feishu:alice", "parent");
    }

    @Test
    @DisplayName("层的顺序与 ADR 的表一致：模型 → 发送者 → 子 Agent → 继承")
    void layersFollowTheDocumentedOrder() {
        ToolPolicyAssembler.Decision decision = ToolPolicyAssembler.decide(bothConfig(),
                new ToolPolicyContext("sales", "deepseek/deepseek-chat", "feishu", "alice",
                        null, false, "leaf", ToolPolicyLayer.denying("parent", Set.of("Write"))));

        List<String> labels = decision.layers().stream().map(ToolPolicyLayer::label).toList();
        assertThat(labels).containsExactly(
                "byProvider:deepseek/deepseek-chat",
                "sender:channel:feishu:alice",
                "subagent:leaf",
                "parent");
    }
    /**
     * 沙箱会话的三张表。
     *
     * @param allow 白名单
     * @return 配置
     */
    private static SandboxProperties.Tools sandboxTools(List<String> allow) {
        return new SandboxProperties.Tools(allow, List.of(), List.of());
    }

    @Test
    @DisplayName("沙箱会话里没写 bundle-mcp：MCP 工具看不见，即使 allow 是空的")
    void mcpInvisibleWithoutBundleMcp() {
        ToolPolicyContext sandboxed = ToolPolicyContext.of("http", "alice").withSandboxed(true);

        ToolPolicyAssembler.Decision decision = ToolPolicyAssembler.decide(ToolsProperties.defaults(),
                sandboxed, sandboxTools(List.of()), Set.of("cronNo"));

        assertThat(decision.narrow(List.of("Bash", "cronNo")).kept())
                .as("空 allow 的语义是「这一层不收窄」，但 MCP 那一道是额外的闸门，要显式写 bundle-mcp")
                .containsExactly("Bash");
    }

    @Test
    @DisplayName("写了 bundle-mcp：MCP 工具进来，别的工具仍按 allow 收窄")
    void mcpVisibleWithBundleMcp() {
        ToolPolicyContext sandboxed = ToolPolicyContext.of("http", "alice").withSandboxed(true);

        ToolPolicyAssembler.Decision decision = ToolPolicyAssembler.decide(ToolsProperties.defaults(),
                sandboxed, sandboxTools(List.of("Bash", SandboxProperties.Tools.MCP_BUNDLE)),
                Set.of("cronNo"));

        assertThat(decision.narrow(List.of("Bash", "cronNo", "WebFetch")).kept())
                .containsExactly("Bash", "cronNo");
    }

    @Test
    @DisplayName("非沙箱会话不看沙箱那三张表：同一份配置对清单没有影响")
    void sandboxToolsIgnoredOutsideSandbox() {
        ToolPolicyAssembler.Decision decision = ToolPolicyAssembler.decide(ToolsProperties.defaults(),
                ToolPolicyContext.of("http", "alice").withSandboxed(false),
                sandboxTools(List.of("Bash")), Set.of("cronNo"));

        assertThat(decision.layers()).extracting(ToolPolicyLayer::label).doesNotContain("sandbox");
        assertThat(decision.narrow(List.of("Bash", "cronNo")).kept()).containsExactly("Bash", "cronNo");
    }

    @Test
    @DisplayName("沙箱没配工具表、也没有 MCP 工具时不出这一层：审计里不该多一条空记录")
    void emptySandboxConfigAddsNoLayer() {
        ToolPolicyAssembler.Decision decision = ToolPolicyAssembler.decide(ToolsProperties.defaults(),
                ToolPolicyContext.of("http", "alice").withSandboxed(true),
                new SandboxProperties.Tools(null, null, null), Set.of());

        assertThat(decision.layers()).isEmpty();
    }
}

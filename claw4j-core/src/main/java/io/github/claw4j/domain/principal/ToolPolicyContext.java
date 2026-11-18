package io.github.claw4j.domain.principal;

/**
 * 这一轮"谁、用哪个模型、在哪个会话里"——策略管线按它挑出该生效的那几层
 * （ADR-0030 裁决二）。
 *
 * <p>它只装"已经确定的事实"，不装配置、不做判定：配置在装配侧，判定在
 * {@link ToolPolicyLayer}。让这个对象自己会算，等于把"哪一层该生效"与"这一层怎么算"
 * 混在一起，而排错时要分清的正是这两件事。
 *
 * <p>字段大多可以为 {@code null}，含义是"这一维不参与"：没有群概念的通道（脚本、私聊）不该
 * 因为拿不到 groupId 就整份清单被封，而没有 sender 的部署也不该凭空多出一条白名单。
 *
 * <p><b>为什么没有 {@code groupId} 之外还单列 {@code topicId}</b>：它的群策略候选链是
 * {@code chatId:topic:X:sender:Y} → {@code chatId:topic:X} → {@code chatId}，逐级回落。
 * 我们目前没有渠道群，这一维留空；等有了群，候选派生放在组装侧（那里知道完整键形），
 * 这个对象只带"这一轮到底是哪个群"。
 *
 * @param agentId      这个部署的 agent 标识（现在是选中的人设名）
 * @param providerKey  当前模型的 provider 键，形如 {@code deepseek} 或 {@code deepseek/deepseek-chat}；
 *                     未知时为 {@code null}（按模型的那几层就不生效）
 * @param channel      通道名（{@code feishu} / {@code wecom} / {@code http} / ...），未知为 {@code null}
 * @param senderId     这一轮的发送者标识；没有身份时为 {@code null}
 * @param groupId      渠道群标识；不是群聊时为 {@code null}
 * @param sandboxed    这一轮是不是在沙箱会话里跑
 * @param subagentRole 子 Agent 的角色（如 {@code leaf}）；主链为 {@code null}
 * @param inherited    从父会话继承下来的那一层；没有父会话时为 {@code null}
 */
public record ToolPolicyContext(String agentId,
                                String providerKey,
                                String channel,
                                String senderId,
                                String groupId,
                                boolean sandboxed,
                                String subagentRole,
                                ToolPolicyLayer inherited) {

    /** 一条命都可以不带的上下文：只有全局层会生效。它是默认值，也是"没有身份线索"时的正确姿势。 */
    public static ToolPolicyContext anonymous() {
        return new ToolPolicyContext(null, null, null, null, null, false, null, null);
    }

    /**
     * 只有身份与通道的上下文：主链每轮最少能拿到这两样。
     *
     * @param channel  通道名
     * @param senderId 发送者
     * @return 上下文
     */
    public static ToolPolicyContext of(String channel, String senderId) {
        return new ToolPolicyContext(null, null, channel, senderId, null, false, null, null);
    }

    /**
     * 换一个 provider。模型路由每轮可能换一家，换完之后按模型的那几层跟着变。
     *
     * @param key provider 键
     * @return 新的上下文
     */
    public ToolPolicyContext withProvider(String key) {
        return new ToolPolicyContext(agentId, key, channel, senderId, groupId, sandboxed, subagentRole, inherited);
    }

    /**
     * 标记这一轮在不在沙箱会话里。
     *
     * <p>沙箱层（第 9 层）只看这一个字段：它决定 {@code tools.sandbox.tools} 那三张表生不生效。
     * 部署级的值由装配处给（{@code claw4j.tools.sandbox.mode}），逐会话的判定还没做
     * （{@code mode=non-main} 配了启动失败，见 {@code SandboxProperties#requireUsable}）。
     *
     * @param enabled 这一轮是不是跑在沙箱里
     * @return 新的上下文
     */
    public ToolPolicyContext withSandboxed(boolean enabled) {
        return new ToolPolicyContext(agentId, providerKey, channel, senderId, groupId, enabled, subagentRole, inherited);
    }

    /**
     * 换一个子 Agent 角色。
     *
     * @param role 角色名
     * @return 新的上下文
     */
    public ToolPolicyContext withSubagentRole(String role) {
        return new ToolPolicyContext(agentId, providerKey, channel, senderId, groupId, sandboxed, role, inherited);
    }

    /**
     * 带上从父会话继承的那一层。
     *
     * @param layer 父会话的策略层
     * @return 新的上下文
     */
    public ToolPolicyContext withInherited(ToolPolicyLayer layer) {
        return new ToolPolicyContext(agentId, providerKey, channel, senderId, groupId, sandboxed, subagentRole, layer);
    }

    /** 有没有任何一维能筛出额外层。全空时管线只需要全局那一层。 */
    public boolean hasDimensions() {
        return providerKey != null || channel != null || senderId != null
                || groupId != null || sandboxed || subagentRole != null || inherited != null;
    }
}

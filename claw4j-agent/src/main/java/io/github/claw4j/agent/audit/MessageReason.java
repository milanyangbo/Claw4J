package io.github.claw4j.agent.audit;

/**
 * 消息审计的原因码，闭合词表（ADR-0037 票 04）。
 *
 * <p>逐字照 OpenClaw 的四组（{@code src/audit/audit-event-types.ts:22-57}）。为什么是常量而不是枚举：
 * 这些值只在两个地方出现（生产者写、库里挡），枚举要多一层 {@code column()} 转换，而它们不与任何
 * 分支判断绑定——真正需要类型约束的是方向与结果，那两个是枚举。
 *
 * <p>只列我们真的会写的那些：OpenClaw 里 {@code plugin_bound_*} / {@code acp_*} 属于插件与 ACP
 * 那条链，这个仓库没有对应能力，抄过来只会留下一批永远写不出来的值。库里的 check 是全集的超集
 * （{@code V12}），将来接上那条链时不必改表。
 */
public final class MessageReason {

    /** 同一条消息重复投递（平台重推）：跳过，不执行第二遍。 */
    public static final String DUPLICATE = "duplicate";

    /** 这段对话正有一条在跑，这条先跳过（车道串行）。 */
    public static final String REPLY_OPERATION_ACTIVE = "reply_operation_active";

    /** 出站没有可见内容：空文本、或者全是控制标记。 */
    public static final String NO_VISIBLE_PAYLOAD = "no_visible_payload";

    private MessageReason() {
    }
}

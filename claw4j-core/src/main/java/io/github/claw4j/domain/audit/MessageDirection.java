package io.github.claw4j.agent.audit;

/**
 * 一条消息往哪个方向走（ADR-0037 票 04）。
 *
 * <p>照 OpenClaw 的 {@code AuditMessageDirection}（{@code src/audit/audit-event-types.ts:16}）。
 * 两个值就够：进来的与出去的。中间那条"转给子代理"不是第三个方向，它是入站处理的一个结果。
 */
public enum MessageDirection {

    INBOUND("inbound"),
    OUTBOUND("outbound");

    private final String column;

    MessageDirection(String column) {
        this.column = column;
    }

    /**
     * 落库的写法，与 {@code V12} 的 check 约束逐字对应。
     *
     * @return 方向名
     */
    public String column() {
        return column;
    }
}

package io.github.claw4j.domain;

/**
 * 一件事的通知策略：结果出来之后要不要主动去找人（ADR-0018）。
 *
 * <p>策略是 <b>run 的属性</b>，不是通道的属性。同一个人可以在飞书里盯着一件事、
 * 同时让另一件定时跑——"吵不吵人"取决于那件事是怎么起头的，
 * 而不是它是从哪个通道问出来的。
 *
 * <p>三档，各对应一个真实的场景：
 * <ul>
 *   <li>{@link #DONE_ONLY}：做完了说一次。交互式提问的默认。</li>
 *   <li>{@link #SILENT}：一次都不说，结果等人来查。<b>定时任务用它</b>（P05）——
 *       到点自己醒来跑一趟的事，每跑一趟就推一条消息，等于把"自动化"做成"骚扰"；
 *       而"结果查不到"是另一回事：结果仍然落在那段对话与审计里，只是没有人被通知。</li>
 *   <li>{@link #STATE_CHANGES}：每次状态变化都说一句。等审批（P02）与长时间委派要它——
 *       那种场景下"什么都没发生"和"正在等"看起来一模一样。</li>
 * </ul>
 *
 * <p><b>{@link #SILENT} 不等于"结果丢了"</b>：三档都不影响结果落库，
 * 只影响要不要主动推。这是本条与"结果投递"分开的原因——
 * 推是找人，存是记账，两者失败姿势完全不同（推失败记一条日志，
 * 存失败是审计少一行）。
 */
public enum NotifyPolicy {

    DONE_ONLY("done_only"),
    SILENT("silent"),
    STATE_CHANGES("state_changes");

    /** 不点名的调用方（与老代码）走这一档：交互式提问的默认。 */
    public static final NotifyPolicy DEFAULT = DONE_ONLY;

    private final String column;

    NotifyPolicy(String column) {
        this.column = column;
    }

    /** 落库的写法，与 {@code V9} 里那条 check 约束逐字对应。 */
    public String column() {
        return column;
    }

    /** 终态（做完、失败、额度用尽）时要不要推一次。 */
    public boolean announcesFinish() {
        return this != SILENT;
    }

    /** 中间态（挂起等审批）时要不要也推一句。 */
    public boolean announcesChange() {
        return this == STATE_CHANGES;
    }

    /**
     * 回读时把列值翻回枚举。
     *
     * @throws IllegalArgumentException 库里出现了枚举不认识的值时。
     *                                  宁可抛也不返回默认值：把"不吵人"读成"吵人"只是烦一次，
     *                                  反过来会把一件说好了不打扰的事变成每次都推。
     */
    public static NotifyPolicy fromColumn(String column) {
        for (NotifyPolicy policy : values()) {
            if (policy.column.equals(column)) {
                return policy;
            }
        }
        throw new IllegalArgumentException("未知的通知策略：" + column);
    }
}

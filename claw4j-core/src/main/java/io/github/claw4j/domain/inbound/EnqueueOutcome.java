package io.github.claw4j.domain.inbound;

/**
 * 入队的结果。重复也要说清是哪一种重复：四种状态对调用方是四件不同的事。
 *
 * <p>把它做成 {@code boolean}（"放进去没有"）的代价很具体：日志里只剩一句"重复"，
 * 而四种重复的处理完全不同。
 *
 * <ul>
 *   <li>{@link #DUPLICATE_PENDING}：前一条还在排队，等它跑就行；</li>
 *   <li>{@link #DUPLICATE_CLAIMED}：有人正在跑它，什么都不用做；</li>
 *   <li>{@link #DUPLICATE_COMPLETED}：已经答过了，这就是重推；</li>
 *   <li>{@link #DUPLICATE_FAILED}：它已经进了死信，这条要人看——平台的重推在这里是"再试一次"
 *       的唯一机会，而它不会成功，静默当成"重复"等于把一次真正的失败藏起来。</li>
 * </ul>
 *
 * <p>词表照 OpenClaw 的 {@code kind: accepted | pending | claimed | completed | failed}
 * （src/channels/message/ingress-queue.ts:349）。
 */
public enum EnqueueOutcome {

    /** 第一次见，已经落库。调用方该去认领它。 */
    ACCEPTED,

    /** 已经有一条同 id 的还在等认领。 */
    DUPLICATE_PENDING,

    /** 已经有一条同 id 的被认领了，正在跑。 */
    DUPLICATE_CLAIMED,

    /** 已经有一条同 id 的跑完了（tombstone 还在）。 */
    DUPLICATE_COMPLETED,

    /** 已经有一条同 id 的进了死信。 */
    DUPLICATE_FAILED;

    /** 是不是"这条以前来过"。 */
    public boolean duplicate() {
        return this != ACCEPTED;
    }
}

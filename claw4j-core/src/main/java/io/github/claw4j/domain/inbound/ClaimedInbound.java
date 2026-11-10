package io.github.claw4j.domain.inbound;

/**
 * 认领到的一条：事件本体 + 它已经被试过几次 + 结算用的令牌。
 *
 * @param claim    结算凭据（队列名、eventId、令牌）
 * @param event    事件本体
 * @param attempts 在这之前失败过几次。恢复孤儿不涨它（丢了一个认领人不算一次投递失败），
 *                 只有 {@link InboundStore#release} 涨——否则"进程重启三次"就能把一条正常
 *                 消息的额度耗光
 */
public record ClaimedInbound(ClaimRef claim, QueuedInbound event, int attempts) {
}

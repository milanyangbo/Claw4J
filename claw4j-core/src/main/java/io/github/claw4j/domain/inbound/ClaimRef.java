package io.github.claw4j.domain.inbound;

import java.util.UUID;

/**
 * 一条入站事件的归属：它在哪个队列里、是哪一条、拿什么令牌结算。
 *
 * <p>令牌是必要的：一条被认领的事件如果超时没结算，会被租约回收、再被别人认领；原来那个
 * 执行者这时才回来写结论的话，会把后一次执行的结果覆盖掉。带上令牌之后，后写的那一次会发现
 * 自己手里的令牌已经不属于它，于是什么都不做。
 *
 * <p>队列名进身份也是同一个道理：主键是 {@code (queueName, eventId)}，同一个平台的两个 bot
 * 会收到同一个 {@code eventId}，它们该各跑一次。
 *
 * @param queueName 队列名（一个通道一份，见 {@link QueuedInbound#queueName()})
 * @param eventId   平台侧的事件标识
 * @param token     认领时发出的令牌；只有持有者能结算
 */
public record ClaimRef(String queueName, String eventId, UUID token) {

    public ClaimRef {
        if (queueName == null || queueName.isBlank()) {
            throw new IllegalArgumentException("队列名不能为空");
        }
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId 不能为空");
        }
        if (token == null) {
            throw new IllegalArgumentException("结算必须带令牌：不带的话谁都可能盖掉别人的结论");
        }
    }
}

package io.github.claw4j.domain.inbound;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

/**
 * 入站事件的落点：先落库、再 ACK、然后认领执行。状态机照 OpenClaw 的
 * {@code channel_ingress_events}（表结构与判断写在 {@code V7__channel.sql} 的注释里）。
 *
 * <p>它存在的理由是一句话：平台已经收到 ACK、不会重投，而那条消息只在内存里——进程一退，
 * 用户的话就永久消失。留一份去重表在内存里解决的是"多实例之间不重复"，那件事在单实例形态下
 * （ADR-0009）本来就不成立；真正会丢消息的是"重启之后没人接着跑"。
 *
 * <p>四种状态：{@code pending → claimed → completed | failed}。中间那一档是"有人认领了、
 * 还在跑"，它和"正在执行"不是一件事：认领人会失联（进程被杀），于是它要能被租约回收，
 * 而 {@code completed} 不能。
 *
 * <p>完成是 tombstone，不是删除：{@link #complete} 把内容抹掉、状态置 {@code completed}、
 * 行留着。这行就是去重记录——同一条重推再来时撞主键，于是"这条处理过"。删掉行等于重推会再跑
 * 一遍，那是这件事唯一要防的东西。顺带的好处是消息内容不留长期副本。
 *
 * <p>顺序与串行分开：顺序靠 {@code (receivedAt, eventId)}，串行靠车道——
 * {@link #claimNext} 收一份"已被占用的车道"，把那些车道整片排除。不取号、不加行锁、
 * 不会出现空洞；代价是顺序等于到达顺序（对入站来说那才是对的）。
 */
public interface InboundStore {

    /**
     * 入队，已经在的不会被覆盖。
     *
     * <p>这一句必须是原子的（库里是 {@code on conflict do nothing} 再加一次回读）：两条重推
     * 几乎同时到达时，只有一条能插进去，另一条必须看到"已经有了"。
     *
     * @param event   要入队的那条
     * @param laneKey 串行车道，通常是 conversationId
     * @return 落库结果；重复时是它属于哪一种重复
     */
    EnqueueOutcome enqueue(QueuedInbound event, String laneKey);

    /**
     * 认领一条：在该队列里按 {@code (receivedAt, eventId)} 取最早的、且车道不在
     * {@code blockedLanes} 里的那一条，置 {@code claimed} 并发出令牌。
     *
     * <p>乐观更新（{@code update … where status = 'pending'}）：两个执行者同时认领时只有一个能成，
     * 另一个空手而归，而不是两条都拿到同一条。
     *
     * @param queueName    队列名，一个通道一份
     * @param blockedLanes 正在跑的车道与刚刚放回去的车道。看着它才能串行
     * @param claimOwner   认领人标识，写进认领行
     * @return 认领到的那条；没有可认领的返回空
     */
    Optional<ClaimedInbound> claimNext(String queueName, Set<String> blockedLanes, String claimOwner);

    /**
     * 完成：状态置 completed、内容清空、留 tombstone。
     *
     * @param claim        认领凭据
     * @param metadataJson 随行记下的元数据，可以为 {@code null}
     * @return 令牌对不上（已被别人接管）时为 {@code false}
     */
    boolean complete(ClaimRef claim, String metadataJson);

    /**
     * 交还：回 pending、{@code attempts + 1}、记下原因。
     *
     * @param claim     认领凭据
     * @param lastError 这一次失败的原因
     * @return 令牌对不上时为 {@code false}
     */
    boolean release(ClaimRef claim, String lastError);

    /**
     * 终止：进死信，记原因。到上限与"永久失败"都走它。
     *
     * @param claim   认领凭据
     * @param reason  机器可读的原因码
     * @param message 人可读的说明
     * @return 令牌对不上时为 {@code false}
     */
    boolean fail(ClaimRef claim, String reason, String message);

    /**
     * 回收租约到期的认领（含重启留下的孤儿）：回 pending、清认领三列，{@code attempts} 不变。
     *
     * <p>丢了一个认领人不算一次投递失败，它的重试历史该保留。涨 {@code attempts} 的话，
     * "进程重启三次"就能把一条完全正常的消息的额度耗光。
     *
     * @param queueName 队列名
     * @param lease     认领多久没结算算超时。启动时传 {@link Duration#ZERO}：那一刻只有一个进程，
     *                  所有 {@code claimed} 都是孤儿
     * @return 收回了几条
     */
    int recoverStaleClaims(String queueName, Duration lease);

    /**
     * 按 {@link Retention} 清理过期的与超量的行。
     *
     * @param retention 保留策略
     * @return 删掉了几行
     */
    int prune(Retention retention);
}

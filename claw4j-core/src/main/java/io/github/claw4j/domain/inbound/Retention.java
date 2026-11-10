package io.github.claw4j.domain.inbound;

import java.time.Duration;
import java.util.Objects;

/**
 * 队列表的保留策略：按状态分档。
 *
 * <p>按状态分档而不是一刀切，因为三种行的寿命本来就是三件事：
 *
 * <ul>
 *   <li>{@code pending}：还没跑，按最后更新时间算。等太久的（比如某个通道一直没起来）
 *       不该永远占着位置；</li>
 *   <li>{@code completed}：它就是去重记录。留多久等于重推窗口有多长——删掉之后再来的重推
 *       会被当成新消息再跑一遍。这就是 tombstone 的代价，写在这里；</li>
 *   <li>{@code failed}：死信。它要留得比 completed 久（有人会去看为什么失败），
 *       但又不能无限长。</li>
 * </ul>
 *
 * <p>条数上限是给 completed 与 failed 兜底的：TTL 只保证"不会永远留"，不保证"量有界"。
 *
 * <p>{@code claimed} 的行不受任何一档影响：它正在被跑（或者被一个已经崩了的执行者占着），
 * 删掉它等于让那条消息永久消失。孤儿由 {@link InboundStore#recoverStaleClaims} 收回，
 * 不是由清理删掉。
 *
 * @param pending       {@code pending} 行按 {@code updatedAt} 的保留期
 * @param completed     {@code completed} 行按 {@code completedAt} 的保留期
 * @param failed        {@code failed} 行按 {@code failedAt} 的保留期
 * @param maxCompleted  留多少条 completed（去重窗口的条数上限）
 * @param maxFailed     留多少条 failed
 */
public record Retention(Duration pending,
                        Duration completed,
                        Duration failed,
                        int maxCompleted,
                        int maxFailed) {

    public Retention {
        Objects.requireNonNull(pending, "pending");
        Objects.requireNonNull(completed, "completed");
        Objects.requireNonNull(failed, "failed");
        if (maxCompleted <= 0 || maxFailed <= 0) {
            throw new IllegalArgumentException("条数上限必须为正：0 等于把去重记录整批删掉");
        }
    }

    /**
     * 默认保留期与条数上限。
     *
     * <p>去重窗口取 7 天：三种平台的重推都是秒级到分钟级，7 天已经远超需要；
     * 再长只是在给"这条来过"付存储费。死信留 30 天（与审计同一个量级，便于一起查）。
     * 条数上限取 5 万 / 1 万：一个日活几百人的单实例，一周的入站量到不了这个数。
     */
    public static Retention defaults() {
        return new Retention(Duration.ofDays(1), Duration.ofDays(7), Duration.ofDays(30),
                50_000, 10_000);
    }
}

package io.github.claw4j.agent;

import io.github.claw4j.agent.session.SessionKeys;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 一段对话同时只跑一个 run（ADR-0026 决定二）。
 *
 * <p><b>为什么需要它</b>：会话记忆的读取在 {@code before}、写回在 {@code after}。
 * 用户问"华东多少"、紧接着问"那华南呢"，两条消息各开一个 run、各拼一次提示词——
 * 第二句的上下文里有第一句的<b>问题</b>、没有<b>数字</b>，模型要么重查一遍、要么反问。
 * 而这个失败长得像"模型偶尔会忘事"，不会有人想到根因是并发。
 *
 * <p>OpenClaw 的队列文档把这条写成硬保证："only one active run per session"，
 * 入队按 session key。我们照抄的是这条保证，不是它的 collect/steer 语义
 * （那要先回答"合并之后算谁的问题"，不是串行化的必要条件）。
 *
 * <p><b>键是"对话地址"，不是 sessionId</b>：段会轮换（ADR-0026 决定一），
 * 拿 sessionId 当锁键，跨过切换点就是两把不同的锁——串行化在最需要它的那一刻失效。
 * 段在锁<b>里面</b>算，这也正好是"两条消息同时跨过 4:00 只轮换一次"的保证来源。
 *
 * <p><b>不回收的话，这张表会随对话数一直长</b>：一个跑半年的进程里，那是一张没人清理的
 * 表。所以用完就删——但不是"没人用就删"（那有竞态：判断完到删除之间可能有人正好拿到
 * 这把锁），而是<b>引用计数归零才删</b>，计数与删除都在 {@code compute} 的原子段里。
 * 早先想的"unlock 之后看 hasQueuedThreads"更省事，但两个线程之间有一瞬间两边都以为
 * 自己是唯一的——那正好是本类要消灭的那种并发。
 *
 * <p><b>不用公平锁</b>：公平锁每次唤醒都要查队列，而这里的争用只发生在"同一个人连发
 * 两句"这种短窗口。需要顺序的是"同一段会话内的历史"，那个由串行化本身保证，
 * 不由锁的公平性保证。
 */
public final class ConversationLane {

    /**
     * 等不到 lane 时给人的话。
     *
     * <p>与 {@code InboundDispatcher.OVERLOADED_REPLY} 逐字相同：用户看到的是同一件事
     * （"现在问不了"），而两处写两句话会让人以为有两种不同的问题。那个常量在 core 的
     * 包内可见，跨模块拿不到，所以这里照抄一份——文案分家的风险由这条注释承担。
     */
    public static final String BUSY_REPLY = "现在有点忙，请稍后再问一次。";

    private final Map<String, Lane> lanes = new ConcurrentHashMap<>();

    private final Duration wait;

    public ConversationLane(Duration wait) {
        this.wait = wait == null ? Duration.ZERO : wait;
    }

    /**
     * 一条对话的地址：{@code addr:<owner>|<通道>|<chatId>}。
     *
     * <p>带 {@code owner} 而不是裸 {@code userId}：同一份对话在不同权限下是两段会话
     * （ADR-0007），串行化跟着同一个粒度才不会把"降权后的追问"和"降权前那一轮"锁在一起。
     * 分隔符用 {@code |} 而不是 {@code :}：{@code chatId} 里出现冒号是常事
     * （飞书的 {@code oc_xxx} 不带，但自定义通道可能带），出现竖线几乎不可能。
     */
    public static String addressOf(String owner, String channel, String chatId) {
        return "addr:" + owner
                + "|" + SessionKeys.placeholder(channel)
                + "|" + SessionKeys.placeholder(chatId);
    }

    /**
     * 在这条对话的锁里跑一段活。
     *
     * @throws LaneBusyException 等到上限还没轮到自己。**不静默丢**：
     *                           已经 ACK 的消息没有任何回音，用户只会以为机器人坏了
     */
    public <T> T run(String address, Supplier<T> work) {
        if (address == null || address.isBlank()) {
            // 没有地址就没法串行化（定时任务、子 run 这类没有对话的调用点）。
            // 照跑，不假装锁住了一个不存在的东西。
            return work.get();
        }
        Lane lane = acquire(address);
        try {
            if (!lock(lane)) {
                throw new LaneBusyException();
            }
            try {
                return work.get();
            } finally {
                lane.lock.unlock();
            }
        } finally {
            release(address, lane);
        }
    }

    /** 没在用的地址有多少个。判据"跑完 100 个地址回到 0"看的就是它。 */
    public int size() {
        return lanes.size();
    }

    private boolean lock(Lane lane) {
        try {
            // 等待上限为 0 时 tryLock 立即返回：那等于"忙就说话"，不是默认行为，
            // 但配置成 0 是用户明确要的（他宁愿收到"有点忙"也不要排队）。
            return lane.lock.tryLock(Math.max(0L, wait.toMillis()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new LaneBusyException();
        }
    }

    /**
     * 取一个 lane 并把引用数加一，两步在 {@code compute} 的原子段里。
     *
     * <p>计数必须在<b>加锁之前</b>发生：加锁之后再计数的话，另一个线程可能在这个空档里
     * 看到引用数为 0 而把 lane 删掉，而这个线程正拿着它。
     */
    private Lane acquire(String address) {
        return lanes.compute(address, (key, existing) -> {
            Lane lane = existing == null ? new Lane() : existing;
            lane.users++;
            return lane;
        });
    }

    /** 引用数归零就删掉。删与判断在同一段原子逻辑里，所以不存在"删了别人正拿着的锁"。 */
    private void release(String address, Lane lane) {
        lanes.computeIfPresent(address, (key, existing) -> {
            if (existing != lane) {
                // 不该发生：同一个地址在持有期间被换成了另一把锁，说明回收逻辑出了问题。
                // 保留现状而不是覆盖，至少不会把别人手里的锁弄丢。
                return existing;
            }
            existing.users--;
            return existing.users <= 0 ? null : existing;
        });
    }

    /** 一把锁加它的引用数。{@code users} 只在 {@link #lanes} 的原子段里读写。 */
    private static final class Lane {

        private final ReentrantLock lock = new ReentrantLock();

        private int users;
    }
}

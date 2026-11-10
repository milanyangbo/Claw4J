package io.github.claw4j.agent;

import io.github.claw4j.agent.session.SessionKeys;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 让一段对话同时只跑一个 run（ADR-0026 决定二）。
 *
 * <p>会话记忆在 {@code before} 读、在 {@code after} 写。用户问「华东多少」再问「那华南呢」，
 * 两条消息各开一个 run、各拼一次提示词，第二句的上下文里有第一句的问题、没有数字，
 * 模型要么重查、要么反问。这种失败看起来像模型偶尔忘事，不会有人追到并发上。
 *
 * <p>OpenClaw 把同类约束写成 session lock：only one run should mutate a given session at a time
 * （{@code docs/concepts/parallel-specialist-lanes.md:21}），串行化的最小单位是
 * <b>physical session</b>——"The session controller selects one turn at a time"（{@code docs/concepts/queue.md}）。
 * 这里只取串行化，不取它的 collect/steer 语义——合并之后算谁的问题要另答，不是串行化的前提。
 *
 * <p>锁键因此是<b>会话节点键</b>（{@link SessionKeys#conversationKeyOf}），不是对话地址、
 * 也不是 sessionId：
 * <ul>
 *   <li>不是 sessionId：段会轮换（ADR-0026 决定一），拿它当键会在轮换那一刻换成另一把锁，
 *       串行化正好在最需要它的时候失效。段在锁内计算，这也是「两条消息同时跨过 4:00
 *       只轮换一次」的来源。</li>
 *   <li>不是「通道 + chatId」：{@code dm-scope: main} 下同一个人的飞书与网页私聊<b>是同一段
 *       会话</b>，按地址分锁等于让它们各跑各的——两条 run 同时往同一份记忆里写。
 *       改之前这里正是这个形状，与它的 physical session 口径不一致。</li>
 * </ul>
 * 代价：{@code main} 档下跨通道会互相阻塞，一路在跑时另一路拿到 {@link #BUSY_REPLY}。
 * 这是"同一段会话串行"的必然结果，不是缺陷；不想要就配 {@code per-channel-peer}。
 *
 * <p>用完即删，删的条件是引用计数归零，计数与删除同在 {@code compute} 的原子段里。改成 unlock
 * 后看 {@code hasQueuedThreads} 更省事，但两个线程之间有一瞬间都以为自己是唯一的。不回收则表
 * 随对话数一直长。不用公平锁：争用只出现在同一个人连发两句的短窗口，需要顺序的是同一段会话内的
 * 历史，由串行化本身保证。
 */
public final class ConversationLane {

    /**
     * 等不到 lane 时回给用户的那一句。
     *
     * <p>文案只此一份：{@link ChatClientAgentPort} 在同步与流式两条路径上都原样发出它。
     * 两处若写成不同的句子，用户会以为是两种不同的问题。
     */
    public static final String BUSY_REPLY = "现在有点忙，请稍后再问一次。";

    private final Map<String, Lane> lanes = new ConcurrentHashMap<>();

    private final Duration wait;

    /**
     * 建一张空的 lane 表。
     *
     * @param wait 等锁的上限；null 按 0 处理，也就是拿不到就回「有点忙」
     */
    public ConversationLane(Duration wait) {
        this.wait = wait == null ? Duration.ZERO : wait;
    }

    /**
     * 锁键：由会话节点键加前缀得到。
     *
     * <p>前缀 {@code addr:} 只为了让日志里的锁键一眼看出是锁而不是会话。键本身来自
     * {@link SessionKeys#conversationKeyOf}，这里<b>不重新拼</b>——上一条缺陷就是"同一个键两处
     * 拼法"，两处一错位，锁保护的和写的就不是同一段会话，而且不报错。
     *
     * @param sessionKey 不含段的会话节点键
     * @return 可直接当锁键用的地址
     */
    public static String addressOf(String sessionKey) {
        Objects.requireNonNull(sessionKey, "sessionKey");
        return "addr:" + sessionKey;
    }

    /**
     * 在这条对话的锁里跑一段活。
     *
     * @param address 对话地址，由 {@link #addressOf} 生成；null 或空白表示不串行化，直接跑
     * @param work 要执行的活
     * @param <T> 返回值类型
     * @return {@code work} 的返回值
     * @throws LaneBusyException 等到上限还没拿到锁。不能静默丢：已经 ACK 的消息没有任何回音，
     *     用户只会以为机器人坏了
     */
    public <T> T run(String address, Supplier<T> work) {
        if (address == null || address.isBlank()) {
            // 没有地址就无从串行化（定时任务、子 run 这类没有对话的调用点），照跑。
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

    /**
     * 当前还活着的 lane 数。回收正确时，跑完一批地址会回到 0。
     *
     * @return 表里的 lane 数量
     */
    public int size() {
        return lanes.size();
    }

    private boolean lock(Lane lane) {
        try {
            // 等待上限为 0 时 tryLock 立即返回，等于「忙就回话」。这是用户显式配置的结果：
            // 他宁愿收到「有点忙」也不排队。
            return lane.lock.tryLock(Math.max(0L, wait.toMillis()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new LaneBusyException();
        }
    }

    /**
     * 取一个 lane 并把引用数加一，两步在 {@code compute} 的原子段里。
     *
     * <p>计数必须先于加锁：若在加锁之后计数，另一个线程可能在这个空档看到引用数为 0
     * 而把 lane 删掉，而这个线程正拿着它。
     *
     * @param address 对话地址
     * @return 该地址对应的 lane，新建或复用
     */
    private Lane acquire(String address) {
        return lanes.compute(address, (key, existing) -> {
            Lane lane = existing == null ? new Lane() : existing;
            lane.users++;
            return lane;
        });
    }

    /**
     * 引用数归零就删掉。判断与删除在同一段原子逻辑里，所以不会删掉别人正拿着的锁。
     *
     * @param address 对话地址
     * @param lane 本次持有的 lane
     */
    private void release(String address, Lane lane) {
        lanes.computeIfPresent(address, (key, existing) -> {
            if (existing != lane) {
                // 不该发生：持有期间同一地址被换成了另一把锁。保留现状而不是覆盖，
                // 至少不会把别人手里的锁弄丢。
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

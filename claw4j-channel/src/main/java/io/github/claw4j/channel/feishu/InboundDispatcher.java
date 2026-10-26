package io.github.claw4j.channel.feishu;

import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.port.AgentPort;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.port.OutboundMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * 飞书入站调度：一条事件进来之后要发生的全部事情，且都在飞书的事件线程之外。
 *
 * <p>存在的理由是飞书的一条硬约束（ADR-0009）：事件推送<b>3 秒内</b>没处理完就重推，
 * 而一次 Text-to-SQL 可能十几秒。所以正确的形状不是"把 Agent 调快"，而是
 * <b>立刻 ACK → 异步编排 → 主动回推</b>。本类的 {@link #accept} 返回即 ACK：
 * 它只做去重、认人、提交任务，绝不在这里等模型。
 *
 * <p>四件事，按顺序：
 * <ol>
 *   <li><b>去重</b>：重推送的是同一个 {@code eventId}。命中就<b>照常 ACK 但不执行</b>——
 *       不 ACK 会触发下一轮重推，ACK 了却执行会让群里出现两条一样的回答（模型与库都白跑一趟）。</li>
 *   <li><b>认人</b>：{@code open_id} → {@link Principal}。认不出就回一句"没有权限"并记 WARN。
 *       两个"省事"的做法都不行：放行等于匿名访问（ADR-0007），静默丢弃等于用户以为机器人坏了。</li>
 *   <li><b>异步执行</b>：Agent 在注入的 executor 上跑，一条消息慢不拖住另一条，也不拖住事件线程。</li>
 *   <li><b>回推</b>：算完按段切开推回去；Agent 抛异常也推一条说清失败，而不是让群里什么都没有。</li>
 * </ol>
 *
 * <p><b>这一层只认 JDK 类型</b>：事件是 {@link FeishuInboundEvent}，发送是 {@link FeishuReplySender}，
 * 两者都由 SDK 绑定层实现。所以它能进默认构建，用假发送 + 可控 executor 断言上面这四条。
 *
 * <p><b>去重是进程内带 TTL 的缓存</b>，不是 Redis。依据 ADR-0009：长连接是集群投递、不支持广播，
 * 单实例是既定形态，"多实例之间去重"在这个形态下没有意义（要它就得先有广播，而那件事本身不支持）。
 * 记在内存里的代价是重启后十分钟内的重推会被当成新事件——比引入一个连不上就没法启动的组件划算。
 */
public class InboundDispatcher {

    private static final Logger log = LoggerFactory.getLogger(InboundDispatcher.class);

    /**
     * 去重窗口。飞书的重推发生在秒级、最多分钟级，10 分钟已经远超实际需要；
     * 窗口越长越像"永久记住"，而旧的 {@code eventId} 被记住没有价值，只占内存。
     */
    static final Duration DEFAULT_DEDUP_TTL = Duration.ofMinutes(10);

    /** 认不出人时的答复。说清"找谁"比说"无权访问"有用——后者会被当成系统故障。 */
    static final String NO_PERMISSION_REPLY =
            "没有权限：你的飞书账号还没有在 Claw4J 里登记。请联系管理员开通后重新提问。";

    /** 处理失败时的答复前缀。带上原因，用户才知道是"数据查不到"还是"系统出问题"。 */
    static final String FAILURE_REPLY_PREFIX = "这条消息没能处理成功：";

    /** 线程池满时的答复。已经 ACK 了，不再回一句用户就只能干等。 */
    static final String OVERLOADED_REPLY = "现在有点忙，请稍后再问一次。";

    /** 模型返回空内容时的占位：飞书不接受空文本，而"群里什么都不出现"会被当成没收到。 */
    static final String EMPTY_ANSWER_REPLY = "（模型没有返回内容，可以换个问法再试一次）";

    /** 失败原因截断长度：异常消息可能带整段 SQL 或响应体，全贴进群里没人读。 */
    private static final int MAX_REASON_CHARS = 300;

    private final AgentPort agentPort;

    private final Executor executor;

    private final Function<String, Optional<Principal>> principalResolver;

    private final FeishuReplySender replySender;

    private final SeenEventIds seenEventIds;

    public InboundDispatcher(AgentPort agentPort,
                             Executor executor,
                             Function<String, Optional<Principal>> principalResolver,
                             FeishuReplySender replySender) {
        this(agentPort, executor, principalResolver, replySender,
                new SeenEventIds(DEFAULT_DEDUP_TTL, Clock.systemUTC()));
    }

    /** 给测试换可控时钟用：TTL 到期这件事不该靠 {@code Thread.sleep} 去证明。 */
    InboundDispatcher(AgentPort agentPort,
                      Executor executor,
                      Function<String, Optional<Principal>> principalResolver,
                      FeishuReplySender replySender,
                      SeenEventIds seenEventIds) {
        this.agentPort = Objects.requireNonNull(agentPort, "agentPort");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.principalResolver = Objects.requireNonNull(principalResolver, "principalResolver");
        this.replySender = Objects.requireNonNull(replySender, "replySender");
        this.seenEventIds = Objects.requireNonNull(seenEventIds, "seenEventIds");
    }

    /**
     * 接一条事件。<b>方法返回就是 ACK</b>：这里不等 Agent。
     *
     * <p>两条"没有任务可跑"的分支——认不出人、线程池满——在事件线程上直接回一句话
     * （一次很小的发送，不涉及模型）。它们必须当场回：前者不回用户就以为机器人坏了，
     * 后者已经 ACK 过、飞书不会重推，不回就永远没人应。其余一律交给 executor。
     */
    public void accept(FeishuInboundEvent event) {
        Objects.requireNonNull(event, "event");
        String eventId = event.eventId();
        String chatId = event.chatId();
        log.info("收到飞书事件：eventId={} chatId={} sender={}", eventId, chatId, event.senderId());

        if (isBlank(eventId)) {
            log.warn("飞书事件没有 eventId，去重对这条失效（照常处理）：chatId={}", chatId);
        } else if (!seenEventIds.remember(eventId)) {
            log.info("同一 eventId 重复投递：已 ACK，不再执行，避免群里出现第二条回答。eventId={}", eventId);
            return;
        }

        Optional<Principal> principal;
        try {
            principal = principalResolver.apply(event.senderId());
        } catch (RuntimeException error) {
            // 角色表本身坏了（配置写错）和"这个人没登记"是两件事，但都不能把用户晾着
            log.error("认人失败：sender={} chatId={}", event.senderId(), chatId, error);
            sendWithoutBlocking(chatId, NO_PERMISSION_REPLY);
            return;
        }
        if (principal == null || principal.isEmpty()) {
            log.warn("飞书用户未登记，已拒绝：sender={} chatId={}", event.senderId(), chatId);
            sendWithoutBlocking(chatId, NO_PERMISSION_REPLY);
            return;
        }

        try {
            executor.execute(() -> handle(event, principal.get()));
            log.info("已提交到执行线程池（事件线程此刻已可返回）：eventId={}", eventId);
        } catch (RejectedExecutionException rejected) {
            log.error("执行线程池已满，这条事件的回答没能安排上：eventId={} chatId={}", eventId, chatId);
            sendWithoutBlocking(chatId, OVERLOADED_REPLY);
        }
    }

    /**
     * Agent 跑一趟并把结果推回去。任何失败都在这里收口——这条任务在线程池里，
     * 抛出去只会变成一行没人看的堆栈，群里则是永远的沉默。
     */
    private void handle(FeishuInboundEvent event, Principal principal) {
        String chatId = event.chatId();
        long startedAt = System.nanoTime();
        try {
            InboundMessage message = new InboundMessage(
                    principal, chatId, event.text(), event.receivedAt(), event.eventId());
            log.info("开始处理：eventId={} user={} chatId={}", event.eventId(), principal.userId(), chatId);

            OutboundMessage answer = agentPort.reply(message);
            log.info("Agent 算完，用时 {} ms，准备回推：eventId={}",
                    elapsedMillis(startedAt), event.eventId());
            sendAnswer(chatId, answer == null ? "" : answer.text());
            log.info("已回推：eventId={} chatId={}", event.eventId(), chatId);
        } catch (RuntimeException | Error error) {
            log.error("处理失败，回推失败说明：eventId={} chatId={}", event.eventId(), chatId, error);
            sendAnswer(chatId, FAILURE_REPLY_PREFIX + reasonOf(error) + "。可以稍后重试。");
        }
    }

    /**
     * 按段发送。一段发不出去就不再往下发：后续段落单独看是"后半截"，发出去只会更乱，
     * 所以停在这里并记 ERROR——群里会缺内容，但日志里能查到缺在哪一条。
     */
    private void sendAnswer(String chatId, String text) {
        List<String> parts = MessageSplitter.split(text, MessageSplitter.DEFAULT_MAX_CHARS);
        if (parts.isEmpty()) {
            sendWithoutBlocking(chatId, EMPTY_ANSWER_REPLY);
            return;
        }
        for (int index = 0; index < parts.size(); index++) {
            try {
                replySender.send(chatId, parts.get(index));
            } catch (RuntimeException error) {
                log.error("回推失败，后续段落不再发送：chatId={} 第 {} / {} 段",
                        chatId, index + 1, parts.size(), error);
                return;
            }
        }
    }

    /** 认人失败、池满这类"答复很短但要立刻发"的场景：发送本身失败只记日志，不能反过来打断 ACK。 */
    private void sendWithoutBlocking(String chatId, String text) {
        try {
            replySender.send(chatId, text);
        } catch (RuntimeException error) {
            log.error("回推失败：chatId={}", chatId, error);
        }
    }

    private static String reasonOf(Throwable error) {
        String message = error.getMessage();
        String reason = (message == null || message.isBlank())
                ? error.getClass().getSimpleName()
                : error.getClass().getSimpleName() + "：" + message.strip();
        return reason.length() <= MAX_REASON_CHARS ? reason : reason.substring(0, MAX_REASON_CHARS) + "…";
    }

    private static long elapsedMillis(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 进程内的事件去重表：记住最近见过的 {@code eventId}，超过 TTL 的忘掉。
     *
     * <p>两处刻意的取舍：
     * <ul>
     *   <li><b>先记再跑</b>（而不是跑完再记）：两条重推几乎同时到达时，只有先记的那条能通过，
     *       否则两条都会看到"没记过"然后各跑一遍。</li>
     *   <li><b>有条硬上限</b>：TTL 到了才清理，意味着十分钟内的流量都会堆在表里。表涨到上限时
     *       直接清掉最早的一批，接受"极少见的高峰里少数重推会被处理两次"——
     *       重复回答一次比 OOM 好，而且这个量级只在演示之外的场景才出现。</li>
     * </ul>
     */
    static final class SeenEventIds {

        private static final int SWEEP_THRESHOLD = 1024;

        private static final int HARD_LIMIT = 8192;

        private final Duration ttl;

        private final Clock clock;

        private final Map<String, Instant> seen = new ConcurrentHashMap<>();

        SeenEventIds(Duration ttl, Clock clock) {
            this.ttl = Objects.requireNonNull(ttl, "ttl");
            this.clock = Objects.requireNonNull(clock, "clock");
        }

        /**
         * @return {@code true} 表示这是第一次见（该执行）；{@code false} 表示 TTL 内已经处理过
         */
        boolean remember(String eventId) {
            Instant now = clock.instant();
            Instant deadline = now.minus(ttl);
            AtomicBoolean first = new AtomicBoolean(false);
            // compute 是原子的：两条重推同时到达时只有一条能把时间戳写进去
            seen.compute(eventId, (key, seenAt) -> {
                if (seenAt == null || seenAt.isBefore(deadline)) {
                    first.set(true);
                    return now;
                }
                return seenAt;
            });
            if (first.get()) {
                sweepIfCrowded(now);
            }
            return first.get();
        }

        private void sweepIfCrowded(Instant now) {
            if (seen.size() < SWEEP_THRESHOLD) {
                return;
            }
            Instant deadline = now.minus(ttl);
            seen.values().removeIf(seenAt -> seenAt.isBefore(deadline));
            if (seen.size() <= HARD_LIMIT) {
                return;
            }
            log.warn("事件去重表超过硬上限 {}，清掉最早的一批（此后的少数重推会被处理两次）", HARD_LIMIT);
            seen.entrySet().stream()
                    .sorted(Map.Entry.comparingByValue())
                    .limit(seen.size() - HARD_LIMIT)
                    .map(Map.Entry::getKey)
                    .toList()
                    .forEach(seen::remove);
        }
    }
}

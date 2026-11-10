package io.github.claw4j.agent.schedule;

import io.github.claw4j.agent.TaskLauncher;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.port.OutboundDelivery;
import io.github.claw4j.domain.principal.Principal;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 到点了就跑（P04）：每 30 秒查一次库，把到点的任务各跑一次。
 *
 * <p>扫表而不是给每个任务起一个定时器：定时器活在进程里，进程重启就全没了，而已经定好的
 * 时间点必须比进程活得久。扫表还有个副作用是好的：同一个部署起两个实例时，它们不需要
 * 互相认识，认领那条语句（{@code for update skip locked}）自己就把重复挡掉了。
 *
 * <p>跑在扫描线程上，不另开池。一次扫描最多认领 {@code max-per-scan} 条，逐条跑完再回来看
 * 下一轮。另开线程池的后果是同一时刻有 N 个模型调用，各自烧不同人的额度；在没做配额之前，
 * 串行是唯一说得清的算法。代价是一条慢任务会推迟后面的任务，这就是 {@code max-per-scan}
 * 要小的理由。
 *
 * <p>成功与失败都推进时间点。不推进的话，失败的任务会在 30 秒后被再跑一次，一次模型故障
 * 就能在一分半里耗光三次机会。推进之后，「连续三次」指的是三个到点没跑成——对每天九点的
 * 任务来说就是连着三天。
 *
 * <p>停用必须有人知道。失败计数走到阈值时那条 update 会顺手停掉任务，而「任务悄悄不跑了」
 * 与「一切正常」在界面上长得一样。所以停用那一下往任务登记的通道推一条带最后错误的通知；
 * 推不出去（HTTP 部署没有可推的通道）时至少留一条 WARN，库里的 {@code disabled_reason}
 * 也可以查到。
 *
 * <p>到点之前重新算一次权限（P05）：跑的是任务主人的身份，权限按此刻的角色表现算
 * （{@link ScheduleAuthorization}）。算不出来就不跑并当场停用：一条被收权的任务继续按旧权限跑，
 * 是这条链上唯一一个越权能持续发生的形状。
 */
public class ScheduleScanner {

    /** 计数前缀，与 {@code claw4j.run.*} 同一条纪律：判据回读，不问措辞。 */
    public static final String METRIC = "claw4j.schedule.fired";

    private static final Logger log = LoggerFactory.getLogger(ScheduleScanner.class);

    private final ScheduleStore store;

    /** 到点跑一轮的那条腿。可以为 null——那种部署里定时任务跑不起来，会被记成失败。 */
    private final TaskLauncher launcher;

    /** "这条任务现在还能不能按它主人的权限跑"（P05）。建的时候问的是同一个对象。 */
    private final ScheduleAuthorization authorization;

    /** 各通道的推送实现，用来送"任务已停用"那条通知。没有就是只留日志。 */
    private final List<OutboundDelivery> deliveries;

    private final ScheduleProperties properties;

    /** 为 null 表示没装 actuator：照常扫，只是没有指标可报。 */
    private final MeterRegistry registry;

    /** 库读不到时只喊一次：一个后台任务每 30 秒重复一条同样的 WARN，等于把日志变成噪声。 */
    private boolean complained;

    public ScheduleScanner(ScheduleStore store, TaskLauncher launcher,
                           ScheduleAuthorization authorization,
                           List<OutboundDelivery> deliveries,
                           ScheduleProperties properties, MeterRegistry registry) {
        this.store = Objects.requireNonNull(store, "store");
        this.launcher = launcher;
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.deliveries = deliveries == null ? List.of() : List.copyOf(deliveries);
        this.properties = Objects.requireNonNull(properties, "properties");
        this.registry = registry;
    }

    /**
     * 扫一轮到点的任务并逐条跑掉。
     *
     * <p>间隔用 {@code fixedDelay} 而不是 {@code fixedRate}：固定频率在上一轮没跑完时会排队，
     * 积出一串同时开始的扫描，互相锁在同一批行上。
     *
     * <p>占位符里的默认值与 {@link ScheduleProperties} 的默认值是同一个数，两边读同一个键，
     * 所以「配了 5 分钟却还是 30 秒」不会发生。
     */
    @Scheduled(fixedDelayString = "${claw4j.schedule.scan-interval:PT30S}")
    public void scan() {
        List<ScheduleStore.Due> due;
        try {
            due = store.claim(properties.maxPerScan(), properties.lease());
            complained = false;
        } catch (RuntimeException failure) {
            if (!complained) {
                log.warn("定时任务的表读不出来（claw4j_run.agent_schedule）：扫描这一轮跳过，"
                        + "下一轮再试。迁移没跑过时就是这个样子", failure);
                complained = true;
            }
            return;
        }
        for (ScheduleStore.Due task : due) {
            fire(task);
        }
    }

    /**
     * 跑一条。五条出口：解析不了 → 直接停用；身份没了或权限没了 → 直接停用；
     * 跑不动或跑炸了 → 记一次失败；跑成了 → 清零并推进。
     *
     * <p>不同的坏法走不同的出口。cron 坏了、角色被删了、权限被收回，这三样再试三次也不会变好，
     * 攒三次只是晚三天才停，而「任务悄悄停摆三天」与「当天就告诉你」差的是三个早上。
     * 反过来，模型抽风是暂时的，一次就停用会让一个每天在用的任务被一次抖动杀掉。
     *
     * <p>权限在开跑之前算，不在跑的过程中撞（P05）：让 run 开起来再由鉴权 Advisor 一个个拒掉，
     * 结果是一条每天照跑的任务每天交回一句「没有权限」，看起来像在正常工作。
     *
     * @param task 本轮认领到的一条任务
     */
    void fire(ScheduleStore.Due task) {
        Instant now = Instant.now();
        Optional<Instant> next;
        try {
            next = ScheduleStore.nextRunAt(task.cron(), task.zone(), now);
        } catch (IllegalArgumentException broken) {
            // 解析不了：这条任务永远不会有下一次，停用它并把原文记下来。
            disable(task, broken.getMessage());
            return;
        }
        if (next.isEmpty()) {
            disable(task, "这条 cron 没有下一次了（" + task.cron() + "），不会再跑");
            return;
        }
        Optional<Principal> owner = authorization.currentOwner(task.userId(), task.role());
        if (owner.isEmpty()) {
            disable(task, "任务主人的身份认不出来了（用户 " + task.userId() + "，角色 "
                    + task.role() + "）：要么这个角色已经从授权表里删掉，要么这个部署没有角色表。"
                    + "把角色加回来，再重新建一条任务");
            return;
        }
        Optional<String> refusal = authorization.refusalFor(owner.get(), task.requiredTools());
        if (refusal.isPresent()) {
            disable(task, "这条任务要用的工具现在不能用了：" + refusal.get()
                    + "。权限改回来之后，重新建一条任务");
            return;
        }
        if (launcher == null) {
            fail(task, next.get(), "这个部署没有可用的 Agent，到点了也跑不起来");
            return;
        }
        try {
            Optional<String> answer = launcher.launch(message(task, owner.get(), now));
            if (answer.isEmpty()) {
                fail(task, next.get(), "这一轮没有跑成（没有 run 存储，或者根本没开跑）");
                return;
            }
            store.settle(task.id(), next.get());
            count("ok");
            log.debug("定时任务跑完 id={} goal={}", task.id(), task.goal());
        } catch (RuntimeException | Error failure) {
            log.warn("定时任务这一轮炸了 id={}", task.id(), failure);
            fail(task, next.get(), failure.getClass().getSimpleName()
                    + (failure.getMessage() == null ? "" : "：" + failure.getMessage()));
        }
    }

    /**
     * 组装交给 Agent 的那一条提问。
     *
     * <p>{@code runId} 是 null：每次到点都是新的一件，而不是接着上一件，定时任务的每一步
     * 都该是一份独立的记录。
     *
     * <p>两个 id 是两件事（ADR-0026 决定三）：{@code chatId} 是「结果送到哪儿」，审批卡片、
     * 续跑、通知策略全走它；{@code conversationId} 是「这是哪段对话」。只传前者的话，
     * 定时任务会跑在创建者那段对话里——问答出现在用户的聊天记录中、继承私聊的上下文作答、
     * 还吃掉用户那份预算。
     *
     * <p>会话标识带本次触发时刻：每次运行天然是一个新段，不需要走指针（{@code session_entry}
     * 的段只服务「人」这条路，那里才有「上次说到哪儿」）。形状与 OpenClaw 的
     * {@code cron:<jobId>} 同源（{@code src/cron/isolated-agent/run-prepare.ts:190}），
     * 一眼能看出这不是人的对话。
     *
     * @param task 本轮要跑的任务
     * @param owner 任务主人此刻的身份
     * @param now 本次触发时刻
     * @return 交给 {@link TaskLauncher} 的消息
     */
    private static InboundMessage message(ScheduleStore.Due task, Principal owner, Instant now) {
        return new InboundMessage(owner, task.channelRef() == null ? task.id() : task.channelRef(),
                task.goal(), now, null, task.channel(), task.notifyPolicy(),
                "cron:" + task.id() + ":" + now.toEpochMilli(), null,
                // 定时任务没有"听众"这个概念：它不是群聊，会话键也不按 peer 分段（ADR-0029）。
                true);
    }

    private void fail(ScheduleStore.Due task, Instant nextRunAt, String error) {
        count("failed");
        Optional<ScheduleStore.AfterFailure> after =
                store.fail(task.id(), nextRunAt, error, properties.failureThreshold());
        if (after.isPresent() && !after.get().enabled()) {
            count("disabled");
            notifyDisabled(task, after.get(), error);
        }
    }

    private void disable(ScheduleStore.Due task, String reason) {
        count("disabled");
        store.disable(task.id(), reason);
        notifyDisabled(task, new ScheduleStore.AfterFailure(task.failures(), false, reason), reason);
        log.error("定时任务被停用 id={}：{}", task.id(), reason);
    }

    /**
     * 告诉任务主人它不跑了，并带上最后一次错误。
     *
     * <p>发在这一刻，不等他自己发现：任务停用之后一切照常安静，「没有消息」既可能是
     * 今天没到点，也可能是三天前就死了。这条通知是它们之间唯一的区别。
     *
     * <p>推不出去（HTTP 部署、或者通道没实现推）时只留一条 WARN：停用已经落库，
     * {@code disabled_reason} 就在那一行上，查调度表就能看到。
     *
     * @param task 被停用的任务
     * @param after 记完失败之后的样子
     * @param error 最后一次失败的原因
     */
    private void notifyDisabled(ScheduleStore.Due task, ScheduleStore.AfterFailure after, String error) {
        OutboundDelivery delivery = deliveryFor(task.channel());
        // 不写"连续 N 次"：被停用的原因有两种（跑到阈值，或者根本不该再跑），
        // 通知里那句现成的原因已经说清是哪一种，再补一句次数只会让第二种看起来像第一种。
        String text = "定时任务已停用。\n"
                + "任务：" + task.goal() + "\n"
                + "为什么：" + error + "\n"
                + "库里的说明：" + (after.disabledReason() == null ? "（无）" : after.disabledReason());
        if (delivery == null) {
            log.warn("定时任务停用，但这个任务没有可推的通道（channel={}），只能靠库里那行看到",
                    task.channel());
            return;
        }
        try {
            delivery.send(task.channelRef(), text);
            count("notified");
        } catch (RuntimeException failure) {
            // 通知送不出去不能把调度器带崩：停用这件事已经落库了。
            log.error("定时任务停用的通知没送出去 id={} channel={}", task.id(), task.channel(), failure);
        }
    }

    private OutboundDelivery deliveryFor(String channel) {
        if (channel == null || channel.isBlank()) {
            return null;
        }
        for (OutboundDelivery delivery : deliveries) {
            if (channel.equals(delivery.channel())) {
                return delivery;
            }
        }
        return null;
    }

    private void count(String outcome) {
        if (registry == null) {
            return;
        }
        Counter.builder(METRIC)
                .description("定时任务到点执行的计数，按结果分档")
                .tag("outcome", outcome)
                .register(registry)
                .increment();
    }
}

package io.github.claw4j.agent.run;

import io.github.claw4j.agent.session.SessionKeys;
import io.github.claw4j.domain.NotifyPolicy;
import io.github.claw4j.domain.port.OutboundDelivery;
import io.github.claw4j.domain.port.RunQuery;
import io.github.claw4j.domain.port.RunView;
import io.github.claw4j.domain.principal.Principal;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 一轮的结果怎么回到人手里（ADR-0018，ADR-0027 决定四重写）。
 *
 * <p><b>只有一件事：把结果推到"这一轮来自的那段对话"</b>。没有登记表，没有"按 runId
 * 查这个 run 登记过哪些地址"。
 *
 * <p>以前那套（{@code agent_run_delivery} 一对多、HTTP 发起飞书批准企微收结果）
 * 建立在"一件事能跨请求活着"上。那个前提没了之后，它也站不住：一条消息只来自
 * 一段对话，而那段对话的地址就在消息上（{@code channel + chatId}）。
 * OpenClaw 的原文是 <b>"never falls back to lastChannel"</b>——结果回到它开始的地方，
 * 不回退到"最近用过的通道"。
 *
 * <p><b>推与存是两件事</b>：推失败只记一条日志（重试策略属于具体平台，见
 * {@link OutboundDelivery}）；结果本身早就落在 run 上了，收不到推送的人照样查得到。
 * 这是"投递"敢失败的前提——它从来不是结果的唯一副本。
 */
public class RunDelivery implements RunQuery {

    /** 投递指标的前缀。与 {@code claw4j.run.*} 同一条纪律：判据回读，不问措辞。 */
    public static final String METRIC = "claw4j.run.delivery";

    private static final Logger log = LoggerFactory.getLogger(RunDelivery.class);

    private final RunStore runs;

    /** 各通道的推送实现。没有实现推的通道（HTTP）不在里面，它靠轮询。 */
    private final List<OutboundDelivery> deliveries;

    /** 为 null 表示没装 actuator：照常投递，只是没有指标可报。 */
    private final MeterRegistry registry;

    public RunDelivery(RunStore runs, List<OutboundDelivery> deliveries, MeterRegistry registry) {
        this.runs = Objects.requireNonNull(runs, "runs");
        this.deliveries = deliveries == null ? List.of() : List.copyOf(deliveries);
        this.registry = registry;
    }

    /**
     * 结果出来了，按通知策略送到这段对话（ADR-0027 决定四）。
     *
     * <p><b>调用方只在"没有人在就地等"的时候调它</b>：同步 HTTP 那一轮的回答已经从请求里
     * 返回了，再推一次就是同一条内容出现两遍。审批续跑与定时任务没有人等，所以它们推。
     *
     * <p><b>策略在这儿生效</b>：{@link NotifyPolicy#SILENT} 一句都不推——它不等于结果丢了，
     * 查得到与推不推是两件事。
     *
     * @param target 送到哪个通道的哪个会话
     * @param policy 这一轮的通知策略
     * @param text   结果全文。空就不推——"什么都没有"推出去只会让人以为坏了
     */
    public void announce(DeliveryTarget target, NotifyPolicy policy, String text) {
        if (target == null || text == null || text.isBlank()) {
            return;
        }
        if (policy != null && !policy.announcesFinish()) {
            count("skipped", "policy", policy.column());
            return;
        }
        OutboundDelivery delivery = deliveryFor(target.channel());
        if (delivery == null) {
            // 没有实现推的通道（HTTP）：它的结果靠轮询取，这不是失败，是设计。
            count("polled", "channel", target.channel());
            return;
        }
        try {
            delivery.send(target.channelRef(), text);
            count("sent", "channel", target.channel());
        }
        catch (RuntimeException failure) {
            count("failed", "channel", target.channel());
            log.error("投递失败 channel={}（结果仍在库里，可查）", target.channel(), failure);
        }
    }

    /**
     * 按 id 回收一轮（{@code GET /api/runs/{id}} 那一读，票 05 随 run 一起下线）。
     *
     * <p><b>不属于这个人的与没有这个 run 是同一个答案</b>：分成 403 与 404
     * 等于替对方确认"这个 id 存在，只是不是你的"，而 run id 是唯一一个
     * 能凭空试出别人在做什么事的入口。
     */
    @Override
    public Optional<RunView> find(String runId, Principal reader) {
        if (runId == null || runId.isBlank() || reader == null) {
            return Optional.empty();
        }
        Optional<Run> found = runs.find(runId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Run run = found.get();
        if (!run.owner().equals(SessionKeys.ownerOf(reader))) {
            count("rejected");
            return Optional.empty();
        }
        List<RunView.Step> steps = runs.stepsOf(runId).stream()
                .map(step -> new RunView.Step(step.seq(), step.kind().column(), step.status().column(),
                        step.resultSummary()))
                .toList();
        count("read");
        return Optional.of(new RunView(run.id(), run.goal(), run.status().column(), run.result(),
                run.notifyPolicy(), steps));
    }

    private OutboundDelivery deliveryFor(String channel) {
        for (OutboundDelivery delivery : deliveries) {
            if (delivery.channel().equals(channel)) {
                return delivery;
            }
        }
        return null;
    }

    private void count(String suffix) {
        count(suffix, null, null);
    }

    private void count(String suffix, String tagKey, String tagValue) {
        if (registry == null) {
            return;
        }
        Counter.Builder counter = Counter.builder(METRIC + "." + suffix)
                .description("结果投递的计数：" + suffix);
        if (tagKey != null) {
            counter = counter.tag(tagKey, tagValue);
        }
        counter.register(registry).increment();
    }
}

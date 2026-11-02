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
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 一件事的结果怎么回到人手里（A05，ADR-0018）：登记收件地址、按通知策略投递、按 id 回收。
 *
 * <p><b>run 绑 principal，不绑通道</b>。今天结果只能回到"问那一句的人"手里，
 * 于是在飞书开的头，在别的地方就收不到；定时跑完时压根没人在等。
 * 这里把"送到哪儿"从请求里摘出来，落成库里一对多的地址，
 * 于是同一件事可以 HTTP 发起、飞书批准、企微收结果。
 *
 * <p><b>三件事放在一起，因为它们争的是同一个答案</b>：地址登记在库里、
 * 投递按 {@link NotifyPolicy} 决定推不推、回收是 {@code GET /api/runs/{id}} 那一读。
 * 分开的话"有没有登记过"与"有没有推出去"会各说各的，
 * 而它们本来就是同一条链上的两节。
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

    private final JdbcTemplate jdbc;

    /** 各通道的推送实现。没有实现推的通道（HTTP）不在里面，它靠轮询。 */
    private final List<OutboundDelivery> deliveries;

    /** 为 null 表示没装 actuator：照常投递，只是没有指标可报。 */
    private final MeterRegistry registry;

    public RunDelivery(RunStore runs,
                       JdbcTemplate jdbc,
                       List<OutboundDelivery> deliveries,
                       MeterRegistry registry) {
        this.runs = Objects.requireNonNull(runs, "runs");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.deliveries = deliveries == null ? List.of() : List.copyOf(deliveries);
        this.registry = registry;
    }

    /**
     * 登记一个收件地址。同一个地址登记两次只留一行（主键挡住）。
     *
     * <p><b>没有通道名就不登记</b>：老调用方与单测构造的入站消息不带通道，
     * 那时没有"送回哪儿"可言。它不影响这一轮的回答——只影响这件事以后能不能被找到。
     *
     * <p><b>登记不上不影响回答</b>：与 run 落库同一个降级口径。地址少一个，
     * 后果是某个人收不到推送；而把一次提问打成 500，后果是这个人连回答都没有。
     */
    public void register(String runId, String channel, String channelRef) {
        if (runId == null || runId.isBlank() || channel == null || channel.isBlank()
                || channelRef == null || channelRef.isBlank()) {
            return;
        }
        try {
            jdbc.update("insert into claw4j_run.agent_run_delivery (run_id, channel, channel_ref)"
                            + " values (?, ?, ?) on conflict (run_id, channel, channel_ref) do nothing",
                    runId, channel, channelRef);
            count("registered", "channel", channel);
        } catch (RuntimeException failure) {
            count("errors");
            log.warn("登记收件地址失败 run={} channel={}（这一轮照常回答）", runId, channel, failure);
        }
    }

    /** 这个 run 登记过的全部地址，按登记顺序。读不到时是空列表，不抛。 */
    public List<DeliveryTarget> targetsOf(String runId) {
        try {
            return jdbc.query("select channel, channel_ref from claw4j_run.agent_run_delivery"
                            + " where run_id = ? order by created_at",
                    this::targetOf, runId);
        } catch (RuntimeException failure) {
            count("errors");
            log.warn("读收件地址失败 run={}（这一轮不投递）", runId, failure);
            return List.of();
        }
    }

    private DeliveryTarget targetOf(ResultSet row, int index) throws SQLException {
        return new DeliveryTarget(row.getString("channel"), row.getString("channel_ref"));
    }

    /**
     * 结果出来了，按通知策略送到登记过的每一个地址（A05）。
     *
     * <p><b>刚被就地回答过的那个地址要跳过</b>：这一轮的回答已经从请求里返回给
     * "{@code answeredAt}" 了，再推一次就是同一条内容出现两遍。而反过来——
     * <b>别的地址照推</b>——正是这条要的那个场景：在飞书开的头，
     * 在别处接着做，飞书那头收到结果。
     *
     * <p><b>策略在这儿生效，不在登记时生效</b>：同一个 run 可能先被交互地问、
     * 后来被定时续跑，两轮的吵闹程度该由这一轮说了算。
     * {@link NotifyPolicy#SILENT} 一句都不推——它不等于结果丢了，
     * 查得到与推不推是两件事。
     *
     * <p><b>一个地址失败不牵连别的地址</b>：飞书的限流把那一条挡回来，
     * 企微这一条照样要送出去。
     *
     * @param status      收尾的状态。终态走 {@link NotifyPolicy#announcesFinish()}，
     *                    中间态（等审批）走 {@link NotifyPolicy#announcesChange()}
     * @param text        结果全文。空就不推——"什么都没有"推出去只会让人以为坏了
     * @param answeredAt  这一轮已经就地回答过的地址，可为 null（异步生产没有就地回答）
     */
    public void announce(String runId, RunStatus status, String text, DeliveryTarget answeredAt) {
        if (runId == null || text == null || text.isBlank()) {
            return;
        }
        Optional<Run> found = runs.find(runId);
        if (found.isEmpty()) {
            return;
        }
        NotifyPolicy policy = found.get().notifyPolicy();
        boolean terminal = status == RunStatus.DONE || status == RunStatus.FAILED
                || status == RunStatus.BUDGET_EXHAUSTED;
        if (!(terminal ? policy.announcesFinish() : policy.announcesChange())) {
            count("skipped", "policy", policy.column());
            return;
        }
        for (DeliveryTarget target : targetsOf(runId)) {
            if (target.equals(answeredAt)) {
                continue;
            }
            OutboundDelivery delivery = deliveryFor(target.channel());
            if (delivery == null) {
                // 没有实现推的通道（HTTP）：它的结果靠轮询取，这不是失败，是设计。
                count("polled", "channel", target.channel());
                continue;
            }
            try {
                delivery.send(target.channelRef(), text);
                count("sent", "channel", target.channel());
            } catch (RuntimeException failure) {
                count("failed", "channel", target.channel());
                log.error("投递失败 run={} channel={}（结果仍在库里，可查）",
                        runId, target.channel(), failure);
            }
        }
    }

    /**
     * 按 id 回收一件事（{@code GET /api/runs/{id}} 那一读）。
     *
     * <p><b>不属于这个人的与没有这个 run 是同一个答案</b>：分成 403 与 404
     * 等于替对方确认"这个 id 存在，只是不是你的"，而 run id 是唯一一个
     * 能凭空试出别人在做什么事的入口。
     *
     * <p><b>归谁由 owner 判，不由"哪个通道问的"判</b>：在飞书开的头可以在 HTTP 上查到，
     * 这正是"绑 principal 不绑通道"落到这一读上的样子。
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

package io.github.claw4j.agent.inbound;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.claw4j.domain.inbound.ClaimRef;
import io.github.claw4j.domain.inbound.ClaimedInbound;
import io.github.claw4j.domain.inbound.EnqueueOutcome;
import io.github.claw4j.domain.inbound.InboundStore;
import io.github.claw4j.domain.inbound.QueuedInbound;
import io.github.claw4j.domain.inbound.Retention;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 入站队列落在 {@code claw4j_channel.inbound_event} 上（V27，票 04）。
 *
 * <p>表结构与四条判断（复合主键、tombstone、没有序号、孤儿回收不涨 attempts）在迁移文件里写着，
 * 这里只负责把它们翻译成 SQL。三件事值得单独说：
 *
 * <p><b>入队靠 {@code on conflict do nothing} 再一次回读</b>：并发重推时只有一条能插进去，
 * 另一条看到的必须是"已经有了"以及**它是哪一种重复**——先查后插做不到这件事（两条都可能查到
 * "没有"），而 {@code insert ... returning} 在冲突时什么都不返回，于是回读是必要的第二句。
 *
 * <p><b>认领是"挑一条 + 条件更新"两步</b>：先按 {@code (received_at, event_id)} 挑最早的一条
 * （跳过正在跑的车道），再 {@code update … where status = 'pending'}。两个执行者同时挑中同一条时，
 * 只有一条更新得到行，另一条回来重挑。没有用 {@code for update skip locked}：那会把"挑"和"锁"
 * 绑成一次往返，但语义一样，而条件更新在读写分离的库上也能用。
 *
 * <p><b>结算必须带令牌</b>：{@code where claim_token = ?} 挡住"我认领的那条已经被回收并重新认领"——
 * 不带令牌的话，晚回来的那一次会把别人的结论盖掉，而日志里看起来一切正常。
 */
public class JdbcInboundStore implements InboundStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcInboundStore.class);

    /** 挑一条待认领时最多重挑几次。并发认领撞车才会用到第二次，5 次已经远超需要。 */
    private static final int CLAIM_ATTEMPTS = 5;

    private static final String INSERT = """
            insert into claw4j_channel.inbound_event
                (queue_name, event_id, account_id, status, lane_key, payload_json, received_at, updated_at)
            values (?, ?, 'default', 'pending', ?, ?, ?, now())
            on conflict (queue_name, event_id) do nothing
            """;

    private static final String EXISTING_STATUS =
            "select status from claw4j_channel.inbound_event where queue_name = ? and event_id = ?";

    private static final String CLAIM_UPDATE = """
            update claw4j_channel.inbound_event
               set status = 'claimed', claim_token = ?, claim_owner = ?, claimed_at = now(),
                   last_attempt_at = now(), updated_at = now()
             where queue_name = ? and event_id = ? and status = 'pending'
            """;

    private static final String COMPLETE = """
            update claw4j_channel.inbound_event
               set status = 'completed', payload_json = 'null', completed_metadata_json = ?,
                   completed_at = now(), updated_at = now(),
                   claim_token = null, claim_owner = null, claimed_at = null
             where queue_name = ? and event_id = ? and claim_token = ? and status = 'claimed'
            """;

    private static final String RELEASE = """
            update claw4j_channel.inbound_event
               set status = 'pending', attempts = attempts + 1, last_error = ?, updated_at = now(),
                   claim_token = null, claim_owner = null, claimed_at = null
             where queue_name = ? and event_id = ? and claim_token = ? and status = 'claimed'
            """;

    private static final String FAIL = """
            update claw4j_channel.inbound_event
               set status = 'failed', failed_reason = ?, last_error = ?, failed_at = now(),
                   updated_at = now(), claim_token = null, claim_owner = null, claimed_at = null
             where queue_name = ? and event_id = ? and claim_token = ? and status = 'claimed'
            """;

    /**
     * 回收超时的认领。<b>不动 attempts</b>：丢了一个认领人不算一次投递失败。
     * 另外把 {@code claimed_at} 为空的坏行也收掉——它已经不可能被谁正常结算了。
     */
    private static final String RECOVER = """
            update claw4j_channel.inbound_event
               set status = 'pending', claim_token = null, claim_owner = null, claimed_at = null,
                   updated_at = now()
             where queue_name = ? and status = 'claimed'
               and (claimed_at is null or claimed_at <= now() - (? * interval '1 millisecond'))
            """;

    private static final String PRUNE_PENDING = """
            delete from claw4j_channel.inbound_event
             where status = 'pending' and updated_at < now() - (? * interval '1 millisecond')
            """;

    private static final String PRUNE_COMPLETED = """
            delete from claw4j_channel.inbound_event
             where status = 'completed' and completed_at < now() - (? * interval '1 millisecond')
            """;

    private static final String PRUNE_FAILED = """
            delete from claw4j_channel.inbound_event
             where status = 'failed' and failed_at < now() - (? * interval '1 millisecond')
            """;

    /** 条数上限：留下最新的那些。行值 {@code (queue_name, event_id)} 才能对上复合主键。 */
    private static final String CAP_COMPLETED = """
            delete from claw4j_channel.inbound_event
             where status = 'completed' and (queue_name, event_id) in (
                   select queue_name, event_id from claw4j_channel.inbound_event
                    where status = 'completed' order by completed_at desc offset ?)
            """;

    private static final String CAP_FAILED = """
            delete from claw4j_channel.inbound_event
             where status = 'failed' and (queue_name, event_id) in (
                   select queue_name, event_id from claw4j_channel.inbound_event
                    where status = 'failed' order by failed_at desc offset ?)
            """;

    private final JdbcTemplate jdbc;

    /**
     * 自己一份 mapper：信封只有六个字段，用应用那份（带各种自定义序列化器）反而要把
     * "队列表里存的是什么"这件事的解释权分散出去。时间存成 epoch 毫秒，不引 jsr310 模块。
     */
    private final ObjectMapper mapper = new ObjectMapper();

    public JdbcInboundStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /**
     * 启动时说一句"入站事件现在落库了"。
     *
     * <p>不说的话，运维会把"库慢"和"模型慢"混起来——改造之前入站是纯内存的，
     * 库挂了照样能跑，而之后库挂了入站全停。这条日志是那个代价的落点。
     */
    @PostConstruct
    void announce() {
        log.info("入站事件落库：claw4j_channel.inbound_event（重启不丢、同对话串行；"
                + "代价是每次入站多一次写入，库不可用时入站会停）");
    }

    @Override
    public EnqueueOutcome enqueue(QueuedInbound event, String laneKey) {
        int inserted = jdbc.update(INSERT, event.queueName(), event.eventId(), laneKey,
                payloadOf(event), java.sql.Timestamp.from(event.receivedAt()));
        if (inserted > 0) {
            return EnqueueOutcome.ACCEPTED;
        }
        // 插不进去只说明"已经有了"，而四种已有状态对调用方是三件不同的事
        List<String> existing = jdbc.queryForList(EXISTING_STATUS, String.class,
                event.queueName(), event.eventId());
        if (existing.isEmpty()) {
            // 插不进去又查不到：中间那一行被清理删掉了。当成"第一次见"是安全的——
            // 它确实不在了，不去重比丢一条消息好。
            return EnqueueOutcome.ACCEPTED;
        }
        return switch (existing.getFirst()) {
            case "pending" -> EnqueueOutcome.DUPLICATE_PENDING;
            case "claimed" -> EnqueueOutcome.DUPLICATE_CLAIMED;
            case "completed" -> EnqueueOutcome.DUPLICATE_COMPLETED;
            case "failed" -> EnqueueOutcome.DUPLICATE_FAILED;
            default -> throw new IllegalStateException(
                    "队列表里出现了词表外的状态：" + existing.getFirst() + "（V27 的 check 应该挡住它）");
        };
    }

    @Override
    public Optional<ClaimedInbound> claimNext(String queueName, Set<String> blockedLanes, String claimOwner) {
        String candidate = candidateSql(blockedLanes);
        List<Object> arguments = new ArrayList<>();
        arguments.add(queueName);
        arguments.addAll(blockedLanes);

        for (int attempt = 0; attempt < CLAIM_ATTEMPTS; attempt++) {
            List<Map<String, Object>> rows = jdbc.queryForList(candidate, arguments.toArray());
            if (rows.isEmpty()) {
                return Optional.empty();
            }
            Map<String, Object> row = rows.getFirst();
            String eventId = (String) row.get("event_id");
            UUID token = UUID.randomUUID();
            int updated = jdbc.update(CLAIM_UPDATE, token, claimOwner, queueName, eventId);
            if (updated == 0) {
                // 有人抢先了：回去重挑。这不是错误，是乐观更新应有的样子。
                continue;
            }
            QueuedInbound event = payloadOf((String) row.get("payload_json"), queueName, eventId);
            int attempts = ((Number) row.get("attempts")).intValue();
            return Optional.of(new ClaimedInbound(new ClaimRef(queueName, eventId, token), event, attempts));
        }
        return Optional.empty();
    }

    /**
     * 挑一条候选的 SQL。车道用 {@code not in (?, ?, …)}：车道集合通常只有零到三条，
     * 拼占位符比传数组简单，也不会把方言细节（{@code = any(?)} 要 {@code createArrayOf}）带进来。
     */
    private static String candidateSql(Set<String> blockedLanes) {
        String lanes = blockedLanes.isEmpty()
                ? ""
                : " and (lane_key is null or lane_key not in ("
                        + String.join(", ", blockedLanes.stream().map(lane -> "?").toList()) + "))";
        return "select event_id, payload_json, attempts from claw4j_channel.inbound_event"
                + " where queue_name = ? and status = 'pending'" + lanes
                + " order by received_at, event_id limit 1";
    }

    @Override
    public boolean complete(ClaimRef claim, String metadataJson) {
        return jdbc.update(COMPLETE, metadataJson, claim.queueName(), claim.eventId(), claim.token()) > 0;
    }

    @Override
    public boolean release(ClaimRef claim, String lastError) {
        return jdbc.update(RELEASE, lastError, claim.queueName(), claim.eventId(), claim.token()) > 0;
    }

    @Override
    public boolean fail(ClaimRef claim, String reason, String message) {
        return jdbc.update(FAIL, reason, message, claim.queueName(), claim.eventId(), claim.token()) > 0;
    }

    @Override
    public int recoverStaleClaims(String queueName, Duration lease) {
        return jdbc.update(RECOVER, queueName, lease.toMillis());
    }

    @Override
    public int prune(Retention retention) {
        int removed = jdbc.update(PRUNE_PENDING, retention.pending().toMillis());
        removed += jdbc.update(PRUNE_COMPLETED, retention.completed().toMillis());
        removed += jdbc.update(PRUNE_FAILED, retention.failed().toMillis());
        removed += jdbc.update(CAP_COMPLETED, retention.maxCompleted());
        removed += jdbc.update(CAP_FAILED, retention.maxFailed());
        if (removed > 0) {
            log.info("入站队列清掉 {} 行（pending {} / completed {} / failed {}，"
                            + "条数上限 {} / {}）", removed, retention.pending(), retention.completed(),
                    retention.failed(), retention.maxCompleted(), retention.maxFailed());
        }
        return removed;
    }

    /**
     * 每小时清一次。
     *
     * <p>不清理的话 tombstone 会无限长，而它存在的唯一目的是覆盖重投窗口。
     * 与 {@code AuditLedger} 同一个调度方式：启动一次 + 每小时一次。
     */
    @Scheduled(fixedDelayString = "${claw4j.inbound.prune-interval:PT1H}")
    public void pruneOnSchedule() {
        try {
            prune(Retention.defaults());
        }
        catch (RuntimeException failure) {
            // 表还没建（迁移没跑）或库这一下不通：下一轮再说，不要让后台任务反复抛栈
            log.debug("入站队列的清理这一轮没跑成", failure);
        }
    }

    @PostConstruct
    void pruneOnStartup() {
        pruneOnSchedule();
    }

    private String payloadOf(QueuedInbound event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("queueName", event.queueName());
        payload.put("eventId", event.eventId());
        payload.put("conversationId", event.conversationId());
        payload.put("senderId", event.senderId());
        payload.put("text", event.text());
        payload.put("receivedAt", event.receivedAt().toEpochMilli());
        try {
            return mapper.writeValueAsString(payload);
        }
        catch (JsonProcessingException failure) {
            throw new IllegalStateException("入站事件序列化失败（" + event.eventId() + "）", failure);
        }
    }

    @SuppressWarnings("unchecked")
    private QueuedInbound payloadOf(String json, String queueName, String eventId) {
        try {
            Map<String, Object> payload = mapper.readValue(json, Map.class);
            return new QueuedInbound(
                    queueName,
                    eventId,
                    (String) payload.get("conversationId"),
                    (String) payload.get("senderId"),
                    (String) payload.get("text"),
                    Instant.ofEpochMilli(((Number) payload.get("receivedAt")).longValue()));
        }
        catch (JsonProcessingException failure) {
            throw new IllegalStateException("入站事件反序列化失败（" + queueName + " / " + eventId + "）", failure);
        }
    }
}

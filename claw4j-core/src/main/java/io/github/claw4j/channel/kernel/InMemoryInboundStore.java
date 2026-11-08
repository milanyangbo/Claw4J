package io.github.claw4j.channel.kernel;

import io.github.claw4j.domain.inbound.ClaimRef;
import io.github.claw4j.domain.inbound.ClaimedInbound;
import io.github.claw4j.domain.inbound.EnqueueOutcome;
import io.github.claw4j.domain.inbound.InboundStore;
import io.github.claw4j.domain.inbound.QueuedInbound;
import io.github.claw4j.domain.inbound.Retention;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 不落库的入站队列：**语义与 JDBC 那份一样，寿命只有进程那么长**。
 *
 * <p>它存在的场合只有一个：{@code claw4j.persistence.enabled=false} 的部署（以及单测）。
 * 那种部署里没有 DataSource，而入站路径不能因此少一条——所以换成内存实现，
 * 行为与改造之前一致（重推窗口靠进程内的表、重启之后没人接着跑）。
 *
 * <p><b>它与 JDBC 那份共用同一份 {@code accept} 逻辑</b>：端口只有一套方法，
 * 调度层里没有 {@code if (jdbc) … else …}。两套分支迟早会不一致，
 * 而不一致的表现是"带库的部署丢掉了一种消息、不带库的没有"——只在某一种部署里复现。
 *
 * <p>启动时会 WARN 一句"入站事件不落库，重启会丢"（见 {@link InboundDispatcher}）：
 * 这是**能选**的降级，不是默认值，所以它必须说出来。
 */
public final class InMemoryInboundStore implements InboundStore {

    private final Map<Key, Row> rows = new LinkedHashMap<>();

    @Override
    public synchronized EnqueueOutcome enqueue(QueuedInbound event, String laneKey) {
        Key key = new Key(event.queueName(), event.eventId());
        Row existing = rows.get(key);
        if (existing != null) {
            return switch (existing.status) {
                case PENDING -> EnqueueOutcome.DUPLICATE_PENDING;
                case CLAIMED -> EnqueueOutcome.DUPLICATE_CLAIMED;
                case COMPLETED -> EnqueueOutcome.DUPLICATE_COMPLETED;
                case FAILED -> EnqueueOutcome.DUPLICATE_FAILED;
            };
        }
        rows.put(key, Row.pending(event, laneKey));
        return EnqueueOutcome.ACCEPTED;
    }

    @Override
    public synchronized Optional<ClaimedInbound> claimNext(String queueName, Set<String> blockedLanes,
                                                           String claimOwner) {
        Instant now = Instant.now();
        return rows.values().stream()
                .filter(row -> row.event.queueName().equals(queueName))
                .filter(row -> row.status == Status.PENDING)
                .filter(row -> !blockedLanes.contains(row.laneKey))
                .sorted(Comparator.comparing((Row row) -> row.event.receivedAt())
                        .thenComparing(row -> row.event.eventId()))
                .findFirst()
                .map(row -> {
                    row.status = Status.CLAIMED;
                    row.token = UUID.randomUUID();
                    row.claimOwner = claimOwner;
                    row.claimedAt = now;
                    row.lastAttemptAt = now;
                    row.updatedAt = now;
                    return new ClaimedInbound(
                            new ClaimRef(row.event.queueName(), row.event.eventId(), row.token),
                            row.event, row.attempts);
                });
    }

    @Override
    public synchronized boolean complete(ClaimRef claim, String metadataJson) {
        Row row = rowFor(claim);
        if (row == null) {
            return false;
        }
        row.status = Status.COMPLETED;
        // tombstone：内容抹掉、行留着。这行就是去重记录（见 InboundStore 的类注释）。
        row.event = new QueuedInbound(row.event.queueName(), row.event.eventId(),
                row.event.conversationId(), null, null, row.event.receivedAt());
        row.completedAt = Instant.now();
        row.completedMetadata = metadataJson;
        row.updatedAt = row.completedAt;
        row.clearClaim();
        return true;
    }

    @Override
    public synchronized boolean release(ClaimRef claim, String lastError) {
        Row row = rowFor(claim);
        if (row == null) {
            return false;
        }
        row.status = Status.PENDING;
        row.attempts = row.attempts + 1;
        row.lastError = lastError;
        row.updatedAt = Instant.now();
        row.clearClaim();
        return true;
    }

    @Override
    public synchronized boolean fail(ClaimRef claim, String reason, String message) {
        Row row = rowFor(claim);
        if (row == null) {
            return false;
        }
        row.status = Status.FAILED;
        row.failedReason = reason;
        row.lastError = message;
        row.failedAt = Instant.now();
        row.updatedAt = row.failedAt;
        row.clearClaim();
        return true;
    }

    @Override
    public synchronized int recoverStaleClaims(String queueName, Duration lease) {
        Instant cutoff = Instant.now().minus(lease);
        int recovered = 0;
        for (Row row : rows.values()) {
            if (!row.event.queueName().equals(queueName) || row.status != Status.CLAIMED) {
                continue;
            }
            // 认领时间丢了（坏行）也一起收：它已经不可能被谁正常结算了
            if (row.claimedAt != null && row.claimedAt.isAfter(cutoff)) {
                continue;
            }
            row.status = Status.PENDING;
            row.updatedAt = Instant.now();
            row.clearClaim();
            // attempts 不动：丢了一个认领人不算一次投递失败
            recovered++;
        }
        return recovered;
    }

    @Override
    public synchronized int prune(Retention retention) {
        Instant now = Instant.now();
        int before = rows.size();
        rows.values().removeIf(row -> expired(row, retention, now));
        cap(retention.maxCompleted(), Status.COMPLETED,
                Comparator.comparing((Row row) -> row.completedAt));
        cap(retention.maxFailed(), Status.FAILED,
                Comparator.comparing((Row row) -> row.failedAt));
        return before - rows.size();
    }

    private boolean expired(Row row, Retention retention, Instant now) {
        return switch (row.status) {
            // claimed 不受任何一档影响：删掉它等于让那条消息永久消失（孤儿由恢复收回）
            case CLAIMED -> false;
            case PENDING -> row.updatedAt.isBefore(now.minus(retention.pending()));
            case COMPLETED -> row.completedAt != null
                    && row.completedAt.isBefore(now.minus(retention.completed()));
            case FAILED -> row.failedAt != null && row.failedAt.isBefore(now.minus(retention.failed()));
        };
    }

    /** 条数上限：按时间留下最新的那些。TTL 只保证"不会永远留"，不保证"量有界"。 */
    private void cap(int limit, Status status, Comparator<Row> oldestFirst) {
        List<Row> matching = new ArrayList<>();
        for (Row row : rows.values()) {
            if (row.status == status) {
                matching.add(row);
            }
        }
        if (matching.size() <= limit) {
            return;
        }
        matching.sort(oldestFirst);
        matching.subList(0, matching.size() - limit).forEach(row -> rows.remove(
                new Key(row.event.queueName(), row.event.eventId())));
    }

    private Row rowFor(ClaimRef claim) {
        Row row = rows.get(new Key(claim.queueName(), claim.eventId()));
        // 令牌对不上说明这一行已经被回收并重新认领过了：后写的那一次不能盖掉别人的结论
        if (row == null || row.status != Status.CLAIMED || !claim.token().equals(row.token)) {
            return null;
        }
        return row;
    }

    private record Key(String queueName, String eventId) {
    }

    private enum Status {
        PENDING, CLAIMED, COMPLETED, FAILED
    }

    private static final class Row {

        private QueuedInbound event;

        private final String laneKey;

        private Status status = Status.PENDING;

        private UUID token;

        private String claimOwner;

        private Instant claimedAt;

        private int attempts;

        private Instant lastAttemptAt;

        private String lastError;

        private String failedReason;

        private Instant failedAt;

        private Instant completedAt;

        private String completedMetadata;

        private Instant updatedAt;

        private Row(QueuedInbound event, String laneKey) {
            this.event = event;
            this.laneKey = laneKey;
            this.updatedAt = Instant.now();
        }

        private static Row pending(QueuedInbound event, String laneKey) {
            return new Row(event, laneKey);
        }

        private void clearClaim() {
            token = null;
            claimOwner = null;
            claimedAt = null;
        }
    }
}

package io.github.claw4j.agent.schedule;

import io.github.claw4j.domain.NotifyPolicy;
import io.github.claw4j.domain.principal.Principal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.support.CronExpression;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 定时任务的持久化：建、认领、推进、记失败、停用（P04）。
 *
 * <p>只有一个具体类，不预先开接口：表与它的读写方式是同一件事，另一种实现（内存版、别的库）
 * 现在没有需求。预先开接口只会让每个方法多一处要同步改的签名。
 *
 * <p>认领是一条语句：{@code update ... where id in (select ... for update skip locked) returning ...}。
 * 拆成先 select 再 update，两个实例会在两步之间读到同一批行，各自认为这条归自己了，
 * 于是同一条任务跑两遍。{@code skip locked} 让后来的那个跳过而不是等待：等待会把整轮扫描
 * 卡在别人的一次模型调用上。
 *
 * <p>行锁管同一瞬间，租约管认领之后进程没了。只有行锁的话，进程崩溃后那条任务会一直躺在
 * 「已认领」里，直到 {@code claimed_until} 过期才可能被重新认领。
 *
 * <p>推进到哪个时间点由调用方算（{@link #settle}）：下一次是几点取决于这一轮几点跑完，
 * 那只有调度器知道。这里只负责写值。
 */
public class ScheduleStore {

    private static final Logger log = LoggerFactory.getLogger(ScheduleStore.class);

    /**
     * 认领一批到点的任务。
     *
     * <p>{@code claimed_until} 用库的时钟算（{@code now() + ...}），不用 JVM 的：
     * 与 {@code expires_at <= now()} 同一个理由。两个时钟有偏差时，「我以为还没过期」
     * 和库里的判断会各说各的，而那种不一致只能靠翻日志解释。
     */
    private static final String CLAIM_SQL = """
            update claw4j_run.agent_schedule s
            set claimed_until = now() + make_interval(secs => cast(? as double precision)),
                updated_at = now()
            where s.id in (
                select id from claw4j_run.agent_schedule
                where enabled
                  and next_run_at <= now()
                  and (claimed_until is null or claimed_until < now())
                order by next_run_at
                limit ?
                for update skip locked
            )
            returning s.id, s.owner, s.channel, s.channel_ref, s.goal, s.cron, s.zone,
                      s.notify_policy, s.owner_user_id, s.owner_role, s.required_tools,
                      s.next_run_at, s.failures
            """;

    private static final String INSERT_SQL = """
            insert into claw4j_run.agent_schedule
                (id, owner, channel, channel_ref, goal, cron, zone, notify_policy,
                 owner_user_id, owner_role, required_tools, next_run_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String SELECT_SQL = """
            select id, owner, channel, channel_ref, goal, cron, zone, notify_policy,
                   next_run_at, last_fired_at, failures, last_error, enabled, disabled_reason
            from claw4j_run.agent_schedule
            """;

    /** 跑成了一轮：清零失败计数、推进时间点、把租约还回去。 */
    private static final String SETTLE_SQL = """
            update claw4j_run.agent_schedule
            set next_run_at = ?, last_fired_at = now(), failures = 0, last_error = null,
                claimed_until = null, updated_at = now()
            where id = ?
            """;

    /**
     * 这一轮没跑成：计数加一、记下原因、照样推进时间点。
     *
     * <p>时间点照样推进：不推进的话下一次扫描（30 秒后）会立刻重试，一次模型故障就能在
     * 一分半里用光三次机会，于是「连着坏三天」和「坏了 90 秒」变成同一件事。
     *
     * <p>停用与计数写同一条语句：分成两句，第二个实例可能在中间又跑一次，于是失败计数
     * 跨过阈值两次、发两条通知。
     */
    private static final String FAIL_SQL = """
            update claw4j_run.agent_schedule
            set failures = failures + 1,
                last_error = ?,
                next_run_at = ?,
                last_fired_at = now(),
                claimed_until = null,
                enabled = case when failures + 1 >= ? then false else enabled end,
                disabled_reason = case when failures + 1 >= ? then ? else disabled_reason end,
                updated_at = now()
            where id = ?
            returning failures, enabled, disabled_reason
            """;

    private static final String DISABLE_SQL = """
            update claw4j_run.agent_schedule
            set enabled = false, disabled_reason = ?, claimed_until = null, updated_at = now()
            where id = ?
            """;

    /**
     * 一条到点的任务：认领时读出来的全部内容，够跑一轮，也够解释为什么没跑成。
     *
     * <p>带回来的是身份，不是权限（P05）：{@code userId} 与 {@code role} 交给
     * {@link ScheduleAuthorization} 换一份此刻的权限。存权限快照的话，收权对定时任务无效，
     * 而那条缝只有在有人拿这份快照直接去跑时才会露出来。
     *
     * @param requiredTools 建的时候声明的工具名，到点之前按当前权限再核一遍；空表示不碰工具
     */
    public record Due(String id, String owner, String channel, String channelRef, String goal,
                      String cron, String zone, NotifyPolicy notifyPolicy,
                      String userId, String role, List<String> requiredTools,
                      Instant nextRunAt, int failures) {
    }

    /**
     * 新建一条定时任务要的东西。
     *
     * @param owner 归属键，与 run 的口径一致（{@code SessionKeys.ownerOf}）
     * @param goal 每次到点要问的那句话
     * @param cron 6 段 cron，创建时就解析一遍：写错了要当场说，而不是等到明天九点
     * @param zone 时区；null 表示用部署所在时区
     * @param notifyPolicy 通知策略；null 按 {@link NotifyPolicy#SILENT} 落库
     * @param channel 结果推回的通道；null 表示不推
     * @param channelRef 通道内的收件地址
     * @param principal 任务主人。只取 {@code userId} 与 {@code role} 落库，
     *     tables 与 actions 一律不存，它们要到点那一刻按当时的角色表重算
     * @param requiredTools 这条任务会用到的工具名，建的时候已经核过一遍
     */
    public record Request(String owner, String goal, String cron, String zone,
                          NotifyPolicy notifyPolicy, String channel, String channelRef,
                          Principal principal, List<String> requiredTools) {
    }

    /** 记完一次失败之后的样子：还启用吗、第几次了、停用原因是什么。 */
    public record AfterFailure(int failures, boolean enabled, String disabledReason) {
    }

    private final JdbcTemplate jdbc;

    public ScheduleStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /**
     * 建一条定时任务，第一次触发时间按 cron 从此刻往后算。
     *
     * @param request 任务的主人与内容
     * @return 落库后回读的那条；写不进库时为空
     * @throws IllegalArgumentException cron 或时区解析不了。调用方（P05 的工具）把这句话
     *     原样告诉模型，而不是自己编一句「创建失败」
     */
    public Optional<Schedule> create(Request request) {
        Objects.requireNonNull(request, "request");
        Principal owner = Objects.requireNonNull(request.principal(), "principal");
        Instant next = firstRunAt(request.cron(), request.zone());
        String id = UUID.randomUUID().toString();
        try {
            jdbc.update(INSERT_SQL, id, request.owner(), request.channel(), request.channelRef(),
                    request.goal(), request.cron(), request.zone(),
                    (request.notifyPolicy() == null ? NotifyPolicy.SILENT : request.notifyPolicy()).column(),
                    owner.userId(), owner.role(),
                    // 显式转成 Object：String[] 直接当变参会被当成一串参数，而不是一个 text[]
                    (Object) toolsOf(request.requiredTools()).toArray(new String[0]),
                    java.sql.Timestamp.from(next));
        } catch (RuntimeException failure) {
            log.warn("定时任务没记下来 owner={} cron={}", request.owner(), request.cron(), failure);
            return Optional.empty();
        }
        return find(id);
    }

    private static List<String> toolsOf(List<String> requiredTools) {
        return requiredTools == null ? List.of() : requiredTools;
    }

    /**
     * 算第一次触发的时间点：严格晚于此刻的那一个。
     *
     * <p>正好站在那个点上创建的任务，当天这次不算：{@code next} 取的是严格之后，
     * 所以 9:00:00 建的任务今天不会再跑一次。取「最近一次」会把「现在建、立刻跑」和
     * 「明天九点跑」变成同一件事，而用户说的显然是后者。
     *
     * @param cron 6 段 cron 原文
     * @param zone 时区；null 表示部署所在时区
     * @return 第一次触发的时刻
     * @throws IllegalArgumentException cron 没有下一次，或 cron 与时区解析不了
     */
    public static Instant firstRunAt(String cron, String zone) {
        CronExpression expression = parse(cron);
        ZonedDateTime now = ZonedDateTime.now(zoneOf(zone));
        ZonedDateTime next = expression.next(now);
        if (next == null) {
            throw new IllegalArgumentException("这条 cron 没有下一次：" + cron);
        }
        return next.toInstant();
    }

    /**
     * 算严格晚于 {@code after} 的下一个时间点。
     *
     * @param cron 6 段 cron 原文
     * @param zone 时区；null 表示部署所在时区
     * @param after 起点
     * @return 下一个时间点；空表示没有下一次，例如一次性日期已经过去
     */
    public static Optional<Instant> nextRunAt(String cron, String zone, Instant after) {
        ZonedDateTime next = parse(cron).next(ZonedDateTime.ofInstant(after, zoneOf(zone)));
        return next == null ? Optional.empty() : Optional.of(next.toInstant());
    }

    /**
     * 解析 cron。
     *
     * <p>只认 6 段（秒 分 时 日 月 周）：Spring 的 {@link CronExpression} 不接受 5 段，
     * 少写一段不会被猜一个秒，而是当场报错。这条错误原样交给模型，比一句「格式不对」有用。
     *
     * @param cron cron 原文
     * @return 解析后的表达式
     * @throws IllegalArgumentException cron 为空或解析不了
     */
    public static CronExpression parse(String cron) {
        if (cron == null || cron.isBlank()) {
            throw new IllegalArgumentException("cron 是空的");
        }
        try {
            return CronExpression.parse(cron.strip());
        } catch (IllegalArgumentException broken) {
            throw new IllegalArgumentException("cron 解析不了（要 6 段：秒 分 时 日 月 周）："
                    + cron + "，" + broken.getMessage(), broken);
        }
    }

    /**
     * 把时区字符串解析成 {@link ZoneId}，为空取部署所在时区。
     *
     * <p>包内可见：工具要把算好的时间点按同一个时区写给人看。两处各解析一遍的话，
     * 「下一次 09:00」与「下一次 01:00」这种差八小时的显示错，看起来像 cron 写错了。
     *
     * @param zone 时区名；null 或空表示部署所在时区
     * @return 时区
     * @throws IllegalArgumentException 时区认不出来
     */
    static ZoneId zoneOf(String zone) {
        if (zone == null || zone.isBlank()) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(zone.strip());
        } catch (RuntimeException broken) {
            throw new IllegalArgumentException("时区认不出来：" + zone, broken);
        }
    }

    /**
     * 认领一批到点的任务，多实例安全。
     *
     * <p>读不到库时抛出，不在这里吞：吞掉之后调用方分不出「这一轮没有到点任务」与
     * 「这张表根本读不出来」。前者是常态，后者要么该告警，要么该有人去跑迁移。
     * 喊几次由 {@link ScheduleScanner} 决定，它只喊一次。
     *
     * @param max 这一轮最多认领几条
     * @param lease 认领之后占用多久
     * @return 本轮认领到的任务
     */
    public List<Due> claim(int max, Duration lease) {
        return jdbc.query(CLAIM_SQL, (row, index) -> due(row),
                (double) lease.toMillis() / 1000, max);
    }

    /** 这一轮跑成了：清零计数、推进到 {@code nextRunAt}。 */
    public void settle(String id, Instant nextRunAt) {
        try {
            jdbc.update(SETTLE_SQL, java.sql.Timestamp.from(nextRunAt), id);
        } catch (RuntimeException failure) {
            log.warn("定时任务推进时间点失败 id={}（下一轮可能重跑）", id, failure);
        }
    }

    /**
     * 这一轮没跑成：记一次，可能顺手停用。
     *
     * @param id 任务编号
     * @param nextRunAt 这一次之后的下一个时间点，照样推进，理由见 {@link #FAIL_SQL}
     * @param error 给人看的失败原因，超长会截断到列宽
     * @param threshold 连续几次就停用
     * @return 记完之后的样子；写不进去时为空，那时宁可不通知，也不能假装停用了
     */
    public Optional<AfterFailure> fail(String id, Instant nextRunAt, String error, int threshold) {
        String trimmed = error == null || error.isBlank() ? "（没有说明）" : clip(error);
        try {
            return jdbc.query(FAIL_SQL, (row, index) -> new AfterFailure(
                            row.getInt("failures"), row.getBoolean("enabled"),
                            row.getString("disabled_reason")),
                    trimmed, java.sql.Timestamp.from(nextRunAt), threshold, threshold,
                    "连续 " + threshold + " 次没跑成", id).stream().findFirst();
        } catch (RuntimeException failure) {
            log.warn("定时任务记失败失败 id={}", id, failure);
            return Optional.empty();
        }
    }

    /**
     * 直接停用，记下原因。
     *
     * <p>用于 cron 坏了、没有下一次这类问题：它们不会因为再试三次而变好，走失败计数只是
     * 让停用晚三天发生。
     *
     * @param id 任务编号
     * @param reason 停用原因，写进 {@code disabled_reason}
     */
    public void disable(String id, String reason) {
        try {
            jdbc.update(DISABLE_SQL, clip(reason), id);
        } catch (RuntimeException failure) {
            log.warn("定时任务停用失败 id={}", id, failure);
        }
    }

    /**
     * 回读一条任务。
     *
     * @param id 任务编号
     * @return 任务；没有这条记录或读不到库时为空
     */
    public Optional<Schedule> find(String id) {
        try {
            return jdbc.query(SELECT_SQL + " where id = ?", scheduleMapper(), id).stream().findFirst();
        } catch (RuntimeException failure) {
            log.warn("读定时任务失败 id={}", id, failure);
            return Optional.empty();
        }
    }

    /**
     * 这个人建过的定时任务，最近建的在前。
     *
     * @param owner 归属键，与 run 的口径一致
     * @return 任务列表；读不到库时为空列表
     */
    public List<Schedule> listOf(String owner) {
        try {
            return jdbc.query(SELECT_SQL + " where owner = ? order by created_at desc",
                    scheduleMapper(), owner);
        } catch (RuntimeException failure) {
            log.warn("读定时任务列表失败 owner={}", owner, failure);
            return List.of();
        }
    }

    private RowMapper<Schedule> scheduleMapper() {
        return (row, index) -> new Schedule(
                row.getString("id"), row.getString("owner"), row.getString("channel"),
                row.getString("channel_ref"), row.getString("goal"), row.getString("cron"),
                row.getString("zone"), NotifyPolicy.fromColumn(row.getString("notify_policy")),
                instant(row, "next_run_at"), instant(row, "last_fired_at"),
                row.getInt("failures"), row.getString("last_error"),
                row.getBoolean("enabled"), row.getString("disabled_reason"));
    }

    private static Due due(ResultSet row) throws SQLException {
        return new Due(row.getString("id"), row.getString("owner"), row.getString("channel"),
                row.getString("channel_ref"), row.getString("goal"), row.getString("cron"),
                row.getString("zone"), NotifyPolicy.fromColumn(row.getString("notify_policy")),
                row.getString("owner_user_id"), row.getString("owner_role"),
                toolsOf(row.getArray("required_tools")), instant(row, "next_run_at"),
                row.getInt("failures"));
    }

    /** {@code text[]} → 列表。列是 {@code not null default '{}'}，但驱动仍可能给 null，两种都当空。 */
    private static List<String> toolsOf(java.sql.Array column) throws SQLException {
        if (column == null) {
            return List.of();
        }
        String[] values = (String[]) column.getArray();
        return values == null ? List.of() : List.of(values);
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        java.sql.Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    /** 库里两列都是 {@code varchar(1024)}：截断在这里，别让一次超长错误把整条 update 打回。 */
    private static String clip(String text) {
        if (text == null) {
            return null;
        }
        String oneLine = text.replace('\n', ' ').strip();
        return oneLine.length() <= 1024 ? oneLine : oneLine.substring(0, 1021) + "…";
    }
}

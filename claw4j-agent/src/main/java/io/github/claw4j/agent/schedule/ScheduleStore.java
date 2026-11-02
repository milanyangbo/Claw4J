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
 * 定时任务的落点（P04）：建、认领、推进、记失败、停用。
 *
 * <p><b>为什么只有一个具体类，不预先开接口</b>：定时任务的表与它的读写方式是同一件事，
 * 而"另一种实现"（内存版、别的库）现在没有需求。预先开一个接口，只会让每个方法
 * 多一处要同步改的签名；等真有第二种实现时再抽，代价是机械的。
 *
 * <p><b>认领是<b>一条</b>语句</b>：
 * {@code update ... where id in (select ... for update skip locked) returning ...}。
 * 拆成"先 select 再 update"的话，两个实例会在两者之间读到同一批行——而它们各自都会
 * 认为"这条归我了"，于是同一条任务跑两遍。{@code skip locked} 让后来的那个
 * <b>跳过</b>而不是等：等它会让整轮扫描卡在别人的一次模型调用上。
 *
 * <p><b>租约与行锁解决的是两件事</b>：行锁管"同一瞬间"，租约管"认领之后进程没了"。
 * 只有行锁的话，进程崩溃后那条任务会一直躺在"已认领"里——锁随连接消失，
 * 但下一次扫描看到 {@code claimed_until} 还没到，就会一直不肯碰它。
 *
 * <p><b>推进时间点由调用方算</b>（{@link #settle}）："下一次是什么时候"取决于这一轮
 * 从几点跑完，而那是调度器才知道的事。这里只负责把值写下去，不猜。
 */
public class ScheduleStore {

    private static final Logger log = LoggerFactory.getLogger(ScheduleStore.class);

    /**
     * 认领一批到点的任务。
     *
     * <p>{@code claimed_until} 用<b>库的时钟</b>算（{@code now() + ...}），不用 JVM 的：
     * 与 {@code expires_at <= now()} 同一个理由——两个时钟有偏差时，
     * "我以为还没过期"和库里的判断会各说各的，而那种不一致只能靠翻日志解释。
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
     * <p><b>时间点照样推进</b>：不推进的话下一次扫描（30 秒后）会立刻重试，
     * 于是一次模型故障会让三次失败在一分半内用光——那会把"连着坏三天"和
     * "坏了 90 秒"变成同一件事，而它们的处置完全不同。
     *
     * <p><b>停用与计数是同一条语句</b>：分成两句的话，第二个实例可能在这之间又跑一次，
     * 于是失败计数跨过阈值两次、发两条通知。
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
     * <p><b>带回来的是身份，不是权限</b>（P05）：{@code userId} + {@code role} 要交给
     * {@link ScheduleAuthorization} 换一份<b>此刻</b>的权限。P04 这一格放的是整份
     * Principal 快照，于是收权对定时任务无效——那条缝在"有人拿这份快照直接去跑"时才露出来。
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
     * @param owner     归属键，与 run 的口径一致（{@code SessionKeys.ownerOf}）
     * @param goal      每次到点要问的那句话
     * @param cron      6 段 cron，创建时就解析一遍：写错了要当场说，而不是等到明天九点
     * @param zone      时区；null 表示用部署所在时区
     * @param channel   结果送回哪个通道；null 表示不推
     * @param channelRef 通道内的收件地址
     * @param principal 任务主人。<b>只取 {@code userId} 与 {@code role} 落库</b>——
     *                  tables / actions 一律不存，它们要到点那一刻按当时的角色表重算
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
     * 建一条定时任务，第一次触发时间按 cron 从"现在"往后算。
     *
     * @throws IllegalArgumentException cron 或时区解析不了。调用方（P05 的工具）把这句话
     *                                  原样告诉模型，而不是自己编一句"创建失败"
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
                    // 显式转成 Object：String[] 直接当变参会被当成"一串参数"，而不是一个 text[]
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
     * 第一次触发的时间点：严格晚于"现在"的那一个。
     *
     * <p><b>正好站在那个点上创建的任务，今天这次不算</b>：`next` 是严格之后，
     * 所以 9:00:00 建的任务今天不会再跑一次。反过来（取"最近一次"）会让
     * "现在建、立刻跑"和"明天九点跑"变成同一件事，而用户说的显然是后者。
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

    /** 解析 cron 并给出"下一个时间点"；没有下一次时返回空（例如一次性日期已经过去）。 */
    public static Optional<Instant> nextRunAt(String cron, String zone, Instant after) {
        ZonedDateTime next = parse(cron).next(ZonedDateTime.ofInstant(after, zoneOf(zone)));
        return next == null ? Optional.empty() : Optional.of(next.toInstant());
    }

    /**
     * 解析 cron。
     *
     * <p><b>只认 6 段</b>（秒 分 时 日 月 周）：Spring 的 {@link CronExpression} 不接受 5 段，
     * 少写一段不会被"猜一个秒"，而是当场报错。这条错误会原样交给模型——
     * 让它知道该补哪一段，比一句"格式不对"有用。
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
     * 时区；为空取部署所在时区。
     *
     * <p>包内可见：工具要把算好的时间点按同一个时区写给人看——两处各解析一遍的话，
     * "下一次 09:00"与"下一次 01:00"这种差八小时的显示错，看起来像 cron 写错了。
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
     * 认领一批到点的任务（多实例安全）。读不到库时返回空列表，不抛——
     * 下一轮扫描再来，一个后台任务不该反复抛栈。
     */
    public List<Due> claim(int max, Duration lease) {
        try {
            return jdbc.query(CLAIM_SQL, (row, index) -> due(row),
                    (double) lease.toMillis() / 1000, max);
        } catch (RuntimeException failure) {
            log.warn("认领定时任务失败（这一轮不跑）", failure);
            return List.of();
        }
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
     * @param nextRunAt   这一次之后的下一个时间点（照样推进，理由见 {@link #FAIL_SQL}）
     * @param error       给人看的失败原因（截断到列宽）
     * @param threshold   连续几次就停用
     * @return 记完之后的样子；写不进去时返回空——那时宁可不通知，也不能假装停用了
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
     * 直接停用（cron 坏了、没有下一次了）。这类问题不会因为再试三次而变好，
     * 所以不走失败计数——攒三次只是让它晚三天才停。
     */
    public void disable(String id, String reason) {
        try {
            jdbc.update(DISABLE_SQL, clip(reason), id);
        } catch (RuntimeException failure) {
            log.warn("定时任务停用失败 id={}", id, failure);
        }
    }

    /** 回读一条任务。没有、或者读不到库，都是空。 */
    public Optional<Schedule> find(String id) {
        try {
            return jdbc.query(SELECT_SQL + " where id = ?", scheduleMapper(), id).stream().findFirst();
        } catch (RuntimeException failure) {
            log.warn("读定时任务失败 id={}", id, failure);
            return Optional.empty();
        }
    }

    /** 这个人建过的定时任务，最近的在前。P05 的"列出我的任务"用它。 */
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

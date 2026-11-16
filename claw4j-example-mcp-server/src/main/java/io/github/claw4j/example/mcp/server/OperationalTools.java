package io.github.claw4j.example.mcp.server;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 这个示例 server 导出的三个工具。
 *
 * <p>它们刻意不和 {@code examples/mcp/demo_mcp_server.py} 的 {@code demo_echo} / {@code demo_now}
 * 重名，也不干同一件事：那两个是"回显一句话、报一次时间"，证明的是"帧能过去"；这三个各自有
 * 一点真逻辑，证明的是"server 侧就是一段普通 Java"。
 *
 * <ul>
 *   <li>{@code runtimeSnapshot}：这个进程是谁、活了多久、服务了几次。{@code pid} 与
 *       {@code startedAt} 一起用，能判"重连之后接上的是不是新起的那个实例"。</li>
 *   <li>{@code cronNext}：五字段 cron 求解。{@link CronSchedule} 是真在算，不是查表。</li>
 *   <li>{@code simulateSlowCall}：可控的慢调用，用来卡客户端的 {@code request-timeout}
 *       （默认 30 秒）——慢不等于不可用，也不等于会话丢失，这两种故障的处理方式不一样。</li>
 * </ul>
 *
 * <p>计数在 {@link #handledCalls} 上，三个工具每次调用都加一：连着调两次 {@code runtimeSnapshot}
 * 看到数字涨了，说明是同一个实例在答；重启之后再调，数字从头开始而 {@code startedAt} 变了。
 */
@Component
public class OperationalTools {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 触发时间打成 {@code 2026-11-17 09:00 周二}：读的人要能一眼看出是星期几。 */
    private static final DateTimeFormatter RUN_AT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm EEE", Locale.SIMPLIFIED_CHINESE);

    private static final DateTimeFormatter STARTED_AT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.SIMPLIFIED_CHINESE);

    /** 一次慢调用最多睡多久：一分钟，够越过默认的 30 秒请求超时。 */
    private static final int MAX_SLEEP_MILLIS = 60_000;

    /** 一次最多求几次触发时间：再多的意义只是把回答撑长。 */
    private static final int MAX_RUNS = 20;

    private final Instant startedAt = Instant.now();

    private final AtomicLong handledCalls = new AtomicLong();

    /**
     * 这个进程的身份与状态。
     *
     * <p>{@code handledCalls} 包含本次调用：第一次拿到的是 1。
     *
     * @return 一行一个字段的快照
     */
    @McpTool(name = "runtimeSnapshot",
            description = "返回这个 server 进程的身份与状态：host、pid、启动时刻、已运行时长、"
                    + "已处理的工具调用次数、JVM 版本与时区。用来确认调用真的发生在另一个进程里，"
                    + "以及重启之后接上的是不是新实例")
    public String runtimeSnapshot() {
        long calls = handledCalls.incrementAndGet();
        Duration uptime = Duration.between(startedAt, Instant.now());
        return """
                host=%s
                pid=%d
                startedAt=%s
                uptime=%s
                handledCalls=%d
                java=%s
                timezone=%s（UTC%s）
                """.formatted(hostName(),
                ProcessHandle.current().pid(),
                STARTED_AT.format(startedAt.atZone(ZONE)),
                humanUptime(uptime),
                calls,
                System.getProperty("java.version"),
                ZONE.getId(),
                ZONE.getRules().getOffset(Instant.now()));
    }

    /**
     * 求一条 cron 接下来几次触发时间。
     *
     * @param expression 五字段表达式（分 时 日 月 周），支持 {@code *}、{@code a-b}、
     *                   {@code *}{@code /n}、{@code a-b/n} 与逗号列表；日与周不能同时限制
     * @param count      要几次，1..20
     * @return 触发时间列表，按时间递增；四年内凑不满时只有实际能凑到的那些
     * @throws IllegalArgumentException 表达式不合法，或四年内没有匹配的时间
     */
    @McpTool(name = "cronNext",
            description = "解析一条五字段 cron（分 时 日 月 周）并给出未来几次触发时间（Asia/Shanghai）。"
                    + "支持 *、a-b、*/n、a,b；星期 0 与 7 都是周日；日与周不能同时限制")
    public String cronNext(
            @McpToolParam(description = "五字段 cron 表达式，例如 0 9 * * 1-5", required = true) String expression,
            @McpToolParam(description = "要几次触发时间，1..20，默认 3", required = false) Integer count) {

        handledCalls.incrementAndGet();
        int wanted = count == null ? 3 : count;
        if (wanted < 1 || wanted > MAX_RUNS) {
            throw new IllegalArgumentException("count 要在 1.." + MAX_RUNS + " 之间，实际 " + wanted);
        }

        CronSchedule schedule = CronSchedule.parse(expression);
        List<java.time.LocalDateTime> runs = schedule.next(ZONE, Instant.now(), wanted);
        if (runs.isEmpty()) {
            throw new IllegalArgumentException("表达式 " + expression + " 在未来四年内没有匹配的时间——"
                    + "多半是日与月凑不成一个日子（例如 2 月 30 日）");
        }

        StringBuilder answer = new StringBuilder("未来 ").append(runs.size())
                .append(" 次（").append(ZONE.getId()).append("），表达式：").append(expression.strip());
        for (int index = 0; index < runs.size(); index++) {
            answer.append(System.lineSeparator())
                    .append(index + 1).append(". ").append(RUN_AT.format(runs.get(index)));
        }
        return answer.toString();
    }

    /**
     * 睡一段时间再回话，用来卡客户端的请求超时。
     *
     * <p>它**不会**被当成"server 不在了"：超时与连接断掉是两种故障，前者说明这次调用太慢，
     * 后者才触发重连。所以这里只是睡，不抛异常、不关会话。
     *
     * @param millis 睡多少毫秒，0..60000
     * @return 实际睡了多久
     * @throws IllegalArgumentException 超出范围
     */
    @McpTool(name = "simulateSlowCall",
            description = "睡指定毫秒再返回，用来验证客户端的 request-timeout 边界（默认 30 秒）。"
                    + "上限 60000 毫秒；超时不会被当成 server 掉线，也不会触发重连")
    public String simulateSlowCall(
            @McpToolParam(description = "要睡多少毫秒，0..60000", required = true) Integer millis) {

        handledCalls.incrementAndGet();
        int sleepMillis = millis == null ? 0 : millis;
        if (sleepMillis < 0 || sleepMillis > MAX_SLEEP_MILLIS) {
            throw new IllegalArgumentException("millis 要在 0.." + MAX_SLEEP_MILLIS + " 之间，实际 " + sleepMillis);
        }

        long beganAt = System.nanoTime();
        try {
            Thread.sleep(sleepMillis);
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("这次调用在睡的过程中被中断了", interrupted);
        }
        long actual = Duration.ofNanos(System.nanoTime() - beganAt).toMillis();
        return "请求睡 " + sleepMillis + " 毫秒，实际 " + actual + " 毫秒（上限 " + MAX_SLEEP_MILLIS + "）";
    }

    private static String hostName() {
        String host = System.getenv("HOSTNAME");
        if (host != null && !host.isBlank()) {
            return host;
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        }
        catch (UnknownHostException unavailable) {
            return "unknown";
        }
    }

    /** {@code 3d 04:05:06} 这种形状：比一串毫秒好读，也省得调用方自己换算。 */
    private static String humanUptime(Duration uptime) {
        long days = uptime.toDays();
        long hours = uptime.toHoursPart();
        long minutes = uptime.toMinutesPart();
        long seconds = uptime.toSecondsPart();
        return days > 0
                ? "%dd %02d:%02d:%02d".formatted(days, hours, minutes, seconds)
                : "%02d:%02d:%02d".formatted(hours, minutes, seconds);
    }
}

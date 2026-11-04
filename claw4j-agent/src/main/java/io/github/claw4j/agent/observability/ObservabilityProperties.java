package io.github.claw4j.agent.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * trace 导出的配置（P14）：一个 OTLP HTTP 端点，加四个关于"怎么导"的旋钮。
 *
 * <p><b>默认全关</b>：{@link #enabled} 的默认值是 false。观测的成本不只是几条 span，
 * 而是一个 netty/okhttp 连接池、一个 batch 线程、以及一条可能的跨网络出站——
 * 这三样都该由部署的人明确说"我要"。默认打开还会让"没配端点"变成本地的一堆重试。
 *
 * <p><b>{@code enabled=true} 但端点为空的处理写在这里而不是留给装配去猜</b>：
 * {@link #exporting()} 是唯一的判据，装配只看它。空端点下 OTLP exporter 会回落到
 * {@code http://localhost:4318}，于是"开了但没配"表现为"每 5 秒一条连接被拒的日志"，
 * 而运维会去查网络——真正缺的那一项没人说。这种"以为开了"是最坏的姿势，所以
 * {@link #exporting()} 为 false 时装配会退回 {@link RunTracer#NONE} 并打一条点名缺项的话。
 *
 * <p>键名见 {@code application.yml} 的 {@code claw4j.observability} 段。
 *
 * @param enabled      总开关。关着时不建 provider、不建 exporter、不建线程
 * @param endpoint     OTLP HTTP 的 base，例如 {@code http://localhost:4318}。
 *                     带了 {@code /v1/traces} 也认（不会拼成两遍）
 * @param serviceName  resource 上的 {@code service.name}。后端按它分组，默认 {@code claw4j}
 * @param sampleRatio  采样比例，0..1。用 ParentBased(TraceIdRatioBased) 而不是裸 ratio，
 *                     否则子 span 可能被单独丢掉（见装配类里那段注释）
 * @param timeout      单次导出的超时。默认 10 秒：collector 不在同机时留出一次重试的余量
 * @param maxQueueSize batch 队列上限。满了会丢，丢的时候 OTel 自己打日志
 */
@ConfigurationProperties("claw4j.observability")
public record ObservabilityProperties(Boolean enabled,
                                      String endpoint,
                                      String serviceName,
                                      Double sampleRatio,
                                      Duration timeout,
                                      Integer maxQueueSize) {

    /** 默认服务名。与项目同名：后端里一眼能认出这一堆 span 是谁的。 */
    public static final String DEFAULT_SERVICE_NAME = "claw4j";

    /** 默认全采：单机部署下量本来就小，采样是等量大了才该做的取舍。 */
    public static final double DEFAULT_SAMPLE_RATIO = 1.0;

    /** 默认 10 秒。OTLP 的批量导出是后台线程，卡住这一批不该卡住回答。 */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    /** 默认 2048。按每条 span 一两百字节算，满队列约是几百 KB。 */
    public static final int DEFAULT_MAX_QUEUE_SIZE = 2048;

    /** OTLP HTTP 的固定路径。自己拼而不依赖 exporter 的默认值：默认值随版本变，拼死在这里。 */
    private static final String TRACES_PATH = "/v1/traces";

    /**
     * 补齐缺省值，并把"配了个空串"与"没配"归一。
     *
     * <p>这一层不做校验抛错：配置写错（比如 ratio 写成 1.5）在启动时炸，比在第一次导出时
     * 静默按 0 采要清楚得多——但 ratio 夹到 [0,1] 就够了，它是连续量、越界的意图很明确。
     */
    public ObservabilityProperties {
        enabled = Boolean.TRUE.equals(enabled);
        endpoint = endpoint == null ? "" : endpoint.strip();
        serviceName = serviceName == null || serviceName.isBlank()
                ? DEFAULT_SERVICE_NAME : serviceName.strip();
        sampleRatio = sampleRatio == null ? DEFAULT_SAMPLE_RATIO
                : Math.max(0.0, Math.min(1.0, sampleRatio));
        timeout = timeout == null || timeout.isNegative() || timeout.isZero()
                ? DEFAULT_TIMEOUT : timeout;
        maxQueueSize = maxQueueSize == null || maxQueueSize < 1
                ? DEFAULT_MAX_QUEUE_SIZE : maxQueueSize;
    }

    /** 一个都不配时的取值：关。 */
    public static ObservabilityProperties defaults() {
        return new ObservabilityProperties(false, null, null, null, null, null);
    }

    /**
     * 真的会往外导吗。**装配只看这一个判据**：开着却配不出端点的那些组合，
     * 在这里就归到"不导"，而不是让 exporter 回落到 localhost 之后每 5 秒报一次连接被拒。
     */
    public boolean exporting() {
        return enabled && !endpoint.isEmpty();
    }

    /**
     * 完整的上报地址。用户配 base（{@code http://host:4318}）是最常见的写法，
     * 直接配完整路径的人也不少——两种都认，避免拼成 {@code /v1/traces/v1/traces}。
     */
    public String tracesEndpoint() {
        if (endpoint.isEmpty()) {
            return "";
        }
        String base = endpoint.endsWith("/")
                ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        return base.endsWith(TRACES_PATH) ? base : base + TRACES_PATH;
    }
}

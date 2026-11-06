package io.github.claw4j.app;

import com.sun.net.httpserver.HttpServer;
import io.github.claw4j.agent.observability.ObservabilityProperties;
import io.github.claw4j.agent.observability.OtelSpanTracer;
import io.github.claw4j.agent.observability.SpanTracer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * trace 真的导出去（P14）：起一个假的 OTLP collector（JDK 自带 {@link HttpServer}），
 * 让它收 {@code /v1/traces}，断言"收到了、内容不是空的、Content-Type 对、该 flush 的都 flush 了"。
 *
 * <p><b>为什么这份放在 app 而不是 agent</b>：agent 的 {@code ObservabilityTest} 验的是
 * 层级、属性、默认不导（内存 exporter，进程内）；这里验的是**真的走一遍 HTTP**：
 * exporter 的地址与路径、Content-Type、关闭前的 flush。两件事分开之后，
 * 前者回答"画得对不对"，后者回答"送得出去吗"。
 *
 * <p><b>为什么不解析 protobuf</b>：OTLP 的载荷是 protobuf，而本地没有 protobuf 解析器
 * （也不该为一个测试引一个）。span 名在载荷里是原样出现的 UTF-8 字段值，
 * 所以"数某个名字在 body 里出现了几次"就等价于"收到了几个 span"——这个等价关系的前提
 * （名字唯一、不被压缩）写在这里，比引一个解析器诚实。
 *
 * <p><b>判据是收端收到了什么，不是"没抛异常"</b>：导出的失败姿势恰恰是不抛——
 * Batch 队列满了丢、collector 不可达时重试到超时，两种都只留一条日志。
 */
class TraceExportTest {

    /** 一次上送：路径、Content-Type、原样的 body。 */
    private record Received(String path, String contentType, byte[] body) {
    }

    private final List<Received> received = new CopyOnWriteArrayList<>();

    private HttpServer collector;

    @BeforeEach
    void startCollector() throws IOException {
        collector = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        collector.createContext("/v1/traces", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            received.add(new Received(exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Content-Type"), body));
            // 204 就够：OTLP 只看状态码，正文内容它不读。
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        collector.start();
    }

    @AfterEach
    void stopCollector() {
        collector.stop(0);
    }

    @Test
    @DisplayName("导出真的发出：collector 在 /v1/traces 上收到一段 protobuf（P14）")
    void spansReachTheCollectorAsOtlpHttp() {
        try (OtelSpanTracer tracer = OtelSpanTracer.start(properties(1.0))) {
            try (SpanTracer.SpanScope span = tracer.open("run", Map.of("claw4j.run.id", "run-1"))) {
                span.attribute("claw4j.outcome", "ok");
            }
        }

        assertThat(received).as("close() 里先 flush 再关，这一段不该还留在队列里").isNotEmpty();
        assertThat(received.getFirst().path()).isEqualTo("/v1/traces");
        assertThat(received.getFirst().contentType())
                .as("OTLP HTTP 的 protobuf 就是这个 Content-Type；写成 zipkin 的 json 说明配错了 exporter")
                .isEqualTo("application/x-protobuf");
        assertThat(received.getFirst().body()).isNotEmpty();
    }

    @Test
    @DisplayName("关掉之前先 flush：还没到批次时间的 span 一条都不留在手里（P14）")
    void closingFlushesWhatIsStillInTheQueue() {
        // 默认的批次间隔是 5 秒，而这一段在毫秒内就结束了——不 flush 的话它们全丢。
        try (OtelSpanTracer tracer = OtelSpanTracer.start(properties(1.0))) {
            for (int i = 0; i < 3; i++) {
                try (SpanTracer.SpanScope span = tracer.open("model.turn", Map.of("claw4j.run.id",
                        "run-" + (i + 1)))) {
                    span.attribute("claw4j.tokens", "34");
                }
            }
        }

        int exported = received.stream().mapToInt(request -> occurrences(request.body(), "model.turn")).sum();
        assertThat(exported).as("关之前 end 了三段，收端就该收到三段").isEqualTo(3);
    }

    @Test
    @DisplayName("采样比例 0：一条都不导，队列里也不留（P14）")
    void aZeroRatioExportsNothing() {
        try (OtelSpanTracer tracer = OtelSpanTracer.start(properties(0.0))) {
            try (SpanTracer.SpanScope span = tracer.open("run", Map.of())) {
                span.attribute("claw4j.outcome", "ok");
            }
        }

        assertThat(received).as("没采的 span 连队列都不进，自然也就没有请求").isEmpty();
    }

    @Test
    @DisplayName("端点写成完整路径也认：不拼成 /v1/traces/v1/traces（P14）")
    void theTracesPathIsNotDoubled() {
        assertThat(propertiesOf("http://collector:4318").tracesEndpoint())
                .isEqualTo("http://collector:4318/v1/traces");
        assertThat(propertiesOf("http://collector:4318/").tracesEndpoint())
                .as("结尾多一个斜杠是最常见的写法").isEqualTo("http://collector:4318/v1/traces");
        assertThat(propertiesOf("http://collector:4318/v1/traces").tracesEndpoint())
                .as("已经写全的照原样用").isEqualTo("http://collector:4318/v1/traces");
    }

    /** 指向本地假 collector 的那一份配置。队列给小一点，不影响结论。 */
    private ObservabilityProperties properties(double sampleRatio) {
        return new ObservabilityProperties(true,
                "http://127.0.0.1:" + collector.getAddress().getPort(),
                "claw4j-test", sampleRatio, Duration.ofSeconds(5), 64);
    }

    private static ObservabilityProperties propertiesOf(String endpoint) {
        return new ObservabilityProperties(true, endpoint, "claw4j", 1.0, Duration.ofSeconds(5), 64);
    }

    /**
     * 某个字节序列在 body 里出现了几次。
     *
     * <p>用它数 span：span 名在 protobuf 里是按 UTF-8 原样编码的字段值，而这一段里
     * 每个 span 的 {@code name} 都不同（{@code model.turn} 只做 span 名用，不会被别的字段带上）。
     */
    private static int occurrences(byte[] body, String needle) {
        byte[] target = needle.getBytes(StandardCharsets.UTF_8);
        int count = 0;
        for (int i = 0; i + target.length <= body.length; i++) {
            boolean matched = true;
            for (int j = 0; j < target.length && matched; j++) {
                matched = body[i + j] == target[j];
            }
            if (matched) {
                count++;
            }
        }
        return count;
    }
}

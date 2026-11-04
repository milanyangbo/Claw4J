package io.github.claw4j.agent.observability;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 整套代码里**唯一**碰 {@code io.opentelemetry} 的类（P14）。
 *
 * <p>业务侧只认 {@link RunTracer}，所以换后端时改的是这一个文件；而"换版本"的风险也被
 * 收在这里——{@code opentelemetry-sdk} 的版本是显式写的 1.35.0，与 Boot BOM 里的 1.62.0
 * 并存，理由见票面第一节（离线仓库里只有 1.35.0 这一条链是齐的）。
 *
 * <p><b>为什么是 HTTP 而不是 gRPC</b>：{@code exporter-otlp} 的 jar 里两个 exporter 都在，
 * 但运行时依赖只带 {@code exporter-sender-okhttp}——gRPC 那个编译得过、启动时找不到 sender。
 * 这是"本地跑得起来、换台机器就炸"的那类错，所以这里写死 HTTP。
 *
 * <p><b>采样用 {@code ParentBased(TraceIdRatioBased)}</b>：裸的 ratio 在有父 span 的情况下
 * 仍可能把子 span 丢掉，表现是"trace 里有 run、没有 tool"——而这种缺口看起来像
 * 工具根本没被调，运维会去查业务。ParentBased 保证"父采了，子一定采"。
 *
 * <p><b>关闭前必须 flush</b>：Batch 队列里最后几条在进程退出时直接消失，而丢的恰好是
 * "出事前的那几秒"——那正是有人去翻 trace 的时刻。{@link #close()} 先
 * {@code forceFlush} 再 {@code close}，并且等它完成（最多 {@link #FLUSH_TIMEOUT_SECONDS} 秒）。
 */
public final class OtelRunTracer implements RunTracer, AutoCloseable {

    /** instrumentation 名。后端里按它区分"是哪套代码产生的 span"。 */
    static final String INSTRUMENTATION = "io.github.claw4j";

    /** flush 最多等多久。退出路径上不能无限等：collector 不可达时也要能关掉。 */
    private static final int FLUSH_TIMEOUT_SECONDS = 5;

    /** resource 上的服务名。常量而不是字面量，是因为它同时出现在配置默认值里。 */
    private static final AttributeKey<String> SERVICE_NAME = AttributeKey.stringKey("service.name");

    private final Tracer tracer;

    /** 生命周期归自己管的那一份 provider。为 null 表示是外面给了一个（测试与宿主容器用）。 */
    private final SdkTracerProvider owned;

    /** 用宿主给的 provider：生命周期也归宿主，{@link #close()} 不碰它。 */
    public OtelRunTracer(SdkTracerProvider provider) {
        this(provider.get(INSTRUMENTATION), null);
    }

    private OtelRunTracer(Tracer tracer, SdkTracerProvider owned) {
        this.tracer = tracer;
        this.owned = owned;
    }

    /**
     * 按配置造一套：resource、采样、batch、OTLP HTTP 导出。
     *
     * <p>只该在 {@link ObservabilityProperties#exporting()} 为真时调用——端点为空的组合
     * 由装配那一层挡掉，这里不为它造一个指向 localhost 的 exporter。
     */
    public static OtelRunTracer start(ObservabilityProperties properties) {
        SdkTracerProvider provider = providerOf(properties);
        return new OtelRunTracer(provider.get(INSTRUMENTATION), provider);
    }

    /**
     * 造 provider。单独提出来是给装配用的：provider 是一个 bean（容器里能查到它存不存在），
     * tracer 是另一个——两者分开，"没开观测"这件事才有一句可断言的话。
     */
    public static SdkTracerProvider providerOf(ObservabilityProperties properties) {
        OtlpHttpSpanExporter exporter = OtlpHttpSpanExporter.builder()
                .setEndpoint(properties.tracesEndpoint())
                .setTimeout(properties.timeout())
                .build();
        return SdkTracerProvider.builder()
                .setResource(Resource.getDefault().merge(
                        Resource.create(Attributes.of(SERVICE_NAME, properties.serviceName()))))
                .setSampler(Sampler.parentBased(Sampler.traceIdRatioBased(properties.sampleRatio())))
                .addSpanProcessor(BatchSpanProcessor.builder(exporter)
                        .setMaxQueueSize(properties.maxQueueSize())
                        .build())
                .build();
    }

    @Override
    public SpanScope open(String name, Map<String, String> attributes) {
        Span span = tracer.spanBuilder(name).startSpan();
        if (attributes != null) {
            attributes.forEach((key, value) -> {
                // null 值不设：runId 在没装 run 存储的部署里确实是 null，
                // 而 OTel 的 setAttribute 收到 null 会抛 NPE——埋点不该因此打断一次回答。
                if (key != null && value != null) {
                    span.setAttribute(key, value);
                }
            });
        }
        // makeCurrent 之后嵌套的 open 才是子 span。不调它的话所有 span 都是并列的根，
        // 断言"个数"仍然会绿，而图上是一堆互不相干的点。
        return new OtelSpan(span, span.makeCurrent());
    }

    /**
     * 关掉自己造的 provider：先 flush 再 close。
     *
     * <p>宿主给的那份不碰：谁造的谁关，两处都关会得到一条"already shut down"的告警，
     * 而它看起来像一次真正的失败。
     */
    @Override
    public void close() {
        if (owned == null) {
            return;
        }
        owned.forceFlush().join(FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        owned.close();
    }

    /** 一段真的 span。结束顺序是"先恢复上下文，再 end"——与 OTel 文档的写法一致。 */
    private record OtelSpan(Span span, Scope scope) implements SpanScope {

        @Override
        public void attribute(String key, String value) {
            if (key != null && value != null) {
                span.setAttribute(key, value);
            }
        }

        @Override
        public void failed(Throwable cause) {
            if (cause == null) {
                return;
            }
            // 只把类型名写进 status 描述：message 里可能有业务数据，而 trace 会进第三方后端。
            span.recordException(cause);
            span.setStatus(StatusCode.ERROR, cause.getClass().getSimpleName());
        }

        @Override
        public void close() {
            scope.close();
            span.end();
        }
    }
}

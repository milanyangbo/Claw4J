package io.github.claw4j.agent.observability;

import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;

import java.util.concurrent.TimeUnit;

/**
 * trace 导出的装配（P14）：**默认一件都不装**，配了 {@code claw4j.observability.enabled=true}
 * 才进来，进来之后还要看端点配没配。
 *
 * <p><b>为什么不用 Boot 的 {@code spring-boot-starter-opentelemetry}</b>：Boot 4.1.2 的 BOM
 * 管着 {@code opentelemetry 1.62.0} 与 {@code micrometer-tracing 1.7.2}，也管那个 starter 的坐标，
 * 但本地仓库里一个 jar 都没有，而门禁是 {@code ./mvnw -o}（离线）跑的。显式写 1.35.0
 * 的那条链离线解析成功（依赖树见票面第一节）。理由与 P11 不用 {@code mcp-server} starter 同一条：
 * starter 会带回一整套控制不了的装配，而这里只需要一个 provider。
 *
 * <p><b>三个 bean 的边界</b>：
 * <ul>
 *   <li>{@link SdkTracerProvider}——只在配了端点时存在。它是"导不导"唯一可断言的东西，
 *       所以必须是个 bean，而不是藏在 tracer 里的字段。</li>
 *   <li>{@link SpanTracer}——总是存在（装配进来之后）。端点没配时它是 {@link SpanTracer#NONE}，
 *       并打一条点名缺项的话：埋点代码只有一种写法，而"开了却没配"必须能自己说出来。</li>
 *   <li>{@link FlushOnShutdown}——退出时先 flush 再 close。见它的注释。</li>
 * </ul>
 *
 * <p><b>为什么关着的时候一个 bean 都不建</b>：关掉是绝大多数部署的默认，而一个空转的
 * provider 会带起一个 batch 线程与一个 okhttp 连接池——省电开关不该只是"少发几条数据"。
 */
@AutoConfiguration
@ConditionalOnProperty(value = "claw4j.observability.enabled", havingValue = "true")
@EnableConfigurationProperties(ObservabilityProperties.class)
public class ObservabilityAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityAutoConfiguration.class);

    /** 退出时最多等这么久把队列吐完。与 {@code OtelSpanTracer} 里那个常量同一个量级。 */
    private static final int FLUSH_TIMEOUT_SECONDS = 5;

    /**
     * 真的往外导的那一半。端点为空时这个 bean 不存在，{@link #runTracer} 会看见。
     *
     * <p>{@code destroyMethod = ""} 是刻意的：生命周期交给 {@link FlushOnShutdown}，
     * 由它保证"先 flush 再 close"。让 Spring 按推断关掉 provider 的话，关闭顺序取决于
     * bean 的销毁次序，而"没来得及吐完"的表现是**退出前那几条 span 不见了**——
     * 恰好是出事时最想看的那几条。
     */
    @Bean(destroyMethod = "")
    @Conditional(OtlpConfiguredCondition.class)
    public SdkTracerProvider sdkTracerProvider(ObservabilityProperties properties) {
        return OtelSpanTracer.providerOf(properties);
    }

    /**
     * 埋点用的入口。端点没配时退回空实现——**不制造"看起来在导"的假象**。
     *
     * <p>这条 WARN 是给运维看的：开了观测却没有端点，症状是"后端一条 trace 都没有"，
     * 而人会先去查网络、查防火墙。把缺的那一项直接打在启动日志里，比让人猜快得多。
     */
    @Bean
    @ConditionalOnMissingBean(SpanTracer.class)
    public SpanTracer runTracer(ObjectProvider<SdkTracerProvider> providers,
                               ObservabilityProperties properties) {
        SdkTracerProvider provider = providers.getIfAvailable();
        if (provider == null) {
            log.warn("claw4j.observability.enabled=true，但没有配置 claw4j.observability.endpoint："
                    + "trace 一条都不会导出，埋点退化为空实现。要导出请填 OTLP HTTP 的地址，"
                    + "例如 http://localhost:4318");
            return SpanTracer.NONE;
        }
        log.info("trace 导出已打开：{}（服务名 {}，采样比例 {}，队列上限 {}）",
                properties.tracesEndpoint(), properties.serviceName(),
                properties.sampleRatio(), properties.maxQueueSize());
        return new OtelSpanTracer(provider);
    }

    /**
     * 退出前把 Batch 队列吐完，再关 provider。
     *
     * <p><b>不 flush 的代价是"丢的恰好是出事前那几秒"</b>：Batch 默认 5 秒一批，
     * 进程退出时手里通常还压着最后一批——而那一刻的 span 正是有人去翻 trace 的原因。
     *
     * <p>用独立的 bean 而不是 {@code @PreDestroy} 挂在装配类上：销毁顺序由 bean 依赖决定，
     * 而这个 bean 依赖 provider，于是它一定在 provider 之前被销毁。
     */
    @Bean(destroyMethod = "close")
    @Conditional(OtlpConfiguredCondition.class)
    public FlushOnShutdown flushOnShutdown(SdkTracerProvider provider) {
        return new FlushOnShutdown(provider);
    }

    /** 关闭顺序：先 flush（把队列吐给 exporter），再 close。等它有上限，退出路径不能无限等。 */
    static final class FlushOnShutdown implements AutoCloseable {

        private final SdkTracerProvider provider;

        FlushOnShutdown(SdkTracerProvider provider) {
            this.provider = provider;
        }

        @Override
        public void close() {
            provider.forceFlush().join(FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            provider.close();
        }
    }
}

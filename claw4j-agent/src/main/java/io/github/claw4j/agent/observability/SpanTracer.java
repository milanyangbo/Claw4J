package io.github.claw4j.agent.observability;

import java.util.Map;

/**
 * 观测那一侧的入口：给一个动作开一段 span，关闭时它自己结束（P14）。
 *
 * <p>用这层接口而不是直接暴露 {@code io.opentelemetry.api.Tracer}：那个类型一进业务代码，换后端
 * （OTel / Brave / 内部链路系统）就得改遍埋点处，而埋点分散在工具回调、模型轮、run 三处，改漏一处
 * 的表现是这条链路少一段——不报错，只是图上缺一截。只有 {@code OtelSpanTracer} 一个实现碰 SDK。
 *
 * <p>{@link #NONE} 是默认值，而不是「没有 tracer 时传 null」：三处埋点都写
 * {@code try (var span = tracer.open(...))}，null 会让每处多一个判空，而判空写错的地方恰好是
 * 「观测没开」这条最常见路径——平时不跑，出问题的当天才会发现 {@code NPE}。空实现零开销（JIT 会把
 * 空方法内联掉），也保证埋点代码只有一种写法。
 *
 * <p>父 span 靠 {@code Context} 自动接上，不显式传：agent 的一轮从入口到工具执行都在同一条线程上
 * （工具循环是同步的），OTel 的 {@code Context} 是 ThreadLocal，{@code makeCurrent} 之后嵌套的
 * {@link #open} 自然就是子 span。要显式传的是跨线程的场景，这一版没有（见票面「不做的事」第 1 条：
 * 不做请求级 traceparent 透传）。
 *
 * <p>这里没有 {@code event} / {@code link} / {@code baggage}：需要什么再加。一个只在想象里被用到的
 * 接口方法，最后总会被某处按自己的理解实现一遍。
 */
public interface SpanTracer {

    /** 不记录任何东西。默认值——没配 {@code claw4j.observability.enabled} 时就是它。 */
    SpanTracer NONE = (name, attributes) -> SpanScope.NONE;

    /**
     * 开一段 span 并让它成为当前 span，直到 {@link SpanScope#close()}。
     *
     * <p>用法固定是 try-with-resources：{@code open} 里的属性要在开始时就定下，而后续才知道的那些
     * （step 序号、结果档位）走 {@link SpanScope#attribute}。
     *
     * @param name 动作名。用 OTel 的语义约定写成 {@code run} / {@code model.turn} /
     *     {@code tool.<工具名>}——后端按名字分组，改名字等于改仪表盘
     * @param attributes 开始时就知道的属性。允许为空；值为 null 的条目会被忽略（{@code Map.of}
     *     装不下 null，而 {@code runId} 在没装存储时确实是 null）
     * @return 这段 span 的句柄，关闭即结束
     */
    SpanScope open(String name, Map<String, String> attributes);

    /**
     * 一段正在进行的 span。关闭即结束，且必须关闭——不关的 span 永远不会被导出，
     * 而它在内存里还占着一份，表现是"trace 里少一段"。
     */
    interface SpanScope extends AutoCloseable {

        /** 什么都不做的那一份。给 {@link #NONE} 用。 */
        SpanScope NONE = new SpanScope() {

            @Override
            public void attribute(String key, String value) {
            }

            @Override
            public void failed(Throwable cause) {
            }

            @Override
            public void close() {
            }
        };

        /**
         * 补一个属性，必须在 {@code close()} 之前——已经结束的 span 上再设属性是静默的空操作
         * （OTel 不报错），而它看起来与设成功了完全一样。
         *
         * @param key 属性名
         * @param value 属性值
         */
        void attribute(String key, String value);

        /**
         * 这一段以失败结束：标 ERROR 并记一条 exception 事件。
         *
         * <p>只记异常类型，不记 message：message 里常有数据（一句贴了 SQL 的报错就够搬走一张表），
         * 而 trace 会进第三方后端。这与审计只存归一化错误码是同一条口径。
         *
         * @param cause 失败的异常
         */
        void failed(Throwable cause);

        /** 结束这一段。{@code span} 不关就永远不会被导出。 */
        @Override
        void close();
    }
}

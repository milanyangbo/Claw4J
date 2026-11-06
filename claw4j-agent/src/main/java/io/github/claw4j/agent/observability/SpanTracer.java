package io.github.claw4j.agent.observability;

import java.util.Map;

/**
 * 观测那一侧的入口：给一个动作开一段 span，关闭时它自己结束（P14）。
 *
 * <p><b>为什么是一个我们自己的接口，而不是直接用 {@code io.opentelemetry.api.Tracer}</b>：
 * 那个类型一露到业务代码里，换后端（OTel / Brave / 内部链路系统）就得改遍埋点处，
 * 而埋点分散在工具回调、模型轮、run 三处，改漏一处的表现是"这条链路少一段",
 * 不报错、只是图上缺一截。这一层只有 {@code OtelSpanTracer} 一个实现碰 SDK。
 *
 * <p><b>为什么 {@link #NONE} 是默认而不是"没有 tracer 时传 null"</b>：三处埋点都要写
 * {@code try (var span = tracer.open(...))}，null 会让每处都多一个判空，而判空写错的地方
 * 恰好是"观测没开"这条最常见路径——它平时不跑，出问题的当天才会发现 {@code NPE}。
 * 空实现是零开销的（JIT 会把空方法内联掉），也保证埋点代码只有一种写法。
 *
 * <p><b>父 span 靠 Context 自动接上，不显式传</b>：agent 的一轮从入口到工具执行都在同一条
 * 线程上（工具循环是同步的），OTel 的 {@code Context} 是 ThreadLocal，{@code makeCurrent}
 * 之后嵌套的 {@link #open} 自然就是子 span。要显式传的是"跨线程"的场景，而这一版没有
 * （见票面「不做的事」第 1 条：不做请求级 traceparent 透传）。
 *
 * <p><b>这里没有 {@code event} / {@code link} / {@code baggage}</b>：需要什么再加。
 * 一个只在想象里被用到的接口方法，最后总会被某处按自己的理解实现一遍。
 */
public interface SpanTracer {

    /** 不记录任何东西。默认值——没配 {@code claw4j.observability.enabled} 时就是它。 */
    SpanTracer NONE = (name, attributes) -> SpanScope.NONE;

    /**
     * 开一段 span 并让它成为当前 span，直到 {@link SpanScope#close()}。
     *
     * <p>用法固定是 try-with-resources：{@code open} 里的属性要在开始时就定下，
     * 而后续才知道的那些（step 序号、结果档位）走 {@link SpanScope#attribute}。
     *
     * @param name       动作名。用 OTel 的语义约定写成 {@code run} / {@code model.turn} /
     *                   {@code tool.<工具名>}——后端按名字分组，改名字等于改仪表盘
     * @param attributes 开始时就知道的属性。允许为空；值为 null 的条目会被忽略
     *                   （{@code Map.of} 装不下 null，而 runId 在没装存储时确实是 null）
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
         * 补一个属性。**必须在 {@code close()} 之前**——已经结束的 span 上再设属性是
         * 静默的空操作（OTel 不报错），而它看起来与"设成功了"完全一样。
         */
        void attribute(String key, String value);

        /**
         * 这一段以失败结束：标 ERROR 并记一条 exception 事件。
         *
         * <p><b>只记异常类型，不记 message</b>：message 里常有数据（一句贴了 SQL 的报错
         * 就够搬走一张表），而 trace 会进第三方后端。这与审计表
         * {@code ToolCallAudit#executed} 的口径是同一条。
         */
        void failed(Throwable cause);

        @Override
        void close();
    }
}

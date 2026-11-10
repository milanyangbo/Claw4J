package io.github.claw4j.domain.port;

import java.util.function.Consumer;

/**
 * Agent 向外暴露的唯一入口。通道调用它，但不需要知道它的实现里有没有 Spring AI。
 *
 * <p>这个接口放在 domain 而不是 agent，是为了让依赖方向单向：
 * <pre>
 *   channel ──► domain ◄── agent
 *                  ▲
 *                  └── app（唯一知道两边的地方，负责接线）
 * </pre>
 * channel 与 agent 互不依赖，谁也不知道对方存在。加一个通道不需要碰 agent，
 * 换一个 Agent 实现不需要碰 channel。
 *
 * <p>流式用 {@code Consumer} 而不是 Reactor 的 {@code Flux}：domain 承诺不依赖任何框架
 * （ADR-0010），而 Flux 会把 reactor 拖进来。代价是拿不到背压，通道侧自己决定怎么攒块、
 * 怎么限流——对 IM 场景足够。
 */
public interface AgentPort {

    /**
     * 一次性回答，拿到完整结果才返回。
     *
     * @param message 入站消息
     * @return 完整回答；实现不应返回 {@code null}
     */
    OutboundMessage reply(InboundMessage message);

    /**
     * 流式回答，每生成一块就回调一次，最后回调一个 {@code finalChunk} 为 true 的块。
     *
     * <p>{@code finalChunk} 是唯一的正常结束信号（文本可以为空），实现必须保证它最终发生一次
     * 且仅一次，哪怕模型一块都没吐出来——通道据此收尾，不必另设超时去猜。
     *
     * <p>实现必须响应中断，否则客户端走了，背后的线程还在烧模型调用：Reactor 的
     * {@code blockLast()} 天然满足，裸 HTTP 客户端这类实现要显式处理
     * {@code InterruptedException}。
     *
     * @param message 入站消息
     * @param onChunk 逐块消费，保证最后一次调用带 finalChunk
     */
    void stream(InboundMessage message, Consumer<OutboundMessage> onChunk);
}

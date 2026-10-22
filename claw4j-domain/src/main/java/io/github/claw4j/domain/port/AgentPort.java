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
 * <p>流式用 {@code Consumer<String>} 而不是 Reactor 的 {@code Flux}：
 * domain 承诺不依赖任何框架（ADR-0010），而 Flux 会把 reactor 拖进来。
 * 代价是拿不到背压，通道侧自己决定怎么攒块、怎么限流——对 IM 场景足够。
 */
public interface AgentPort {

    /**
     * 一次性回答，拿到完整结果才返回。
     */
    OutboundMessage reply(InboundMessage message);

    /**
     * 流式回答，每生成一块就回调一次，最后回调一个 {@code finalChunk} 为 true 的块。
     *
     * @param onChunk 逐块消费，实现必须保证最后一次调用带 finalChunk
     */
    void stream(InboundMessage message, Consumer<OutboundMessage> onChunk);
}

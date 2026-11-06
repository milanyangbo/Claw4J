package io.github.claw4j.agent;

import io.github.claw4j.domain.port.InboundMessage;

import java.util.Optional;

/**
 * 没有人说话，但有一件事该做了（P04）。
 *
 * <p><b>它不是 {@code AgentPort#reply} 的别名</b>：那一条的契约是"有人问了一句，我要把回答
 * 还给他"，于是结果会回到那次请求里；而定时任务那一头没有任何请求在等——
 * 结果只能按通知策略推到登记过的地址，或者等人来查。同一段跑法，两种交付方式，
 * 差别就落在"这次有没有就地回答的地址"上。
 *
 * <p>与 {@link RunResumer} 分开是因为入参不同：续跑靠 runId 把一件事接着做，
 * 这里要开<b>新</b>的一件。合成一个接口的话，每个实现都要多写一个它用不到的方法，
 * 而那种方法通常长成"抛 UnsupportedOperation"的样子。
 */
public interface TaskLauncher {

    /**
     * 用 {@code message.principal()} 的身份把 {@code message.text()} 跑一轮，开一个新的 run。
     *
     * <p><b>身份不是可选项</b>：定时任务用的必须是任务主人的身份，不能是"第一个点了启用的人"，
     * 也不能是一个管理员身份——那样每一次到点执行都会用的权限比创建者大。
     *
     * @param message 到点的这一次"提问"。{@code runId} 传 null：每次到点都是新的一件
     * @return 这一轮的回答全文。空表示没跑成（没有 run 存储、或者根本没开跑）——
     *         调用方据此记一次失败，而不是当成"跑完了但没话说"
     * @throws RuntimeException 模型或工具那一侧炸了。调用方要把这当成一次失败计数，
     *                          而不是吞掉——定时任务出问题时没有人站在旁边看日志
     */
    Optional<String> launch(InboundMessage message);
}

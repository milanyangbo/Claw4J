package io.github.claw4j.agent;

import io.github.claw4j.domain.port.InboundMessage;

import java.util.Optional;

/**
 * 在没有入站请求的情况下跑一轮对话，供定时任务使用（P04）。
 *
 * <p>它与 {@code AgentPort#reply} 的差别在交付方式：{@code reply} 有一条正在等回答的请求，
 * 结果回到那次请求里；定时任务那一头没有请求，结果只能按通知策略推给这段对话，或者等人来查。
 * 跑法相同，区别在于这次有没有就地回答的地址。
 */
public interface TaskLauncher {

    /**
     * 以 {@code message.principal()} 的身份把 {@code message.text()} 跑成新的一轮。
     *
     * <p>身份取任务主人，不是第一个点了启用的人，也不是管理员：否则每次到点执行的权限都比创建者大。
     *
     * @param message 到点触发的这一次提问，带着对话地址与主人身份，其余与一条入站消息相同
     * @return 这一轮的回答全文；空表示没有跑成（未装配 agent，或根本没开跑），调用方据此记一次失败，
     *     而不是当作跑完了但没话说
     * @throws RuntimeException 模型或工具侧抛错。调用方要计为失败，不要吞掉：定时任务出问题时
     *     没有人站在旁边看日志
     */
    Optional<String> launch(InboundMessage message);
}

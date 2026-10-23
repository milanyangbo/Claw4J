package io.github.claw4j.agent;

import io.github.claw4j.agent.advisor.AuthorizationAdvisor;
import io.github.claw4j.agent.advisor.PiiRedactor;
import io.github.claw4j.agent.session.SessionKeys;
import io.github.claw4j.domain.port.AgentPort;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.port.OutboundMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * {@link AgentPort} 的默认实现：把入站消息喂给 ChatClient，把回答包成出站消息。
 *
 * <p>这是整个 Agent 对外唯一的门。通道、测试、MCP 入口都走这里，
 * 于是"换编排方式"这件事被收在模块内部，外部不受影响。
 *
 * <p>{@link #stream} 会阻塞到流结束才返回。调用方（通道）自己决定在哪个线程调用它——
 * 飞书那种「立刻 ACK 再异步回推」的场景就靠这个：通道收到事件后立刻返回，
 * 把 stream 丢给业务线程池，结果再通过主动推送接口发回去（ADR-0009）。
 *
 * <p>中断契约（见 {@link AgentPort#stream}）靠 {@code blockLast()} 天然满足：
 * 它在等待流时响应中断。客户端断开后通道 {@code cancel(true)}，
 * 这里会立刻以异常结束，而不是把整条模型流烧完。
 */
public class ChatClientAgentPort implements AgentPort {

    private static final Logger log = LoggerFactory.getLogger(ChatClientAgentPort.class);

    private final ChatClient chatClient;

    public ChatClientAgentPort(ChatClient chatClient) {
        this.chatClient = Objects.requireNonNull(chatClient, "chatClient");
    }

    /**
     * 出站日志敢打**全文**，是因为脱敏已经在 Advisor 链上做完了。
     * 反过来：这条日志也是"脱敏发生在出站之前而不是事后替换"的证据——
     * 哪天有人在链路之外打日志，这里会立刻出现原始值。
     */
    @Override
    public OutboundMessage reply(InboundMessage message) {
        Objects.requireNonNull(message, "message");
        String text = promptOf(message).call().content();
        log.debug("出站回答 chatId={} text={}", message.chatId(), text);
        return OutboundMessage.of(message.chatId(), text);
    }

    @Override
    public void stream(InboundMessage message, Consumer<OutboundMessage> onChunk) {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(onChunk, "onChunk");

        promptOf(message)
                .stream()
                .content()
                .doOnNext(chunk -> {
                    log.debug("出站分块 chatId={} chunk={}", message.chatId(), chunk);
                    onChunk.accept(OutboundMessage.chunk(message.chatId(), chunk));
                })
                .doOnComplete(() -> onChunk.accept(OutboundMessage.of(message.chatId(), "")))
                .blockLast();
    }

    /**
     * 把 Principal 与会话 id 交给 Advisor 链：走 Advisor 的上下文，不走 ThreadLocal。
     *
     * <p>上下文会跟着 {@code ChatClientRequest} 走完工具循环的每一轮（{@code ToolCallingAdvisor}
     * 每轮都把 {@code context} 原样带下去），也跟着 Reactor 的订阅走；
     * ThreadLocal 在"流式 + 换线程"的组合下会掉，而掉身份的失败姿势是放行。
     *
     * <p>会话 id 由 {@link SessionKeys} 从 Principal 派生，不是 {@link InboundMessage#chatId()}：
     * 后者只是通道侧的寻址键，拿它当会话主键会让"换通道"变成"换会话"。
     * 这里设的是 Advisor 上下文里的参数，链上没有记忆 Advisor 时它安静地被忽略。
     */
    private ChatClient.ChatClientRequestSpec promptOf(InboundMessage message) {
        // 入站就脱敏（ADR-0011 里真模型测试抓到的那个缺陷）：
        // 会话记忆是在 Advisor 链的 before 阶段最早那一步把用户消息写进库的，
        // 而出站脱敏发生在 after——它改得到模型的回答，改不到已经落库的那条用户消息。
        // 于是"出站是掩码、会话表里躺着明文"这种更持久的泄露就出现了，
        // 而且没有任何一条假模型的测试会发现它：假模型时代的用例从不往用户消息里放号码。
        //
        // 代价要记账：模型从此看不到用户写的原始号码。当前没有"按手机号查订单"这类工具，
        // 掩码不影响任何能力；哪天有了，得给那个工具开一条白名单，而不是把入站脱敏撤掉。
        return chatClient.prompt(PiiRedactor.mask(message.text()))
                .advisors(spec -> spec
                        .param(AuthorizationAdvisor.PRINCIPAL_CONTEXT_KEY, message.principal())
                        .param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY,
                                SessionKeys.sessionIdOf(message.principal()))
                        .param(SessionMemoryAdvisor.USER_ID_CONTEXT_KEY, message.principal().userId()));
    }
}

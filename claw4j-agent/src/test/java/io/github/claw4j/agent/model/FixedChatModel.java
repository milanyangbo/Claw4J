package io.github.claw4j.agent.model;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Objects;

/**
 * 组件级测试里替换"模型这一环"的桩：不管问什么，都按指定文本回答。
 *
 * <p>它与 {@link ChainStubs} 是同一性质（那个替换 Advisor 链上的模型输出，
 * 这个替换 {@code AgentPort} 链上的），都<b>不是</b>被删掉的 {@code ScriptedChatModel}：
 * 那个靠脚本编排"模型第几轮调哪个工具"来假装端到端，这个只用在
 * "这一趟要验的东西根本不在模型身上"的用例里——模型在这里只是个文本来源。
 *
 * <p><b>使用边界，越界就是悄悄退回假模型</b>：凡是要验"模型会不会调工具、算不算得出数"
 * 的用例一律走 {@link RealModelSupport}；只有验的是 AgentPort / Advisor / 日志这一层、
 * 而模型只是把一段已知文本送进链路时才用它。
 *
 * <p>它存在的理由具体而窄：出站脱敏的判据是"模型输出里的号码被脱敏了，且出站日志
 * 打的是脱敏后的文本"。号码若由用户消息提供，入站脱敏会先把它掩码，
 * 于是断言被入站那一层满足，出站脱敏等于没验到；号码必须由"模型这一轮说了什么"提供，
 * 而真模型不会稳定地凭空造一个指定号码——这正是需要这个桩的那一处。
 */
public final class FixedChatModel implements ChatModel {

    private final String reply;

    private FixedChatModel(String reply) {
        this.reply = Objects.requireNonNull(reply, "reply");
    }

    public static FixedChatModel replying(String reply) {
        return new FixedChatModel(reply);
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return responseOf(reply);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.just(responseOf(reply));
    }

    /**
     * 不能返回 {@code null}：{@code ChatClient} 装配请求时会直接 {@code getOptions().mutate()}，
     * 返回 null 就是 NPE，而且 NPE 发生在框架里、看不出是这个桩的问题。
     * 这里给一份空 options——不设模型、不设温度，因为这段文本本来就不是模型算出来的。
     */
    @Override
    public ChatOptions getOptions() {
        return ToolCallingChatOptions.builder().build();
    }

    private static ChatResponse responseOf(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}

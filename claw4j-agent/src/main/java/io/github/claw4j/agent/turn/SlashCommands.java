package io.github.claw4j.agent.turn;

import io.github.claw4j.agent.approval.ElevatedCommand;
import io.github.claw4j.agent.approval.Elevations;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.principal.Principal;

import java.util.Optional;

/**
 * 斜杠命令：不进模型、当场办、回一句话。
 *
 * <p>这条路与「跑一轮模型」是<b>两条路</b>，所以从门面里抽出来单独一处。这里只负责认一遍、
 * 并给出「这一条到底要不要去模型」。
 *
 * <p>抽出来的原因是有过一次不对称：同一套判断在 {@code reply} 与 {@code stream} 里各写一遍，
 * 于是 <b>流式入口上漏了 {@code /elevated}</b>——用户走 SSE 发 {@code /elevated full}，那句话被
 * 当成一个普通问题送去模型，模型只能猜。同一套判断只有一个落点，这类不对称就没有第二次机会。
 *
 * <p>2026-11-25 起只剩 {@code /elevated} 一条（票 03）：{@code /new} 与 {@code /reset} 下线，
 * 它们现在与任何一句普通正文一样进模型。下线的原因不在"命令太多"，而在它做的事与名字不符——
 * 它删的是整条会话行，不是历史（框架没有"只删事件"的接口）。清空上下文现在只由压缩负责。
 */
final class SlashCommands {

    /**
     * 解析结果：要送给模型的文本，以及要不要就地回一句。
     *
     * @param text 去掉命令之后的内容
     * @param reply 非空表示这一条不进模型，直接回它；为空表示照常跑一轮
     */
    record Parsed(String text, Optional<String> reply) {
    }

    /** 提权档（票 06）；没有时为 {@code null}，等同于"这一族命令没开"。 */
    private final Elevations elevations;

    /**
     * @param elevations 提权状态，可以为 null
     */
    SlashCommands(Elevations elevations) {
        this.elevations = elevations;
    }

    /**
     * 认一遍命令。
     *
     * @param message 入站消息
     * @param conversationKey 这一条对话的键，{@code /elevated} 改的就是它
     * @return 解析结果；{@code reply} 为空表示这一条要去模型
     */
    Parsed parse(InboundMessage message, String conversationKey) {
        String text = message.text() == null ? "" : message.text();
        Optional<ElevatedCommand.Invocation> elevated = ElevatedCommand.parse(text);
        if (elevated.isPresent()) {
            return new Parsed(text,
                    Optional.of(elevatedReply(elevated.get(), message.principal(), conversationKey)));
        }
        return new Parsed(text, Optional.empty());
    }

    /**
     * 处理 {@code /elevated}。
     *
     * @param invocation 已解析出的调用
     * @param principal 发起人
     * @param conversationKey 这一条对话的键
     * @return 要回给用户的那句话
     */
    private String elevatedReply(ElevatedCommand.Invocation invocation, Principal principal,
                                 String conversationKey) {
        if (elevations == null) {
            return "这个部署没有开提权（claw4j.tools.elevated.enabled=false），命令只能跑在配置好的位置。";
        }
        return ElevatedCommand.execute(invocation, elevations, principal, conversationKey);
    }
}

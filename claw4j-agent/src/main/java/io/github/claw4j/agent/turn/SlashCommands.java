package io.github.claw4j.agent.turn;

import io.github.claw4j.agent.approval.ElevatedCommand;
import io.github.claw4j.agent.approval.Elevations;
import io.github.claw4j.agent.goal.GoalCommand;
import io.github.claw4j.agent.goal.SessionGoalStore;
import io.github.claw4j.agent.session.SessionCommand;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.principal.Principal;

import java.util.Optional;

/**
 * 斜杠命令：不进模型、当场办、回一句话。
 *
 * <p>这条路与「跑一轮模型」是<b>两条路</b>，所以从门面里抽出来单独一处。三个命令各有自己的解析器
 * （{@link SessionCommand}、{@link GoalCommand}、{@link ElevatedCommand}），这里只负责按顺序问
 * 一遍、并给出「这一条到底要不要去模型」。
 *
 * <p>抽出来之前的代价是实打实的：同一套判断在 {@code reply} 与 {@code stream} 里各写一遍，
 * 于是 <b>流式入口上漏了 {@code /elevated}</b>——用户走 SSE 发 {@code /elevated full}，那句话被
 * 当成一个普通问题送去模型，模型只能猜。同一套判断只有一个落点，这类不对称就没有第二次机会。
 *
 * <p>命令与 {@code /new} 同层：认出来就当场办，不开 run、不写事件、不调模型。OpenClaw 对纯命令
 * 消息的处理也是绕过队列与模型的（{@code docs/concepts/queue.md}）。
 */
final class SlashCommands {

    /**
     * 解析结果：剥掉命令之后要送给模型的文本、这一句是否要求换段、以及要不要就地回一句。
     *
     * @param text 去掉命令之后的内容；{@code /new 帮我查华南} 的这一项是「帮我查华南」
     * @param reset 这一句是否换段（{@code /new} 或 {@code /new ...}）
     * @param reply 非空表示这一条不进模型，直接回它；为空表示照常跑一轮
     */
    record Parsed(String text, boolean reset, Optional<String> reply) {
    }

    /** 换段靠它：{@code /new} 是这里唯一会改变会话状态的动作。 */
    private final SessionAddress address;

    /** 会话目标（ADR-0027）。为 null 表示没开目标存储：{@code /goal} 那族命令会说明用不了。 */
    private final SessionGoalStore goals;

    /** 这一段会话的提权档（票 06）；没有时为 {@code null}，等同于"这一族命令没开"。 */
    private final Elevations elevations;

    /**
     * @param address 会话定位
     * @param goals 目标存储，可以为 null
     * @param elevations 提权状态，可以为 null
     */
    SlashCommands(SessionAddress address, SessionGoalStore goals, Elevations elevations) {
        this.address = address;
        this.goals = goals;
        this.elevations = elevations;
    }

    /**
     * 认一遍命令。按 {@code /new} → {@code /goal} → {@code /elevated} 的顺序问，与它们的
     * 前缀长度无关：三者前缀互不为前缀，顺序只影响读代码的顺眼程度。
     *
     * @param message 入站消息
     * @param owner 归属键，{@code /new} 换段要用
     * @param conversationKey 会话节点键，目标与提权都挂在它上面（不含段）
     * @return 解析结果；{@code reply} 为空表示这一条要去模型
     */
    Parsed parse(InboundMessage message, String owner, String conversationKey) {
        SessionCommand.Parsed parsed = SessionCommand.parse(message.text());
        if (parsed.bareCommand()) {
            // 光发了一个 /new：换段，回一句确认。不开 run、不调模型：这一轮除了「新的开始了」
            // 没有别的信息，一次模型调用不值。它在 lane 里被调用，所以与同一对话的另一条消息
            // 不会各换一段。
            address.rotate(message, owner);
            return new Parsed(parsed.text(), parsed.reset(), Optional.of(SessionCommand.ACK));
        }
        Optional<GoalCommand> goal = GoalCommand.parse(parsed.text());
        if (goal.isPresent()) {
            return new Parsed(parsed.text(), parsed.reset(),
                    Optional.of(GoalCommand.execute(goal.get(), goals, conversationKey)));
        }
        Optional<ElevatedCommand.Invocation> elevated = ElevatedCommand.parse(parsed.text());
        if (elevated.isPresent()) {
            return new Parsed(parsed.text(), parsed.reset(),
                    Optional.of(elevatedReply(elevated.get(), message.principal(), conversationKey)));
        }
        return new Parsed(parsed.text(), parsed.reset(), Optional.empty());
    }

    /**
     * 处理 {@code /elevated}。
     *
     * @param invocation 已解析出的调用
     * @param principal 发起人
     * @param conversationKey 这一段会话的键
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

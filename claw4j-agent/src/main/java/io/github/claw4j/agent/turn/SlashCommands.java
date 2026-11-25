package io.github.claw4j.agent.turn;

import io.github.claw4j.agent.approval.ElevatedCommand;
import io.github.claw4j.agent.approval.Elevations;
import io.github.claw4j.agent.session.SessionCommand;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.principal.Principal;
import org.springframework.ai.session.SessionService;

import java.util.Optional;

/**
 * 斜杠命令：不进模型、当场办、回一句话。
 *
 * <p>这条路与「跑一轮模型」是<b>两条路</b>，所以从门面里抽出来单独一处。两个命令各有自己的解析器
 * （{@link SessionCommand}、{@link ElevatedCommand}），这里只负责按顺序问一遍、并给出
 * 「这一条到底要不要去模型」。
 *
 * <p>抽出来之前的代价是实打实的：同一套判断在 {@code reply} 与 {@code stream} 里各写一遍，
 * 于是 <b>流式入口上漏了 {@code /elevated}</b>——用户走 SSE 发 {@code /elevated full}，那句话被
 * 当成一个普通问题送去模型，模型只能猜。同一套判断只有一个落点，这类不对称就没有第二次机会。
 *
 * <p>命令与 {@code /new} 同层：认出来就当场办，不开 run、不写事件、不调模型。OpenClaw 对纯命令
 * 消息的处理也是绕过队列与模型的（{@code docs/concepts/queue.md}）。
 *
 * <p>2026-11-25 起 {@code /new} 不再换段（ADR-0026 修订）：它删掉这条会话的历史，id 不变。
 * 于是一个人的侧边栏不会因为"今天开了新对话"多出一行，而"刚才那些不会再被带进来"这件事
 * 仍然成立——只是换成了删除，不是换段。
 */
final class SlashCommands {

    /**
     * 解析结果：剥掉命令之后要送给模型的文本，以及要不要就地回一句。
     *
     * @param text 去掉命令之后的内容；{@code /new 帮我查华南} 的这一项是「帮我查华南」
     * @param reply 非空表示这一条不进模型，直接回它；为空表示照常跑一轮
     */
    record Parsed(String text, Optional<String> reply) {
    }

    /**
     * 会话存储。为 null 表示这个部署没装会话层：{@code /new} 会说明清不了。
     *
     * <p>存储不可用时让它抛，不在这里吞：与整条链同一个口径——会话能力不在，就不要假装清过。
     */
    private final SessionService sessions;

    /** 这一段会话的提权档（票 06）；没有时为 {@code null}，等同于"这一族命令没开"。 */
    private final Elevations elevations;

    /**
     * @param sessions 会话存储，可以为 null
     * @param elevations 提权状态，可以为 null
     */
    SlashCommands(SessionService sessions, Elevations elevations) {
        this.sessions = sessions;
        this.elevations = elevations;
    }

    /**
     * 认一遍命令。按 {@code /new} → {@code /elevated} 的顺序问，与它们的前缀长度无关：
     * 两者前缀互不为前缀，顺序只影响读代码的顺眼程度。
     *
     * @param message 入站消息
     * @param sessionId 这一轮的会话 id，{@code /new} 要删的就是它
     * @return 解析结果；{@code reply} 为空表示这一条要去模型
     */
    Parsed parse(InboundMessage message, String sessionId) {
        SessionCommand.Parsed parsed = SessionCommand.parse(message.text());
        if (parsed.bareCommand()) {
            // 光发了一个 /new：清历史，回一句确认。不开 run、不调模型：这一轮除了「清干净了」
            // 没有别的信息，一次模型调用不值。它在 lane 里被调用，所以与同一对话的另一条消息
            // 不会各删一次。
            return new Parsed(parsed.text(), Optional.of(clear(sessionId)));
        }
        if (parsed.reset()) {
            // /new 带内容：先清，再让这一句照常进模型。清在前是因为它必须对"这一句"生效——
            // 放到提示词拼好之后再清，模型这一轮读到的还是旧历史。
            clear(sessionId);
        }
        Optional<ElevatedCommand.Invocation> elevated = ElevatedCommand.parse(parsed.text());
        if (elevated.isPresent()) {
            return new Parsed(parsed.text(),
                    Optional.of(elevatedReply(elevated.get(), message.principal(), sessionId)));
        }
        return new Parsed(parsed.text(), Optional.empty());
    }

    /**
     * 删掉这条会话的全部历史。
     *
     * @param sessionId 会话 id
     * @return 要回给用户的那句话
     */
    private String clear(String sessionId) {
        if (sessions == null) {
            return "这个部署没有装会话存储（claw4j.persistence.enabled=false），没有历史可清。";
        }
        sessions.delete(sessionId);
        return SessionCommand.ACK;
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

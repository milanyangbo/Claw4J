package io.github.claw4j.agent.session;

import java.util.Locale;
import java.util.Set;

/**
 * 消息里的会话命令（ADR-0026 决定一的第三条途径）。
 *
 * <p>为什么是消息而不是接口：OpenClaw 的 reset 就是一条消息，{@code /new} 或 {@code /reset}
 * 之后剩余文本继续当这一轮的提问（{@code docs/concepts/session.md:279-283}）。
 * 三条通道（HTTP / 飞书 / 企微）因此自动都有，不用给每个通道注册斜杠命令，
 * {@code InboundMessage} 也不必加字段。
 *
 * <p>整词匹配，不是前缀：{@code /newer} 不是命令。用 {@code startsWith("/new")} 会让
 * "问一个新问题"被当成"清空上下文"，用户只看到"它怎么突然不记得了"，没有任何报错。
 *
 * <p>不做 {@code /new <model>}：OpenClaw 支持用它指定这一段用哪个模型
 * （{@code docs/concepts/session.md:279-280}）。本仓库的模型由部署配置加主备切换决定，
 * 按会话换模型会绕开成本口径与主备语义。
 */
public final class SessionCommand {

    /**
     * 单独发 {@code /new} 时的回答。
     *
     * <p>固定文案，不跑一轮模型。OpenClaw 会把确认语写进新会话
     * （{@code docs/concepts/session.md:66-67}），那一轮除了"新的开始了"没有别的信息，
     * 不值一次模型调用。重开条件：有人要求确认语接上一段的结尾。
     */
    public static final String ACK = "好，这是一段新对话。刚才那些不会再被带进来。";

    /** 认这两个，大小写不敏感。OpenClaw 两个都认（{@code docs/concepts/session.md:279}）。 */
    private static final Set<String> COMMANDS = Set.of("/new", "/reset");

    private SessionCommand() {
    }

    /**
     * 拆一条消息。
     *
     * @param text  这一轮真正要问的话。{@code /new 帮我查华南} 里它是"帮我查华南"——
     *              命令之后剩下的部分才进模型，命令本身不进去
     * @param reset 这一条是不是要求"换一段"
     */
    public record Parsed(String text, boolean reset) {

        /** 光发了一个命令，没有下文。 */
        public boolean bareCommand() {
            return reset && text.isEmpty();
        }
    }

    /**
     * 从消息正文里认命令，返回要交给模型的正文与是否换段。
     *
     * @param text 用户这一条消息的原文，可以为 null
     * @return 拆开的正文与换段标记；不是命令时正文原样返回
     */
    public static Parsed parse(String text) {
        String raw = text == null ? "" : text;
        String stripped = raw.strip();
        if (!stripped.startsWith("/")) {
            return new Parsed(raw, false);
        }
        int split = firstWhitespace(stripped);
        String head = (split < 0 ? stripped : stripped.substring(0, split)).toLowerCase(Locale.ROOT);
        if (!COMMANDS.contains(head)) {
            // 不是命令就当普通问题：/newer 怎么说、/etc 的权限 都要原样进模型。
            return new Parsed(raw, false);
        }
        String rest = split < 0 ? "" : stripped.substring(split).strip();
        return new Parsed(rest, true);
    }

    private static int firstWhitespace(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        return -1;
    }
}

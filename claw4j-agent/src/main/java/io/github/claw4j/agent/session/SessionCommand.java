package io.github.claw4j.agent.session;

import java.util.Locale;
import java.util.Set;

/**
 * 消息里的会话命令（ADR-0026 决定一的第三条途径）。
 *
 * <p><b>为什么是消息，不是接口</b>：OpenClaw 的 reset 就是一条消息——{@code /new} 或 {@code /reset}
 * "start a fresh session id and <b>pass the remainder of the message through</b>"。
 * 照它做有三件好处：用户不必"先点一下再问一次"；解析放在 agent 侧，所以三条通道
 * （HTTP / 飞书 / 企微）自动都有，不用给每个通道注册斜杠命令；它不需要穿过各层
 * （{@code InboundMessage} 不加字段、控制器不加参数）。
 *
 * <p><b>整词匹配，不是前缀</b>：{@code /newer} 不是命令。这一条看起来琐碎，但
 * {@code startsWith("/new")} 会让"问一个新问题"被当成"清空上下文"——用户看到的是
 * "它怎么突然不记得了"，而没有任何报错。
 *
 * <p><b>不做 {@code /new <model>}</b>：OpenClaw 能用它指定这一段用哪个模型。我们的模型是
 * 部署配置加主备切换，按会话换模型会绕开成本口径与主备语义。重开条件写在票面里。
 */
public final class SessionCommand {

    /**
     * 单独发 {@code /new} 时的回答。
     *
     * <p><b>固定文案，不跑一轮模型</b>：OpenClaw 这时会跑一个 hello 轮次。那一轮除了
     * "好，新的开始了"没有别的信息，一次模型调用的价值不抵成本。重开条件：有人要求
     * 确认语要接上一段的结尾。
     */
    public static final String ACK = "好，这是一段新对话。刚才那些不会再被带进来。";

    /** 认这两个，大小写不敏感。OpenClaw 两个都认，我们照做。 */
    private static final Set<String> COMMANDS = Set.of("/new", "/reset");

    private SessionCommand() {
    }

    /**
     * 拆一条消息。
     *
     * @param text  这一轮真正要问的话。{@code /new 帮我查华南} 里它是"帮我查华南"——
     *              命令之后剩下的那部分才进模型，命令本身不进去
     * @param reset 这一条是不是要求"换一段"
     */
    public record Parsed(String text, boolean reset) {

        /** 光发了一个命令，没有下文。 */
        public boolean bareCommand() {
            return reset && text.isEmpty();
        }
    }

    public static Parsed parse(String text) {
        String raw = text == null ? "" : text;
        String stripped = raw.strip();
        if (!stripped.startsWith("/")) {
            return new Parsed(raw, false);
        }
        int split = firstWhitespace(stripped);
        String head = (split < 0 ? stripped : stripped.substring(0, split)).toLowerCase(Locale.ROOT);
        if (!COMMANDS.contains(head)) {
            // 不是命令就当普通问题：`/newer 怎么说` 与 `/etc 的权限` 都要原样进模型。
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

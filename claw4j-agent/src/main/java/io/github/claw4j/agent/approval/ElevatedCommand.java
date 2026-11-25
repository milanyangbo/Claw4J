package io.github.claw4j.agent.approval;

import io.github.claw4j.domain.principal.Elevation;
import io.github.claw4j.domain.principal.Principal;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

/**
 * 消息里的 {@code /elevated} 命令（ADR-0030 裁决六）。
 *
 * <p>与已经下线的 {@code /new} 同层：做成消息而不是通道侧注册的命令，三条通道自动都有；
 * 命令本身不进提示词、不占一次模型调用。
 *
 * <p>四个词：{@code on} / {@code off} / {@code ask} / {@code full}。{@code ask} 是 {@code on}
 * 的别名（OpenClaw 文档里两者同义），不带参数时回一句"现在是哪一档"。
 *
 * <p><b>三重门，缺一条就拒</b>：
 * <ol>
 *   <li>部署开着（{@code claw4j.tools.elevated.enabled}）；</li>
 *   <li>发送者在 {@code allow-from} 里——名单为空时谁都不许；</li>
 *   <li>只改这一段会话（键是会话目标键）。</li>
 * </ol>
 * 这不是"多一层小心"：{@code full} 的效果是"这一段不问了"，任何能发消息的人都发得出来的话，
 * 审批那一层就等于不存在。
 */
public final class ElevatedCommand {

    /** 命令词。整词匹配：{@code /elevateds} 不是命令。 */
    public static final String HEAD = "/elevated";

    private static final DateTimeFormatter UNTIL = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private ElevatedCommand() {
    }

    /**
     * 一条命令。
     *
     * @param level 要设成的档；{@code null} 表示"没给档位"——可能是裸命令，也可能是词不认识
     * @param word  用户写的那个词，没写时为 {@code null}
     */
    public record Invocation(Elevation level, String word) {

        /** 写了词但认不出来。 */
        public boolean unknownWord() {
            return level == null && word != null;
        }
    }

    /**
     * 拆一条消息。
     *
     * @param text 消息原文，可以为 {@code null}
     * @return 命令；空表示这条消息不是 {@code /elevated} 命令，它该原样进模型
     */
    public static Optional<Invocation> parse(String text) {
        String stripped = (text == null ? "" : text).strip();
        if (!stripped.startsWith(HEAD)) {
            return Optional.empty();
        }
        if (stripped.length() > HEAD.length() && !Character.isWhitespace(stripped.charAt(HEAD.length()))) {
            // /elevateds、/elevatedfoo：前缀匹配会把普通问题当成命令，用户看到的却是"它没回答我"。
            return Optional.empty();
        }
        String rest = stripped.substring(HEAD.length()).strip();
        if (rest.isEmpty()) {
            return Optional.of(new Invocation(null, null));
        }
        int split = rest.indexOf(' ');
        String head = (split < 0 ? rest : rest.substring(0, split)).toLowerCase(Locale.ROOT);
        return Optional.of(new Invocation(Elevation.parse(head).orElse(null), head));
    }

    /**
     * 执行一条命令，回一句话。
     *
     * <p>所有"不"都在这里说清楚：不认识这个词、部署没开、人不在名单里。三种回答不一样，
     * 因为要改的地方不一样——改词、改部署、找管理员。
     *
     * @param invocation      命令
     * @param elevations      状态
     * @param principal       发起人
     * @param conversationKey 这一段会话的键
     * @return 回给用户的一句话
     */
    public static String execute(Invocation invocation, Elevations elevations, Principal principal,
                                 String conversationKey) {
        if (!elevations.allows(principal)) {
            return elevations.enabled()
                    ? "你不在提权名单里（claw4j.tools.elevated.allow-from）。这一步要找人开，不能自己批自己。"
                    : "这个部署没有开提权（claw4j.tools.elevated.enabled=false），命令只能跑在配置好的位置。";
        }
        if (invocation.level() == null) {
            // 裸命令是"看当前档"，写了认不出的词才是用法提示。
            return invocation.unknownWord() ? usage() : current(elevations, conversationKey);
        }
        Instant until = elevations.set(conversationKey, invocation.level());
        return switch (invocation.level()) {
            case OFF -> "好，这一段回到不提权：命令按部署配置跑，审批照常问。";
            case ON -> "好，这一段跑到沙箱外，但审批照旧"
                    + (until == null ? "。" : "（到 " + UNTIL.format(until) + " 自动失效）。");
            case FULL -> "好，这一段跳过审批" + (until == null ? "。" : "（到 " + UNTIL.format(until) + " 自动失效）。")
                    + "只对这一条对话有效。";
        };
    }

    private static String usage() {
        return "认不出这个词。用法：/elevated on|off|ask|full（不带参数看当前档）。";
    }

    private static String current(Elevations elevations, String conversationKey) {
        Elevation level = elevations.level(conversationKey);
        return "这一段现在是 " + level.id() + "。";
    }
}

package io.github.claw4j.agent.session;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 会话键的 peer 段怎么拼（ADR-0029）。
 *
 * <p>两件事分开配：私聊的边界（{@link #dmScope()}）与群聊的边界（{@link #groupScope()}）。
 * 它这么分是因为两者的默认值本来就该不同——私聊默认收进一个人的主会话，群聊默认按群隔开。
 * 一个键管两件事的话，只有一种组合能被表达出来。
 *
 * <p>默认值与 OpenClaw 同值（{@code docs/concepts/session.md:158-166} 与 {@code :191-200}）：
 * 私聊 {@code main}、群聊 {@code per-group}。
 *
 * <p>代价要认清：{@code main} 意味着同一个人的所有私聊共用一段上下文。单用户部署这是便利，
 * 多人可及的部署里它等于串话——网页里问到的数字，飞书私聊里的模型也看得见。所以生效值在启动
 * 日志里打一行，多用户部署必须显式配 {@code per-channel-peer}。
 *
 * @param dmScope    私聊的分段方式，见 {@link #DM_MAIN} 等四档
 * @param groupScope 群聊的分段方式，见 {@link #GROUP_PER_GROUP} 与 {@link #DM_MAIN}
 */
@ConfigurationProperties("claw4j.session")
public record SessionScopeProperties(String dmScope, String groupScope) {

    /** 一个人的所有私聊共用一个主会话。默认，与它同值。 */
    public static final String DM_MAIN = "main";

    /** 按对端身份分：同一个人跨通道共用一个。 */
    public static final String DM_PER_PEER = "per-peer";

    /** 按通道 + 对端分：换一条通道就是另一段。 */
    public static final String DM_PER_CHANNEL_PEER = "per-channel-peer";

    /**
     * 按账号 + 通道 + 对端分。
     *
     * <p>本仓库没有 account 这一维（一个通道实例只配一个机器人），所以它与
     * {@link #DM_PER_CHANNEL_PEER} 等价。保留这一档是为了让配置文件能从它的文档直接抄过来，
     * 抄过来之后行为还能对上，而不是报"认不出"。
     */
    public static final String DM_PER_ACCOUNT_CHANNEL_PEER = "per-account-channel-peer";

    /** 群聊按群隔离。默认，与它同值。 */
    public static final String GROUP_PER_GROUP = "per-group";

    public SessionScopeProperties {
        dmScope = normalizeDmScope(dmScope);
        groupScope = normalizeGroupScope(groupScope);
    }

    /** 全部走默认：私聊并进主会话，群聊按群。 */
    public static SessionScopeProperties defaults() {
        return new SessionScopeProperties(null, null);
    }

    /**
     * 私聊的 peer 段。
     *
     * @param channel 通道名，缺省已由调用方落成 {@code '-'}
     * @param chatId  通道里的地址，缺省同上
     * @return 要拼进会话键的那一段
     */
    public String directPeerOf(String channel, String chatId) {
        return switch (dmScope) {
            case DM_PER_PEER -> chatId;
            case DM_PER_CHANNEL_PEER, DM_PER_ACCOUNT_CHANNEL_PEER -> channel + ":" + chatId;
            default -> SessionKeys.MAIN_PEER;
        };
    }

    /**
     * 群聊的 peer 段。
     *
     * @param channel 通道名，缺省已由调用方落成 {@code '-'}
     * @param chatId  通道里的地址，缺省同上
     * @return 要拼进会话键的那一段
     */
    public String groupPeerOf(String channel, String chatId) {
        return DM_MAIN.equals(groupScope) ? SessionKeys.MAIN_PEER : channel + ":" + chatId;
    }

    /**
     * 认不出的值落到 {@code main}，与它一致。
     *
     * <p>不像 {@code session.maintenance.mode} 那样让拼错启动失败：那一档的两侧是"删数据"与
     * "不删数据"，错的方向必须选得出来；这一档的两侧只是"合并上下文"与"分开上下文"，
     * 而默认侧（合并）就是它的默认，照抄它不会更意外。代价是拼错不会被告知，所以启动日志
     * 打的是**生效值**而不是配置值——写了 {@code per-channel-pee} 的人会看到日志里是
     * {@code main}。
     */
    private static String normalizeDmScope(String value) {
        if (value == null || value.isBlank()) {
            return DM_MAIN;
        }
        String trimmed = value.trim();
        return switch (trimmed) {
            case DM_MAIN, DM_PER_PEER, DM_PER_CHANNEL_PEER, DM_PER_ACCOUNT_CHANNEL_PEER -> trimmed;
            default -> DM_MAIN;
        };
    }

    /** 认不出的值落到 {@code per-group}，理由同 {@link #normalizeDmScope(String)}。 */
    private static String normalizeGroupScope(String value) {
        if (value == null || value.isBlank()) {
            return GROUP_PER_GROUP;
        }
        String trimmed = value.trim();
        return DM_MAIN.equals(trimmed) ? DM_MAIN : GROUP_PER_GROUP;
    }
}

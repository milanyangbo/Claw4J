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
 * 多人可及的部署里它等于串话——网页里问到的数字，飞书私聊里的模型也看得见。这一条不再只写在
 * 注释里（ADR-0032）：生效值进启动日志（{@code SessionMemoryConfiguration}），而"多个人可及 +
 * {@code main}"由 {@code claw4j-app} 的 {@code SessionBoundaryCheck} 直接拒绝启动。
 *
 * @param dmScope    私聊的分段方式，见 {@link #DM_MAIN} 等四档
 * @param groupScope 群聊的分段方式，见 {@link #GROUP_PER_GROUP} 与 {@link #DM_MAIN}
 */
@ConfigurationProperties("claw4j.advisor.session")
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
        // 定时任务不是「谁的私聊」，不参与 dmScope（见 SessionKeys#CRON_PREFIX）。
        // 落到的这一段与 per-channel-peer 同形，于是节点键恰好等于它会话键去掉末尾那个
        // 触发时刻——同一个任务的所有触发共用一行「当前状态」，而不是每次新建一行。
        if (SessionKeys.isSelfAddressed(chatId)) {
            return channel + ":" + chatId;
        }
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
     * 认不出的值直接抛，让启动失败。
     *
     * <p>不落回 {@code main}：这一档两侧的后果不对称——{@code main} 把一个人的所有私聊并成一段
     * 上下文，多人可及的部署里就是串话；而拼错的人恰好以为自己配的是"分开"。静默落回默认值等于
     * 把"我配了"变成"其实没配"，且没有任何东西会说一声（ADR-0032）。
     *
     * <p>"没配"仍然是 {@code main}（对齐 ADR-0029 与上游默认值）；它可不可接受由装配层判：
     * {@code claw4j-app} 的 {@code SessionBoundaryCheck} 拿准入名单的并集与它对照，
     * 多个人共用一段就拒绝启动。
     *
     * @param value 配置里写的值
     * @return 认得的那个值；{@code null} 或空白返回 {@code main}
     * @throws IllegalArgumentException 写了值但不在四档之内
     */
    private static String normalizeDmScope(String value) {
        if (value == null || value.isBlank()) {
            return DM_MAIN;
        }
        String trimmed = value.trim();
        return switch (trimmed) {
            case DM_MAIN, DM_PER_PEER, DM_PER_CHANNEL_PEER, DM_PER_ACCOUNT_CHANNEL_PEER -> trimmed;
            default -> throw new IllegalArgumentException("claw4j.advisor.session.dm-scope 只接受 "
                    + DM_MAIN + " / " + DM_PER_PEER + " / " + DM_PER_CHANNEL_PEER + " / "
                    + DM_PER_ACCOUNT_CHANNEL_PEER + "，收到的是「" + trimmed + "」。"
                    + "拼错不再静默落回 " + DM_MAIN + "：那会把多个人的私聊并成一段（ADR-0032）。");
        };
    }

    /**
     * 认不出的值落到 {@code per-group}。
     *
     * <p>与 {@link #normalizeDmScope(String)} 不同：这一档错的方向是安全的——分得开。
     * "群并进主会话"必须显式写 {@code main}，它不是一个会自己发生的默认值（ADR-0032 只拦 dm-scope）。
     */
    private static String normalizeGroupScope(String value) {
        if (value == null || value.isBlank()) {
            return GROUP_PER_GROUP;
        }
        String trimmed = value.trim();
        return DM_MAIN.equals(trimmed) ? DM_MAIN : GROUP_PER_GROUP;
    }
}

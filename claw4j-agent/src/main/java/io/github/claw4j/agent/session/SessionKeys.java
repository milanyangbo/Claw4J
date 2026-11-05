package io.github.claw4j.agent.session;

import io.github.claw4j.agent.PermissionFingerprint;
import io.github.claw4j.domain.principal.Principal;

import java.util.Objects;

/**
 * 会话 id 怎么来。两把键，管两件事，不要混。
 *
 * <p><b>{@link #ownerOf} = 归属键</b>：{@code userId@权限指纹}。run 的 owner、
 * 审计、定时任务的归属都用它。回答的是"这是谁的东西"。
 *
 * <p><b>{@link #sessionIdOf} = 对话键</b>：{@code 前缀 + owner + 通道 + chatId + 段}。
 * 只用于对话历史（记忆 Advisor 的 sessionId）。回答的是"这是哪一段话"。
 *
 * <p><b>段是对话键的第三件事</b>（ADR-0026）：前两件回答"谁的""哪条通道的哪个地址"，
 * 都是<b>不随时间变</b>的；段回答"第几世"，由 {@link ConversationPointers} 按
 * 跨天 / 空闲轮换。没有段的时候，"一段对话"没有结束这个动作——记忆永不换新，
 * 预算只能自己按空闲猜一套，用户也没有"开一段新对话"的手段。
 * 段的形状与判定不在这两个方法里：这里只负责拼，判定在
 * {@link ConversationPointers}，因为判定要读库。
 *
 * <p><b>为什么必须分开</b>：同一个人在网页私聊里问"华东成交额"答了 128,400，
 * 十分钟后在项目群里问"合同违约金怎么算"。如果两段共用一个会话，
 * 群里有二十个人，其中三个没有华东数据的权限——模型只要顺嘴引用一次那个数字，
 * 就是一次没人复核的泄露。权限指纹挡不住这一层：它保证"权限一样才共享"，
 * 而这里两个人的权限一样，<b>听众不一样</b>。
 *
 * <p><b>为什么不只按 chatId、还要带通道</b>：{@code chatId} 是通道侧寻址键，
 * 不同通道各自编号，撞车是常事（飞书的 {@code oc_xxx} 与网页的某个 id 完全可能相同）。
 * 带上通道之后，"哪条通道的哪一段会话"才是唯一的。这与 OpenClaw 的
 * session key（{@code agent:<agentId>:<channel>:<peer>}）是同一个形状，
 * 它的 {@code dmScope: per-channel-peer} 就是"按通道 + 对端分开"的档位，
 * 也是它文档里对多发送者场景的推荐值。
 *
 * <p><b>代价要记账</b>：同一个人从网页切到飞书私聊，讲过的口径要重讲一遍。
 * 这是照 OpenClaw 的默认行为选的——它把"跨通道共享"做成显式配置（{@code identityLinks}），
 * 默认不共享。共享是声明出来的，不是猜出来的。Claw4J 现在没有这个配置项，
 * 所以这条路上的行为就是"换通道 = 换一段对话"，与"换人就断开"是同一个方向的安全取舍。
 *
 * <p><b>权限指纹仍然在会话键里</b>：会话记忆是"把上一次的答案原样带进下一次提示词"。
 * 一个人被降权后，若历史里躺着降权前查出来的数字，模型会直接从上下文里念出来——
 * 不查库、不鉴权、没有任何一处报错，因为记忆读取本来就早于工具调用。
 * 掺进指纹之后，权限变了就是另一段会话：他会"什么都想不起来"，而不是"还记得不该记的"。
 *
 * <p><b>不用角色名当指纹</b>：角色名会改、会复用、也可能两个角色其实是同一份权限。
 * 按 {@code tables + actions} 算出来的指纹只认"能看见什么"，这份判定与语义缓存
 * （{@code CacheAdvisor}）用的是同一个口径，两处不会打架。
 *
 * <p>前缀不是装饰：会话表里还可能存别的命名空间（按工具、按技能），
 * 裸 id 进去之后，两种主键混在一张表里就没法分辨了。
 */
public final class SessionKeys {

    /** 会话 id 的命名空间。换会话维度时改这里加后缀，不要在这里去掉通道与会话 id。 */
    public static final String PREFIX = "claw4j:user:";

    /** 权限指纹与 userId 之间的分隔。库里 {@code ai_session.user_id} 存的就是这个形状。 */
    static final String OWNER_SEPARATOR = "@";

    /** 通道与 chatId 之间的分隔，以及缺 chatId 时的占位。 */
    static final String SESSION_SEPARATOR = ":";

    /** 没有 chatId 的通道（脚本、内部调用）落在这里：同一通道内共享，跨通道不共享。 */
    static final String NO_CHAT = "-";

    private SessionKeys() {
    }

    /**
     * 归属键：{@code userId@权限指纹}。会话表的 {@code user_id} 列、run 的归属、
     * 审计、定时任务都认它，召回不认——召回按会话查（见 {@link RecallSessionTool}）。
     *
     * <p>它仍然要掺权限指纹：会话表的 {@code user_id} 存的就是它，而会话内容是按权限隔离的；
     * 写裸 userId 会让"降权之后什么都想不起来"这条在存储这一层失效——
     * 两段会话落在同一个 user_id 下，将来任何一条按 user_id 汇总的查询都会把它们混在一起。
     *
     * @throws IllegalArgumentException userId 为空时。空的后果不是"存不下"，
     *                                  而是所有人共用一个会话——那比没有记忆更糟，
     *                                  因为它看起来是记得住的，只是记得是别人的。
     */
    public static String ownerOf(Principal principal) {
        Objects.requireNonNull(principal, "principal");
        String userId = principal.userId();
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException(
                    "Principal.userId 不能为空：会话 id 由它派生，空了等于所有人共享一个会话");
        }
        return userId + OWNER_SEPARATOR + PermissionFingerprint.shortOf(principal);
    }

    /**
     * 对话键：{@code claw4j:user:<owner>:<通道>:<chatId>}。
     *
     * <p>拆出 {@link #ownerOf} 是为了让调用方只算一次指纹：一轮请求里会话 id 与
     * {@code user_id} 都要用，各算一遍的话，指纹口径一改就可能只改到一半——
     * 表现是"记得住，但召回查不到"。
     *
     * @param owner   必须来自 {@link #ownerOf}，不要自己拼 {@code userId@x}
     * @param channel 这条消息从哪来（{@code InboundMessage#channel()}）
     * @param chatId  这一段对话在通道里的地址（{@code InboundMessage#chatId()}）；
     *                为空时落到 {@link #NO_CHAT}，表示"这条通道上不区分会话"
     */
    public static String sessionIdOf(String owner, String channel, String chatId) {
        Objects.requireNonNull(owner, "owner");
        return PREFIX + owner
                + SESSION_SEPARATOR + orPlaceholder(channel)
                + SESSION_SEPARATOR + orPlaceholder(chatId);
    }

    /**
     * 带段的对话键：{@code claw4j:user:<owner>:<通道>:<chatId>:<段>}（ADR-0026）。
     *
     * <p>段是"这段对话的第几世"。它由 {@link ConversationPointers} 按时间给（跨天或空闲
     * 就换一个），所以 <b>{@code sessionId} 从"一段对话的固定坐标"变成了"某一次的坐标"</b>：
     * 换段之后记忆、预算、召回全都从零开始，而旧段还在库里只读。
     *
     * <p><b>空段 = 老形状</b>，这一支是刻意留的，只给两处用：审批续跑读不到 run 上的
     * {@code session_id} 时的回落，以及段上线之前就存在的那些会话。它们<b>只读不写</b>——
     * 新的一轮永远带段，因为不带段的话，换段就等于没发生过。
     *
     * <p><b>段拼在最后，不拼在 owner 前面</b>：召回的身份校验是
     * {@code sessionId.startsWith(PREFIX + owner + ":")}（{@code RecallSessionTool}）。
     * 拼在前面会让一个人的历史对另一个人成立，而那是一条静默的越权读。
     *
     * @param segment 空或 null 表示"不加段"（见上）
     */
    public static String sessionIdOf(String owner, String channel, String chatId, String segment) {
        String base = sessionIdOf(owner, channel, chatId);
        return segment == null || segment.isBlank() ? base : base + SESSION_SEPARATOR + segment;
    }

    /** 便捷入口：与三参版对称，指纹只算一次。 */
    public static String sessionIdOf(Principal principal, String channel, String chatId,
                                     String segment) {
        return sessionIdOf(ownerOf(principal), channel, chatId, segment);
    }

    /** 便捷入口：一轮请求里 owner 与 sessionId 都要算，指纹只算一次。 */
    public static String sessionIdOf(Principal principal, String channel, String chatId) {
        return sessionIdOf(ownerOf(principal), channel, chatId);
    }

    private static String orPlaceholder(String part) {
        return part == null || part.isBlank() ? NO_CHAT : part.trim();
    }

    /**
     * 缺省落占位。给 {@link ConversationPointers} 与 {@code ConversationLane} 用：
     * 它们要拿同样规整过的三样去查指针、拼锁键，两边对 {@code null} 与空串的处理必须一致——
     * 否则 {@code sessionId} 里的 {@code "-"} 与指针行里的 {@code ""} 会是同一条对话的两个身份，
     * 而锁键上差一个字符就是两把不同的锁。
     */
    public static String placeholder(String part) {
        return orPlaceholder(part);
    }
}

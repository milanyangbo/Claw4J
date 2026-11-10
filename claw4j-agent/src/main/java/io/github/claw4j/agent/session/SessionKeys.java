package io.github.claw4j.agent.session;

import io.github.claw4j.agent.PermissionFingerprint;
import io.github.claw4j.domain.principal.Principal;

import java.util.Objects;

/**
 * 会话 id 的拼法。两把键各管一件事。
 *
 * <p>{@link #ownerOf} 是归属键：{@code userId@权限指纹}。run 的 owner、审计、定时任务的归属
 * 都用它，回答"这是谁的东西"。
 *
 * <p>{@link #sessionIdOf} 是对话键：{@code 前缀 + owner + 通道 + chatId + 段}，只用于对话历史
 * （记忆 Advisor 的 sessionId），回答"这是哪一段话"。
 *
 * <p>段是对话键的第三件事（ADR-0026）：前两件回答"谁的""哪条通道的哪个地址"，都不随时间变；
 * 段回答"第几世"，由 {@link SessionEntries} 按跨天 / 空闲轮换。没有段的时候，一段对话没有
 * 结束这个动作，用户也没有"开一段新对话"的手段。判定要读库，所以放在 {@link SessionEntries}。
 *
 * <p>两把键必须分开：同一个人在网页私聊里问到的数字，十分钟后在二十人的项目群里被念一次，
 * 就是一次没人复核的泄露。权限指纹挡不住这一层——它保证"权限一样才共享"，
 * 而这里两个人的权限一样，听众不一样。
 *
 * <p>对话键还要带通道：{@code chatId} 是通道侧寻址键，不同通道各自编号，撞车是常事。
 * 形状与 OpenClaw 的 session key 同构（{@code agent:<agentId>:<channel>:<peer>}，
 * {@code docs/channels/whatsapp.md:140}），对应它的 {@code session.dmScope: per-channel-peer}
 * （{@code docs/concepts/session.md:164}）。
 *
 * <p>代价：同一个人从网页切到飞书私聊，讲过的口径要重讲一遍。OpenClaw 把跨通道共享做成显式
 * 配置 {@code session.identityLinks}（{@code docs/concepts/session.md:172-176}），
 * 本仓库没有这个配置项，所以换通道等于换一段对话。
 *
 * <p>权限指纹仍然在对话键里：记忆是把上一次的答案原样带进下一次提示词。降权之后若历史里躺着
 * 降权前查出的数字，模型会直接从上下文里念出来——不查库、不鉴权、不报错，因为记忆读取早于
 * 工具调用。掺进指纹后，权限变了就是另一段会话。
 *
 * <p>不用角色名当指纹：角色名会改、会复用，两个角色也可能其实是同一份权限。按
 * {@code tables + actions} 算出的指纹只认"能看见什么"，与语义缓存的键同一口径。
 *
 * <p>前缀不是装饰：会话表里还可能存别的命名空间（按工具、按技能），裸 id 进去就分不清了。
 */
public final class SessionKeys {

    /** 会话 id 的命名空间。换会话维度时改这里加后缀，不要在这里去掉通道与会话 id。 */
    public static final String PREFIX = "claw4j:user:";

    /** 权限指纹与 userId 之间的分隔。库里 {@code ai_session.user_id} 存的就是这个形状。 */
    static final String OWNER_SEPARATOR = "@";

    /** 通道与 chatId 之间的分隔，以及缺 chatId 时的占位。 */
    static final String SESSION_SEPARATOR = ":";

    /**
     * 主会话占的 peer 段（ADR-0029）。
     *
     * <p>{@code dm-scope: main} 时所有私聊都拼这一段，于是"一个人的所有私聊算同一段"
     * 由键本身表达，而不是靠一张额外的映射表。
     */
    public static final String MAIN_PEER = "main";

    /** 没有 chatId 的通道（脚本、内部调用）落在这里：同一通道内共享，跨通道不共享。 */
    static final String NO_CHAT = "-";

    private SessionKeys() {
    }

    /**
     * 归属键：{@code userId@权限指纹}。会话表的 {@code user_id} 列、run 的归属、审计、
     * 定时任务都认它，召回不认——召回按会话查（见 {@link RecallSessionTool}）。
     *
     * <p>它仍然要掺权限指纹：会话表的 {@code user_id} 存的就是它，而会话内容按权限隔离；
     * 写裸 userId 会让"降权之后什么都想不起来"在存储这一层失效——两段会话落在同一个
     * user_id 下，任何按 user_id 汇总的查询都会把它们混在一起。
     *
     * @param principal 当前身份，不能为 null
     * @return 归属键，形状为 {@code userId@短指纹}
     * @throws IllegalArgumentException userId 为空时。空的后果不是"存不下"，
     *                                  而是所有人共用一个会话——它看起来是记得住的，
     *                                  只是记得是别人的
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
     * @return 不带段的对话键
     */
    public static String sessionIdOf(String owner, String channel, String chatId) {
        Objects.requireNonNull(owner, "owner");
        return PREFIX + owner
                + SESSION_SEPARATOR + orPlaceholder(channel)
                + SESSION_SEPARATOR + orPlaceholder(chatId);
    }

    /**
     * 会话节点键：{@code claw4j:user:<owner>:<peer 段>}，不含段（票 06、ADR-0029）。
     *
     * <p>peer 段由 {@code direct} 与两个 scope 决定：私聊默认落到 {@link #MAIN_PEER}，
     * 群聊默认按通道 + 群 id。同一个人的私聊与他在群里的发言因此是两个节点——这一条是硬要求，
     * 不区分的话群里的上下文会并进私聊，那是一处静默的越权读。
     *
     * <p>目标是跨段的一份状态（{@code SessionGoalStore}）与会话状态行（{@code SessionEntryStore}）
     * 都按它定位：{@code /new} 换的是段，不是节点。
     *
     * @param owner   归属键，来自 {@link #ownerOf}
     * @param direct  是不是私聊，来自 {@code InboundMessage#direct()}
     * @param channel 通道，可以为 null
     * @param chatId  通道里的地址，可以为 null
     * @param scopes  两个 scope 的生效值，见 {@link SessionScopeProperties}
     * @return 不含段的会话节点键
     */
    public static String conversationKeyOf(String owner, boolean direct, String channel, String chatId,
                                           SessionScopeProperties scopes) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(scopes, "scopes");
        String normalizedChannel = orPlaceholder(channel);
        String normalizedChat = orPlaceholder(chatId);
        String peer = direct
                ? scopes.directPeerOf(normalizedChannel, normalizedChat)
                : scopes.groupPeerOf(normalizedChannel, normalizedChat);
        return PREFIX + owner + SESSION_SEPARATOR + peer;
    }

    /**
     * 会话节点键的旧形状：{@code claw4j:user:<owner>:<通道>:<chatId>}。
     *
     * <p>只给两类地方：召回的身份校验（{@link SessionRecallScope#belongsTo} 用前缀判归属），
     * 以及审批续跑时拿到的那个已经算好的键。新代码按
     * {@link #conversationKeyOf(String, boolean, String, String, SessionScopeProperties)} 走。
     *
     * @param owner   归属键，来自 {@link #ownerOf}
     * @param channel 通道，可以为 null
     * @param chatId  通道里的地址，可以为 null
     * @return 不含段的会话节点键
     */
    public static String conversationKeyOf(String owner, String channel, String chatId) {
        return sessionIdOf(owner, channel, chatId);
    }

    /**
     * 带段的对话键：{@code claw4j:user:<owner>:<通道>:<chatId>:<段>}（ADR-0026）。
     *
     * <p>段是"这段对话的第几世"，由 {@link SessionEntries} 按时间给（跨天或空闲就换一个），
     * 所以 sessionId 从"一段对话的固定坐标"变成了"某一次的坐标"：换段之后记忆、预算、召回
     * 全都从零开始，旧段还在库里只读。
     *
     * <p>空段是老形状，这一支刻意留着，只给两处用：审批续跑读不到 run 上的 {@code session_id}
     * 时的回落，以及段上线之前就存在的会话。它们只读不写——新的一轮永远带段，
     * 不带段的话换段就等于没发生过。
     *
     * <p>段拼在最后，不拼在 owner 前面：召回的身份校验是
     * {@code sessionId.startsWith(PREFIX + owner + ":")}（见 {@link SessionRecallScope#belongsTo}）。
     * 拼在前面会让一个人的历史对另一个人成立，那是一条静默的越权读。
     *
     * @param owner   归属键，来自 {@link #ownerOf}
     * @param channel 通道，可以为 null
     * @param chatId  通道里的地址，可以为 null
     * @param segment 空或 null 表示"不加段"
     * @return 对话键
     */
    public static String sessionIdOf(String owner, String channel, String chatId, String segment) {
        return sessionIdOf(sessionIdOf(owner, channel, chatId), segment);
    }

    /**
     * 从会话节点键派生带段的对话键：{@code <节点键>:<段>}。
     *
     * <p>段是"这段对话的第几世"，由 {@link SessionEntries} 给；节点键回答"哪条对话"。
     * 分开传而不是把两个东西再拼一次，是因为节点键可能来自 scope 规则（{@code main}），
     * 也可能来自库里已有的一行——两种来源拼出来的形状必须一模一样，才能与
     * {@code ai_session.id} 对上。
     *
     * @param conversationKey 不含段的节点键
     * @param segment         空或 null 表示"不加段"
     * @return 对话键，也就是 {@code ai_session.id} 与 {@code session_windows.session_id}
     */
    public static String sessionIdOf(String conversationKey, String segment) {
        Objects.requireNonNull(conversationKey, "conversationKey");
        return segment == null || segment.isBlank()
                ? conversationKey
                : conversationKey + SESSION_SEPARATOR + segment;
    }

    /**
     * 便捷入口：同四参版，指纹只算一次。
     *
     * @param principal 当前身份
     * @param channel   通道，可以为 null
     * @param chatId    通道里的地址，可以为 null
     * @param segment   空或 null 表示"不加段"
     * @return 对话键
     */
    public static String sessionIdOf(Principal principal, String channel, String chatId,
                                     String segment) {
        return sessionIdOf(ownerOf(principal), channel, chatId, segment);
    }

    /**
     * 便捷入口：一轮请求里 owner 与 sessionId 都要算，指纹只算一次。
     *
     * @param principal 当前身份
     * @param channel   通道，可以为 null
     * @param chatId    通道里的地址，可以为 null
     * @return 不带段的对话键
     */
    public static String sessionIdOf(Principal principal, String channel, String chatId) {
        return sessionIdOf(ownerOf(principal), channel, chatId);
    }

    private static String orPlaceholder(String part) {
        return part == null || part.isBlank() ? NO_CHAT : part.trim();
    }

    /**
     * 缺省落占位。给 {@link SessionEntries} 与 {@code ConversationLane} 用：它们要拿同样规整过的
     * 三样去查指针、拼锁键，两边对 null 与空串的处理必须一致——否则 sessionId 里的 {@code "-"}
     * 与指针行里的 {@code ""} 会是同一条对话的两个身份，而锁键上差一个字符就是两把不同的锁。
     *
     * @param part 通道或 chatId，可以为 null
     * @return 规整后的值，空则落到 {@link #NO_CHAT}
     */
    public static String placeholder(String part) {
        return orPlaceholder(part);
    }
}

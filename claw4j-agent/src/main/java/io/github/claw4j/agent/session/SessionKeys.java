package io.github.claw4j.agent.session;

import io.github.claw4j.agent.PermissionFingerprint;
import io.github.claw4j.domain.principal.Principal;

import java.util.Objects;

/**
 * 会话 id 怎么来。
 *
 * <p><b>会话属于"一个人在某份权限下的身份"</b>，既不属于通道，也不只属于人：
 * 会话 id = 前缀 + {@code userId} + 权限指纹（见 {@link #ownerOf}）。
 *
 * <p><b>为什么掺权限指纹</b>：会话记忆是"把上一次的答案原样带进下一次提示词"。
 * 如果一个人被降权，而他历史里躺着降权之前查出来的数字，那么他换一个权限再问同一句话，
 * 模型会直接从上下文里念出那些数字——不查库、不鉴权、没有任何一处报错，
 * 因为记忆读取本来就早于工具调用。ADR-0007 画的权限边界在"会话"这一层被绕过去了。
 * 掺进指纹之后，权限变了就是另一段会话：他会"什么都想不起来"，而不是"还记得不该记的"。
 *
 * <p><b>为什么不用角色名当指纹</b>：角色名会改、会复用、也可能两个角色其实是同一份权限。
 * 按 {@code tables + actions} 算出来的指纹只认"能看见什么"，这份判定与语义缓存
 * （{@code CacheAdvisor}）用的是同一个口径，两处不会打架。
 *
 * <p><b>为什么仍然不掺 {@code chatId}</b>：{@code chatId} 是 M0 就定下来的
 * <b>通道侧寻址键</b>（HTTP 的请求头、飞书的 chat_id），同一段对话换一条通道进来时它就会变。
 * 一旦把它编进会话主键，"换通道"和"换会话"就成了同一件事：员工从网页切到飞书，
 * 讲过的口径要重讲一遍，而存储明明是共享的 Postgres（ADR-0005）。
 *
 * <p><b>代价要记账</b>：权限一调整，历史就断了；老数据（那些不含 {@code @} 的会话 id）
 * 也再读不到——这是刻意选的，因为它只丢记忆，不泄数据。一个人在两段对话里问两件事，
 * 记忆仍然混在一起，那笔账照旧记着（等真实的
 * 多话题数据再定，到那时加维度的做法是加前缀而不是把 chatId 接回来）。
 *
 * <p>前缀不是装饰：会话表里还可能存别的命名空间（按工具、按技能），
 * 裸 id 进去之后，两种主键混在一张表里就没法分辨了。
 */
public final class SessionKeys {

    /** 会话 id 的命名空间。换会话维度时改这里加后缀，不要回去用 {@code chatId}。 */
    public static final String PREFIX = "claw4j:user:";

    /** 权限指纹与 userId 之间的分隔。库里 {@code ai_session.user_id} 存的就是这个形状。 */
    static final String OWNER_SEPARATOR = "@";

    private SessionKeys() {
    }

    /**
     * 归属键：{@code userId@权限指纹}。会话表的 {@code user_id} 列与跨会话召回都认它。
     *
     * <p>召回必须用同一个键：{@code CrossSessionRecallTools} 走
     * {@code findEventsByUserId}（{@code WHERE user_id = ?}），传裸 userId 就两种权限都能查到，
     * 换角色这个防线在那条路上等于没做。
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
     * 会话 id：{@code claw4j:user:<userId>@<权限指纹>}。
     *
     * <p>拆出 {@link #sessionIdOfOwner} 是为了让调用方只算一次指纹：
     * 一轮请求里会话 id 与 {@code user_id} 都要用，各算一遍的话，
     * 指纹口径一改就可能只改到一半——表现是"记得住，但召回查不到"。
     */
    public static String sessionIdOf(Principal principal) {
        return sessionIdOfOwner(ownerOf(principal));
    }

    /** 由 {@link #ownerOf} 的结果拼会话 id。参数必须来自它，不要自己拼 {@code userId@x}。 */
    public static String sessionIdOfOwner(String owner) {
        return PREFIX + owner;
    }
}

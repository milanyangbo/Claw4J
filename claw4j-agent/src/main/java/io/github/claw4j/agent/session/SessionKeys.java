package io.github.claw4j.agent.session;

import io.github.claw4j.domain.principal.Principal;

import java.util.Objects;

/**
 * 会话 id 怎么来。
 *
 * <p><b>会话属于人，不属于通道</b>：会话 id 只由 {@link Principal#userId()} 派生，
 * 不掺 {@code chatId}。这不是忘了用，是刻意的——{@code chatId} 是 M0 就定下来的
 * <b>通道侧寻址键</b>（HTTP 的请求头、飞书的 chat_id），同一段对话换一条通道进来时它就会变。
 * 一旦把它编进会话主键，"换通道"和"换会话"就成了同一件事：员工从网页切到飞书，
 * 讲过的口径要重讲一遍，而存储明明是共享的 Postgres（ADR-0005）。
 *
 * <p>代价要记账：<b>一个人在两段对话里问两件事，记忆是混在一起的</b>。
 * 这一轮不解决它——M1 只做"记得住上一轮"，多话题并行是产品问题，
 * 等有真实多轮数据再定，而且到那时加维度的做法是加前缀而不是把 chatId 接回来。
 *
 * <p>前缀不是装饰：会话表将来还可能存别的命名空间（按工具、按技能），
 * 裸 userId 进去之后，两种主键混在一张表里就没法分辨了。
 */
public final class SessionKeys {

    /** 会话 id 的命名空间。换会话维度时改这里加后缀，不要回去用 {@code chatId}。 */
    public static final String PREFIX = "claw4j:user:";

    private SessionKeys() {
    }

    /**
     * @throws IllegalArgumentException userId 为空时。空的后果不是"存不下"，
     *                                  而是所有人共用一个会话——那比没有记忆更糟，
     *                                  因为它看起来是记得住的，只是记得是别人的。
     */
    public static String sessionIdOf(Principal principal) {
        Objects.requireNonNull(principal, "principal");
        String userId = principal.userId();
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException(
                    "Principal.userId 不能为空：会话 id 由它派生，空了等于所有人共享一个会话");
        }
        return PREFIX + userId;
    }
}

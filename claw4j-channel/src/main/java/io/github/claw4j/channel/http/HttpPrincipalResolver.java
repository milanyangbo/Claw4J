package io.github.claw4j.channel.http;

import io.github.claw4j.channel.spi.PrincipalRejectedException;
import io.github.claw4j.domain.principal.Principal;

import java.util.Objects;

/**
 * 从 HTTP 请求头里认出「这是谁」。
 *
 * <p>这是 ADR-0007 边界的通道那一半：认人属于通道，授权查询发生在 Agent 侧，两边不越界。
 * 放在通道而不是 Agent，是因为用户标识从哪来完全由接入方式决定——HTTP 是网关塞的头部，
 * 飞书是事件里的 {@code sender_id}，换通道时这段必然重写，不该让 Agent 去猜。
 *
 * <p>前置条件是网关已经在边缘做过认证、把结果写成请求头。本项目不做认证体系，
 * 这里负责把头部翻译成 {@link Principal}，并按 {@code claw4j.channel.http.users} 判准入：
 * 缺失用户标识、或者人不在白名单里，都拒绝。<b>没有默认身份</b>——角色请求头
 * 这个头随角色一起下线，认不出就是进不来。
 */
public class HttpPrincipalResolver {

    /** 用户标识所在的头，由网关写入。 */
    public static final String USER_HEADER = "X-Claw4j-User";

    /** 会话标识所在的头。缺省时回落到用户标识。 */
    public static final String CHAT_ID_HEADER = "X-Claw4j-Chat-Id";

    private final HttpChannelProperties properties;

    public HttpPrincipalResolver(HttpChannelProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    /**
     * 把请求头里的用户标识翻译成一个身份。
     *
     * @param userId 网关写入的用户标识，缺失即拒绝
     * @return 身份
     * @throws PrincipalRejectedException 缺用户标识，或这个人不在 {@code claw4j.channel.http.users} 里
     */
    public Principal resolve(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new PrincipalRejectedException("缺少 " + USER_HEADER + " 请求头");
        }
        if (!properties.allows(userId)) {
            throw new PrincipalRejectedException("用户不在 claw4j.channel.http.users 白名单里：" + userId
                    + "。把网关认证出来的用户名填进去（本机演示就是你自己），或者整份名单写 [\"*\"]"
                    + "表示这条通道不设准入。");
        }
        return new Principal(userId);
    }

    /**
     * 算这次请求属于哪个会话。
     *
     * <p>Chat ID 只是 Session 的寻址方式（GLOSSARY），Session 本身（记忆、压缩、追踪的作用域）
     * 由 Agent 侧决定，这里不预判（ADR-0005）。没带该头时回落到用户标识，
     * 于是一个人一条对话，本机演示够用。
     *
     * @param userId 已经认证过的用户标识
     * @param explicitChatId 请求头里的会话标识，可为 {@code null}
     * @return 加了 {@code http:} 前缀的会话标识
     */
    public String chatId(String userId, String explicitChatId) {
        String id = (explicitChatId == null || explicitChatId.isBlank()) ? userId : explicitChatId;
        return "http:" + id;
    }
}

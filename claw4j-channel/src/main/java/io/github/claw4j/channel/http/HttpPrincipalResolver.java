package io.github.claw4j.channel.http;

import io.github.claw4j.domain.principal.Principal;

import java.util.Objects;

/**
 * 从 HTTP 请求头里认出"这是谁"。
 *
 * <p>这是 ADR-0007 边界的通道那一半：认人属于通道，授权查询发生在 Agent 侧，
 * 两边不越界。放在通道而不是 Agent，是因为"用户标识从哪来"完全由接入方式决定——
 * HTTP 是网关塞的头部，飞书是事件里的 {@code sender_id}，换通道时这段必然重写，
 * 不该让 Agent 去猜。
 *
 * <p>现实里的前置条件是网关已经在边缘做过认证、并把结果写成请求头；
 * 本项目不做认证体系，这里只负责把头部翻译成 {@link Principal}。
 * 头部缺失或角色不在授权表里就拒绝，绝不放匿名请求进来。
 */
public class HttpPrincipalResolver {

    public static final String USER_HEADER = "X-Claw4j-User";
    public static final String ROLE_HEADER = "X-Claw4j-Role";
    public static final String SESSION_HEADER = "X-Claw4j-Session";

    private final HttpChannelProperties properties;

    public HttpPrincipalResolver(HttpChannelProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    /**
     * @param userId 网关写入的用户标识，缺失即拒绝
     * @param role   角色头；留空按 {@code defaultRole} 处理，给了但表里没有则拒绝
     */
    public Principal resolve(String userId, String role) {
        if (userId == null || userId.isBlank()) {
            throw new PrincipalRejectedException("缺少 " + USER_HEADER + " 请求头");
        }
        String effectiveRole = (role == null || role.isBlank()) ? properties.getDefaultRole() : role;

        HttpChannelProperties.RoleGrant grant = properties.getRoles().get(effectiveRole);
        if (grant == null) {
            throw new PrincipalRejectedException("角色未在 claw4j.channel.http.roles 中登记：" + effectiveRole);
        }
        return Principal.of(userId, effectiveRole, grant.tables(), grant.actions());
    }

    /**
     * 会话寻址键。通道只负责"回哪条会话"，Session 本身是什么由 Agent 侧决定（ADR-0005）。
     * 没带会话头时回落到用户标识，于是一个人一条对话，本机演示够用。
     */
    public String sessionKey(String userId, String sessionId) {
        String id = (sessionId == null || sessionId.isBlank()) ? userId : sessionId;
        return "http:" + id;
    }
}

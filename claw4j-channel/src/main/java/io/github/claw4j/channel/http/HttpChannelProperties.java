package io.github.claw4j.channel.http;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * HTTP 通道的配置：谁能进来、进来之后是什么身份、一次流式最长多久。
 *
 * <p>角色到授权的映射放在这里而不是代码里，是因为"谁能看哪张表"是运营口径，
 * 改一次不该重新发版。ADR-0007 的分工是：通道认人、配置授权、Agent 只执行。
 */
@ConfigurationProperties(prefix = "claw4j.channel.http")
public class HttpChannelProperties {

    /**
     * 角色 → 授权表。默认两个角色够跑演示，生产上按需扩展。
     * 表里没有的角色一律拒绝，不做"找不到就给个默认权限"这种兜底。
     */
    private Map<String, RoleGrant> roles = new LinkedHashMap<>(Map.of(
            "read-only", new RoleGrant(Set.of("*"), Set.of()),
            "admin", new RoleGrant(Set.of("*"), Set.of("*"))));

    /**
     * 请求没带角色头时使用的角色。取最小权限而不是拒绝：本机 curl 演示不该先去配角色。
     */
    private String defaultRole = "read-only";

    /**
     * 一次 SSE 连接的最长存活时间。超时即断开，避免客户端半途消失后连接吊着。
     */
    private Duration streamTimeout = Duration.ofSeconds(60);

    public Map<String, RoleGrant> getRoles() {
        return roles;
    }

    public void setRoles(Map<String, RoleGrant> roles) {
        this.roles = roles == null ? new LinkedHashMap<>() : new LinkedHashMap<>(roles);
    }

    public String getDefaultRole() {
        return defaultRole;
    }

    public void setDefaultRole(String defaultRole) {
        this.defaultRole = defaultRole;
    }

    public Duration getStreamTimeout() {
        return streamTimeout;
    }

    public void setStreamTimeout(Duration streamTimeout) {
        this.streamTimeout = streamTimeout;
    }

    /**
     * 一个角色的授权范围。
     *
     * @param tables  可访问的表，"*" 表示全部
     * @param actions 可执行的动作名，"*" 表示全部
     */
    public record RoleGrant(Set<String> tables, Set<String> actions) {
    }
}

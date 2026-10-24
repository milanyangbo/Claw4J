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
     * 角色 → 授权表。默认三个角色够跑演示，生产上按需扩展。
     * 表里没有的角色一律拒绝，不做"找不到就给个默认权限"这种兜底。
     *
     * <p>{@code read-only} 拿到的是读动作而不是空集：空集意味着连"上周成交额多少"都问不了，
     * 默认角色就成了废角色。{@code guest} 演示的是表级——它会读，但读不到
     * {@code sales_order}，于是同一个问题在它这里是明确的"没权限"（ADR-0007）。
     *
     * <p>这两个能读的角色也都给了 {@code use_skill}：加载一份做法（M2-04）本身不读表、
     * 不写东西，Skill 只是把"先用哪个工具、再输出什么"写出来，真正碰表的那一步
     * 仍按表级与动作级各自鉴权。把 {@code use_skill} 与 {@code read} 分开列，
     * 是为了让"谁能用什么做法"成为一行配置能回答的问题，而不是"能读就顺便能用"。
     * {@code guest} 的受限仍然纯粹是表级——这是它要演示的那一件事。
     */
    private Map<String, RoleGrant> roles = new LinkedHashMap<>(Map.of(
            "read-only", new RoleGrant(Set.of("*"), Set.of("read", "use_skill")),
            "guest", new RoleGrant(Set.of("region"), Set.of("read", "use_skill")),
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
     * @param actions 可执行的动作名，"*" 表示全部。取值与工具登记的动作名同一个口径
     *                （读动作是 {@code read}，有副作用的动作用自己的名字，例如 {@code send_email}）
     */
    public record RoleGrant(Set<String> tables, Set<String> actions) {
    }
}

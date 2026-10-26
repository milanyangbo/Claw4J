package io.github.claw4j.channel.feishu.sdk;

import io.github.claw4j.domain.principal.Principal;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * 飞书通道的配置：凭据、谁能进来、握手等多久。
 *
 * <p>认人的口径与 HTTP 通道同一个形状（角色 → 表 / 动作），差别在登记的粒度：
 * HTTP 是从请求头读角色（网关已经认证过），飞书是从事件里读 {@code open_id}，
 * 所以这里要有一张"谁是谁"的表。写在角色下面而不是单独一张 {@code users} 表，
 * 是为了让"这个人能看什么"仍然只有一个答案——两处写同一件事，早晚会不一致。
 *
 * <p><b>没有默认角色。</b>HTTP 有一个 {@code default-role}（为了本机 curl 演示时不用先配角色），
 * 这里刻意没有：飞书进来的是真人，认不出就必须拒绝（ADR-0007）。少一个兜底，少一条"匿名进来还拿到了数"的路。
 */
@ConfigurationProperties(prefix = "claw4j.channel.feishu")
public class FeishuChannelProperties {

    /** 关掉整个通道。默认为开，但凭据不全时装配条件本来就不成立。 */
    private boolean enabled = true;

    /** 自建应用的 App ID，来自环境变量 {@code FEISHU_APP_ID}（ADR-0002 的收口原则）。 */
    private String appId = "";

    /** 自建应用的 App Secret，来自环境变量 {@code FEISHU_APP_SECRET}，不进仓库。 */
    private String appSecret = "";

    /**
     * 握手超时：{@code start()} 之后等长连接就绪的上限。
     *
     * <p>必须有这个上限：SDK 在连不上时会自己重连，若不主动判一次"到底连上没有"，
     * 应用会带着一个收不到消息的通道"看起来在跑"——那比启动失败难查得多。
     */
    private Duration readyTimeout = Duration.ofSeconds(15);

    /**
     * 角色 → 授权 + 登记在这个角色下的人。
     *
     * <p>默认只有 {@code read-only} 且 {@code users} 为空：装上飞书通道之后，
     * 任何人问话都会先拿到"没有权限"，直到有人把 {@code open_id} 填进来。这是对的默认值——
     * 反过来（默认放行）意味着"忘了配置"等于"全员可见"。
     */
    private Map<String, RoleGrant> roles = new LinkedHashMap<>(Map.of(
            "read-only", new RoleGrant(Set.of("*"), Set.of("read", "use_skill", "delegate_task", "read_system"),
                    Set.of())));

    /** 凭据是否齐全。半配（只给了 app-id）等同于没配：装配条件不成立。 */
    public boolean configured() {
        return !isBlank(appId) && !isBlank(appSecret);
    }

    /**
     * {@code open_id} → {@link Principal}。认不出返回 {@code Optional#empty()}，
     * 绝不回落到某个默认角色。
     */
    public Optional<Principal> resolve(String openId) {
        if (isBlank(openId)) {
            return Optional.empty();
        }
        for (Map.Entry<String, RoleGrant> entry : roles.entrySet()) {
            if (entry.getValue().users().contains(openId)) {
                RoleGrant grant = entry.getValue();
                return Optional.of(Principal.of(openId, entry.getKey(), grant.tables(), grant.actions()));
            }
        }
        return Optional.empty();
    }

    /**
     * 同一个人在两个角色里登记 → 启动失败。
     *
     * <p>不选一个"先匹配到的算"，是因为那等于把"他能看什么"变成配置文件的书写顺序：
     * 改一行无关的配置就可能悄悄换了权限。这种歧义只能在启动时说出来。
     */
    @PostConstruct
    void validate() {
        Set<String> duplicated = new TreeSet<>();
        Set<String> seen = new LinkedHashSet<>();
        for (RoleGrant grant : roles.values()) {
            for (String user : grant.users()) {
                if (!seen.add(user)) {
                    duplicated.add(user);
                }
            }
        }
        if (!duplicated.isEmpty()) {
            throw new IllegalStateException(
                    "claw4j.channel.feishu.roles 里同一个人登记了多个角色：" + String.join(", ", duplicated)
                            + "。一个人只能有一个身份，请删掉多余的那几处。");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId == null ? "" : appId;
    }

    public String getAppSecret() {
        return appSecret;
    }

    public void setAppSecret(String appSecret) {
        this.appSecret = appSecret == null ? "" : appSecret;
    }

    public Duration getReadyTimeout() {
        return readyTimeout;
    }

    public void setReadyTimeout(Duration readyTimeout) {
        this.readyTimeout = readyTimeout == null ? Duration.ofSeconds(15) : readyTimeout;
    }

    public Map<String, RoleGrant> getRoles() {
        return roles;
    }

    public void setRoles(Map<String, RoleGrant> roles) {
        this.roles = roles == null ? new LinkedHashMap<>() : new LinkedHashMap<>(roles);
    }

    /**
     * 一个角色的授权范围，以及登记在这个角色下的人。
     *
     * @param tables  可访问的表，"*" 表示全部
     * @param actions 可执行的动作名，与工具登记的动作名同一个口径（{@code read} 是读）
     * @param users   飞书 {@code open_id}。空集合意味着"这个角色暂时没人"
     */
    public record RoleGrant(Set<String> tables, Set<String> actions, Set<String> users) {

        public RoleGrant {
            tables = tables == null ? Set.of() : Set.copyOf(tables);
            actions = actions == null ? Set.of() : Set.copyOf(actions);
            users = users == null ? Set.of() : Set.copyOf(users);
        }
    }
}

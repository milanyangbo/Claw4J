package io.github.claw4j.channel.dingtalk.client;

import io.github.claw4j.channel.http.HttpChannelProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * 钉钉通道的配置：企业内部应用的凭据、机器人编码、谁能进来。
 *
 * <p>三样东西缺一不可，缺任何一样都算"没配"（{@link #configured()}）：
 *
 * <table border="1">
 *   <caption>它们在哪儿、干什么、不配对的样子</caption>
 *   <tr><th>键</th><th>后台哪里拿</th><th>不配对的样子</th></tr>
 *   <tr><td>{@code client-id}</td><td>开发者后台 → 应用 → 凭证与基础信息 → Client ID（AppKey）</td>
 *       <td>换不到 token，发送全失败</td></tr>
 *   <tr><td>{@code client-secret}</td><td>同一页 → Client Secret（AppSecret）</td>
 *       <td>换不到 token；验签也全失败（回调一律被拒）</td></tr>
 *   <tr><td>{@code robot-code}</td><td>机器人配置页 → RobotCode（通常与 Client ID 同值）</td>
 *       <td>token 换得到，消息发不出去</td></tr>
 * </table>
 *
 * <p>三样之外还有一样不在配置里：回调地址。它要在开发者后台手填
 * （机器人 → 消息接收地址），与本文件对不上时表现为"钉钉那边保存不了"或
 * "保存了但一条也没进来"，两种都很安静。地址写在 {@code DingtalkCallbackController#PATH}。
 *
 * <p>这里只登记"谁是谁"，"角色能看什么"只有一份，在 {@code claw4j.channel.http.roles}：
 * 一张表写在三个地方，迟早改一处漏两处。
 *
 * <p>没有默认角色，认不出就拒绝（ADR-0007）。{@code roles.*.users} 填的是企业内 userId
 * （{@code senderStaffId}），没登记的人不该拿到任何一张表。
 *
 * <p>凭据是环境变量占位，不是 P08 的 {@code secret:} 句柄：解析句柄的
 * {@code SpringSecretResolver} 在 {@code claw4j-capability}，而 channel 不依赖它
 * （{@code ChannelBoundaryTest} 钉着这条边）。要让通道也用句柄，得先把
 * {@code SecretResolver} 接口下沉到 core，那是另一件事。
 */
@ConfigurationProperties(prefix = "claw4j.channel.dingtalk")
public class DingtalkProperties {

    private static final Logger log = LoggerFactory.getLogger(DingtalkProperties.class);

    /** 关掉整个通道。默认为开，但凭据不全时装配条件本来就不成立。 */
    private boolean enabled = true;

    /** 企业内部应用的 AppKey，来自 {@code DINGTALK_CLIENT_ID}。 */
    private String clientId = "";

    /**
     * 企业内部应用的 AppSecret，来自 {@code DINGTALK_CLIENT_SECRET}，不进仓库。
     *
     * <p>它同时是换 token 的凭据<b>与验签的密钥</b>——这两件事在钉钉是同一个秘密。
     * 所以配错它有两个症状：发不出消息，以及所有回调被拒。
     */
    private String clientSecret = "";

    /** 机器人的 RobotCode，来自 {@code DINGTALK_ROBOT_CODE}。 */
    private String robotCode = "";

    /**
     * 接口地址。写成可改的是为了本地能拿一个假的钉钉跑通"换 token → 发消息"这条链路
     * （{@code DingtalkChannelTest} 的用例就是这么做的）。
     */
    private String apiBase = "https://api.dingtalk.com";

    /**
     * 角色 → 登记在这个角色下的人（企业内 userId）。
     *
     * <p>默认空表＝谁都进不来，正是要的方向。凭据齐了却一个人都没登记时，
     * 启动日志里会说一声（{@link #warnIfNobodyIsRegistered()}）：那种状态里通道完全正常、
     * 日志干净，而每个人都收到"没有权限"。
     */
    private Map<String, RoleGrant> roles = new LinkedHashMap<>();

    /** 三样齐了才算配好。半配（只给了 client-id）等同于没配：装配条件不成立。 */
    public boolean configured() {
        return !isBlank(clientId) && !isBlank(clientSecret) && !isBlank(robotCode);
    }

    /**
     * {@code userId} → 角色名。认不出来返回 {@code Optional#empty()}，绝不回落到默认角色。
     *
     * <p>只到角色名为止：表与动作要拿这个名字去共用的那张授权表里取
     * （{@code DingtalkPrincipalResolver}）。
     */
    public Optional<String> roleOf(String userId) {
        if (isBlank(userId)) {
            return Optional.empty();
        }
        return roles.entrySet().stream()
                .filter(entry -> entry.getValue().users().contains(userId))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    /**
     * 同一个人登记在两个角色里 → 启动失败。
     *
     * <p>与另外两个通道同一条理由：不选"先匹配到的算"，
     * 因为那等于把"他能看什么"变成配置的书写顺序。
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
                    "claw4j.channel.dingtalk.roles 里同一个人登记了多个角色：" + String.join(", ", duplicated)
                            + "。一个人只能有一个身份，请删掉多余的那几处。");
        }
        warnIfNobodyIsRegistered();
    }

    /** 凭据齐了、一个人都没登记 → 启动日志里必须有句话。同另外四份。 */
    private void warnIfNobodyIsRegistered() {
        if (!enabled || !configured()) {
            return;
        }
        boolean anyoneRegistered = roles.values().stream().anyMatch(grant -> !grant.users().isEmpty());
        if (!anyoneRegistered) {
            log.warn("钉钉通道已启用、凭据齐全，但 claw4j.channel.dingtalk.roles.*.users 一个人都没登记："
                    + "回调会正常收到，而任何人提问都只会收到“没有权限”。"
                    + "把提问者的企业内 userId 填进 application-local.yml"
                    + "（那条“未登记”的 WARN 里带着 sender=...）。");
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

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId == null ? "" : clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret == null ? "" : clientSecret;
    }

    public String getRobotCode() {
        return robotCode;
    }

    public void setRobotCode(String robotCode) {
        this.robotCode = robotCode == null ? "" : robotCode;
    }

    public String getApiBase() {
        return apiBase;
    }

    public void setApiBase(String apiBase) {
        this.apiBase = apiBase == null || apiBase.isBlank() ? "https://api.dingtalk.com" : apiBase.strip();
    }

    public Map<String, RoleGrant> getRoles() {
        return roles;
    }

    public void setRoles(Map<String, RoleGrant> roles) {
        this.roles = roles == null ? new LinkedHashMap<>() : new LinkedHashMap<>(roles);
    }

    /**
     * 一个角色下登记的人。
     *
     * @param users 企业内 userId。空集合意味着"这个角色暂时没人"
     */
    public record RoleGrant(Set<String> users) {

        public RoleGrant {
            users = users == null ? Set.of() : Set.copyOf(users);
        }
    }
}

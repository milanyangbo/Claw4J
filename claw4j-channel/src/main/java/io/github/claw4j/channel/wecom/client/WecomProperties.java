package io.github.claw4j.channel.wecom.client;

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
 * 企业微信通道的配置：一个自建应用的凭据、回调的加解密参数、谁能进来。
 *
 * <p>五样东西缺一不可，缺任何一样都算"没配"（{@link #configured()}）：
 *
 * <table border="1">
 *   <caption>它们在哪儿、干什么</caption>
 *   <tr><th>键</th><th>后台哪里拿</th><th>不配对的样子</th></tr>
 *   <tr><td>{@code corp-id}</td><td>我的企业 → 企业信息 → 企业 ID</td>
 *       <td>换不到 token，发送全失败</td></tr>
 *   <tr><td>{@code corp-secret}</td><td>应用 → 自建应用 → Secret</td><td>同上</td></tr>
 *   <tr><td>{@code agent-id}</td><td>同一个页面 → AgentId</td><td>接口报 {@code 40056}，消息发不到人</td></tr>
 *   <tr><td>{@code token}</td><td>接收消息 → API 接收消息 → Token</td>
 *       <td>签名永远对不上，回调一律被拒</td></tr>
 *   <tr><td>{@code encoding-aes-key}</td><td>同一页 → EncodingAESKey</td>
 *       <td>解密抛异常，用户永远等不到回答</td></tr>
 * </table>
 *
 * <p><b>这里只登记"谁是谁"，不写"角色能看什么"</b>：授权表只有一份，在
 * {@code claw4j.channel.http.roles}。与飞书那一份逐条同构，理由也同构（见
 * {@code FeishuChannelProperties} 的 javadoc）：两个通道的差别只在认人，
 * 一张表写在两个地方，迟早改一处漏一处。
 *
 * <p><b>没有默认角色。</b>认不出就拒绝（ADR-0007）：企业微信进来的是真人，
 * 而这里的 {@code roles.*.users} 填的是成员 UserID——没登记的人不该拿到任何一张表。
 */
@ConfigurationProperties(prefix = "claw4j.channel.wecom")
public class WecomProperties {

    private static final Logger log = LoggerFactory.getLogger(WecomProperties.class);

    /** 关掉整个通道。默认为开，但凭据不全时装配条件本来就不成立。 */
    private boolean enabled = true;

    /** 企业 ID，来自环境变量 {@code WECOM_CORP_ID}（ADR-0002 的收口原则）。 */
    private String corpId = "";

    /** 自建应用的 Secret，来自 {@code WECOM_CORP_SECRET}，不进仓库。 */
    private String corpSecret = "";

    /** 自建应用的 AgentId。0 表示没配——它不是凭据，但缺了消息发不到人。 */
    private int agentId;

    /** 接收消息用的 Token，签名按它算。 */
    private String token = "";

    /** 接收消息用的 EncodingAESKey（43 位），解密密钥由它派生。 */
    private String encodingAesKey = "";

    /**
     * 接口地址。写成可改的是为了本地能拿一个假的企业微信跑通"换 token → 发消息"
     * 这条链路（{@code WecomHttpReplySender} 的用例就是这么做的）。
     */
    private String apiBase = "https://qyapi.weixin.qq.com";

    /**
     * 角色 → 登记在这个角色下的人（成员 UserID）。
     *
     * <p>默认空表＝谁都进不来，正是要的方向。装上通道、凭据也齐了却一个人都没登记时，
     * 启动日志里会说一声（{@link #warnIfNobodyIsRegistered()}）：那种状态里
     * 通道完全正常、日志干净，而每个人收到的都是"没有权限"。
     */
    private Map<String, RoleGrant> roles = new LinkedHashMap<>();

    /** 五样齐了才算配好。半配（只给了 corp-id）等同于没配：装配条件不成立。 */
    public boolean configured() {
        return !isBlank(corpId) && !isBlank(corpSecret) && agentId > 0
                && !isBlank(token) && !isBlank(encodingAesKey);
    }

    /**
     * {@code UserID} → 角色名。认不出来返回 {@code Optional#empty()}，绝不回落到默认角色。
     *
     * <p>只到角色名为止：表与动作要拿这个名字去共用的那张授权表里取
     * （{@link WecomPrincipalResolver}）。
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
     * <p>与飞书同一条理由：不选"先匹配到的算"，因为那等于把"他能看什么"变成配置的书写顺序。
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
                    "claw4j.channel.wecom.roles 里同一个人登记了多个角色：" + String.join(", ", duplicated)
                            + "。一个人只能有一个身份，请删掉多余的那几处。");
        }
        warnIfNobodyIsRegistered();
    }

    /** 凭据齐了、一个人都没登记 → 启动日志里必须有句话。同 {@code FeishuChannelProperties}。 */
    private void warnIfNobodyIsRegistered() {
        if (!enabled || !configured()) {
            return;
        }
        boolean anyoneRegistered = roles.values().stream().anyMatch(grant -> !grant.users().isEmpty());
        if (!anyoneRegistered) {
            log.warn("企业微信通道已启用、凭据齐全，但 claw4j.channel.wecom.roles.*.users 一个人都没登记："
                    + "回调会正常收到，而任何人提问都只会收到“没有权限”。"
                    + "把提问者的成员 UserID 填进 application-local.yml"
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

    public String getCorpId() {
        return corpId;
    }

    public void setCorpId(String corpId) {
        this.corpId = corpId == null ? "" : corpId;
    }

    public String getCorpSecret() {
        return corpSecret;
    }

    public void setCorpSecret(String corpSecret) {
        this.corpSecret = corpSecret == null ? "" : corpSecret;
    }

    public int getAgentId() {
        return agentId;
    }

    public void setAgentId(int agentId) {
        this.agentId = agentId;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token == null ? "" : token;
    }

    public String getEncodingAesKey() {
        return encodingAesKey;
    }

    public void setEncodingAesKey(String encodingAesKey) {
        this.encodingAesKey = encodingAesKey == null ? "" : encodingAesKey;
    }

    public String getApiBase() {
        return apiBase;
    }

    public void setApiBase(String apiBase) {
        this.apiBase = apiBase == null || apiBase.isBlank() ? "https://qyapi.weixin.qq.com" : apiBase.strip();
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
     * @param users 成员 UserID。空集合意味着"这个角色暂时没人"
     */
    public record RoleGrant(Set<String> users) {

        public RoleGrant {
            users = users == null ? Set.of() : Set.copyOf(users);
        }
    }
}

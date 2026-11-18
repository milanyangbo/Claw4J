package io.github.claw4j.channel.wecom.client;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashSet;
import java.util.Set;

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
 * <p>这里只登记「谁是谁」，不写「角色能看什么」：授权表只有一份，在
 * {@code claw4j.channel.http.roles}。与飞书那一份逐条同构，理由也同构（见
 * {@code FeishuChannelProperties} 的 javadoc）：
 * 两个通道的差别只在认人，一张表写在两个地方，迟早改一处漏一处。
 *
 * <p>没有默认角色。认不出就拒绝（ADR-0007）：企业微信进来的是真人，而这里的
 * {@code roles.*.users} 填的是成员 UserID，没登记的人不该拿到任何一张表。
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
     * 谁可以用这条通道：成员 UserID。
     *
     * <p>空的含义是**谁都不能**，不是"谁都能"——这是"任何能往通道里发消息的人都能触发这个
     * agent"的唯一一道门。默认写成空，忘了配的表现是所有人都收到「不在白名单里」，而反向的
     * 默认（空＝全放）会让一次漏配变成对外裸奔。{@code ["*"]} 是显式写下的"谁都行"。
     *
     * <p>原来这里按角色分组（{@code roles.<角色>.users}），角色再指向一张共用授权表。那套授权
     * 随权限登记表在 ADR-0030 里整块下线：进来之后能看见什么由工具清单与审批决定。
     */
    private Set<String> users = new LinkedHashSet<>();

    /** 五样齐了才算配好。半配（只给了 corp-id）等同于没配：装配条件不成立。 */
    public boolean configured() {
        return !isBlank(corpId) && !isBlank(corpSecret) && agentId > 0
                && !isBlank(token) && !isBlank(encodingAesKey);
    }

    /**
     * 这个用户能不能用这条通道。
     *
     * <p>{@code ["*"]} 是显式写下的"谁都行"，与"忘了配"（空集合）在行为上必须能区分开。
     *
     * @param userId 成员 UserID
     * @return 在名单里时为 {@code true}
     */
    public boolean allows(String userId) {
        if (isBlank(userId)) {
            return false;
        }
        return users.contains("*") || users.contains(userId);
    }

    /**
     * 启动校验：凭据齐了却一个人都没登记时，在启动日志里说一句。
     *
     * <p>原来这里还查"同一个人登记在两个角色下"——一份白名单里不存在这种歧义，那条检查随
     * 角色一起下线。
     */
    @PostConstruct
    void validate() {
        warnIfNobodyIsAllowed();
    }

    /** 凭据齐了但一个人都没登记时，在启动日志里说一句。这种状态最难查：通道连得上、事件收得到。 */
    private void warnIfNobodyIsAllowed() {
        if (!enabled || !configured()) {
            return;
        }
        if (users.isEmpty()) {
            log.warn("企业微信通道已启用、凭据齐全，但 claw4j.channel.wecom.users 一个人都没登记："
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

    public Set<String> getUsers() {
        return users;
    }

    public void setUsers(Set<String> users) {
        this.users = users == null ? new LinkedHashSet<>() : new LinkedHashSet<>(users);
    }
}

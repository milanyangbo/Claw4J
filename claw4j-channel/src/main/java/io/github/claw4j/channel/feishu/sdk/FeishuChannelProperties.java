package io.github.claw4j.channel.feishu.sdk;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 飞书通道的配置：凭据、谁能进来、握手等多久。
 *
 * <p>这里只登记「谁是谁」，不写「角色能看什么」。授权表只有一份，在
 * {@code claw4j.channel.http.roles}（由 {@link HttpChannelProperties} 绑定，两个通道共用），
 * 飞书按角色名去那边取 {@code tables} 与 {@code actions}，取不到就在装配时启动失败
 * （{@link FeishuPrincipalResolver}）。
 *
 * <p>不为每个通道各写一份：同一张 actions 列表要改两处，漏一处不报错，只是那些角色少一个能力。
 * 两个通道的差别本来只在认人——HTTP 从请求头读角色（网关已经认证过），飞书从事件里读
 * {@code open_id}，所以这里需要一张「谁是谁」的表。
 *
 * <p>没有默认角色。HTTP 有一个 {@code default-role}，为的是本机 curl 演示时不必先配角色；
 * 这里刻意没有：飞书进来的是真人，认不出就必须拒绝（ADR-0007）。少一个兜底，
 * 少一条匿名进来还能拿到数据的路。
 */
@ConfigurationProperties(prefix = "claw4j.channel.feishu")
public class FeishuChannelProperties {

    private static final Logger log = LoggerFactory.getLogger(FeishuChannelProperties.class);

    /** 关掉整个通道。默认为开，但凭据不全时装配条件本来就不成立。 */
    private boolean enabled = true;

    /** 自建应用的 App ID，来自环境变量 {@code FEISHU_APP_ID}（ADR-0002 的收口原则）。 */
    private String appId = "";

    /** 自建应用的 App Secret，来自环境变量 {@code FEISHU_APP_SECRET}，不进仓库。 */
    private String appSecret = "";

    /**
     * 握手超时：{@code start()} 之后等长连接就绪的上限。
     *
     * <p>SDK 连不上时会自己重连，没有这个上限就判不出到底连上没有，应用会带着一个
     * 收不到消息的通道看起来在跑，比启动失败难查。
     */
    private Duration readyTimeout = Duration.ofSeconds(15);

    /**
     * 谁可以用这条通道：飞书 {@code open_id}。
     *
     * <p>空的含义是<b>谁都不能</b>，不是"谁都能"——这是"任何能往通道里发消息的人都能触发这个
     * agent"的唯一一道门。默认写成空，忘了配的表现是所有人都收到「不在白名单里」，而反向的
     * 默认（空＝全放）会让一次漏配变成对外裸奔。{@code ["*"]} 是显式写下的"谁都行"。
     *
     * <p>原来这里按角色分组（{@code roles.<角色>.users}），角色再指向一张共用授权表。那套授权
     * 随权限登记表在 ADR-0030 里整块下线：进来之后能看见什么由工具清单与审批决定。
     */
    private Set<String> users = new LinkedHashSet<>();

    /** 凭据是否齐全。半配（只给了 app-id）等同于没配：装配条件不成立。 */
    public boolean configured() {
        return !isBlank(appId) && !isBlank(appSecret);
    }

    /**
     * 这个用户能不能用这条通道。
     *
     * <p>{@code ["*"]} 是显式写下的"谁都行"，与"忘了配"（空集合）在行为上必须能区分开。
     *
     * @param openId 通道侧的用户标识
     * @return 在名单里时为 {@code true}
     */
    public boolean allows(String openId) {
        if (isBlank(openId)) {
            return false;
        }
        return users.contains("*") || users.contains(openId);
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

    /** 凭据齐了、一个人都没登记 → 启动日志里必须有句话。这种状态最难查：通道连得上、事件收得到。 */
    private void warnIfNobodyIsAllowed() {
        if (!enabled || !configured()) {
            return;
        }
        if (users.isEmpty()) {
            log.warn("feishu通道已启用、凭据齐全，但 claw4j.channel.feishu.users 一个人都没登记："
                    + "回调会正常收到，而任何人提问都只会收到“不在白名单里”。"
                    + "把提问者的 open_id填进 claw4j.channel.feishu.users"
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

    public Set<String> getUsers() {
        return users;
    }

    public void setUsers(Set<String> users) {
        this.users = users == null ? new LinkedHashSet<>() : new LinkedHashSet<>(users);
    }
}

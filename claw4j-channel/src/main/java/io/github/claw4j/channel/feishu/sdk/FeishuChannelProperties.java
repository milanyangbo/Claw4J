package io.github.claw4j.channel.feishu.sdk;

import io.github.claw4j.channel.http.HttpChannelProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p><b>这里只登记"谁是谁"，不写"角色能看什么"</b>：授权表只有一份，在
 * {@code claw4j.channel.http.roles}（由 {@link HttpChannelProperties} 绑定，
 * 它是两个通道共用的那张表）。飞书按角色名去那边取 {@code tables} / {@code actions}，
 * 取不到就在装配时启动失败（{@link FeishuPrincipalResolver}）。
 *
 * <p>为什么不让两个通道各写一份：{@code recall_session} 那一次加动作，同一张 actions 列表
 * 要改两处（提交 3d099ee），漏一处不报错——只是那群人少一个能力。两个通道的差别本来就只在认人：
 * HTTP 从请求头读角色（网关已经认证过），飞书从事件里读 {@code open_id}，所以这里要有一张"谁是谁"的表。
 *
 * <p><b>没有默认角色。</b>HTTP 有一个 {@code default-role}（为了本机 curl 演示时不用先配角色），
 * 这里刻意没有：飞书进来的是真人，认不出就必须拒绝（ADR-0007）。少一个兜底，少一条"匿名进来还拿到了数"的路。
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
     * <p>必须有这个上限：SDK 在连不上时会自己重连，若不主动判一次"到底连上没有"，
     * 应用会带着一个收不到消息的通道"看起来在跑"——那比启动失败难查得多。
     */
    private Duration readyTimeout = Duration.ofSeconds(15);

    /**
     * 角色 → 登记在这个角色下的人。
     *
     * <p>默认空：空表的效果是"谁都进不来"，正是要的方向——反过来（默认放行）意味着"忘了配置"等于"全员可见"。
     * 于是装上飞书通道、凭据也齐了却一个人都没登记时，表现就是所有人拿到"没有权限"；
     * 这种情况启动时会在日志里说一声（{@link #warnIfNobodyIsRegistered()}）。
     *
     * <p>键是<b>角色名</b>，必须能在 {@code claw4j.channel.http.roles} 里找到同名角色：
     * 这里只回答"这个人是谁"，不回答"他能看什么"。
     */
    private Map<String, RoleGrant> roles = new LinkedHashMap<>();

    /** 凭据是否齐全。半配（只给了 app-id）等同于没配：装配条件不成立。 */
    public boolean configured() {
        return !isBlank(appId) && !isBlank(appSecret);
    }

    /**
     * {@code open_id} → 角色名。认不出来返回 {@code Optional#empty()}，绝不回落到某个默认角色。
     *
     * <p>只到角色名为止：表与动作要拿这个名字去共用的那张授权表里取
     * （{@link FeishuPrincipalResolver}）。"这个人是谁"与"这个角色能看什么"是两个问题，
     * 答案也不该来自两份配置。
     */
    public Optional<String> roleOf(String openId) {
        if (isBlank(openId)) {
            return Optional.empty();
        }
        return roles.entrySet().stream()
                .filter(entry -> entry.getValue().users().contains(openId))
                .map(Map.Entry::getKey)
                .findFirst();
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
        warnIfNobodyIsRegistered();
    }

    /**
     * 凭据齐了、一个人都没登记 → 启动日志里必须有句话。
     *
     * <p>这种状态最难查：通道连得上、事件收得到、日志干净，而每个提问的人拿到的都是"没有权限"——
     * 与"服务根本没起来"在现象上长得一样。责任不在提问的人，所以不能只在群里回一句就完事，
     * 启动的时候就得说（同 {@code docs/design/pitfalls.md} 第九条那类"看起来在跑"的坑）。
     */
    private void warnIfNobodyIsRegistered() {
        if (!enabled || !configured()) {
            return;
        }
        boolean anyoneRegistered = roles.values().stream().anyMatch(grant -> !grant.users().isEmpty());
        if (!anyoneRegistered) {
            log.warn("飞书通道已启用、凭据齐全，但 claw4j.channel.feishu.roles.*.users 一个人都没登记："
                    + "通道会正常连上，而群里任何人提问都只会收到“没有权限”。"
                    + "把提问者的 open_id 填进 application-local.yml（那条“未登记”的 WARN 里带着 sender=ou_...）。");
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
     * 一个角色下登记的人。
     *
     * @param users 飞书 {@code open_id}。空集合意味着"这个角色暂时没人"
     */
    public record RoleGrant(Set<String> users) {

        public RoleGrant {
            users = users == null ? Set.of() : Set.copyOf(users);
        }
    }
}

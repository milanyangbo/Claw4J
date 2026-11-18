package io.github.claw4j.channel.slack.client;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Slack 通道的配置：两把凭据、一个限流间隔、谁能进来。
 *
 * <p>两样凭据缺一不可，缺任何一样都算"没配"（{@link #configured()}）：
 *
 * <table border="1">
 *   <caption>它们在哪儿、干什么、不配对的样子</caption>
 *   <tr><th>键</th><th>后台哪里拿</th><th>不配对的样子</th></tr>
 *   <tr><td>{@code signing-secret}</td><td>App 后台 → Basic Information → App Credentials</td>
 *       <td>所有回调被拒（含配置地址那一步的握手），后台只显示"URL 验证失败"</td></tr>
 *   <tr><td>{@code bot-token}</td><td>Install App → Bot User OAuth Token（{@code xoxb-} 开头）</td>
 *       <td>收得到提问，发不出答案（{@code not_authed} / {@code invalid_auth}）</td></tr>
 * </table>
 *
 * <p>两把凭据不是同一个东西：{@code signing-secret} 只能验签、{@code bot-token} 只能发消息，
 * 两个都要，各管一头。搞混的表现是"回调一直被拒"或者"回调正常但答案发不出去"。
 *
 * <p>bot token 长期有效，不需要换 token，所以其他通道那套"缓存 token、提前 5 分钟换"的
 * 状态在这里不存在。少一套状态，少一个出错的地方。
 *
 * <p>这里只登记"谁是谁"，"角色能看什么"只有一份，在 {@code claw4j.channel.http.roles}。
 * 填的是 Slack 的 user id（{@code U} 开头），不是用户名：显示名会变，而且能重名。
 *
 * <p>凭据是环境变量占位，不是 P08 的 {@code secret:} 句柄：解析句柄的
 * {@code SpringSecretResolver} 在 {@code claw4j-capability}，而 channel 不依赖它
 * （{@code ChannelBoundaryTest} 钉着这条边）。
 */
@ConfigurationProperties(prefix = "claw4j.channel.slack")
public class SlackProperties {

    private static final Logger log = LoggerFactory.getLogger(SlackProperties.class);

    /** 关掉整个通道。默认为开，但凭据不全时装配条件本来就不成立。 */
    private boolean enabled = true;

    /**
     * App 的 Signing Secret，来自 {@code SLACK_SIGNING_SECRET}。
     *
     * <p>它只用来验签。与钉钉的 {@code client-secret} 不同，它换不来 token，
     * 也发不了消息——那两件事归 {@link #botToken}。
     */
    private String signingSecret = "";

    /**
     * Bot User OAuth Token，来自 {@code SLACK_BOT_TOKEN}，{@code xoxb-} 开头。
     *
     * <p>长期有效，不用刷新（这一点与钉钉相反）。它是密钥：写进日志等于把工作区的发言权交出去，
     * 所以下面几个打日志的地方都只打前缀。
     */
    private String botToken = "";

    /**
     * 接口地址。写成可改的是为了本地能拿一个假的 Slack 跑通"发消息"这条链路
     * （{@code SlackChannelTest} 的用例就是这么做的）。
     */
    private String apiBase = "https://slack.com/api";

    /**
     * 同一个频道两条消息之间的最小间隔。
     *
     * <p>Slack 对一个频道的建议上限是每秒 1 条，所以默认 1 秒。它不是"配大了更好"：
     * 一条长答案会被切成几段连着发，而 {@code InboundDispatcher} 遇到异常会停止后续段落，
     * 撞上限流的表现是用户收到半截答案。测试里设成 0 是为了不把用例拖慢，生产不要动它。
     */
    private Duration minInterval = Duration.ofSeconds(1);

    /**
     * 谁可以用这条通道：Slack user id（{@code U} 开头）。
     *
     * <p>空的含义是**谁都不能**，不是"谁都能"——这是"任何能往通道里发消息的人都能触发这个
     * agent"的唯一一道门。默认写成空，忘了配的表现是所有人都收到「不在白名单里」，而反向的
     * 默认（空＝全放）会让一次漏配变成对外裸奔。{@code ["*"]} 是显式写下的"谁都行"。
     *
     * <p>原来这里按角色分组（{@code roles.<角色>.users}），角色再指向一张共用授权表。那套授权
     * 随权限登记表在 ADR-0030 里整块下线：进来之后能看见什么由工具清单与审批决定。
     */
    private Set<String> users = new LinkedHashSet<>();

    /** 两样齐了才算配好。半配（只给了 signing-secret）等同于没配：装配条件不成立。 */
    public boolean configured() {
        return !isBlank(signingSecret) && !isBlank(botToken);
    }

    /**
     * 这个用户能不能用这条通道。
     *
     * <p>{@code ["*"]} 是显式写下的"谁都行"，与"忘了配"（空集合）在行为上必须能区分开。
     *
     * @param userId 通道侧的用户标识
     * @return 在名单里时为 {@code true}
     */
    public boolean allows(String userId) {
        if (isBlank(userId)) {
            return false;
        }
        return users.contains("*") || users.contains(userId);
    }

    /** 凭据齐了、一个人都没登记 → 启动日志里必须有句话。这种状态最难查：通道连得上、事件收得到。 */
    private void warnIfNobodyIsAllowed() {
        if (!enabled || !configured()) {
            return;
        }
        if (users.isEmpty()) {
            log.warn("slack通道已启用、凭据齐全，但 claw4j.channel.slack.users 一个人都没登记："
                    + "回调会正常收到，而任何人提问都只会收到“不在白名单里”。"
                    + "把提问者的 Slack user id（U 开头）填进 claw4j.channel.slack.users"
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

    public String getSigningSecret() {
        return signingSecret;
    }

    public void setSigningSecret(String signingSecret) {
        this.signingSecret = signingSecret == null ? "" : signingSecret;
    }

    public String getBotToken() {
        return botToken;
    }

    public void setBotToken(String botToken) {
        this.botToken = botToken == null ? "" : botToken;
    }

    public String getApiBase() {
        return apiBase;
    }

    public void setApiBase(String apiBase) {
        this.apiBase = apiBase == null || apiBase.isBlank() ? "https://slack.com/api" : apiBase.strip();
    }

    public Duration getMinInterval() {
        return minInterval;
    }

    public void setMinInterval(Duration minInterval) {
        this.minInterval = minInterval == null ? Duration.ofSeconds(1) : minInterval;
    }

    public Set<String> getUsers() {
        return users;
    }

    public void setUsers(Set<String> users) {
        this.users = users == null ? new LinkedHashSet<>() : new LinkedHashSet<>(users);
    }
}

package io.github.claw4j.channel.slack.client;

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
 * <p><b>与钉钉的一个好消息</b>：Slack 的 bot token 是长期有效的，<b>不需要换 token</b>，
 * 所以钉钉那边"缓存 token、提前 5 分钟换"的一整段在这里不存在。少一套状态，少一个出错的地方。
 *
 * <p><b>两把凭据不是同一个东西</b>，这是与钉钉最容易被搞混的一处：钉钉的 {@code client-secret}
 * 同时管换 token 与验签；Slack 这边 {@code signing-secret} 只能验签、{@code bot-token} 只能发消息，
 * 两个都要，各管一头。搞混的表现是"回调一直被拒"或者"回调正常但答案发不出去"。
 *
 * <p><b>这里只登记"谁是谁"，不写"角色能看什么"</b>：授权表只有一份，在
 * {@code claw4j.channel.http.roles}。与另外三个通道逐条同构。填的是 Slack 的 user id（{@code U} 开头），
 * 不是用户名（{@code @ethan} 那种会变，而且能重名）。
 *
 * <p><b>凭据写法与另外三个通道一致</b>（环境变量占位，不是 P08 的 {@code secret:} 句柄）。
 * 理由见 P12 的实现记录：解析句柄的 {@code SpringSecretResolver} 在 {@code claw4j-capability}，
 * 而 channel 不依赖它（{@code ChannelBoundaryTest} 钉着这条边）。
 */
@ConfigurationProperties(prefix = "claw4j.channel.slack")
public class SlackProperties {

    private static final Logger log = LoggerFactory.getLogger(SlackProperties.class);

    /** 关掉整个通道。默认为开，但凭据不全时装配条件本来就不成立。 */
    private boolean enabled = true;

    /**
     * App 的 Signing Secret，来自 {@code SLACK_SIGNING_SECRET}。
     *
     * <p>它<b>只</b>用来验签。与钉钉的 {@code client-secret} 不同，它换不来 token，
     * 也发不了消息——那两件事归 {@link #botToken}。
     */
    private String signingSecret = "";

    /**
     * Bot User OAuth Token，来自 {@code SLACK_BOT_TOKEN}，{@code xoxb-} 开头。
     *
     * <p>长期有效，不用刷新（这一点与钉钉相反）。它是<b>密钥</b>：写进日志等于把工作区的发言权交出去，
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
     * <p>文档写的是"generally allow an app to post <b>1 message per second</b> to a specific channel"，
     * 所以默认 1 秒。它不是"配大了更好"：一条长答案会被切成几段连着发，
     * 而 {@code InboundDispatcher} 遇到异常会停止后续段落——撞上限流的表现是用户收到半截答案。
     * 测试里设成 0 是为了不把用例拖慢，生产不要动它。
     */
    private Duration minInterval = Duration.ofSeconds(1);

    /**
     * 角色 → 登记在这个角色下的人（Slack user id，{@code U} 开头）。
     *
     * <p>默认空表＝谁都进不来，正是要的方向。凭据齐了却一个人都没登记时，
     * 启动日志里会说一声（{@link #warnIfNobodyIsRegistered()}）。
     */
    private Map<String, RoleGrant> roles = new LinkedHashMap<>();

    /** 两样齐了才算配好。半配（只给了 signing-secret）等同于没配：装配条件不成立。 */
    public boolean configured() {
        return !isBlank(signingSecret) && !isBlank(botToken);
    }

    /**
     * {@code userId} → 角色名。认不出来返回 {@code Optional#empty()}，绝不回落到默认角色。
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
     * <p>与另外三个通道同一条理由：不选"先匹配到的算"，因为那等于把"他能看什么"
     * 变成配置的书写顺序。
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
                    "claw4j.channel.slack.roles 里同一个人登记了多个角色：" + String.join(", ", duplicated)
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
            log.warn("Slack 通道已启用、凭据齐全，但 claw4j.channel.slack.roles.*.users 一个人都没登记："
                    + "回调会正常收到，而任何人提问都只会收到“没有权限”。"
                    + "把提问者的 Slack user id（U 开头）填进 application-local.yml"
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

    public Map<String, RoleGrant> getRoles() {
        return roles;
    }

    public void setRoles(Map<String, RoleGrant> roles) {
        this.roles = roles == null ? new LinkedHashMap<>() : new LinkedHashMap<>(roles);
    }

    /**
     * 一个角色下登记的人。
     *
     * @param users Slack user id。空集合意味着"这个角色暂时没人"
     */
    public record RoleGrant(Set<String> users) {

        public RoleGrant {
            users = users == null ? Set.of() : Set.copyOf(users);
        }
    }
}

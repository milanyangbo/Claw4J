package io.github.claw4j.channel.dingtalk.client;

import io.github.claw4j.channel.dingtalk.DingtalkReplySender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 用钉钉的 {@code /v1.0/robot/oToMessages/batchSend} 把一段文本私聊发给某个成员。
 *
 * <p>手写 HTTP 而不是引一个 SDK：这条通道一共两个接口，一个是换 token、一个是发消息，
 * 两者都是"一个 POST 一个 JSON"（ADR-0009 的结论，前两个通道一样）。
 *
 * <p><b>三处与直觉不同，写错了都是"看起来发了"：</b>
 *
 * <ol>
 *   <li><b>{@code msgParam} 是字符串，不是对象</b>。它的值是
 *       {@code "{\"content\":\"...\"}"} 这样一段<b>序列化后的 JSON 文本</b>。
 *       按直觉写成嵌套对象 {@code {"content":"..."}} 会被钉钉判成参数非法——
 *       而错误码里那句 {@code invalidParameter} 不会告诉你是哪一层。</li>
 *   <li><b>成功响应里没有 {@code errcode}</b>。与企业微信不同，钉钉新版接口成功时返回
 *       {@code {"processQueryKey":"..."}}，失败时返回 {@code {"code":"...","message":"..."}}
 *       且 HTTP 状态非 200。所以判据是"有没有 {@code code}"，不是"errcode 是不是 0"。</li>
 *   <li><b>不做"token 被判过期就重试一次"</b>。企业微信那段逻辑在这里是<b>死代码</b>：
 *       钉钉的有效期内重复获取会返回同一张并自动续期，不存在"另一个实例先换掉了"。
 *       把那段抄过来，代价是让人以为重试这件事已经处理过了。真正会发生的是
 *       <b>限流</b>（{@code send.too.fast} / {@code send.byToken.tooFast} /
 *       {@code flowControlledStaffIdList}），这里对它记 WARN 再抛。</li>
 * </ol>
 *
 * <p>失败一律抛 {@link IllegalStateException}：调度层要能在日志里记下"这一条没发出去"。
 * 吞掉的表现是对话里没有回答、日志里也没有痕迹——与"根本没跑"一模一样。
 */
class DingtalkHttpReplySender implements DingtalkReplySender {

    private static final Logger log = LoggerFactory.getLogger(DingtalkHttpReplySender.class);

    /** 机器人单聊的文本消息模板名。换它等于换消息类型（markdown / 卡片），这一版只有文本。 */
    private static final String MSG_KEY = "sampleText";

    /** 换 token 的余量。7200 秒的凭据卡在最后一秒换，等于每次都换。 */
    private static final Duration TOKEN_SAFETY = Duration.ofMinutes(5);

    /** 文档里的有效期。读不到 {@code expireIn} 时用它，不是猜——它就是钉钉的固定值。 */
    private static final int DEFAULT_EXPIRE_IN_SECONDS = 7200;

    /** 访问令牌的 header 名。写成 {@code Authorization: Bearer} 会被判未授权。 */
    private static final String TOKEN_HEADER = "x-acs-dingtalk-access-token";

    private final HttpClient http;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final String apiBase;

    private final String clientId;

    private final String clientSecret;

    private final String robotCode;

    /** 缓存的 token 与它的截止时间。读多写少，用 volatile 两字段就够，不引缓存组件。 */
    private volatile String token;

    private volatile Instant tokenDeadline = Instant.EPOCH;

    DingtalkHttpReplySender(DingtalkProperties properties) {
        this(properties, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    /** 给用例换一个连本地假服务的客户端用；生产只走上面那个构造器。 */
    DingtalkHttpReplySender(DingtalkProperties properties, HttpClient http) {
        Objects.requireNonNull(properties, "properties");
        this.http = Objects.requireNonNull(http, "http");
        this.apiBase = properties.getApiBase().endsWith("/")
                ? properties.getApiBase().substring(0, properties.getApiBase().length() - 1)
                : properties.getApiBase();
        this.clientId = properties.getClientId();
        this.clientSecret = properties.getClientSecret();
        this.robotCode = properties.getRobotCode();
    }

    @Override
    public void send(String userId, String text) {
        Objects.requireNonNull(userId, "userId");
        if (text == null || text.isBlank()) {
            // 空文本发出去在客户端是一条空白消息，比不发更让人困惑
            return;
        }
        Result result = post(accessToken(), userId, text);
        if (result.rateLimited()) {
            log.warn("钉钉限流：code={} user={}。这不是我们发得太快就是企业级调用量到顶了，"
                    + "等一会儿再试；持续出现时看 README 里那条调用量上限", result.code(), userId);
        }
        if (!result.ok()) {
            throw new IllegalStateException("钉钉拒绝发送机器人单聊消息：code=" + result.code()
                    + " message=" + result.message() + " user=" + userId);
        }
    }

    /** 拿一张能用的 token：没过期就用手上这张，过期（或还没有）就换。 */
    private String accessToken() {
        String current = token;
        if (current != null && Instant.now().isBefore(tokenDeadline)) {
            return current;
        }
        return refreshToken();
    }

    /** {@code synchronized} + 进锁后再判一次：并发发消息时只换一次。 */
    private synchronized String refreshToken() {
        String current = token;
        if (current != null && Instant.now().isBefore(tokenDeadline)) {
            return current;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("appKey", clientId);
        payload.put("appSecret", clientSecret);

        JsonNode response = postJson(apiBase + "/v1.0/oauth2/accessToken", null, payload);
        String code = textOf(response, "code");
        if (!code.isBlank()) {
            throw new IllegalStateException("换钉钉 access_token 失败：code=" + code
                    + " message=" + textOf(response, "message")
                    + "（client-id / client-secret 错、应用被停用、IP 不在白名单，都会走到这里）");
        }
        String value = textOf(response, "accessToken");
        if (value.isBlank()) {
            throw new IllegalStateException("钉钉换了 token 却没给 accessToken：" + response);
        }
        int expireIn = intOf(response, "expireIn");
        if (expireIn <= 0) {
            expireIn = DEFAULT_EXPIRE_IN_SECONDS;
        }
        this.token = value;
        this.tokenDeadline = Instant.now().plusSeconds(expireIn).minus(TOKEN_SAFETY);
        log.info("换了一张钉钉 access_token：{} 秒后到期（提前 5 分钟换）", expireIn);
        return value;
    }

    /** @return 钉钉返回的 code／message；{@code code} 为空表示成功 */
    private Result post(String accessToken, String userId, String text) {
        // msgParam 必须是**字符串**。见类注释第 1 条：写成嵌套对象会被判参数非法。
        String msgParam = writeJson(Map.of("content", text));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("robotCode", robotCode);
        payload.put("userIds", List.of(userId));
        payload.put("msgKey", MSG_KEY);
        payload.put("msgParam", msgParam);

        JsonNode response = postJson(apiBase + "/v1.0/robot/oToMessages/batchSend", accessToken, payload);
        return new Result(textOf(response, "code"), textOf(response, "message"));
    }

    /**
     * 发一个 JSON POST。
     *
     * @param accessToken null 表示这个接口不要令牌（换 token 那个）
     */
    private JsonNode postJson(String url, String accessToken, Map<String, Object> payload) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(writeJson(payload), StandardCharsets.UTF_8));
        if (accessToken != null) {
            builder.header(TOKEN_HEADER, accessToken);
        }
        return jsonOf(send(builder.build()), url.substring(url.lastIndexOf('/') + 1));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (RuntimeException impossible) {
            throw new IllegalStateException("拼钉钉请求正文失败", impossible);
        }
    }

    /**
     * 解析成 JSON 树。失败时带上状态码与一段响应体：
     * 网关挡住时返回的是 HTML，那时候"不是 JSON"本身就是最有用的信息。
     */
    private JsonNode jsonOf(HttpResponse<String> response, String what) {
        String body = response.body() == null ? "" : response.body();
        try {
            return objectMapper.readTree(body);
        } catch (RuntimeException malformed) {
            throw new IllegalStateException("钉钉在" + what + "时返回的不是 JSON（HTTP "
                    + response.statusCode() + "）："
                    + (body.length() > 200 ? body.substring(0, 200) + "…" : body), malformed);
        }
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException interrupted) {
            // 中断要还原标志，否则上层再也看不到"有人在取消我"
            Thread.currentThread().interrupt();
            throw new IllegalStateException("调用钉钉接口时被中断：" + request.uri().getPath(), interrupted);
        } catch (IOException failure) {
            throw new IllegalStateException("调用钉钉接口失败：" + request.uri().getPath()
                    + " " + failure.getMessage(), failure);
        }
    }

    private static int intOf(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? -1 : value.asInt();
    }

    private static String textOf(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asString("");
    }

    /**
     * 一次发送的结果。两个字段就够：判成功只看 {@code code} 空不空，排错看 {@code message}。
     *
     * @param code    钉钉的错误码；空串表示成功（成功响应里根本没有这个字段）
     * @param message 人读的错误说明
     */
    private record Result(String code, String message) {

        boolean ok() {
            return code.isBlank();
        }

        /**
         * 是不是限流。三个码分别对应：整体太快、按 token 算太快、按人算被控。
         * 单拎出来记 WARN 是因为它的处理方式与其他错误不同——等一会儿就好，不是配置错。
         */
        boolean rateLimited() {
            return "send.too.fast".equals(code) || "send.byToken.tooFast".equals(code)
                    || message.contains("flowControlledStaffIdList");
        }
    }
}

package io.github.claw4j.channel.wecom.client;

import io.github.claw4j.channel.wecom.WecomReplySender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 用企业微信的 {@code /cgi-bin/message/send} 把一段文本发给某个成员。
 *
 * <p>手写 HTTP 而不是引一个 SDK：这条通道一共两个接口，一个是换 token、
 * 一个是发消息，两者都是 {@code POST/GET 一个 JSON}。为它拖进一个第三方包，
 * 换来的是"这个包什么时候跟进 Spring Boot 4"这类与本事无关的维护问题（ADR-0009 的结论：
 * 企业微信没有官方 Java SDK）。
 *
 * <p>三个必须做对的地方：
 *
 * <ol>
 *   <li><b>token 要缓存</b>。它两小时有效、每天有取用上限。每次发消息都换一张，
 *       单测里看不出来，量一上来就是"偶发发不出去"——因为限流返回的也是 JSON。</li>
 *   <li><b>提前换</b>。按 {@code expires_in} 留五分钟余量：卡在最后一秒换，
 *       正好在"我还没过期"和"平台已经认为过期"之间，报的是 {@code 42001}。</li>
 *   <li><b>token 被判过期时重试一次</b>。多实例部署时另一个实例可能刚好换过 token，
 *       旧的那张立刻失效——这不是错误，换一张重发就对。重试只做一次：
 *       连着两次都说不认，那是凭据错，再试也没用。</li>
 * </ol>
 *
 * <p>失败一律抛 {@link IllegalStateException}：调度层要能在日志里记下"这一条没发出去"。
 * 吞掉的表现是群里没有回答、日志里也没有痕迹——与"根本没跑"一模一样。
 */
class WecomHttpReplySender implements WecomReplySender {

    private static final Logger log = LoggerFactory.getLogger(WecomHttpReplySender.class);

    private static final String TEXT = "text";

    /** 换 token 的余量。见类注释第 2 条。 */
    private static final Duration TOKEN_SAFETY = Duration.ofMinutes(5);

    /** 这两个（及 41001）是"token 不认了"，换一张重发一次。 */
    private static final Set<Integer> TOKEN_REJECTED = Set.of(40014, 41001, 42001);

    private static final int DEFAULT_EXPIRES_IN_SECONDS = 7200;

    private final HttpClient http;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final String apiBase;

    private final String corpId;

    private final String corpSecret;

    private final int agentId;

    /** 缓存的 token 与它的截止时间。读多写少，用 volatile 两字段就够，不引缓存组件。 */
    private volatile String token;

    private volatile Instant tokenDeadline = Instant.EPOCH;

    WecomHttpReplySender(WecomProperties properties) {
        this(properties, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    /** 给用例换一个连本地假服务的客户端用；生产只走上面那个构造器。 */
    WecomHttpReplySender(WecomProperties properties, HttpClient http) {
        Objects.requireNonNull(properties, "properties");
        this.http = Objects.requireNonNull(http, "http");
        this.apiBase = properties.getApiBase().endsWith("/")
                ? properties.getApiBase().substring(0, properties.getApiBase().length() - 1)
                : properties.getApiBase();
        this.corpId = properties.getCorpId();
        this.corpSecret = properties.getCorpSecret();
        this.agentId = properties.getAgentId();
    }

    @Override
    public void send(String userId, String text) {
        Objects.requireNonNull(userId, "userId");
        if (text == null || text.isBlank()) {
            // 空文本发出去在客户端是一条空白消息，比不发更让人困惑
            return;
        }
        Result result = post(accessToken(), userId, text);
        if (TOKEN_REJECTED.contains(result.errcode())) {
            log.info("企业微信不认这张 access_token（errcode={}），换一张重发一次：user={}",
                    result.errcode(), userId);
            // 这里必须 renew 而不是 accessToken：手上这张在本地看着还没到期，
            // 平台已经先一步把它废了（另一个实例刚换过）。沿用本地判断会**重发同一张**，
            // 于是第二次照样是 42001——用户看到的是"消息没发出去"，日志里却是"重试过了"。
            result = post(renewToken(), userId, text);
        }
        if (result.errcode() != 0) {
            throw new IllegalStateException("企业微信拒绝发送应用消息：errcode=" + result.errcode()
                    + " errmsg=" + result.errmsg() + " user=" + userId);
        }
    }

    /**
     * 拿一张能用的 token：没过期就用手上这张，过期（或还没有）就换。
     */
    private String accessToken() {
        String current = token;
        if (current != null && Instant.now().isBefore(tokenDeadline)) {
            return current;
        }
        return refreshToken();
    }

    /**
     * 本地判断"该换了"才换。{@code synchronized} + 进锁后再判一次：并发发消息时只换一次——
     * 每天有取用上限，重复换是会被限流的。
     */
    private synchronized String refreshToken() {
        String current = token;
        if (current != null && Instant.now().isBefore(tokenDeadline)) {
            return current;
        }
        return renewToken();
    }

    /** 不管本地怎么判，强制去换一张。只在"平台已经明确说不认了"时用。 */
    private synchronized String renewToken() {
        String url = apiBase + "/cgi-bin/gettoken?corpid=" + encode(corpId) + "&corpsecret=" + encode(corpSecret);
        JsonNode response = getJson(url);
        int code = intOf(response, "errcode");
        if (code != 0) {
            throw new IllegalStateException("换企业微信 access_token 失败：errcode=" + code
                    + " errmsg=" + textOf(response, "errmsg")
                    + "（凭据错、IP 不在可信列表、应用被停用，都会走到这里）");
        }
        String value = textOf(response, "access_token");
        if (value.isBlank()) {
            throw new IllegalStateException("企业微信换了 token 却没给 access_token：" + response);
        }
        int expiresIn = intOf(response, "expires_in");
        if (expiresIn <= 0) {
            expiresIn = DEFAULT_EXPIRES_IN_SECONDS;
        }
        this.token = value;
        this.tokenDeadline = Instant.now().plusSeconds(expiresIn).minus(TOKEN_SAFETY);
        log.info("换了一张企业微信 access_token：{} 秒后到期（提前 5 分钟换）", expiresIn);
        return value;
    }

    /** @return 平台返回的 errcode／errmsg；{@code 0} 是成功 */
    private Result post(String accessToken, String userId, String text) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("touser", userId);
        payload.put("msgtype", TEXT);
        payload.put("agentid", agentId);
        payload.put(TEXT, Map.of("content", text));
        // safe=0：企业微信的"保密消息"会要求成员在客户端里点开才可见，对话场景里那是负担
        payload.put("safe", 0);

        String body;
        try {
            body = objectMapper.writeValueAsString(payload);
        } catch (RuntimeException impossible) {
            throw new IllegalStateException("拼企业微信消息正文失败", impossible);
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(apiBase + "/cgi-bin/message/send?access_token="
                        + encode(accessToken)))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        JsonNode response = send(request);
        return new Result(intOf(response, "errcode"), textOf(response, "errmsg"));
    }

    private JsonNode getJson(String url) {
        return send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build());
    }

    /** 解析成 JSON 树。失败带上一段响应体：企业微信的错误常常只有 errmsg 里那句中文。 */
    private JsonNode send(HttpRequest request) {
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException interrupted) {
            // 中断要还原标志，否则上层再也看不到"有人在取消我"
            Thread.currentThread().interrupt();
            throw new IllegalStateException("调用企业微信接口时被中断：" + request.uri().getPath(), interrupted);
        } catch (IOException failure) {
            throw new IllegalStateException("调用企业微信接口失败：" + request.uri().getPath()
                    + " " + failure.getMessage(), failure);
        }
        String body = response.body() == null ? "" : response.body();
        try {
            return objectMapper.readTree(body);
        } catch (RuntimeException malformed) {
            throw new IllegalStateException("企业微信返回的不是 JSON（HTTP " + response.statusCode() + "）："
                    + (body.length() > 200 ? body.substring(0, 200) + "…" : body), malformed);
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

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** 一次调用的结果。两个字段就够：判成功只看 errcode，排错看 errmsg。 */
    private record Result(int errcode, String errmsg) {
    }
}

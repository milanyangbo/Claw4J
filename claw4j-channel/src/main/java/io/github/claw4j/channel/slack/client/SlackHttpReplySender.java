package io.github.claw4j.channel.slack.client;

import io.github.claw4j.channel.slack.SlackConversation;
import io.github.claw4j.channel.slack.SlackReplySender;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用 Slack 的 {@code chat.postMessage} 把一段文本发到某个频道（或某条线程）。
 *
 * <p>手写 HTTP 而不是引一个 SDK：这条通道一共两个接口——收（回调，什么都不要）与发
 * （一个 POST 一个 JSON）。Slack 的 Java SDK 是给 Socket Mode 那类形态用的，
 * 而这一版走 Events API 的 HTTP 回调（理由见票面第一节）。
 *
 * <p><b>四处与直觉不同，写错了都是"看起来发了"：</b>
 *
 * <ol>
 *   <li><b>HTTP 200 不代表成功</b>。失败时也回 200，只是 body 里 {@code ok} 是 {@code false}。
 *       官方原话："Callers should always check the value of the {@code ok} parameter in the response"。
 *       照抄钉钉（判有没有 {@code code}）或企微（判 {@code errcode}）都会写出一个永远成功的发送器。</li>
 *   <li><b>同一个频道 1 秒只能发 1 条</b>。一条长答案会被切成几段连着发，
 *       而 {@code InboundDispatcher} 遇到异常会<b>停止后续段落</b>——撞上限流的表现是用户
 *       收到半截答案加一行 ERROR。所以这里主动排队（{@link #minInterval}），而不是等着被 429。</li>
 *   <li><b>令牌走 {@code Authorization: Bearer}</b>，长期有效，不用刷新。
 *       这一点与钉钉相反：那边每 7200 秒换一次，这里换 token 的代码根本不存在。</li>
 *   <li><b>{@code thread_ts} 必须是<b>线程根</b>的 ts</b>，不是某条回复的 ts。
 *       传错的表现是"答案开了一条新线程"，群里看到的还是两条独立消息——不报错。</li>
 * </ol>
 *
 * <p>失败一律抛 {@link IllegalStateException}：调度层要能在日志里记下"这一条没发出去"。
 * 吞掉的表现是对话里没有回答、日志里也没有痕迹——与"根本没跑"一模一样。
 */
class SlackHttpReplySender implements SlackReplySender {

    private static final Logger log = LoggerFactory.getLogger(SlackHttpReplySender.class);

    /** 文档里 {@code chat.postMessage} 的路径。 */
    private static final String POST_MESSAGE = "/chat.postMessage";

    /** 限流时最多再试一次。两次都撞上说明真的发得太密，硬撑只会让后面几条也排队。 */
    private static final int RATE_LIMIT_RETRIES = 1;

    private final HttpClient http;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final String apiBase;

    private final String botToken;

    private final Duration minInterval;

    /** 每个频道下一次可以发的时刻。读多写少，一个 map 就够，不引缓存组件。 */
    private final Map<String, Long> nextSlotMillis = new ConcurrentHashMap<>();

    SlackHttpReplySender(SlackProperties properties) {
        this(properties, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    /** 给用例换一个连本地假服务的客户端用；生产只走上面那个构造器。 */
    SlackHttpReplySender(SlackProperties properties, HttpClient http) {
        Objects.requireNonNull(properties, "properties");
        this.http = Objects.requireNonNull(http, "http");
        String base = properties.getApiBase();
        this.apiBase = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.botToken = properties.getBotToken();
        this.minInterval = properties.getMinInterval();
    }

    @Override
    public void send(String conversationId, String text) {
        if (text == null || text.isBlank()) {
            // 空文本发出去在客户端是一条空白消息，比不发更让人困惑
            return;
        }
        String channel = SlackConversation.channelOf(conversationId);
        if (channel.isBlank()) {
            throw new IllegalStateException("要发一条 Slack 消息，但 conversationId 里没有频道："
                    + conversationId);
        }
        String threadTs = SlackConversation.threadTsOf(conversationId);

        awaitTurn(channel);
        Result result = post(channel, threadTs, text);
        for (int attempt = 0; attempt < RATE_LIMIT_RETRIES && result.rateLimited(); attempt++) {
            log.warn("Slack 限流：channel={} error={}，等 {} ms 再试一次。"
                            + "同频道 1 秒只允许 1 条，长答案会连着发好几段", channel, result.error(),
                    result.retryAfterMillis());
            sleep(result.retryAfterMillis(), channel);
            result = post(channel, threadTs, text);
        }
        if (!result.ok()) {
            throw new IllegalStateException("Slack 拒绝发送消息：error=" + result.error()
                    + " channel=" + channel + " threadTs=" + (threadTs.isBlank() ? "（顶层）" : threadTs)
                    + "。not_in_channel 表示机器人不在这个频道；channel_not_found 表示频道 id 不对；"
                    + "invalid_auth / not_authed 表示 bot-token 不对");
        }
    }

    /**
     * 排到本频道的下一个空位，必要时等一下。
     *
     * <p>刻意做成"预约下一个时刻"而不是"记上次发送的时刻"：两条消息几乎同时发出时
     * （回执与短答案就是），后者两条都会看到"已经隔了 0 毫秒"然后一起发。
     * {@code compute} 是原子的，所以并发的那两条会拿到相差一个间隔的两个时刻。
     *
     * <p>第一条不等：拿到的是 {@code now}。这一点很重要——回调线程上也会走到这里
     * （认人失败、线程池满那两条分支是同步发的），多等一个间隔就可能把 3 秒的应答窗口挤掉。
     */
    private void awaitTurn(String channel) {
        long interval = minInterval == null ? 0 : minInterval.toMillis();
        if (interval <= 0) {
            return;
        }
        long[] slot = new long[1];
        nextSlotMillis.compute(channel, (key, next) -> {
            long now = System.currentTimeMillis();
            long at = next == null || next <= now ? now : next;
            slot[0] = at;
            return at + interval;
        });
        long wait = slot[0] - System.currentTimeMillis();
        if (wait > 0) {
            sleep(wait, channel);
        }
    }

    /** @return Slack 的 {@code ok} 与 {@code error}；429 时带上 {@code Retry-After} */
    private Result post(String channel, String threadTs, String text) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("channel", channel);
        payload.put("text", text);
        if (!threadTs.isBlank()) {
            payload.put("thread_ts", threadTs);
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(apiBase + POST_MESSAGE))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("Authorization", "Bearer " + botToken)
                .POST(HttpRequest.BodyPublishers.ofString(writeJson(payload), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = send(request);
        long retryAfter = retryAfterMillis(response);
        JsonNode body = jsonOf(response);
        if (response.statusCode() == 429) {
            return new Result(false, "ratelimited", retryAfter);
        }
        boolean ok = body != null && body.get("ok") != null && body.get("ok").asBoolean(false);
        String error = body == null ? "" : textOf(body, "error");
        return new Result(ok, error, retryAfter);
    }

    /**
     * {@code Retry-After} 的单位是<b>秒</b>。读不到就给一个保守值——
     * 比"立刻重试"好：立刻重试几乎必然再撞一次。
     */
    private static long retryAfterMillis(HttpResponse<String> response) {
        String header = response.headers().firstValue("Retry-After").orElse("");
        if (header.isBlank()) {
            return 1000;
        }
        try {
            return Math.max(0, Long.parseLong(header.strip())) * 1000L;
        } catch (NumberFormatException notANumber) {
            return 1000;
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (RuntimeException impossible) {
            throw new IllegalStateException("拼 Slack 请求正文失败", impossible);
        }
    }

    /**
     * 解析成 JSON 树。{@code null} 表示响应体是空的或不是 JSON——
     * 网关挡住时返回的是 HTML，那时候"不是 JSON"本身就是最有用的信息，所以带上一段原文。
     */
    private JsonNode jsonOf(HttpResponse<String> response) {
        String body = response.body() == null ? "" : response.body();
        if (body.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(body);
        } catch (RuntimeException malformed) {
            log.warn("Slack 返回的不是 JSON（HTTP {}）：{}", response.statusCode(),
                    body.length() > 200 ? body.substring(0, 200) + "…" : body);
            return null;
        }
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException interrupted) {
            // 中断要还原标志，否则上层再也看不到"有人在取消我"
            Thread.currentThread().interrupt();
            throw new IllegalStateException("调用 Slack 接口时被中断：" + request.uri().getPath(), interrupted);
        } catch (IOException failure) {
            throw new IllegalStateException("调用 Slack 接口失败：" + request.uri().getPath()
                    + " " + failure.getMessage(), failure);
        }
    }

    private static void sleep(long millis, String channel) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等 Slack 的发送间隔时被中断：channel=" + channel, interrupted);
        }
    }

    private static String textOf(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asString("");
    }

    /**
     * 一次发送的结果。三个字段就够：成功看 {@code ok}，排错看 {@code error}，限流看重试间隔。
     *
     * @param ok                Slack 的 {@code ok} 字段，<b>不是</b> HTTP 状态码
     * @param error             {@code ok=false} 时的错误码；空串表示没给
     * @param retryAfterMillis  {@code Retry-After} 换算成毫秒；不是限流时无意义
     */
    private record Result(boolean ok, String error, long retryAfterMillis) {

        /**
         * 是不是限流。两个码都见过：{@code ratelimited}（文档 Errors 表里的那个）与
         * {@code rate_limited}（同表里另一个），加上 429 直接判。
         * 单拎出来是因为它的处理方式与其他错误不同——等一会儿就好，不是配置错。
         */
        boolean rateLimited() {
            return "ratelimited".equals(error) || "rate_limited".equals(error);
        }
    }
}

package io.github.claw4j.channel.slack.client;

import io.github.claw4j.channel.spi.ChatChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Slack 通道的声明。它没有连接要维护：入站是 {@link SlackCallbackController} 那个端点，
 * 出站是一次 {@code POST}，两者都不需要"先连上"。
 *
 * <p>仍然保留它，是因为 {@link ChatChannel} 是"这个部署接了哪些通道"的唯一声明，
 * {@link #id()} 与 {@code SlackOutboundDelivery#CHANNEL} 同口径。少一个 bean 也能跑，
 * 但下次有人问"接了哪些通道"时，答案就只剩 {@code yml} 里有没有配凭据。
 *
 * <p>{@link #start()} 只记日志，不做"回调地址通不通"的探活：那个地址是公网的，
 * 从容器里探自己往往走不到真实链路（反向代理、白名单都会让结论反过来），
 * 而探不通就让启动失败，会把"后台还差填一个 URL"变成"服务起不来"。
 */
public class SlackChannel implements ChatChannel {

    private static final Logger log = LoggerFactory.getLogger(SlackChannel.class);

    /**
     * 认出人之后先推的一句"收到了"。
     *
     * <p>回调里回的是空串，从用户发出消息到收到答案的十几秒里会话没有任何动静，
     * 分不清"在算"与"没收到"；Slack 的机器人没有"正在输入"，这一句只能自己发。
     *
     * <p>在频道里它作为线程里的第一条回执出现（{@code thread_ts} 是原消息），频道只多一行。
     */
    public static final String RECEIPT_REPLY = "收到，正在查，算完就发给你。";

    private final SlackProperties properties;

    public SlackChannel(SlackProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Override
    public String id() {
        return "slack";
    }

    @Override
    public void start() {
        log.info("Slack 通道就绪：事件请求地址 POST {}（App 后台 → Event Subscriptions → Request URL 填同一个地址，"
                        + "订阅 app_mention 与 message.im）。入站会走 验签 → 解析 → 去重 → 认人 → 异步执行 → 回推；"
                        + "bot token {}",
                SlackCallbackController.PATH, mask(properties.getBotToken()));
    }

    @Override
    public void stop() {
        log.info("Slack 通道关闭");
    }

    /**
     * 令牌只露前 5 位（就是 {@code xoxb-} 那一段）。
     *
     * <p>它比 {@code robotCode} 严重得多：钉钉那个只是标识，这个是能替应用说话的密钥。
     * 完整串进过一次日志，就等于把工作区的发言权交出去了，而日志往往比密钥活得久。
     */
    private static String mask(String botToken) {
        if (botToken == null || botToken.length() <= 5) {
            return "****";
        }
        return botToken.substring(0, 5) + "****";
    }
}

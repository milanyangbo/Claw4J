package io.github.claw4j.channel.wecom.client;

import io.github.claw4j.channel.spi.ChatChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * 企业微信通道。它没有连接要维护——入站是 {@link WecomCallbackController} 那两个端点，
 * 出站是一次 {@code POST}，两者都不需要"先连上"。
 *
 * <p>那为什么还要有它：{@link ChatChannel} 是这个项目里"一个通道存在"的唯一声明，
 * 它的 {@code id()} 与 {@code OutboundDelivery#channel()} 同口径。HTTP 通道同样是这么做的
 * （端点由容器挂载，{@code start()} 只记一行日志）。少一个 bean 也能跑，
 * 但下次有人问"这个部署接了哪些通道"时，答案就只剩 {@code yml} 里有没有配凭据。
 *
 * <p>{@link #start()} 只记日志，<b>不做"回调地址通不通"的探活</b>：那个地址是公网的，
 * 从容器里探自己往往走不到真实链路（反向代理、白名单都会让结论反过来），
 * 而探不通就让启动失败，反而会把"只差后台填一个 URL"变成"服务起不来"。
 * 与飞书那条"连不上就抛"的区别在于：飞书的连接是它自己发起的，连不上就是真的收不到消息。
 */
public class WecomChannel implements ChatChannel {

    private static final Logger log = LoggerFactory.getLogger(WecomChannel.class);

    /**
     * 认出人之后先推的一句"收到了"。
     *
     * <p>为什么需要它：回调里回的是空串（不做被动回复），于是从用户发出消息到收到答案的
     * 十几秒里，对话框没有任何动静——分不清"在算"与"没收到"。企业微信的客户端没有"正在输入"，
     * 所以这一句只能自己发。
     *
     * <p>为什么是这句话而不是"正在思考…"：用户关心的是"我的问题有没有人接"，
     * 而不是系统在做什么。带上"算完就发给你"是说清"还要等，但不用再问一遍"。
     */
    public static final String RECEIPT_REPLY = "收到，正在查，算完就发给你。";

    private final WecomProperties properties;

    public WecomChannel(WecomProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Override
    public String id() {
        return "wecom";
    }

    @Override
    public void start() {
        log.info("企业微信通道就绪：回调地址 POST {}（后台“接收消息”里填同一个地址，"
                        + "corpId={} agentId={}）。入站会走 验签 → 解密 → 去重 → 认人 → 异步执行 → 回推",
                WecomCallbackController.PATH, mask(properties.getCorpId()), properties.getAgentId());
    }

    @Override
    public void stop() {
        log.info("企业微信通道关闭");
    }

    /** corpId 不是密钥，但也没必要整串进日志：够对上配置就行。 */
    private static String mask(String corpId) {
        if (corpId == null || corpId.length() <= 4) {
            return "****";
        }
        return corpId.substring(0, 4) + "****";
    }
}

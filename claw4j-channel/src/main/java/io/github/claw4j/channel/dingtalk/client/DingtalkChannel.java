package io.github.claw4j.channel.dingtalk.client;

import io.github.claw4j.channel.spi.ChatChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * 钉钉通道。它没有连接要维护——入站是 {@link DingtalkCallbackController} 那个端点，
 * 出站是一次 {@code POST}，两者都不需要"先连上"。
 *
 * <p>那为什么还要有它：{@link ChatChannel} 是这个项目里"一个通道存在"的唯一声明，
 * 它的 {@code id()} 与 {@code DingtalkOutboundDelivery#CHANNEL} 同口径。
 * 另外两个通道也是这么做的。少一个 bean 也能跑，但下次有人问"这个部署接了哪些通道"时，
 * 答案就只剩 {@code yml} 里有没有配凭据。
 *
 * <p>{@link #start()} 只记日志，<b>不做"回调地址通不通"的探活</b>：那个地址是公网的，
 * 从容器里探自己往往走不到真实链路（反向代理、白名单都会让结论反过来），
 * 而探不通就让启动失败，反而会把"只差后台填一个 URL"变成"服务起不来"。
 */
public class DingtalkChannel implements ChatChannel {

    private static final Logger log = LoggerFactory.getLogger(DingtalkChannel.class);

    /**
     * 认出人之后先推的一句"收到了"。
     *
     * <p>为什么需要它：回调里回的是空串（不做被动回复），于是从用户发出消息到收到答案的
     * 十几秒里，对话框没有任何动静——分不清"在算"与"没收到"。钉钉的客户端与企微一样
     * 没有"正在输入"，所以这一句只能自己发。
     *
     * <p>文案与前两个通道一致：用户关心的是"我的问题有没有人接"，
     * 而不是系统在做什么；带上"算完就发给你"是说清"还要等，但不用再问一遍"。
     */
    public static final String RECEIPT_REPLY = "收到，正在查，算完就发给你。";

    private final DingtalkProperties properties;

    public DingtalkChannel(DingtalkProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Override
    public String id() {
        return "dingtalk";
    }

    @Override
    public void start() {
        log.info("钉钉通道就绪：消息接收地址 POST {}（开发者后台“机器人 → 消息接收地址”里填同一个地址，"
                        + "robotCode={}）。入站会走 验签 → 解析 → 去重 → 认人 → 异步执行 → 回推",
                DingtalkCallbackController.PATH, mask(properties.getRobotCode()));
    }

    @Override
    public void stop() {
        log.info("钉钉通道关闭");
    }

    /** robotCode 不是密钥，但也没必要整串进日志：够对上配置就行。 */
    private static String mask(String robotCode) {
        if (robotCode == null || robotCode.length() <= 4) {
            return "****";
        }
        return robotCode.substring(0, 4) + "****";
    }
}

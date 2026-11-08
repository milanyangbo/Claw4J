package io.github.claw4j.channel.feishu.sdk;

import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.ws.Client;

/**
 * 生产实现：包住 {@code new Client.Builder(appId, secret)}。这个包里唯一碰 SDK 连接对象的地方。
 *
 * <p>两个参数都不在这里判：凭据齐全与否由装配条件管（不齐时整块不装配），
 * 连不上由 {@link FeishuLongConnectionChannel} 分类并决定重试。这里只做"造一个"。
 */
public final class LarkFeishuConnectionFactory implements FeishuConnectionFactory {

    private final FeishuChannelProperties properties;

    public LarkFeishuConnectionFactory(FeishuChannelProperties properties) {
        this.properties = properties;
    }

    @Override
    public FeishuConnection open(EventDispatcher events) {
        // 长连接模式不需要 verification token 与 encrypt key：那两个参数是给公网回调做签名校验的。
        Client client = new Client.Builder(properties.getAppId(), properties.getAppSecret())
                .eventHandler(events)
                .build();
        return new LarkConnection(client);
    }

    /**
     * 一对一转发。{@code close()} 会把 SDK 的 {@code userClosed} 置真，于是它自己不再重连——
     * 我们靠这一点保证"同一时刻只有我们这一条连接"，不会出现 SDK 与我们的重连各建一条。
     */
    private record LarkConnection(Client client) implements FeishuConnection {

        @Override
        public void start() {
            client.start();
        }

        @Override
        public void awaitReady(long timeoutMillis) throws Exception {
            client.awaitReady(timeoutMillis);
        }

        @Override
        public void close() {
            client.close();
        }
    }
}

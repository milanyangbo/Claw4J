package io.github.claw4j.channel.feishu.sdk;

import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.lark.oapi.ws.Client;
import io.github.claw4j.channel.feishu.FeishuInboundEvent;
import io.github.claw4j.channel.feishu.FeishuReplySender;
import io.github.claw4j.channel.feishu.InboundDispatcher;
import io.github.claw4j.channel.spi.ChatChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * 飞书长连接通道：SDK 主动外连（不需要公网 IP、域名、内网穿透），把收到的事件交给入站调度。
 *
 * <p>本类只做三件事：建连、把回调转给 {@link InboundDispatcher}、关连接。"什么时候 ACK、
 * 失败了说什么、长回答怎么切"全在调度层——那是唯一能被默认构建测到的部分，逻辑不该往这里搬。
 *
 * <p>两个刻意的选择：
 * <ul>
 *   <li><b>连不上就抛，让启动失败。</b>SDK 自己会重连，所以"没连上"很容易被误当成"还在连"。
 *       先 {@code start()} 再 {@code awaitReady(超时)}：超时没就绪，直接让整个应用起不来。
 *       带着一个收不到消息的通道在跑，是演示现场最难查的故障（ADR-0009 的既有约定）。</li>
 *   <li><b>回调里绝不抛。</b>事件线程抛异常会触发平台重推，而重推的往往是同一条处理不了的消息，
 *       于是"失败 → 重推 → 再失败"能跑好几轮。异常收在这里记下来，比交给平台循环更可控。</li>
 * </ul>
 */
public class FeishuLongConnectionChannel implements ChatChannel {

    private static final Logger log = LoggerFactory.getLogger(FeishuLongConnectionChannel.class);

    /** 认不出消息时的答复。说清"能怎么问"，比回一句"不支持"有用。 */
    static final String UNSUPPORTED_REPLY =
            "我目前只认文字提问：直接把问题打在消息里就行（例如「上周华东区成交额多少」）。";

    private final FeishuChannelProperties properties;

    private final InboundDispatcher dispatcher;

    private final FeishuMessageTranslator translator;

    private final FeishuReplySender replySender;

    /** 建连成功之后才有值；{@code stop()} 之后清空，所以它是可重复调用的。 */
    private volatile Client client;

    public FeishuLongConnectionChannel(FeishuChannelProperties properties,
                                       InboundDispatcher dispatcher,
                                       FeishuMessageTranslator translator,
                                       FeishuReplySender replySender) {
        this.properties = properties;
        this.dispatcher = dispatcher;
        this.translator = translator;
        this.replySender = replySender;
    }

    @Override
    public String id() {
        return "feishu";
    }

    @Override
    public void start() {
        EventDispatcher eventDispatcher = EventDispatcher.newBuilder("", "")
                .onP2MessageReceiveV1(new ImService.P2MessageReceiveV1Handler() {
                    @Override
                    public void handle(P2MessageReceiveV1 event) {
                        onMessage(event);
                    }
                })
                .build();
        // 长连接模式不需要 verification token 与 encrypt key：那两个参数是给公网回调做签名校验的。
        Client starting = new Client.Builder(properties.getAppId(), properties.getAppSecret())
                .eventHandler(eventDispatcher)
                .build();
        try {
            starting.start();
            starting.awaitReady(properties.getReadyTimeout().toMillis());
        } catch (Exception failure) {
            closeQuietly(starting);
            throw new IllegalStateException(
                    "飞书长连接没建起来（appId=" + mask(properties.getAppId()) + "）：" + failure.getMessage()
                            + "。凭据错、应用未发布或未开启长连接、本机出不去，都会走到这里。", failure);
        }
        this.client = starting;
        log.info("飞书通道就绪：长连接已建立（appId={}），入站事件会走 去重 → ACK → 异步执行 → 回推",
                mask(properties.getAppId()));
    }

    @Override
    public void stop() {
        Client closing = this.client;
        this.client = null;
        if (closing != null) {
            closing.close();
            log.info("飞书通道关闭");
        }
    }

    /**
     * 事件回调。这里返回就等于 ACK——调度层的 {@code accept} 只做去重与提交，不等模型。
     */
    private void onMessage(P2MessageReceiveV1 event) {
        try {
            Optional<FeishuInboundEvent> inbound = translator.translate(event);
            if (inbound.isEmpty()) {
                replyUnsupported(event);
                return;
            }
            dispatcher.accept(inbound.get());
        } catch (RuntimeException failure) {
            log.error("飞书事件处理失败（不抛出，避免平台重推一条同样处理不了的消息）", failure);
        }
    }

    /** 翻不出来的消息（图片、富文本、或只有 @ 没有正文）也要回一句，群里的沉默分不清"没收到"和"不会"。 */
    private void replyUnsupported(P2MessageReceiveV1 event) {
        String chatId = chatIdOf(event);
        if (chatId == null) {
            log.warn("翻不出来的飞书事件里连 chat_id 都没有，只能记日志");
            return;
        }
        try {
            replySender.send(chatId, UNSUPPORTED_REPLY);
        } catch (RuntimeException failure) {
            log.error("回推'只认文字'失败：chatId={}", chatId, failure);
        }
    }

    private static String chatIdOf(P2MessageReceiveV1 event) {
        if (event == null || event.getEvent() == null || event.getEvent().getMessage() == null) {
            return null;
        }
        return event.getEvent().getMessage().getChatId();
    }

    private static void closeQuietly(Client client) {
        try {
            client.close();
        } catch (RuntimeException ignored) {
            log.debug("建连失败后关闭客户端时又出错，忽略", ignored);
        }
    }

    /** App ID 不是密钥，但也没必要整串进日志：够对上配置就行。 */
    private static String mask(String appId) {
        if (appId == null || appId.length() <= 4) {
            return "****";
        }
        return appId.substring(0, 4) + "****";
    }
}

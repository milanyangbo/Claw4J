package io.github.claw4j.channel.feishu.sdk;

import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.event.cardcallback.P2CardActionTriggerHandler;
import com.lark.oapi.event.cardcallback.model.CallBackCard;
import com.lark.oapi.event.cardcallback.model.CallBackToast;
import com.lark.oapi.event.cardcallback.model.P2CardActionTrigger;
import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerData;
import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerResponse;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.lark.oapi.ws.Client;
import io.github.claw4j.channel.feishu.FeishuApprovalCard;
import io.github.claw4j.channel.feishu.FeishuInboundEvent;
import io.github.claw4j.channel.feishu.FeishuReplySender;
import io.github.claw4j.channel.kernel.InboundDispatcher;
import io.github.claw4j.channel.spi.ChatChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
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

    /**
     * 翻不出来的消息回什么。
     *
     * <p>刻意不点具体原因：翻不出来的情形有三种（图片/富文本、只有 @ 没有正文、事件里缺发送者），
     * 而这一层只知道"翻不出来"。写死一个原因会在另外两种情形上说错话——说错原因比只说"处理不了"更糟，
     * 用户会照着错的原因去改自己的操作。
     */
    static final String CANNOT_HANDLE_REPLY =
            "这条消息我没法处理：把问题直接打成文字发给我就行（例如「上周华东区成交额多少」）。";

    private final FeishuChannelProperties properties;

    private final InboundDispatcher dispatcher;

    private final FeishuMessageTranslator translator;

    private final FeishuReplySender replySender;

    /**
     * 卡片按钮的处理（P03）。可以为 null——没有装配审批的部署里没人会发卡片，
     * 此时不注册卡片回调。留一个"注册了但没东西可判"的回调，表现是每次点击都回一句
     * "没装审批"，那比没有按钮更让人困惑。
     */
    private final FeishuApprovalActions approvals;

    /** 建连成功之后才有值；{@code stop()} 之后清空，所以它是可重复调用的。 */
    private volatile Client client;

    public FeishuLongConnectionChannel(FeishuChannelProperties properties,
                                       InboundDispatcher dispatcher,
                                       FeishuMessageTranslator translator,
                                       FeishuReplySender replySender,
                                       FeishuApprovalActions approvals) {
        this.properties = properties;
        this.dispatcher = dispatcher;
        this.translator = translator;
        this.replySender = replySender;
        this.approvals = approvals;
    }

    @Override
    public String id() {
        return "feishu";
    }

    @Override
    public void start() {
        EventDispatcher.Builder events = EventDispatcher.newBuilder("", "")
                .onP2MessageReceiveV1(new ImService.P2MessageReceiveV1Handler() {
                    @Override
                    public void handle(P2MessageReceiveV1 event) {
                        onMessage(event);
                    }
                });
        if (approvals != null) {
            // 卡片按钮走的是另一类事件（card.action.trigger），与消息事件共用同一条长连接：
            // 于是"点一下批准"不需要公网回调地址，也不需要另开一个入口（P03 的前提）。
            events.onP2CardActionTrigger(new P2CardActionTriggerHandler() {
                @Override
                public P2CardActionTriggerResponse handle(P2CardActionTrigger event) {
                    return onCardAction(event);
                }
            });
        }
        EventDispatcher eventDispatcher = events.build();
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
                replyCannotHandle(event);
                return;
            }
            dispatcher.accept(inbound.get());
        } catch (RuntimeException failure) {
            log.error("飞书事件处理失败（不抛出，避免平台重推一条同样处理不了的消息）", failure);
        }
    }

    /**
     * 卡片按钮的回调（P03）：返回的这个对象就是"就地换卡片 + 一句提示"。
     *
     * <p><b>不抛</b>，同 {@link #onMessage} 的理由：抛出去平台会重推同一个点不动的按钮。
     * 但这里多一层考虑——点击是人在等回音（toast 会转圈），所以失败也要回一句，
     * 而不是安静地什么都不做。
     */
    private P2CardActionTriggerResponse onCardAction(P2CardActionTrigger event) {
        try {
            return responseOf(approvals.on(clickOf(event)));
        } catch (RuntimeException failure) {
            log.error("飞书卡片回调失败（不抛出，避免平台重推同一次点击）", failure);
            return responseOf(FeishuApprovalActions.Outcome.of("error",
                    "这次点击没处理成功，稍后再点一次；如果一直这样，让管理员看服务日志。"));
        }
    }

    /** 卡片事件 → 三个值：谁点的、哪一条、批还是拒。别的都不进来，判定不需要它们。 */
    private static FeishuApprovalActions.Click clickOf(P2CardActionTrigger event) {
        P2CardActionTriggerData data = event == null ? null : event.getEvent();
        String openId = data == null || data.getOperator() == null ? null : data.getOperator().getOpenId();
        Map<String, Object> value = data == null || data.getAction() == null
                || data.getAction().getValue() == null
                ? Map.of()
                : data.getAction().getValue();
        return new FeishuApprovalActions.Click(openId,
                textOf(value.get(FeishuApprovalCard.VALUE_APPROVAL)),
                textOf(value.get(FeishuApprovalCard.VALUE_DECISION)));
    }

    private static String textOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 结果 → 飞书的响应体。{@code type=raw} 表示"这一整张卡片换成我给的结构"，
     * 于是同一条消息就地变了，群里不会出现第二条自相矛盾的卡片。
     */
    private static P2CardActionTriggerResponse responseOf(FeishuApprovalActions.Outcome outcome) {
        P2CardActionTriggerResponse response = new P2CardActionTriggerResponse();
        CallBackToast toast = new CallBackToast();
        toast.setType(outcome.toastType());
        toast.setContent(outcome.toastText());
        response.setToast(toast);
        if (outcome.card() != null) {
            CallBackCard card = new CallBackCard();
            card.setType("raw");
            card.setData(outcome.card());
            response.setCard(card);
        }
        return response;
    }

    /** 翻不出来的消息也要回一句：群里的沉默分不清"没收到"和"不会"。 */
    private void replyCannotHandle(P2MessageReceiveV1 event) {
        String chatId = chatIdOf(event);
        if (chatId == null) {
            log.warn("翻不出来的飞书事件里连 chat_id 都没有，只能记日志");
            return;
        }
        try {
            replySender.send(chatId, CANNOT_HANDLE_REPLY);
        } catch (RuntimeException failure) {
            log.error("回推'处理不了'失败：chatId={}", chatId, failure);
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

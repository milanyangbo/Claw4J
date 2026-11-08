package io.github.claw4j.channel.feishu.sdk;

import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.event.model.Header;
import com.lark.oapi.service.im.v1.model.EventMessage;
import com.lark.oapi.service.im.v1.model.EventSender;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1Data;
import com.lark.oapi.service.im.v1.model.UserId;
import com.lark.oapi.ws.exception.ClientException;
import com.lark.oapi.ws.exception.ServerUnreachableException;
import io.github.claw4j.channel.feishu.FeishuOutboundDelivery;
import io.github.claw4j.channel.feishu.FeishuReplySender;
import io.github.claw4j.channel.kernel.InboundDispatcher;
import io.github.claw4j.domain.inbound.ClaimRef;
import io.github.claw4j.domain.inbound.ClaimedInbound;
import io.github.claw4j.domain.inbound.EnqueueOutcome;
import io.github.claw4j.domain.inbound.InboundStore;
import io.github.claw4j.domain.inbound.QueuedInbound;
import io.github.claw4j.domain.inbound.Retention;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.port.AgentPort;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.port.OutboundMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 飞书通道的装配条件：什么情况下它在容器里，什么情况下它连一个 bean 都不留。
 *
 * <p>这一份测的不是"翻译 SDK 对象"（那要真凭据，靠起一次应用手验），而是**装配**：
 * 没配飞书的人、没打 SDK 的包、把通道关掉的人，都不该因为这个新通道而起不来。
 * 这三件事在真链路上完全看不出来——只跑 HTTP 的部署照样能启动，直到某次升级把它拖垮。
 *
 * <p>唯一的正例（凭据齐全 → 真连上）不写在这里：它会在跑测试时去连飞书，
 * 让回归依赖网络与一个真实应用的状态。
 *
 * <p>最后一条用例（主动投递）严格说不属于"装配条件"，但它就在这里——理由写在它自己的注释里：
 * channel 模块的测试文件有<b>名额上限</b>（10 个），而只有一条用例的文件腾出来的正好是
 * 下一个通道要用的槽位。
 */
@ExtendWith(OutputCaptureExtension.class)
class FeishuChannelWiringTest {

    private static final String APP_ID = "claw4j.channel.feishu.app-id";

    private static final String APP_SECRET = "claw4j.channel.feishu.app-secret";

    private static final String DUPLICATE_MESSAGE =
            "claw4j.channel.feishu.roles 里同一个人登记了多个角色：ou_zhang。一个人只能有一个身份，请删掉多余的那几处。";

    private static final String UNKNOWN_ROLE_MESSAGE =
            "claw4j.channel.feishu.roles 里的角色在 claw4j.channel.http.roles 里没有同名定义："
                    + "operator（登记了 1 人）。飞书只登记人，授权表共用 HTTP 那一份，角色名必须对得上。";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FeishuChannelAutoConfiguration.class))
            .withBean(AgentPort.class, NoopAgent::new);

    @Test
    @DisplayName("凭据缺失：飞书通道一个 bean 都不留，上下文照常起来")
    void missingCredentialsLeaveTheChannelOut() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(FeishuLongConnectionChannel.class);
            assertThat(context).doesNotHaveBean(InboundDispatcher.class);
            assertThat(context).doesNotHaveBean(FeishuReplySender.class);
        });
    }

    @Test
    @DisplayName("只配了 app-id 没给 secret：算没配，不能拿半套凭据去连")
    void halfCredentialsCountAsNotConfigured() {
        runner.withPropertyValues(APP_ID + "=cli_half")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(FeishuLongConnectionChannel.class);
                });
    }

    @Test
    @DisplayName("SDK 不在 classpath：整个包退让，不出现 ClassNotFoundException")
    void withoutTheSdkTheChannelBacksOff() {
        runner.withPropertyValues(APP_ID + "=cli_x", APP_SECRET + "=secret")
                .withClassLoader(new FilteredClassLoader("com.lark"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(FeishuLongConnectionChannel.class);
                });
    }

    @Test
    @DisplayName("enabled=false：凭据齐了也不装，方便只留 HTTP 的部署")
    void disabledByProperty() {
        runner.withPropertyValues(APP_ID + "=cli_x", APP_SECRET + "=secret",
                        "claw4j.channel.feishu.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(FeishuLongConnectionChannel.class));
    }

    @Test
    @DisplayName("认人只看登记表：登记的人按角色拿到表与动作，没登记的人什么都没有（不回落默认角色）")
    void onlyRegisteredUsersResolveToAPrincipal() {
        runner.withPropertyValues(
                        // 授权只有一份：写在这张两个通道共用的表里
                        "claw4j.channel.http.roles.read-only.tables[0]=*",
                        "claw4j.channel.http.roles.read-only.actions[0]=read",
                        "claw4j.channel.http.roles.guest.tables[0]=region",
                        "claw4j.channel.http.roles.guest.actions[0]=read",
                        // 飞书这边只回答"这个人是谁"
                        "claw4j.channel.feishu.roles.read-only.users[0]=ou_zhang",
                        "claw4j.channel.feishu.roles.guest.users[0]=ou_li")
                .run(context -> {
                    FeishuPrincipalResolver resolver = context.getBean(FeishuPrincipalResolver.class);

                    assertThat(resolver.apply("ou_zhang")).get()
                            .satisfies(principal -> {
                                assertThat(principal.role()).isEqualTo("read-only");
                                assertThat(principal.accessibleTables())
                                        .as("表与动作不是飞书那边配的，是从共用的授权表里按角色名取的")
                                        .containsExactly("*");
                            });
                    assertThat(resolver.apply("ou_li")).get()
                            .satisfies(principal -> {
                                assertThat(principal.role()).isEqualTo("guest");
                                assertThat(principal.accessibleTables()).containsExactly("region");
                            });
                    assertThat(resolver.apply("ou_stranger")).isEmpty();
                });
    }

    @Test
    @DisplayName("飞书登记的角色在共用授权表里没有同名定义：启动失败并点名，别让那群人天天收到「没有权限」")
    void unknownRoleFailsAtStartup() {
        runner.withPropertyValues(
                        "claw4j.channel.http.roles.read-only.tables[0]=*",
                        "claw4j.channel.http.roles.read-only.actions[0]=read",
                        "claw4j.channel.feishu.roles.operator.users[0]=ou_wang")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasRootCauseMessage(UNKNOWN_ROLE_MESSAGE));
    }

    @Test
    @DisplayName("同一个人登记了两个角色：启动失败并点名是谁，而不是按书写顺序挑一个")
    void duplicateRegistrationFailsAtStartup() {
        runner.withPropertyValues(
                        "claw4j.channel.feishu.roles.read-only.users[0]=ou_zhang",
                        "claw4j.channel.feishu.roles.guest.users[0]=ou_zhang")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasRootCauseMessage(DUPLICATE_MESSAGE));
    }

    @Test
    @DisplayName("凭据齐了、roles 里一个人都没登记：启动日志里就要说，别让“全员没有权限”看起来像服务没起来")
    void nobodyRegisteredIsCalledOutAtStartup(CapturedOutput output) {
        FeishuChannelProperties properties = new FeishuChannelProperties();
        properties.setAppId("cli_x");
        properties.setAppSecret("secret");

        properties.validate();

        assertThat(output).contains("一个人都没登记");
    }

    @Test
    @DisplayName("没配凭据时不吵：只跑 HTTP 的部署不该看到飞书通道的告警")
    void unconfiguredChannelSaysNothing(CapturedOutput output) {
        new FeishuChannelProperties().validate();

        assertThat(output).doesNotContain("一个人都没登记");
    }

    /**
     * 主动投递（A05）：一个 chat_id 加一段文本，交给本来就在的回推。
     *
     * <p>原来它单独一个文件（{@code FeishuOutboundDeliveryTest}），只有这一条——
     * 而 channel 模块的测试文件是<b>有名额上限</b>的（10 个，脚本卡着），
     * 钉钉通道那条要新建文件，槽位只能从"只有一条用例的文件"里腾。
     * 放这里而不是删掉：投递实现本来就是这个装配的一个产物，
     * 而它值得一条用例的地方在于两件事不能被改错——通道名必须与
     * {@code agent_run_delivery.channel} 那一列同口径（错了的表现是"登记了地址，
     * 但永远没有实现能送"），地址必须原样交给回推（错了会推到一个拼出来的地址上，
     * 而那种错在日志里看着像"发成功了"）。
     */
    @Test
    @DisplayName("主动投递：通道名与库里那一列同口径，地址原样交给回推")
    void outboundDeliveryHandsTheAddressToTheReplySender() {
        List<String> sent = new ArrayList<>();
        FeishuOutboundDelivery delivery = new FeishuOutboundDelivery(
                (chatId, text) -> sent.add(chatId + "|" + text));

        delivery.send("oc_zhang", "华东上周成交 128400");

        assertThat(delivery.channel()).isEqualTo("feishu");
        assertThat(sent).containsExactly("oc_zhang|华东上周成交 128400");
    }

    // ---------------------------------------------------------------- 可恢复失败与重连（票 02）

    @Test
    @DisplayName("可恢复的连不上：应用照常起来，通道在，状态是 DISCONNECTED")
    void recoverableConnectFailureDoesNotFailStartup() {
        runner.withPropertyValues(APP_ID + "=cli_x", APP_SECRET + "=secret")
                .withBean(FeishuConnectionFactory.class, () -> new FakeFactory(handshakeTimeout()))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(FeishuLongConnectionChannel.class);
                    assertThat(context.getBean(FeishuLongConnectionChannel.class).state())
                            .as("没连上不等于启动失败：后台在退避重连，其它通道照常工作")
                            .isEqualTo(FeishuConnectionState.DISCONNECTED);
                });
    }

    @Test
    @DisplayName("凭据错仍然是启动失败，报错句说清「重启不会好」")
    void permanentCredentialFailureStillFailsStartup() {
        runner.withPropertyValues(APP_ID + "=cli_x", APP_SECRET + "=secret")
                .withBean(FeishuConnectionFactory.class,
                        () -> new FakeFactory(new ClientException(10014, "app not found")))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("重启不会好")
                            .hasStackTraceContaining("是否已发布");
                });
    }

    @Test
    @DisplayName("退避是 30 / 60 / 120 秒：每失败一次翻一倍")
    void backoffDoublesFromThirtySeconds() {
        RecordingScheduler scheduler = new RecordingScheduler(true);
        FeishuLongConnectionChannel channel = channelWith(
                new FakeFactory(handshakeTimeout(), handshakeTimeout(), handshakeTimeout()), scheduler);

        channel.start();

        assertThat(scheduler.delays()).as("第一次 30 秒，之后 60、120").containsExactly(30_000L, 60_000L, 120_000L);
        assertThat(channel.state()).isEqualTo(FeishuConnectionState.CONNECTED);
    }

    @Test
    @DisplayName("重连成功之后状态回 CONNECTED，且不再排新的重试")
    void reconnectSucceedsAndStopsRetrying() {
        RecordingScheduler scheduler = new RecordingScheduler(true);
        FeishuLongConnectionChannel channel = channelWith(
                new FakeFactory(handshakeTimeout(), handshakeTimeout()), scheduler);

        channel.start();

        assertThat(channel.state()).isEqualTo(FeishuConnectionState.CONNECTED);
        assertThat(scheduler.delays()).as("成功之后不该再有第三个任务").containsExactly(30_000L, 60_000L);
    }

    @Test
    @DisplayName("stop() 关掉定时器与连接，且可重复调用")
    void stopCancelsScheduledReconnectAndClosesTheConnection() {
        RecordingScheduler scheduler = new RecordingScheduler(false);
        FakeFactory factory = new FakeFactory(handshakeTimeout());
        FeishuLongConnectionChannel channel = channelWith(factory, scheduler);

        channel.start();
        assertThat(scheduler.delays()).as("排上了，但这一次不跑").containsExactly(30_000L);

        channel.stop();
        channel.stop();

        assertThat(scheduler.isShutdown()).isTrue();
        assertThat(channel.state()).isEqualTo(FeishuConnectionState.STOPPED);
        assertThat(factory.opened()).isNotEmpty()
                .allSatisfy(connection -> assertThat(connection.closed()).isTrue());
    }

    // ---------------------------------------------------------------- 事件回调的两条失败路径（票 04）

    @Test
    @DisplayName("落库失败不被回调吞掉：异常一路抛到平台那一侧，这条会被重推")
    void aPersistFailureReachesThePlatform() {
        FeishuLongConnectionChannel channel = channelWithDispatcher(new BrokenStore());

        assertThatThrownBy(() -> channel.onMessage(textMessage("上周成交额")))
                .as("吞掉它的现场最难查：平台以为我们收下了，库里没有这条，日志里只有一句处理失败")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("库连不上");
    }

    @Test
    @DisplayName("翻译不了的消息照旧吞掉：抛出去平台只会重推同一条，而它还是翻不出来")
    void anUntranslatableMessageIsSwallowedOnPurpose() {
        FeishuLongConnectionChannel channel = channelWithDispatcher(new BrokenStore());

        assertThatCode(() -> channel.onMessage(imageMessage())).doesNotThrowAnyException();
    }

    /**
     * 一条能走完翻译的消息。SDK 的事件对象是 POJO，直接 new 出来就行——不需要凭据，也不需要网络。
     */
    private static P2MessageReceiveV1 textMessage(String text) {
        EventMessage message = EventMessage.newBuilder()
                .messageId("om_1").chatId("oc_demo").messageType("text")
                .content("{\"text\":\"" + text + "\"}")
                .createTime(String.valueOf(Instant.now().toEpochMilli()))
                .build();
        return event(message);
    }

    private static P2MessageReceiveV1 imageMessage() {
        EventMessage message = EventMessage.newBuilder()
                .messageId("om_2").chatId("oc_demo").messageType("image")
                .content("{\"image_key\":\"img_1\"}")
                .createTime(String.valueOf(Instant.now().toEpochMilli()))
                .build();
        return event(message);
    }

    private static P2MessageReceiveV1 event(EventMessage message) {
        EventSender sender = new EventSender();
        sender.setSenderId(UserId.newBuilder().openId("ou_zhang").build());

        P2MessageReceiveV1Data data = new P2MessageReceiveV1Data();
        data.setSender(sender);
        data.setMessage(message);

        Header header = new Header();
        header.setEventId("evt-1");
        header.setEventType("im.message.receive_v1");

        P2MessageReceiveV1 event = new P2MessageReceiveV1();
        event.setHeader(header);
        event.setEvent(data);
        return event;
    }

    /** 一个只服务这两条用例的通道：真的翻译器 + 真的调度器 + 一个落不了库的 store。 */
    private static FeishuLongConnectionChannel channelWithDispatcher(InboundStore store) {
        FeishuChannelProperties properties = new FeishuChannelProperties();
        FeishuReplySender sender = (chatId, text) -> {
        };
        InboundDispatcher dispatcher = new InboundDispatcher(new NoopAgent(), Runnable::run,
                senderId -> Optional.of(Principal.readOnly("u-1")), sender, "飞书", "feishu", null, store);
        // 建连工厂给一个"一开就炸"的：这两条用例都不调 start()，它是必填参数而已。
        FeishuConnectionFactory factory = events -> {
            throw new IllegalStateException("这条用例不建连");
        };
        return new FeishuLongConnectionChannel(properties, dispatcher, new FeishuMessageTranslator(),
                sender, null, factory);
    }

    /**
     * 落不了库的 store：只有 {@code enqueue} 会炸。
     *
     * <p>它模拟的是"库挂了"这一件事，而这条路径的判据只有一个：**异常要冒到通道那一层**——
     * 通道把它吞了，就等于什么都没发生（平台以为收下了，库里没有）。
     */
    private static final class BrokenStore implements InboundStore {

        @Override
        public EnqueueOutcome enqueue(QueuedInbound event, String laneKey) {
            throw new IllegalStateException("库连不上");
        }

        @Override
        public Optional<ClaimedInbound> claimNext(String queueName, Set<String> blockedLanes,
                                                  String claimOwner) {
            throw new UnsupportedOperationException("入队就失败了，走不到这里");
        }

        @Override
        public boolean complete(ClaimRef claim, String metadataJson) {
            throw new UnsupportedOperationException("入队就失败了，走不到这里");
        }

        @Override
        public boolean release(ClaimRef claim, String lastError) {
            throw new UnsupportedOperationException("入队就失败了，走不到这里");
        }

        @Override
        public boolean fail(ClaimRef claim, String reason, String message) {
            throw new UnsupportedOperationException("入队就失败了，走不到这里");
        }

        @Override
        public int recoverStaleClaims(String queueName, Duration lease) {
            return 0;
        }

        @Override
        public int prune(Retention retention) {
            return 0;
        }
    }

    private static ServerUnreachableException handshakeTimeout() {
        return new ServerUnreachableException("websocket handshake did not complete within 15000ms");
    }

    private static FeishuLongConnectionChannel channelWith(FeishuConnectionFactory factory,
                                                          RecordingScheduler scheduler) {
        FeishuChannelProperties properties = new FeishuChannelProperties();
        properties.setAppId("cli_x");
        properties.setAppSecret("secret");
        // 只测建连：调度器、翻译器、回推在 start() 这条路上都用不到。
        return new FeishuLongConnectionChannel(properties, null, null, null, null, factory, scheduler);
    }

    /** 按序给失败：第 n 次 {@code open()} 抛第 n 个异常，给完了就成功。 */
    private static final class FakeFactory implements FeishuConnectionFactory {

        private final Deque<Throwable> failures = new ArrayDeque<>();

        private final List<FakeConnection> opened = new CopyOnWriteArrayList<>();

        private FakeFactory(Throwable... failures) {
            this.failures.addAll(List.of(failures));
        }

        @Override
        public FeishuConnection open(EventDispatcher events) {
            FakeConnection connection = new FakeConnection(failures.poll());
            opened.add(connection);
            return connection;
        }

        private List<FakeConnection> opened() {
            return opened;
        }
    }

    private static final class FakeConnection implements FeishuConnection {

        private final Throwable failure;

        private final AtomicBoolean closed = new AtomicBoolean();

        private FakeConnection(Throwable failure) {
            this.failure = failure;
        }

        @Override
        public void start() {
            if (failure instanceof RuntimeException unchecked) {
                throw unchecked;
            }
        }

        @Override
        public void awaitReady(long timeoutMillis) throws Exception {
            if (failure instanceof Exception checked && !(failure instanceof RuntimeException)) {
                throw checked;
            }
        }

        @Override
        public void close() {
            closed.set(true);
        }

        private boolean closed() {
            return closed.get();
        }
    }

    /**
     * 只记录"排了多久之后跑"，不真的等 30 秒。
     *
     * <p>{@code immediate=true} 时顺手把任务同步跑掉，于是"第一次失败、第二次成功"这种序列
     * 在一条用例里就走完了；{@code false} 时只记录，用来验 {@code stop()} 真的把排着的东西取消了。
     */
    private static final class RecordingScheduler extends ScheduledThreadPoolExecutor {

        private final List<Long> delays = new CopyOnWriteArrayList<>();

        private final boolean immediate;

        private RecordingScheduler(boolean immediate) {
            super(1, runnable -> {
                Thread thread = new Thread(runnable, "claw4j-feishu-reconnect-test");
                thread.setDaemon(true);
                return thread;
            });
            this.immediate = immediate;
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            delays.add(unit.toMillis(delay));
            if (immediate) {
                command.run();
            }
            return null;
        }

        private List<Long> delays() {
            return delays;
        }
    }

    /** 通道只需要一个 AgentPort 在场；这一份用例不验它做什么。 */    private static final class NoopAgent implements AgentPort {

        @Override
        public OutboundMessage reply(InboundMessage message) {
            return OutboundMessage.of(message.chatId(), "答复");
        }

        @Override
        public void stream(InboundMessage message, Consumer<OutboundMessage> onChunk) {
            throw new UnsupportedOperationException("飞书走一次性回答");
        }
    }
}

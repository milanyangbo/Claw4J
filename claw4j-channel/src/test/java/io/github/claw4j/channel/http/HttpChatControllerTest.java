package io.github.claw4j.channel.http;

import io.github.claw4j.channel.http.HttpChatController.AskRequest;
import io.github.claw4j.channel.http.HttpChatController.ErrorResponse;
import io.github.claw4j.channel.http.HttpChatController.InvalidRequestException;
import io.github.claw4j.channel.spi.PrincipalRejectedException;
import io.github.claw4j.channel.spi.TooBusyException;
import io.github.claw4j.domain.NotifyPolicy;
import io.github.claw4j.domain.port.AgentPort;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.port.OutboundMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import java.lang.reflect.RecordComponent;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 控制器的对外形状：SSE 的两个事件发得出去，三个错误各回各的码。
 *
 * <p>不并进 {@code HttpChatChannelTest}，因为那边测的是消息怎么交给 AgentPort，这一份测的是
 * 交给浏览器的是什么。两者错法不同：前者错是拿错身份，后者错是客户端收到 500、
 * 或者 SSE 流永远不结束，而服务端日志干干净净。
 *
 * <p>这里不断言 SSE 的分帧文本（那要起 servlet 容器），只断言块真的被推给了 emitter、
 * 以及错误处理返回哪个状态码，这两件事是控制器自己的责任。
 */
class HttpChatControllerTest {

    private static final String CHAT_ID = "http:u-1";

    @Test
    @DisplayName("流式：普通块与结束块都推到 emitter 上，代理跑完后收尾不抛")
    void streamPushesChunksAndCompletes() {
        ScriptedAgent agent = new ScriptedAgent(
                OutboundMessage.chunk(CHAT_ID, "第一段"),
                OutboundMessage.of(CHAT_ID, ""));

        var emitter = controller(agent, Runnable::run)
                .stream(new AskRequest("上周成交额"), "u-1", "read-only", null);

        assertNotNull(emitter, "流式必须当场把 emitter 交回容器，不能在请求线程上等模型");
        assertEquals(1, agent.received.size());
        assertEquals(2, agent.pushed.size(), "两块（正文 + 结束）都该被推到 emitter 上");
    }

    @Test
    @DisplayName("流式：Agent 失败时收尾的是连接，不是把异常抛回请求线程")
    void streamFailureEndsTheConnection() {
        ScriptedAgent agent = new ScriptedAgent();
        agent.failingWith(new IllegalStateException("模型连不上"));

        // 不抛就是这条的全部要求：抛出去会让客户端等到超时，而日志里只有一行堆栈
        assertNotNull(controller(agent, Runnable::run)
                .stream(new AskRequest("问题"), "u-1", "read-only", null));
    }

    @Test
    @DisplayName("认人失败回 401：不暴露系统里登记了哪些角色")
    void principalRejectionMapsTo401() {
        ResponseEntity<ErrorResponse> response = controller(new ScriptedAgent(), Runnable::run)
                .onPrincipalRejected(new PrincipalRejectedException("没有这个角色"));

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals("没有这个角色", response.getBody().error());
    }

    @Test
    @DisplayName("请求本身有问题回 400：这是调用方写错了，不是服务坏了")
    void invalidRequestMapsTo400() {
        ResponseEntity<ErrorResponse> response = controller(new ScriptedAgent(), Runnable::run)
                .onInvalidRequest(new InvalidRequestException("text 不能为空"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("text 不能为空", response.getBody().error());
    }

    @Test
    @DisplayName("线程池满回 503：过载要让调用方知道可以重试，不能混成 400")
    void overloadMapsTo503() {
        ResponseEntity<ErrorResponse> response = controller(new ScriptedAgent(), Runnable::run)
                .onTooBusy(new TooBusyException());

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertNotNull(response.getBody().error());
    }

    @Test
    @DisplayName("没有可回答的内容：同步与流式都在进 Agent 之前拒掉，不占线程池")
    void blankTextIsRejectedBeforeReachingTheAgent() {
        ScriptedAgent agent = new ScriptedAgent();
        HttpChatController controller = controller(agent, Runnable::run);

        assertThrows(InvalidRequestException.class,
                () -> controller.ask(new AskRequest("   "), "u-1", "read-only", null));
        assertThrows(InvalidRequestException.class,
                () -> controller.stream(new AskRequest(""), "u-1", "read-only", null));

        assertEquals(0, agent.received.size());
    }

    /**
     * 请求体里没有 {@code runId}（ADR-0027）。
     *
     * <p>留着它，它会静默变成一个没人读的摆设，下一个看接口文档的人会以为带上它就能接着做，
     * 而那条路要求一件事能跨请求活着。这条用反射钉住参数名：把 runId 加回去的那一刻它就红。
     */
    @Test
    @DisplayName("/api/chat 的请求体只有 text 与 notifyPolicy：没有续跑令牌")
    void theRequestCarriesNoResumeToken() {
        assertEquals(List.of("text", "notifyPolicy"),
                Arrays.stream(AskRequest.class.getRecordComponents())
                        .map(RecordComponent::getName).toList(),
                "加回 runId 就等于把「一件事能跨请求活着」重新引进来，而它正是这一版要拆掉的");
    }

    @Test
    @DisplayName("通道名与通知策略随消息下去：结果以后能不能被找回、要不要吵人，都靠这两个字段")
    void channelAndNotifyPolicyReachTheAgent() {
        ScriptedAgent agent = new ScriptedAgent();
        HttpChatController controller = controller(agent, Runnable::run);

        controller.ask(new AskRequest("继续", " silent "), "u-1", "read-only", null);
        controller.ask(new AskRequest("随口问问"), "u-1", "read-only", null);

        InboundMessage silent = agent.received.getFirst();
        assertEquals(HttpChatController.CHANNEL, silent.channel(),
                "没有通道名，这件事做完之后没人能把它送回原处");
        assertEquals(NotifyPolicy.SILENT, silent.notifyPolicy(),
                "前后空白不该让一句写对的请求落到 400");
        assertEquals(NotifyPolicy.DONE_ONLY, agent.received.getLast().notifyPolicy(),
                "不点名就该是交互式的默认档：做完了说一次");
    }

    @Test
    @DisplayName("notifyPolicy 拼错就 400：静默退回默认的方向是「该说的一次没说」")
    void unknownNotifyPolicyIsRejected() {
        ScriptedAgent agent = new ScriptedAgent();

        assertThrows(InvalidRequestException.class, () -> controller(agent, Runnable::run)
                .ask(new AskRequest("问题", "slience"), "u-1", "read-only", null));

        assertEquals(0, agent.received.size(), "拼错的请求不该先跑一遍再报错");
    }

    // ---------------------------------------------------------------- 装配

    private static HttpChatController controller(AgentPort agent, Executor executor) {
        HttpChannelProperties properties = new HttpChannelProperties();
        return new HttpChatController(
                new HttpChatChannel(agent, executor),
                new HttpPrincipalResolver(properties),
                properties);
    }

    /** 按脚本回块，并记录"推给调用方的回调一共被调了几次"。 */
    private static final class ScriptedAgent implements AgentPort {

        private final List<InboundMessage> received = new CopyOnWriteArrayList<>();

        private final List<OutboundMessage> pushed = new CopyOnWriteArrayList<>();

        private final List<OutboundMessage> script;

        private RuntimeException failure;

        ScriptedAgent(OutboundMessage... script) {
            this.script = List.of(script);
        }

        void failingWith(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public OutboundMessage reply(InboundMessage message) {
            received.add(message);
            if (failure != null) {
                throw failure;
            }
            return OutboundMessage.of(message.chatId(), "答复");
        }

        @Override
        public void stream(InboundMessage message, Consumer<OutboundMessage> onChunk) {
            received.add(message);
            if (failure != null) {
                throw failure;
            }
            for (OutboundMessage chunk : script) {
                pushed.add(chunk);
                onChunk.accept(chunk);
            }
        }
    }
}

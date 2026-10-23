package io.github.claw4j.channel.http;

import io.github.claw4j.domain.port.AgentPort;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.port.OutboundMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 通道接缝的入口侧：请求进 → 认人 → 交给 Agent。
 *
 * <p>用 standalone MockMvc 而不是起整个容器：这里要验证的是"通道把身份解析对了、
 * 原样往下传"，跟 Spring 容器无关。真正的端到端（含 Agent 与模型）在 app 模块那一层测。
 *
 * <p>ADR-0007 的边界就落在 {@code 解析出的身份原样交给Agent} 这条测试上：
 * Agent 侧不认人，它拿到的 Principal 必须是通道给的，不能自己去读请求头。
 */
class HttpChatControllerTest {

    private final RecordingAgentPort agentPort = new RecordingAgentPort();

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        var properties = new HttpChannelProperties();
        var channel = new HttpChatChannel(agentPort, Runnable::run);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new HttpChatController(channel, new HttpPrincipalResolver(properties), properties))
                .build();
    }

    @Test
    void 解析出的身份原样交给Agent() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .header(HttpPrincipalResolver.USER_HEADER, "u-1")
                        .header(HttpPrincipalResolver.ROLE_HEADER, "admin")
                        .header(HttpPrincipalResolver.CHAT_ID_HEADER, "chat-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"华东区上周成交额\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chatId").value("http:chat-1"))
                .andExpect(jsonPath("$.text").value("回答:华东区上周成交额"));

        InboundMessage delivered = agentPort.last;
        assertEquals("u-1", delivered.principal().userId());
        assertEquals("admin", delivered.principal().role());
        assertEquals("http:chat-1", delivered.chatId());
        assertEquals("华东区上周成交额", delivered.text());
    }

    @Test
    void 默认角色是只读() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .header(HttpPrincipalResolver.USER_HEADER, "u-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"你好\"}"))
                .andExpect(status().isOk());

        assertEquals("read-only", agentPort.last.principal().role());
        assertEquals("http:u-1", agentPort.last.chatId(), "没带 Chat ID 头时回落到用户标识");
    }

    /**
     * 流式线程池满时提交被同步拒绝——这必须是 503（过载）而不是 500（服务器错），
     * 因为调用方重试是有意义的。之前这里直接裸抛 RejectedExecutionException 变 500。
     */
    @Test
    void 线程池满时返回503而不是500() throws Exception {
        Executor rejecting = task -> {
            throw new RejectedExecutionException("pool exhausted");
        };
        var properties = new HttpChannelProperties();
        var channel = new HttpChatChannel(agentPort, rejecting);
        var mockMvc = MockMvcBuilders
                .standaloneSetup(new HttpChatController(channel, new HttpPrincipalResolver(properties), properties))
                .build();

        mockMvc.perform(post("/api/chat/stream")
                        .header(HttpPrincipalResolver.USER_HEADER, "u-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"你好\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void 没有用户标识的请求进不来() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"你好\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void 未登记的角色被拒绝() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .header(HttpPrincipalResolver.USER_HEADER, "u-1")
                        .header(HttpPrincipalResolver.ROLE_HEADER, "god")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"你好\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 空问题返回400而不是把空串喂给模型() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .header(HttpPrincipalResolver.USER_HEADER, "u-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }

    /** 只记录收到的入站消息，用来断言"身份是被传递的，不是被 Agent 自己猜的"。 */
    private static final class RecordingAgentPort implements AgentPort {

        private InboundMessage last;

        @Override
        public OutboundMessage reply(InboundMessage message) {
            this.last = message;
            return OutboundMessage.of(message.chatId(), "回答:" + message.text());
        }

        @Override
        public void stream(InboundMessage message, Consumer<OutboundMessage> onChunk) {
            this.last = message;
            onChunk.accept(OutboundMessage.of(message.chatId(), "回答:" + message.text()));
        }
    }
}

package io.github.claw4j.agent.advisor;

import io.github.claw4j.agent.AgentAutoConfiguration;
import io.github.claw4j.agent.model.ScriptedChatModel;
import io.github.claw4j.domain.port.AgentPort;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.port.OutboundMessage;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.Ordered;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 鉴权 Advisor 的行为断言。
 *
 * <p>每条断言都钉在**外部可观测的事实**上：工具被调用了几次、模型被调了几轮、
 * 客户端拿到的是不是那句拒绝。刻意不去断言"Advisor 内部是怎么判的"——
 * 内部实现换掉而行为不变时，测试不该红。
 *
 * <p>为什么要数工具调用次数而不是看回答里有没有数字：无权限时"工具没跑"与
 * "跑了但返回空"在客户端看起来几乎一样（都是没有业务数据）。只有调用计数能分开这两者。
 */
class AuthorizationAdvisorTest {

    private static final String QUESTION = "上周华东区成交额多少";

    private static final String SALES_TOOL = "querySales";

    private static final String MAIL_TOOL = "sendMail";

    private static final String EXPORT_TOOL = "exportData";

    @Test
    void 无权限时工具一次都没被调用且回答是明确的拒绝() {
        CountingTools tools = new CountingTools();
        ScriptedChatModel model = ScriptedChatModel.builder()
                .thenToolCall(SALES_TOOL, "{\"region\":\"华东\",\"period\":\"上周\"}")
                .thenText("上周华东区成交额是 128400.00 元。")
                .build();

        runner(model, tools).run(context -> {
            OutboundMessage out = context.getBean(AgentPort.class).reply(ask(guest()));

            // 工具一次都没被调用——不是"调用了但没数据"
            assertThat(tools.salesCalls()).isZero();
            // 循环在第一轮就结束了：模型没拿到工具结果，也就没有第二轮的作答
            assertThat(model.taken()).isEqualTo(1);
            // 客户端拿到的是明确的拒绝，不是"查出来是空的"
            assertThat(out.text())
                    .startsWith(AuthorizationAdvisor.DENIAL_PREFIX)
                    .doesNotContain("128400");
        });
    }

    @Test
    void 流式路径同样拦住且输出里没有工具结果() {
        CountingTools tools = new CountingTools();
        ScriptedChatModel model = ScriptedChatModel.builder()
                .thenToolCall(SALES_TOOL, "{\"region\":\"华东\",\"period\":\"上周\"}")
                .thenText("上周华东区成交额是 128400.00 元。")
                .build();

        runner(model, tools).run(context -> {
            List<OutboundMessage> chunks = new ArrayList<>();
            context.getBean(AgentPort.class).stream(ask(guest()), chunks::add);

            assertThat(tools.salesCalls()).isZero();
            assertThat(model.taken()).isEqualTo(1);
            // 必须拼接后再断言：分块边界会落在词中间
            assertThat(chunks.stream().map(OutboundMessage::text).reduce("", String::concat))
                    .startsWith(AuthorizationAdvisor.DENIAL_PREFIX)
                    .doesNotContain("128400");
            assertThat(chunks.getLast().finalChunk()).isTrue();
        });
    }

    /**
     * order 位置的意义：模型在**第二轮**才去调越权工具，一样要被拦。
     *
     * <p>这条同时是"Advisor 排在工具循环内部"的行为证明。摆错了（排到循环外面）
     * 它只在整通对话里走一次，第二轮那个 sendMail 就漏过去了。
     */
    @Test
    void 第二轮才出现的越权工具同样被拦() {
        CountingTools tools = new CountingTools();
        ScriptedChatModel model = ScriptedChatModel.builder()
                .thenToolCall(SALES_TOOL, "{\"region\":\"华东\",\"period\":\"上周\"}")
                .thenToolCall(MAIL_TOOL, "{\"to\":\"boss\",\"body\":\"本周成交额\"}")
                .thenText("邮件已发送。")
                .build();

        runner(model, tools).run(context -> {
            // 只读身份：能查 sales_order（read + 全表），但发不了邮件
            OutboundMessage out = context.getBean(AgentPort.class).reply(ask(Principal.readOnly("u-readonly")));

            assertThat(tools.salesCalls()).isEqualTo(1);
            assertThat(tools.mailCalls()).isZero();
            assertThat(model.taken()).isEqualTo(2);
            assertThat(out.text())
                    .startsWith(AuthorizationAdvisor.DENIAL_PREFIX)
                    .doesNotContain("邮件已发送");
        });
    }

    /** 对照：有权限时一切照旧。没有这条，"一律拒绝"的实现也能全绿。 */
    @Test
    void 有权限时工具照常被调用() {
        CountingTools tools = new CountingTools();
        ScriptedChatModel model = ScriptedChatModel.builder()
                .thenToolCall(SALES_TOOL, "{\"region\":\"华东\",\"period\":\"上周\"}")
                .thenText("已查到。")
                .build();

        runner(model, tools).run(context -> {
            OutboundMessage out = context.getBean(AgentPort.class).reply(ask(Principal.readOnly("u-readonly")));

            assertThat(tools.salesCalls()).isEqualTo(1);
            assertThat(model.taken()).isEqualTo(2);
            assertThat(out.text()).isEqualTo("已查到。");
        });
    }

    /** 权限表里没登记的工具：没人评估过它会碰到什么，按越权处理而不是放行。 */
    @Test
    void 未登记权限的工具默认被拒绝() {
        CountingTools tools = new CountingTools();
        ScriptedChatModel model = ScriptedChatModel.builder()
                .thenToolCall(EXPORT_TOOL, "{\"target\":\"全量订单\"}")
                .thenText("导出完成。")
                .build();

        runner(model, tools).run(context -> {
            OutboundMessage out = context.getBean(AgentPort.class)
                    .reply(ask(Principal.of("u-admin", "admin", Set.of("*"), Set.of("*"))));

            assertThat(tools.exportCalls()).isZero();
            assertThat(out.text())
                    .startsWith(AuthorizationAdvisor.DENIAL_PREFIX)
                    .contains(EXPORT_TOOL);
        });
    }

    /** 没有身份就没有权限：直接拿 ChatClient 说话且不带 Principal 时，工具一律不放行。 */
    @Test
    void 没有身份时工具调用被拒绝() {
        CountingTools tools = new CountingTools();
        ScriptedChatModel model = ScriptedChatModel.builder()
                .thenToolCall(SALES_TOOL, "{\"region\":\"华东\",\"period\":\"上周\"}")
                .thenText("已查到。")
                .build();

        runner(model, tools).run(context -> {
            String content = context.getBean(ChatClient.class).prompt(QUESTION).call().content();

            assertThat(tools.salesCalls()).isZero();
            assertThat(content).startsWith(AuthorizationAdvisor.DENIAL_PREFIX);
        });
    }

    /**
     * 钉住摆位的方向：{@code ToolCallingAdvisor} **之后**的 Advisor 才会被每一次迭代走到，
     * 而链按 order 升序排，所以"循环内部"是 **大于** {@link ToolCallingAdvisor#DEFAULT_ORDER}。
     *
     * <p>写反成"小于"也能编译、也能跑，只是悄悄退化成"整通对话只查一次"。
     * 行为测试（第二轮那条）会红，但红得远且看不出是 order 写反了，所以这条断言单独留着。
     */
    @Test
    void order落在工具循环内部() {
        assertThat(new AuthorizationAdvisor(List.of()).getOrder())
                .isGreaterThan(ToolCallingAdvisor.DEFAULT_ORDER)
                .isLessThan(Ordered.LOWEST_PRECEDENCE);
    }

    /** 一个只能看 region 表的身份：会读，但读不到 sales_order。 */
    private static Principal guest() {
        return Principal.of("u-guest", "guest", Set.of("region"), Set.of(ToolPermission.READ));
    }

    private static InboundMessage ask(Principal principal) {
        return new InboundMessage(principal, "http:auth-test", QUESTION);
    }

    /**
     * 只装配 Agent 侧：模型是脚本化的假模型，工具与权限都是这一条测试自己的实例，
     * 于是调用计数能读到。走 {@code AgentPort} 这个接缝，与生产路径是同一条。
     */
    private static ApplicationContextRunner runner(ScriptedChatModel model, CountingTools tools) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentAutoConfiguration.class))
                .withBean("chatModel", ChatModel.class, () -> model)
                .withBean("toolCallbacks", ToolCallbackProvider.class,
                        () -> MethodToolCallbackProvider.builder().toolObjects(tools).build())
                .withBean("permissions", ToolPermissionRegistry.class, AuthorizationAdvisorTest::permissions);
    }

    private static ToolPermissionRegistry permissions() {
        return ToolPermissionRegistry.of(Map.of(
                SALES_TOOL, ToolPermission.read("sales_order"),
                // 发邮件是有副作用的动作，用独立的动作名登记，不在"只读"的能力范围内
                MAIL_TOOL, ToolPermission.of("sendMail")));
    }

    /** 三个工具：一个可查、一个有副作用、一个压根没登记权限。 */
    static final class CountingTools {

        private final AtomicInteger salesCalls = new AtomicInteger();

        private final AtomicInteger mailCalls = new AtomicInteger();

        private final AtomicInteger exportCalls = new AtomicInteger();

        @Tool(description = "查询指定区域与时间窗内的成交额")
        public String querySales(String region, String period) {
            salesCalls.incrementAndGet();
            return "stub:sales-result";
        }

        @Tool(description = "给指定的人发一封邮件")
        public String sendMail(String to, String body) {
            mailCalls.incrementAndGet();
            return "stub:mail-sent";
        }

        @Tool(description = "导出数据")
        public String exportData(String target) {
            exportCalls.incrementAndGet();
            return "stub:exported";
        }

        int salesCalls() {
            return salesCalls.get();
        }

        int mailCalls() {
            return mailCalls.get();
        }

        int exportCalls() {
            return exportCalls.get();
        }
    }
}

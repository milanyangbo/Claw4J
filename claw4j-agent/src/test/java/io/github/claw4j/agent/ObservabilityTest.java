package io.github.claw4j.agent;

import io.github.claw4j.agent.advisor.AuthorizationAdvisor;
import io.github.claw4j.agent.observability.MeteredChatModel;
import io.github.claw4j.agent.observability.MeteredToolCallback;
import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 可观测：一次提问花掉多少 token、工具被调了几次、谁被拒了。
 *
 * <p>这里用 {@link SimpleMeterRegistry} 而不是 mock：它就在内存里，读计数器的值和生产的
 * 读数路径完全相同。抓 mock 的调用参数只能证明"代码执行到了那一行"，
 * 证明不了"那个数是对的"——而这个功能存在的全部意义就是把数算对。
 */
class ObservabilityTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    @DisplayName("同步调用把 prompt 与 completion 分开记：成本要能拆开看")
    void countsTokensSeparately() {
        MeteredChatModel model = new MeteredChatModel(
                new FixedChatModel(prompt -> withUsage(12, 34)), "deepseek", registry);

        model.call(new Prompt("上周华东成交额"));

        assertThat(counter("claw4j.model.tokens", "route", "deepseek", "kind", "prompt")).isEqualTo(12);
        assertThat(counter("claw4j.model.tokens", "route", "deepseek", "kind", "completion")).isEqualTo(34);
        assertThat(counter("claw4j.model.calls", "route", "deepseek", "outcome", "ok")).isEqualTo(1);
    }

    @Test
    @DisplayName("流式只认最后一帧：每帧都带累计 usage 时，逐帧累加会翻三倍")
    void streamCountsUsageOnceFromTheLastChunk() {
        // 三帧都带 usage(12,34) —— 这是多数供应商的实际形态：每帧给的是**累计值**。
        // 于是"逐帧累加"会记成 36，而真实成本是 12。
        // 前几帧没有 usage 的那种形态反而是无害的：没值就跳过，末帧照样对。
        MeteredChatModel model = new MeteredChatModel(new FixedChatModel(
                prompt -> withUsage(12, 34),
                prompt -> Flux.just(withUsage(12, 34), withUsage(12, 34), withUsage(12, 34))),
                "deepseek", registry);

        model.stream(new Prompt("上周华东成交额")).blockLast();

        assertThat(counter("claw4j.model.tokens", "route", "deepseek", "kind", "prompt"))
                .as("末帧就是总量，不能把三帧加起来")
                .isEqualTo(12);
        assertThat(counter("claw4j.model.tokens", "route", "deepseek", "kind", "completion")).isEqualTo(34);
        assertThat(counter("claw4j.model.calls", "route", "deepseek", "outcome", "ok")).isEqualTo(1);
    }

    @Test
    @DisplayName("流式里前几帧没有 usage 也不影响末帧：跳过空值，不是记 0")
    void streamSkipsChunksWithoutUsage() {
        MeteredChatModel model = new MeteredChatModel(new FixedChatModel(
                prompt -> withUsage(12, 34),
                prompt -> Flux.just(withoutUsage("第一块"), withoutUsage("第二块"), withUsage(12, 34))),
                "deepseek", registry);

        model.stream(new Prompt("上周华东成交额")).blockLast();

        assertThat(counter("claw4j.model.tokens", "route", "deepseek", "kind", "prompt")).isEqualTo(12);
    }

    @Test
    @DisplayName("拿不到 usage 就不记 token：不拿估算值冒充真实值")
    void missingUsageDoesNotFakeATokenCount() {
        MeteredChatModel model = new MeteredChatModel(
                new FixedChatModel(prompt -> withoutUsage("答")), "deepseek", registry);

        model.call(new Prompt("上周华东成交额"));

        assertThat(registry.find("claw4j.model.tokens").counter())
                .as("记 0 会让人以为不花钱，记估算值会让成本口径不可信")
                .isNull();
        assertThat(counter("claw4j.model.calls", "route", "deepseek", "outcome", "ok"))
                .as("调用本身是发生了的，这个数照记")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("调用失败记 error 且不记 token：失败的那次没有产出一分钱的价值")
    void failureIsCountedWithoutTokens() {
        MeteredChatModel model = new MeteredChatModel(new FixedChatModel(prompt -> {
            throw new IllegalStateException("429");
        }), "deepseek", registry);

        assertThatThrownBy(() -> model.call(new Prompt("问")))
                .isInstanceOf(IllegalStateException.class);

        assertThat(counter("claw4j.model.calls", "route", "deepseek", "outcome", "error")).isEqualTo(1);
        assertThat(registry.find("claw4j.model.tokens").counter()).isNull();
    }

    @Test
    @DisplayName("工具成功与失败分别计数，按工具名分开")
    void countsToolCallsPerTool() {
        MeteredToolCallback failing = new MeteredToolCallback(new RecordingTool("querySales", "128400") {
            @Override
            public String call(String toolInput) {
                throw new IllegalStateException("库挂了");
            }
        }, registry);
        MeteredToolCallback succeeding = new MeteredToolCallback(new RecordingTool("querySales", "128400"), registry);

        assertThatThrownBy(() -> failing.call("{}")).isInstanceOf(IllegalStateException.class);
        succeeding.call("{}");
        succeeding.call("{}");

        assertThat(counter("claw4j.tool.calls", "tool", "querySales", "outcome", "ok")).isEqualTo(2);
        assertThat(counter("claw4j.tool.calls", "tool", "querySales", "outcome", "error")).isEqualTo(1);
    }

    @Test
    @DisplayName("被鉴权拒掉的那次也要记：工具没被执行，只有 Advisor 知道拒绝发生过")
    void deniedCallsAreCountedByTheAuthorizationAdvisor() {
        RecordingTool tool = new RecordingTool("querySales", "128400");
        AuthorizationAdvisor advisor = new AuthorizationAdvisor(
                List.of(ToolPermissionRegistry.of(Map.of("querySales", ToolPermission.read("sales_order")))),
                registry);
        ChatClient chat = ChatClient.builder(
                        new FixedChatModel(prompt -> ChatPipelineTest.requestTool("querySales", "{}")),
                        ObservationRegistry.NOOP, null, null, ToolCallingAdvisor.builder())
                .defaultAdvisors(advisor)
                .defaultToolCallbacks(List.of(tool))
                .build();

        // 不带身份：鉴权一律拒绝，工具一次都不会被执行
        chat.prompt("上周华东成交额").call().content();

        assertThat(tool.calls()).as("被拒的那次不该真的碰到工具").isZero();
        assertThat(counter("claw4j.tool.calls", "tool", "querySales", "outcome", "denied")).isEqualTo(1);
    }

    private double counter(String name, String... tags) {
        var counter = registry.find(name).tags(tags).counter();
        assertThat(counter).as("%s %s 应该被记过一次以上", name, List.of(tags)).isNotNull();
        return counter.count();
    }

    private static ChatResponse withUsage(int promptTokens, int completionTokens) {
        return ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("答"))))
                .metadata(ChatResponseMetadata.builder()
                        .model("deepseek-chat")
                        .usage(new DefaultUsage(promptTokens, completionTokens))
                        .build())
                .build();
    }

    private static ChatResponse withoutUsage(String text) {
        return ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage(text))))
                .build();
    }
}

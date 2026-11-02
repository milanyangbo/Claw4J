package io.github.claw4j.agent;

import io.github.claw4j.agent.advisor.AuthorizationAdvisor;
import io.github.claw4j.agent.observability.MeteredChatModel;
import io.github.claw4j.agent.observability.MeteredToolCallback;
import io.github.claw4j.agent.run.RunLauncher;
import io.github.claw4j.agent.schedule.ScheduleAuthorization;
import io.github.claw4j.agent.schedule.ScheduleProperties;
import io.github.claw4j.agent.schedule.ScheduleScanner;
import io.github.claw4j.agent.schedule.ScheduleStore;
import io.github.claw4j.domain.NotifyPolicy;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.port.OutboundDelivery;
import io.github.claw4j.domain.port.RoleDirectory;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 可观测：一次提问花掉多少 token、工具被调了几次、谁被拒了、定时任务到点跑了几次。
 *
 * <p>这里用 {@link SimpleMeterRegistry} 而不是 mock：它就在内存里，读计数器的值和生产的
 * 读数路径完全相同。抓 mock 的调用参数只能证明"代码执行到了那一行"，
 * 证明不了"那个数是对的"——而这个功能存在的全部意义就是把数算对。
 *
 * <p>定时任务那一组（P04）读的也是计数器，但它还要断言"扫描器做了什么决定"，
 * 所以那里对 {@link ScheduleStore} 用的是替身：**库上的那一半不在这个文件里**
 * （认领只跑一次、重启不丢时间点在 app 的 {@code ScheduleFlowTest} 用真库验）。
 */
class ObservabilityTest {

    /** 每天九点（Asia/Shanghai）。 */
    private static final String DAILY_NINE = "0 0 9 * * *";

    private static final String DUE_ID = "s-1";

    /** 任务声明会用到的工具。它的权限是"读 sales_order"，用来验"权限被收回"那一档。 */
    private static final String TOOL = "querySales";

    /** 部署里的角色表：这个文件里一律是"什么都给"，除了那两条专门收权的用例。 */
    private final RoleDirectory everything =
            (userId, role) -> Optional.of(Principal.of(userId, role, Set.of("*"), Set.of("*")));

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
        ChatClient chat = ChatPipelineTest.clientBuilder(
                        new FixedChatModel(prompt -> ChatPipelineTest.requestTool("querySales", "{}")))
                .defaultAdvisors(advisor)
                .defaultToolCallbacks(List.of(tool))
                .build();

        // 不带身份：鉴权一律拒绝，工具一次都不会被执行
        chat.prompt("上周华东成交额").call().content();

        assertThat(tool.calls()).as("被拒的那次不该真的碰到工具").isZero();
        assertThat(counter("claw4j.tool.calls", "tool", "querySales", "outcome", "denied")).isEqualTo(1);
    }

    // ---- 定时任务到点跑不跑（P04） ----
    //
    // 这一组为什么长在这里：agent 的测试文件位已经用满 10 个（P02 的记录），而这一组要断言的
    // 正是"跑了几次、失败几次、停用几次"——它属于这个文件一直在管的那件事（计量）。
    // **库上的那一半不在这里**：认领只跑一次、重启不丢时间点在 app 的 ScheduleFlowTest 里用真库验。
    // 这里用的是替身，因此只断言"扫描器做了什么决定"，不假装能证明 SQL 对不对。

    @Test
    @DisplayName("到点跑成：推进下一次时间点，并且问的是任务主人、带的是静默档")
    void aFiredTaskAdvancesItsNextRunAndCounts() {
        ScheduleStore store = mock(ScheduleStore.class);
        when(store.claim(anyInt(), any())).thenReturn(List.of(due(ObservabilityTest.DAILY_NINE)));
        List<InboundMessage> asked = new ArrayList<>();
        RunLauncher launcher = message -> {
            asked.add(message);
            return Optional.of("上周华东 128400");
        };

        scanner(store, launcher, List.of()).scan();

        verify(store).settle(eq(DUE_ID), any(Instant.class));
        assertThat(asked).hasSize(1);
        assertThat(asked.getFirst().text()).isEqualTo("报一次成交额");
        assertThat(asked.getFirst().principal().userId()).isEqualTo("u-1");
        assertThat(asked.getFirst().channel()).isEqualTo("feishu");
        assertThat(asked.getFirst().runId()).as("每次到点都是新的一件").isNull();
        assertThat(asked.getFirst().notifyPolicy()).isEqualTo(NotifyPolicy.SILENT);
        assertThat(fired("ok")).isEqualTo(1);
    }

    @Test
    @DisplayName("没跑成记一次：原因原样进库，没到阈值不停用也不打扰人")
    void aFailureIsRecordedWithItsReason() {
        ScheduleStore store = mock(ScheduleStore.class);
        when(store.claim(anyInt(), any())).thenReturn(List.of(due(ObservabilityTest.DAILY_NINE)));
        when(store.fail(anyString(), any(Instant.class), anyString(), anyInt()))
                .thenReturn(Optional.of(new ScheduleStore.AfterFailure(1, true, null)));
        Recorder delivery = new Recorder();

        scanner(store, message -> {
            throw new IllegalStateException("模型服务 503");
        }, List.of(delivery)).scan();

        verify(store).fail(eq(DUE_ID), any(Instant.class), contains("模型服务 503"), eq(3));
        assertThat(fired("failed")).isEqualTo(1);
        assertThat(fired("disabled")).isZero();
        assertThat(delivery.sent).as("没到阈值不该通知").isEmpty();
    }

    @Test
    @DisplayName("到了阈值：停用并推一条带最后错误的通知——无人值守唯一能被听见的地方")
    void theThirdFailureDisablesTheTaskAndTellsItsOwner() {
        ScheduleStore store = mock(ScheduleStore.class);
        when(store.claim(anyInt(), any())).thenReturn(List.of(due(ObservabilityTest.DAILY_NINE)));
        when(store.fail(anyString(), any(Instant.class), anyString(), anyInt()))
                .thenReturn(Optional.of(
                        new ScheduleStore.AfterFailure(3, false, "连续 3 次没跑成")));
        Recorder delivery = new Recorder();

        scanner(store, message -> {
            throw new IllegalStateException("模型服务 503");
        }, List.of(delivery)).scan();

        assertThat(delivery.sent).hasSize(1);
        assertThat(delivery.sent.getFirst()).contains("报一次成交额").contains("模型服务 503")
                .contains("已停用").contains("连续 3 次没跑成");
        assertThat(fired("disabled")).isEqualTo(1);
        assertThat(fired("notified")).isEqualTo(1);
    }

    @Test
    @DisplayName("cron 解析不了当场停用，不走失败计数：再试三次也不会变好")
    void aBrokenCronIsDisabledWithoutCountingFailures() {
        ScheduleStore store = mock(ScheduleStore.class);
        when(store.claim(anyInt(), any())).thenReturn(List.of(due("每天九点")));
        Recorder delivery = new Recorder();
        AtomicInteger launched = new AtomicInteger();

        scanner(store, message -> {
            launched.incrementAndGet();
            return Optional.of("不该跑到这里");
        }, List.of(delivery)).scan();

        verify(store).disable(eq(DUE_ID), contains("cron 解析不了"));
        verify(store, never()).fail(anyString(), any(Instant.class), anyString(), anyInt());
        assertThat(launched.get()).as("cron 都坏了，不该去问模型").isZero();
        assertThat(delivery.sent).hasSize(1);
    }

    @Test
    @DisplayName("两种跑不起来各记一次失败：没有可用的 Agent、这一轮根本没开跑")
    void everyWayOfNotRunningIsAFailure() {
        ScheduleStore store = mock(ScheduleStore.class);
        when(store.fail(anyString(), any(Instant.class), anyString(), anyInt()))
                .thenReturn(Optional.of(new ScheduleStore.AfterFailure(1, true, null)));
        when(store.claim(anyInt(), any())).thenReturn(List.of(due(ObservabilityTest.DAILY_NINE)));

        // ① 没有可用的 Agent
        scanner(store, null, List.of()).scan();
        verify(store).fail(eq(DUE_ID), any(Instant.class), contains("没有可用的 Agent"), eq(3));

        // ② 门在，但这一轮没开跑（没有 run 存储）
        scanner(store, message -> Optional.empty(), List.of()).scan();
        verify(store).fail(eq(DUE_ID), any(Instant.class), contains("没有 run 存储"), eq(3));
    }

    @Test
    @DisplayName("身份认不出来当场停用：再试三次也不会变好，而且没人知道它在为谁干活")
    void anUnknownIdentityDisablesTheTaskImmediately() {
        ScheduleStore store = mock(ScheduleStore.class);
        when(store.claim(anyInt(), any())).thenReturn(List.of(due(ObservabilityTest.DAILY_NINE)));
        Recorder delivery = new Recorder();
        AtomicInteger launched = new AtomicInteger();

        scanner(store, message -> {
            launched.incrementAndGet();
            return Optional.of("不该跑到这里");
        }, List.of(delivery), (userId, role) -> Optional.empty()).scan();

        verify(store).disable(eq(DUE_ID), contains("身份认不出来"));
        verify(store, never()).fail(anyString(), any(Instant.class), anyString(), anyInt());
        assertThat(launched.get()).as("身份都没有，不该去问模型").isZero();
        assertThat(delivery.sent).hasSize(1);
    }

    @Test
    @DisplayName("工具权限被收回：同样当场停用，而且理由点名是哪个工具——不是「跑失败了」")
    void losingAToolPermissionDisablesTheTaskImmediately() {
        ScheduleStore store = mock(ScheduleStore.class);
        when(store.claim(anyInt(), any())).thenReturn(List.of(dueNeeding(ObservabilityTest.DAILY_NINE)));
        Recorder delivery = new Recorder();

        // 角色还在，但只剩读 region：querySales 要读 sales_order
        scanner(store, message -> Optional.of("不该跑到这里"), List.of(delivery),
                (userId, role) -> Optional.of(Principal.of(userId, role,
                        Set.of("region"), Set.of(ToolPermission.READ)))).scan();

        verify(store).disable(eq(DUE_ID), contains("sales_order"));
        verify(store, never()).fail(anyString(), any(Instant.class), anyString(), anyInt());
        assertThat(delivery.sent).hasSize(1);
        assertThat(delivery.sent.getFirst()).contains("报一次成交额").contains("sales_order");
    }

    @Test
    @DisplayName("cron 口径：只认 6 段、九点是九点、时区认不出来就报错")
    void cronSemantics() {
        // 5 段不猜秒，当场报错并带上原文——这句话要原样交给模型
        assertThatThrownBy(() -> ScheduleStore.parse("0 9 * * *"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("要 6 段");

        var nine = ScheduleStore.nextRunAt(ObservabilityTest.DAILY_NINE, "Asia/Shanghai",
                Instant.parse("2026-11-02T00:00:00Z")).orElseThrow();
        assertThat(nine).as("上海九点 = UTC 一点").isEqualTo(Instant.parse("2026-11-02T01:00:00Z"));

        assertThatThrownBy(() -> ScheduleStore.firstRunAt("0 0 9 * * *", "Mars/Olympus"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("时区认不出来");
    }

    @Test
    @DisplayName("库读不出来时这一轮跳过：后台任务不抛栈、不记失败、下一轮再来")
    void aBrokenTableSkipsTheRoundWithoutFailingTheTasks() {
        ScheduleStore store = mock(ScheduleStore.class);
        // 迁移没跑过、连接断了：认领这一步就炸了（这一层不吞，调度器要接住）
        when(store.claim(anyInt(), any())).thenThrow(new IllegalStateException("表不存在"));
        Recorder delivery = new Recorder();

        scanner(store, message -> Optional.of("不该跑到这里"), List.of(delivery)).scan();

        verify(store, never()).settle(anyString(), any(Instant.class));
        verify(store, never()).fail(anyString(), any(Instant.class), anyString(), anyInt());
        assertThat(delivery.sent).isEmpty();
        assertThat(fired("ok") + fired("failed")).isZero();
    }

    private ScheduleScanner scanner(ScheduleStore store, RunLauncher launcher,
                                    List<OutboundDelivery> deliveries) {
        return scanner(store, launcher, deliveries, everything);
    }

    private ScheduleScanner scanner(ScheduleStore store, RunLauncher launcher,
                                    List<OutboundDelivery> deliveries, RoleDirectory roles) {
        return new ScheduleScanner(store, launcher, new ScheduleAuthorization(roles, List.of(
                ToolPermissionRegistry.of(Map.of(TOOL, ToolPermission.read("sales_order"))))),
                deliveries, ScheduleProperties.defaults(), registry);
    }

    /** 到点的那一条。身份是"谁 + 什么角色"，权限由 {@link ScheduleAuthorization} 现算。 */
    private static ScheduleStore.Due due(String cron) {
        return due(cron, List.of());
    }

    /** 声明了一个工具的任务：到点之前要按当前权限核一遍。 */
    private static ScheduleStore.Due dueNeeding(String cron) {
        return due(cron, List.of(TOOL));
    }

    private static ScheduleStore.Due due(String cron, List<String> requiredTools) {
        return new ScheduleStore.Due(DUE_ID, "u-1@fp", "feishu", "oc_1", "报一次成交额", cron,
                "Asia/Shanghai", NotifyPolicy.SILENT, "u-1", "admin", requiredTools,
                Instant.now(), 0);
    }

    /** 没被记过的档位是 0，不是"没有这个计数器"。 */
    private double fired(String outcome) {
        var counter = registry.find(ScheduleScanner.METRIC).tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    /** 收集推出去的话：停用通知的原文就是这一组的断言对象。 */
    private static final class Recorder implements OutboundDelivery {

        private final List<String> sent = new ArrayList<>();

        @Override
        public String channel() {
            return "feishu";
        }

        @Override
        public void send(String channelRef, String text) {
            sent.add(text);
        }
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

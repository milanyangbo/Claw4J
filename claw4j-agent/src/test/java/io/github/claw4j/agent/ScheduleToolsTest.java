package io.github.claw4j.agent;

import io.github.claw4j.agent.channel.DeliveryTarget;
import io.github.claw4j.agent.schedule.Schedule;
import io.github.claw4j.agent.approval.ApprovalRequiredTools;
import io.github.claw4j.agent.intent.IntentMatcher;
import io.github.claw4j.agent.intent.IntentTools;
import io.github.claw4j.agent.intent.StandingIntent;
import io.github.claw4j.agent.intent.StandingIntents;
import io.github.claw4j.agent.schedule.ScheduleAuthorization;
import io.github.claw4j.agent.schedule.ScheduleStore;
import io.github.claw4j.agent.schedule.ScheduleTools;
import io.github.claw4j.agent.session.SessionKeys;
import io.github.claw4j.domain.ContextKeys;
import io.github.claw4j.domain.NotifyPolicy;
import io.github.claw4j.domain.principal.Principal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.ai.tool.execution.ToolExecutionException;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 定时任务三件工具（建、列、取消）与「这条任务现在还跑不跑得起来」的判据（P05）。
 *
 * <p>守的是「一次授权、多次执行」那句承诺的前半：建的时候把 cron 解析、是否需要审批两件事
 * 核完，理由原样交给模型。（"工具权限"那一条随角色表在 ADR-0030 里下线。）任何一条被放宽，症状都是任务建成了却永远跑不起来，而要
 * 到点那一刻（没人看着）才发现。
 *
 * <p>库上的那一半不在这里：建出来的行长什么样、收权之后会不会停用，由 app 的
 * {@code ScheduleFlowTest} 用真库验。这里 {@link ScheduleStore} 是替身，断言只落在
 * 「工具做了什么决定、说了什么话」。
 */
class ScheduleToolsTest {

    /** 建得起来的那个工具。 */
    private static final String SAFE = "query_sales";

    /** 每次执行都要人点头的那个工具：定时任务里它没有意义（到点没人能点）。它由
     * {@code claw4j.approval.required-tools} 点名，见 {@link #tools(ScheduleStore)}。 */
    private static final String NEEDS_APPROVAL = "send_email";

    private static final String GOAL = "每天九点报一次华东成交额";

    private static final String CHANNEL = "feishu";

    private static final String CHANNEL_REF = "oc_sched";

    /** 任务主人：身份只有一个 userId，能做什么由清单决定。 */
    private static final Principal OWNER = new Principal("u-1");

    @Test
    @DisplayName("建一条：落库的是身份与工具名，回执里说清编号、下一次、用哪些工具")
    void createsATaskAndSaysWhenItWillRunNext() {
        ScheduleStore store = mock(ScheduleStore.class);
        when(store.create(any())).thenReturn(Optional.of(schedule("3f2a9c1b-0000-4000-8000-000000000001")));

        String reply = create(store, OWNER, """
                {"goal":"%s","cron":"0 0 9 * * *","zone":"Asia/Shanghai","tools":["%s"]}"""
                .formatted(GOAL, SAFE));

        assertThat(reply).contains("已建好").contains("3f2a9c1b")
                .as("回执要说出下一次是什么时候：模型自己算不出 cron 的日历语义")
                .contains("下一次 ").contains("09:00").contains(SAFE)
                .as("默认静默：每一趟都推一条，等于把自动化做成骚扰")
                .contains("不主动推");

        ArgumentCaptor<ScheduleStore.Request> asked = ArgumentCaptor.forClass(ScheduleStore.Request.class);
        verify(store).create(asked.capture());
        ScheduleStore.Request request = asked.getValue();
        assertThat(request.owner()).isEqualTo(SessionKeys.ownerOf(OWNER));
        assertThat(request.requiredTools()).containsExactly(SAFE);
        assertThat(request.notifyPolicy()).isEqualTo(NotifyPolicy.SILENT);
        assertThat(request.channel()).as("送到这件事登记过的那个会话").isEqualTo(CHANNEL);
        assertThat(request.channelRef()).isEqualTo(CHANNEL_REF);
        // 身份只有 userId：角色与两份权限都不存在了（ADR-0030）
        assertThat(request.principal().userId()).isEqualTo(OWNER.userId());
    }

    @Test
    @DisplayName("说「发我」就记成 done_only：通知策略是任务的属性，不是通道的")
    void notifyPolicyComesFromTheRequest() {
        ScheduleStore store = mock(ScheduleStore.class);
        when(store.create(any())).thenReturn(Optional.of(schedule("ap-2")));

        String reply = create(store, OWNER, """
                {"goal":"%s","cron":"0 0 9 * * *","tools":[],"notify":"done_only"}"""
                .formatted(GOAL));

        ArgumentCaptor<ScheduleStore.Request> asked = ArgumentCaptor.forClass(ScheduleStore.Request.class);
        verify(store).create(asked.capture());
        assertThat(asked.getValue().notifyPolicy()).isEqualTo(NotifyPolicy.DONE_ONLY);
        assertThat(reply).contains("跑完推一条").contains("没有（只靠模型回答）");
    }

    @Test
    @DisplayName("cron 写成 5 段：当场拒并带上原文，一条都不落库")
    void aBrokenCronIsRefusedOnTheSpot() {
        ScheduleStore store = mock(ScheduleStore.class);

        String reply = create(store, OWNER, """
                {"goal":"%s","cron":"0 9 * * *","tools":[]}""".formatted(GOAL));

        assertThat(reply).contains("没建成").contains("0 9 * * *").contains("6 段");
        verify(store, never()).create(any());
    }

    @Test
    @DisplayName("时区写错也当场拒：不能悄悄按系统时区算出一个「九点」")
    void aBrokenZoneIsRefusedOnTheSpot() {
        String reply = create(mock(ScheduleStore.class), OWNER, """
                {"goal":"%s","cron":"0 0 9 * * *","zone":"Mars/Olympus","tools":[]}"""
                .formatted(GOAL));

        assertThat(reply).contains("没建成").contains("时区认不出来");
    }

    @Test
    @DisplayName("要审批的工具进不了定时任务：到点那一刻没人能点那个按钮")
    void aToolThatAlwaysNeedsApprovalIsRefused() {
        ScheduleStore store = mock(ScheduleStore.class);

        String reply = create(store, OWNER, """
                {"goal":"%s","cron":"0 0 9 * * *","tools":["%s"]}""".formatted(GOAL, NEEDS_APPROVAL));

        assertThat(reply).contains("没建成").contains(NEEDS_APPROVAL)
                .as("要说出原因，不然模型只会换个名字再试一次")
                .contains("没有人能点");
        verify(store, never()).create(any());
    }

    @Test
    @DisplayName("没有身份就什么都不做：不建、不列、不取消")
    void withoutAPrincipalNothingHappens() {
        ScheduleStore store = mock(ScheduleStore.class);
        ScheduleTools tools = tools(store);

        assertThat(call(tools, ScheduleTools.CREATE, "{\"goal\":\"x\",\"cron\":\"0 0 9 * * *\"}", null))
                .contains("没有携带身份");
        assertThat(call(tools, ScheduleTools.LIST, "{}", null)).contains("没有携带身份");
        assertThat(call(tools, ScheduleTools.CANCEL, "{\"id\":\"3f2a9c1b\"}", null))
                .contains("没有携带身份");
        verify(store, never()).create(any());
        verify(store, never()).listOf(anyString());
        verify(store, never()).disable(anyString(), anyString());
    }

    @Test
    @DisplayName("参数不是 JSON：包成 ToolExecutionException，框架把它变成一段工具结果回灌给模型")
    void badJsonBecomesToolExecutionException() {
        ScheduleTools tools = tools(mock(ScheduleStore.class));
        ToolCallback create = tool(tools, ScheduleTools.CREATE);

        assertThatThrownBy(() -> create.call("不是 JSON", context(OWNER)))
                .as("裸 Jackson 异常冒到路由层会变成一次整轮重试；包成这一种才进得了框架的回落路径")
                .isInstanceOf(ToolExecutionException.class);

        // 框架的处理器（alwaysThrow=false）不抛，而是把异常变成一句话——模型在同一轮就能看到，
        // 自己重发一次调用。这句文本长什么样由框架决定，我们只断言它真的带上了原因。
        String advice = new DefaultToolExecutionExceptionProcessor(false).process(
                (ToolExecutionException) catchThrowable(() -> create.call("不是 JSON", context(OWNER))));
        assertThat(advice).contains("JSON");
    }

    @Test
    @DisplayName("缺 goal 这类参数问题仍回一句话：它不是「读不懂」，换一个参数就能自救")
    void missingArgumentStillComesBackAsAdvice() {
        ScheduleTools tools = tools(mock(ScheduleStore.class));

        assertThat(call(tools, ScheduleTools.CREATE, "{\"cron\":\"0 0 9 * * *\"}", context(OWNER)))
                .contains("没办成").contains("goal");
    }

    @Test
    @DisplayName("列出我的：编号只给前 8 位，停用的带上原因，一条都没有时也说实话")
    void listsWhatIBuilt() {
        ScheduleStore store = mock(ScheduleStore.class);
        when(store.listOf(anyString())).thenReturn(List.of(
                schedule("aaaabbbb-0000-4000-8000-000000000001"),
                disabled(schedule("ccccdddd-0000-4000-8000-000000000002"))));

        String reply = call(tools(store), ScheduleTools.LIST, "{}", context(OWNER));

        assertThat(reply).contains("2 条").contains("aaaabbbb").contains("ccccdddd")
                .contains("下次").contains("已停用").contains("连续 3 次没跑成");

        when(store.listOf(anyString())).thenReturn(List.of());
        assertThat(call(tools(store), ScheduleTools.LIST, "{}", context(OWNER)))
                .contains("还没有定时任务");
    }

    @Test
    @DisplayName("取消我说过的那一条：软停用并留下原因，记录不删")
    void cancelsMyOwnTask() {
        ScheduleStore store = mock(ScheduleStore.class);
        when(store.listOf(anyString())).thenReturn(List.of(schedule("3f2a9c1b-0000-4000-8000-000000000001")));

        String reply = call(tools(store), ScheduleTools.CANCEL, "{\"id\":\"3f2a9c1b\"}", context(OWNER));

        assertThat(reply).contains("已取消").contains("3f2a9c1b").contains(GOAL);
        verify(store).disable(eq("3f2a9c1b-0000-4000-8000-000000000001"), contains("自己取消"));
    }

    @Test
    @DisplayName("别人的任务与不存在的任务给同一个答案：分开说等于替对方确认「这个编号存在」")
    void cancellingSomeoneElsesTaskLooksLikeItDoesNotExist() {
        ScheduleStore store = mock(ScheduleStore.class);
        // listOf 按归属键查，别人的任务根本不在结果里
        when(store.listOf(anyString())).thenReturn(List.of());

        String reply = call(tools(store), ScheduleTools.CANCEL, "{\"id\":\"ffffffff\"}", context(OWNER));

        assertThat(reply).contains("没有找到");
        verify(store, never()).disable(anyString(), anyString());
    }

    @Test
    @DisplayName("前 8 位撞车时不要瞎猜：说清有几条、让模型多写几位")
    void anAmbiguousPrefixIsReportedInsteadOfGuessed() {
        ScheduleStore store = mock(ScheduleStore.class);
        when(store.listOf(anyString())).thenReturn(List.of(
                schedule("aaaabbbb-0000-4000-8000-000000000001"),
                schedule("aaaabbbb-0000-4000-8000-000000000002")));

        String reply = call(tools(store), ScheduleTools.CANCEL, "{\"id\":\"aaaabbbb\"}", context(OWNER));

        assertThat(reply).contains("2 条").contains("多写几位");
        verify(store, never()).disable(anyString(), anyString());
    }

    // ---- 零件 ----

    /** 生产那套装配：真 store、真判据、收件地址来自这一轮的工具上下文。 */
    private static ScheduleTools tools(ScheduleStore store) {
        return new ScheduleTools(store,
                new ScheduleAuthorization(new ApprovalRequiredTools(Set.of(NEEDS_APPROVAL))));
    }

    private static String create(ScheduleStore store, Principal principal, String args) {
        return call(tools(store), ScheduleTools.CREATE, args, context(principal));
    }

    private static String call(ScheduleTools tools, String name, String args, ToolContext context) {
        return Arrays.stream(tools.getToolCallbacks())
                .filter(tool -> tool.getToolDefinition().name().equals(name))
                .map(tool -> tool.call(args, context))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("没有这个工具：" + name));
    }

    /** 拿单个回调，用来断言它会抛什么。 */
    private static ToolCallback tool(ScheduleTools tools, String name) {
        return Arrays.stream(tools.getToolCallbacks())
                .filter(tool -> tool.getToolDefinition().name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("没有这个工具：" + name));
    }

    /** 模型那一侧看到的那份上下文：身份 + 这一轮的 run（工具靠它找收件地址）。 */
    private static ToolContext context(Principal principal) {
        return new ToolContext(Map.of(ContextKeys.PRINCIPAL, principal, ContextKeys.RUN_ID, "run-1",
                ContextKeys.CHANNEL, CHANNEL, ContextKeys.CHAT_REF, CHANNEL_REF));
    }

    private static Schedule schedule(String id) {
        return new Schedule(id, "u-1@fp", CHANNEL, CHANNEL_REF, GOAL, "0 0 9 * * *",
                "Asia/Shanghai", NotifyPolicy.SILENT,
                Instant.parse("2026-11-03T01:00:00Z"), null, 0, null, true, null);
    }

    private static Schedule disabled(Schedule task) {
        return new Schedule(task.id(), task.owner(), task.channel(), task.channelRef(), task.goal(),
                task.cron(), task.zone(), task.notifyPolicy(), task.nextRunAt(), null, 3,
                "模型服务 503", false, "连续 3 次没跑成");
    }

    // ---------------------------------------------------------------- 意图（票 02）

    /**
     * 一条意图：中文触发词、3 次预算、1 天冷却。
     */
    private static StandingIntent intent(String triggers) {
        Instant now = Instant.now();
        return new StandingIntent("abc12345", "alice", "确认是签约数还是活跃数", triggers,
                null, null, "alice", StandingIntent.Status.ARMED,
                now.plus(Duration.ofDays(90)), 3, 0, 86_400, null, now, "s-1");
    }

    private static ToolContext alice() {
        return new ToolContext(Map.of(ContextKeys.PRINCIPAL, new Principal("alice"),
                "claw4j.channel", "http"));
    }

    @Test
    @DisplayName("中文触发词按子串命中，英文按整词（票 02）")
    void cjkMatchesSubstringLatinMatchesWholeWord() {
        assertThat(IntentMatcher.matches(intent("客户数"), "帮我看看客户数"))
                .as("中文不靠空格分词，整词匹配等于永远不命中")
                .isTrue();
        assertThat(IntentMatcher.matches(intent("客户数"), "客户数量是多少"))
                .as("子串：\"客户数\" 就在 \"客户数量\" 里")
                .isTrue();
        assertThat(IntentMatcher.matches(intent("客户数"), "帮我看看表结构"))
                .as("没出现触发词就不该命中")
                .isFalse();
        assertThat(IntentMatcher.matches(intent("customer"), "how many customers"))
                .as("英文整词：customer 不该命中 customers")
                .isFalse();
        assertThat(IntentMatcher.matches(intent("customer"), "one customer asked"))
                .isTrue();
    }

    @Test
    @DisplayName("没有身份就建不了意图：归属从上下文取，不从参数取")
    void intentToolRefusesWithoutPrincipal() {
        StandingIntents store = mock(StandingIntents.class);
        IntentTools tools = new IntentTools(store);

        String result = tools.intent("create", "提醒我", "客户数", 3, 86_400, 90, null, null);

        assertThat(result).contains("没有携带身份");
        verify(store, never()).create(any());
    }

    @Test
    @DisplayName("建意图时 owner 与频道都取自这一轮的上下文")
    void createTakesOwnerAndChannelFromContext() {
        StandingIntents store = mock(StandingIntents.class);
        when(store.create(any())).thenReturn(intent("客户数"));
        IntentTools tools = new IntentTools(store);

        String result = tools.intent("create", "确认是签约数还是活跃数", "客户数", 3, 86_400, 90,
                null, alice());

        ArgumentCaptor<StandingIntents.New> captor = ArgumentCaptor.forClass(StandingIntents.New.class);
        verify(store).create(captor.capture());
        assertThat(captor.getValue().owner()).isEqualTo("alice");
        assertThat(captor.getValue().channelScope()).isEqualTo("http");
        assertThat(captor.getValue().maxFires()).isEqualTo(3);
        assertThat(result).contains("abc12345").contains("客户数");
    }

    @Test
    @DisplayName("列出来时带编号、触发次数与预算：模型要靠编号才能撤销")
    void listShowsIdAndBudget() {
        StandingIntents store = mock(StandingIntents.class);
        when(store.list("alice")).thenReturn(List.of(intent("客户数")));
        IntentTools tools = new IntentTools(store);

        String result = tools.intent("list", null, null, null, null, null, null, alice());

        assertThat(result).contains("abc12345").contains("客户数").contains("0/3");
    }

    @Test
    @DisplayName("取消按 owner 判：猜中别人的编号也撤不掉")
    void cancelIsOwnerScoped() {
        StandingIntents store = mock(StandingIntents.class);
        when(store.cancel("alice", "abc12345")).thenReturn(true);
        IntentTools tools = new IntentTools(store);

        String result = tools.intent("cancel", null, null, null, null, null, "abc12345", alice());

        verify(store).cancel("alice", "abc12345");
        assertThat(result).contains("取消了");
    }
}

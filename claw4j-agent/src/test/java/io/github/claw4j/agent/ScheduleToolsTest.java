package io.github.claw4j.agent;

import io.github.claw4j.agent.channel.DeliveryTarget;
import io.github.claw4j.agent.schedule.Schedule;
import io.github.claw4j.agent.schedule.ScheduleAuthorization;
import io.github.claw4j.agent.schedule.ScheduleStore;
import io.github.claw4j.agent.schedule.ScheduleTools;
import io.github.claw4j.agent.session.SessionKeys;
import io.github.claw4j.domain.ContextKeys;
import io.github.claw4j.domain.NotifyPolicy;
import io.github.claw4j.domain.port.RoleDirectory;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 定时任务这一族：建 / 列 / 取消三个工具，以及"这条任务现在还跑不跑得起来"（P05）。
 *
 * <p><b>这个文件真正要钉的是"一次授权"那句话</b>：建的时候把三件事核完——cron 解析得了、
 * 要用的工具主人现在都有、这些工具里没有"每次都要人批准"的。核不过就当场拒，
 * 理由原样交给模型。核得过的意思就是"以后每一次到点都按他此刻的权限跑"，
 * 而到点那一刻的复核在 {@link ScheduleAuthorization} 里、同一个判据。
 *
 * <p><b>库上的那一半不在这里</b>：建出来的行长什么样、两次到点各开一个 run、
 * 收权之后会不会停用，都在 app 的 {@code ScheduleFlowTest} 里用真库验。
 * 这里对 {@link ScheduleStore} 用替身，只断言"工具做了什么决定、说了什么话"。
 */
class ScheduleToolsTest {

    /** 建得起来的那个工具：要读一张业务表。 */
    private static final String SAFE = "query_sales";

    /** 每次执行都要人点头的那个工具：定时任务里它没有意义（到点没人能点）。 */
    private static final String NEEDS_APPROVAL = "send_email";

    /** 名字写错、或者没登记过权限：一律按"评估不了"处理。 */
    private static final String UNREGISTERED = "querySale";

    private static final String GOAL = "每天九点报一次华东成交额";

    private static final String CHANNEL = "feishu";

    private static final String CHANNEL_REF = "oc_sched";

    /** 任务主人：读得到 sales_order，也有三个定时任务动作。 */
    private static final Principal OWNER = Principal.of("u-1", "read-only", Set.of("*"),
            Set.of(ToolPermission.READ, ScheduleTools.CREATE, ScheduleTools.LIST, ScheduleTools.CANCEL));

    /** 读不到 sales_order 的那个身份：同一个角色，权限收窄。 */
    private static final Principal NARROW = Principal.of("u-2", "guest", Set.of("region"),
            Set.of(ToolPermission.READ, ScheduleTools.CREATE));

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
        // 身份是"谁 + 什么角色"，两份权限一个字都不落库
        assertThat(request.principal().userId()).isEqualTo(OWNER.userId());
        assertThat(request.principal().role()).isEqualTo(OWNER.role());
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
    @DisplayName("要用的工具现在没有：当场拒，理由点名是动作还是表")
    void aToolTheCreatorCannotUseIsRefused() {
        ScheduleStore store = mock(ScheduleStore.class);

        String reply = create(store, NARROW, """
                {"goal":"%s","cron":"0 0 9 * * *","tools":["%s"]}""".formatted(GOAL, SAFE));

        assertThat(reply).contains("没建成").contains("sales_order").contains("guest");
        verify(store, never()).create(any());
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
    @DisplayName("工具名拼错 / 没登记权限：同样当场拒，不假装它会跑")
    void anUnregisteredToolIsRefused() {
        ScheduleStore store = mock(ScheduleStore.class);

        String reply = create(store, OWNER, """
                {"goal":"%s","cron":"0 0 9 * * *","tools":["%s"]}""".formatted(GOAL, UNREGISTERED));

        assertThat(reply).contains("没建成").contains("没登记权限");
        verify(store, never()).create(any());
    }

    @Test
    @DisplayName("角色认不出来就不建：建出来也是一条永远跑不起来的任务")
    void anUnknownRoleIsRefused() {
        ScheduleStore store = mock(ScheduleStore.class);
        ScheduleTools tools = new ScheduleTools(store,
                new ScheduleAuthorization((userId, role) -> Optional.empty(), registries()));

        String reply = call(tools, ScheduleTools.CREATE, """
                {"goal":"%s","cron":"0 0 9 * * *","tools":[]}""".formatted(GOAL),
                context(OWNER));

        assertThat(reply).contains("没建成").contains("认不出你的角色");
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
    @DisplayName("参数不是 JSON、或者缺 goal：回一句话让模型自己改，不抛成整轮 500")
    void badArgumentsComeBackAsAdvice() {
        ScheduleTools tools = tools(mock(ScheduleStore.class));

        assertThat(call(tools, ScheduleTools.CREATE, "不是 JSON", context(OWNER)))
                .contains("没办成").contains("JSON");
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

    private static List<ToolPermissionRegistry> registries() {
        return List.of(ToolPermissionRegistry.of(Map.of(
                SAFE, ToolPermission.read("sales_order"),
                NEEDS_APPROVAL, ToolPermission.requiringApproval("send_email"))));
    }

    /** 生产那套装配：真 store、真判据、收件地址来自这一轮的 run。 */
    private static ScheduleTools tools(ScheduleStore store) {
        return new ScheduleTools(store,
                new ScheduleAuthorization((userId, role) -> Optional.of(Principal.of(userId, role,
                        Set.of("*"), Set.of(ToolPermission.READ, ScheduleTools.CREATE))), registries()));
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
}

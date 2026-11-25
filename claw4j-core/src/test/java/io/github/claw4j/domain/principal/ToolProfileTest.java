package io.github.claw4j.domain.principal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 能力轴：一个部署选哪个画像，以及筛完之后清单里还剩什么（P09 / ADR-0030）。
 *
 * <p>这几条钉的都是清单这一侧：画像里没有的工具不在清单里，不是执行时才被拒。前者模型根本不会去
 * 计划它，后者会让模型先计划再撞墙、换个写法再试一次。
 *
 * <p>四档是四个<b>互不包含</b>的集合，所以"coding 有、messaging 没有"与"messaging 有、coding
 * 没有"都必须能表达——那正是这次从阶梯改集合的唯一理由。
 *
 * <p>三个失败姿势（没声明画像、allow 与 deny 冲突、筛成空）都按"启动失败且点名"验，因为它们的
 * 共同后果是"配置写错了但系统照跑"，那比起不来更难查。
 *
 * <p>断言用 JUnit 自带的：core 的依赖面是设计的一部分（只有 JUnit 与 ArchUnit，见
 * {@code CoreBoundariesTest}），不为了断言好看给领域模块加一条测试依赖。
 */
class ToolProfileTest {

    /** 生产上的形状：地基工具全画像、外部工具 coding+messaging、机器类只有 coding。 */
    private static List<ToolTierRegistry> registries() {
        return List.of(
                ToolTierRegistry.allProfilesFor(Set.of("toolSearchTool", "systemStatus", "intent",
                        "recall_session")),
                ToolTierRegistry.external(Set.of("querySales", "sendMail"),
                        ToolTier.CODING, ToolTier.MESSAGING),
                ToolTierRegistry.sameFor(Set.of("run_script", "propose_script", "delegate_task",
                        "schedule_task", "Bash", "BashOutput", "KillShell"), ToolTier.CODING),
                ToolTierRegistry.sameFor(Set.of("echo", "serverTime"), ToolTier.FULL));
    }

    private static final List<String> MANIFEST = List.of(
            "toolSearchTool", "systemStatus", "intent", "recall_session",
            "querySales", "sendMail",
            "run_script", "propose_script", "delegate_task", "schedule_task",
            "Bash", "BashOutput", "KillShell",
            "echo", "serverTime");

    /** 失败消息里逐条断言"说了该说的东西"，比逐字比对整句稳。 */
    private static String failureMessage(Executable executable) {
        return assertThrows(IllegalStateException.class, executable).getMessage();
    }

    private static Set<String> kept(ToolTier profile) {
        return Set.copyOf(new ToolProfile(profile, Set.of(), Set.of()).select(MANIFEST, registries()).kept());
    }

    @Test
    @DisplayName("默认画像一个都不挡：升级到这套机制不该悄悄少一批工具")
    void unrestrictedKeepsEverything() {
        ToolProfile.Selection selection = ToolProfile.unrestricted().select(MANIFEST, registries());

        assertEquals(MANIFEST, selection.kept());
        assertTrue(selection.byTier().isEmpty());
        assertTrue(selection.byAllow().isEmpty());
        assertTrue(selection.byDeny().isEmpty());
        assertFalse(selection.droppedAnything());
    }

    @Test
    @DisplayName("messaging 画像：碰机器的与自主行动的都不在清单里，查数不受影响")
    void messagingDropsMachineAndAutonomyTools() {
        Set<String> kept = kept(ToolTier.MESSAGING);

        assertEquals(Set.of("toolSearchTool", "systemStatus", "intent", "recall_session",
                "querySales", "sendMail"), kept);
    }

    @Test
    @DisplayName("coding 画像里有定时任务与子 Agent：它们只属于 coding，不属于 messaging")
    void codingHasCronAndSubagents() {
        Set<String> kept = kept(ToolTier.CODING);

        assertTrue(kept.containsAll(Set.of("schedule_task", "delegate_task", "Bash", "run_script")), kept.toString());
        // 反过来也要成立，否则"互不包含"就是一句空话。
        assertFalse(kept(ToolTier.MESSAGING).contains("schedule_task"));
        assertFalse(kept(ToolTier.MESSAGING).contains("delegate_task"));
    }

    @Test
    @DisplayName("minimal 画像只留地基：外部工具（业务/MCP）也不在")
    void minimalKeepsOnlyFoundationTools() {
        Set<String> kept = kept(ToolTier.MINIMAL);

        assertEquals(Set.of("toolSearchTool", "systemStatus", "intent", "recall_session"), kept);
        // 外部工具桶不进 minimal——与 OpenClaw "minimal still hides them" 一致。
        assertFalse(kept.contains("querySales"));
    }

    @Test
    @DisplayName("工具没声明画像：启动失败并点名是哪一个")
    void undeclaredProfilesFailAndNameTheTool() {
        ToolProfile profile = new ToolProfile(ToolTier.MESSAGING, Set.of(), Set.of());

        String message = failureMessage(() -> profile.select(List.of("querySales", "mystery"), registries()));

        assertTrue(message.contains("mystery"), message);
        assertTrue(message.contains("MESSAGING"), message);
        // 报错要说清该去哪补：这是配置错误，不是运行时状况。
        assertTrue(message.contains("ToolTierRegistry"), message);
    }

    @Test
    @DisplayName("allow 只能收窄，不能放宽画像；被画像挡住的进 widenBlocked 而不是静默消失")
    void allowOnlyNarrows() {
        ToolProfile profile = new ToolProfile(ToolTier.MESSAGING,
                Set.of("run_script", "querySales"), Set.of());

        ToolProfile.Selection selection = profile.select(MANIFEST, registries());

        // run_script 写在 allow 里也越不过 messaging 画像——否则 allow 成了绕过画像的口子。
        assertEquals(List.of("querySales"), selection.kept());
        assertTrue(selection.byTier().contains("run_script"));
        assertTrue(selection.byAllow().containsAll(List.of("toolSearchTool", "sendMail")));
        // 这条是"allow 不放宽画像"的唯一可见出口：不报的话下一个人会去改 deny。
        assertEquals(List.of("run_script"), selection.widenBlocked());
    }

    @Test
    @DisplayName("alsoAllow 能突破画像：messaging 加一个脚本，不用把整个部署调成 coding")
    void alsoAllowWidensBeyondProfile() {
        ToolProfile profile = new ToolProfile(ToolTier.MESSAGING, Set.of(), Set.of(), Set.of("run_script"));

        ToolProfile.Selection selection = profile.select(MANIFEST, registries());

        assertTrue(selection.kept().contains("run_script"), selection.kept().toString());
        // 其余仍按 messaging 画像走：放宽的是点名的那个，不是整档。
        assertFalse(selection.kept().contains("Bash"));
        assertTrue(selection.widenBlocked().isEmpty());
    }

    @Test
    @DisplayName("只配 alsoAllow、不配 allow 的那一层等于不设限：writer 的意思是\"再加几个\"")
    void alsoAllowAloneMeansUnrestricted() {
        ToolProfile profile = new ToolProfile(ToolTier.MESSAGING, Set.of(), Set.of(), Set.of("run_script"));

        Set<String> kept = Set.copyOf(profile.select(MANIFEST, registries()).kept());

        // 关键：不能因为写了 alsoAllow 就把其余全封掉——那与"再加几个"的意图相反。
        assertTrue(kept.containsAll(Set.of("querySales", "sendMail", "toolSearchTool", "run_script")), kept.toString());
    }

    @Test
    @DisplayName("deny 在画像与白名单之后生效；allow 与 deny 同时命中同一个工具就是配置错误")
    void denyAppliesAfterAllowAndConflictFails() {
        ToolProfile.Selection selection =
                new ToolProfile(ToolTier.FULL, Set.of(), Set.of("echo", "sendMail"))
                        .select(MANIFEST, registries());
        assertTrue(selection.kept().containsAll(List.of("toolSearchTool", "run_script")));
        assertFalse(selection.kept().contains("echo"));
        assertFalse(selection.kept().contains("sendMail"));

        String message = failureMessage(() -> new ToolProfile(ToolTier.FULL, Set.of("echo"), Set.of("echo")));
        assertTrue(message.contains("echo"), message);
        assertTrue(message.contains("claw4j.tools.allow"), message);
        assertTrue(message.contains("claw4j.tools.deny"), message);
    }

    @Test
    @DisplayName("筛成一个都不剩：启动失败，并说清三个原因各挡掉几个")
    void emptyResultFailsWithBreakdown() {
        ToolProfile profile = new ToolProfile(ToolTier.MINIMAL, Set.of(), Set.of("toolSearchTool"));

        String message = failureMessage(() -> profile.select(
                List.of("toolSearchTool", "run_script"), registries()));

        assertTrue(message.contains("筛空"), message);
        assertTrue(message.contains("画像挡掉 1 个"), message);
        assertTrue(message.contains("deny 排除 1 个"), message);
    }

    @Test
    @DisplayName("清单本来就是空的（没引任何能力模块）不算错：没有工具和把工具挡光不是一回事")
    void emptyManifestIsNotAnError() {
        ToolProfile.Selection selection =
                new ToolProfile(ToolTier.MINIMAL, Set.of(), Set.of()).select(List.of(), registries());

        assertTrue(selection.kept().isEmpty());
    }

    @Test
    @DisplayName("配置里写了、清单里没有的名字进 unused：部署裁剪与拼错长得一样，所以要列出来")
    void unusedConfiguredNamesAreReported() {
        ToolProfile profile = new ToolProfile(ToolTier.FULL, Set.of(), Set.of("run_script", "sun_script"));

        ToolProfile.Selection selection = profile.select(List.of("querySales"), registries());

        assertEquals(List.of("run_script", "sun_script"), selection.unused());
        assertEquals(List.of("querySales"), selection.kept());
    }

    @Test
    @DisplayName("配置里的空白与空项被收掉，不留一个永远匹配不到的空串")
    void blankEntriesAreNormalized() {
        ToolProfile profile = new ToolProfile(ToolTier.FULL,
                Set.of("  querySales  ", "   "), Set.of(""));

        assertEquals(Set.of("querySales"), profile.allow());
        assertTrue(profile.deny().isEmpty());
        assertEquals(List.of("querySales"), profile.select(MANIFEST, registries()).kept());
    }

    @Test
    @DisplayName("四档各是各的：coding 有、messaging 没有的那批工具，反过来一个都不成立")
    void profilesAreIndependent() {
        Set<String> coding = kept(ToolTier.CODING);
        Set<String> messaging = kept(ToolTier.MESSAGING);

        // 只属于 coding 的那批：碰机器的与自主行动的。
        assertTrue(coding.containsAll(Set.of("run_script", "Bash", "schedule_task", "delegate_task")));
        for (String machine : List.of("run_script", "Bash", "schedule_task", "delegate_task")) {
            assertFalse(messaging.contains(machine), machine + " 不该出现在 messaging 画像里");
        }
        // 两边都含地基与外部工具——那才让"客服型部署"这个画像立得住。
        assertTrue(messaging.containsAll(Set.of("toolSearchTool", "querySales")));
    }

    @Test
    @DisplayName("集合语义的表达力：一个工具可以\"在两头、不在中间\"，阶梯表达不了")
    void aToolCanLiveInBothEndsButNotTheMiddle() {
        // 生产数据里 messaging 碰巧是 coding 的子集，所以用一份专门的登记表验表达力。
        // 这正是从阶梯改集合的<b>唯一</b>理由：外部工具要在 coding 与 messaging 都出现、
        // 在 minimal 不出现；阶梯下"MESSAGING"标签会顺带进 coding，那只是巧合，不是声明。
        List<ToolTierRegistry> bothEnds = List.of(
                ToolTierRegistry.allProfilesFor(Set.of("toolSearchTool")),
                ToolTierRegistry.sameFor(Set.of("mcpTool"), ToolTier.MINIMAL, ToolTier.MESSAGING),
                ToolTierRegistry.sameFor(Set.of("Bash"), ToolTier.CODING));
        List<String> manifest = List.of("toolSearchTool", "mcpTool", "Bash");

        assertTrue(new ToolProfile(ToolTier.MINIMAL, Set.of(), Set.of())
                .select(manifest, bothEnds).kept().contains("mcpTool"));
        assertTrue(new ToolProfile(ToolTier.MESSAGING, Set.of(), Set.of())
                .select(manifest, bothEnds).kept().contains("mcpTool"));
        // 中间那一档没有它——阶梯语义下这条不可能表达。
        assertFalse(new ToolProfile(ToolTier.CODING, Set.of(), Set.of())
                .select(manifest, bothEnds).kept().contains("mcpTool"));
    }

    @Test
    @DisplayName("sameFor 只认它自己那组名字：写成\"任何名字都算\"会让未声明校验永远通过")
    void sameForDoesNotActAsCatchAll() {
        ToolTierRegistry registry = ToolTierRegistry.sameFor(List.of("echo"), ToolTier.FULL);

        assertEquals(Optional.of(Set.of(ToolTier.FULL)), registry.profilesFor("echo"));
        assertEquals(Optional.empty(), registry.profilesFor("someone_else"));
        // 内置工具不是外部工具：group:plugins 不该把它们算进去。
        assertTrue(registry.externalToolNames().isEmpty());
    }

    @Test
    @DisplayName("external 既登记画像也登记外部名单：两件事必须同源，否则 group:plugins 关不掉它")
    void externalRegistersBothProfilesAndGroup() {
        ToolTierRegistry registry = ToolTierRegistry.external(Set.of("querySales"),
                ToolTier.CODING, ToolTier.MESSAGING);

        assertEquals(Optional.of(Set.of(ToolTier.CODING, ToolTier.MESSAGING)), registry.profilesFor("querySales"));
        assertEquals(Set.of("querySales"), registry.externalToolNames());
    }

    @Test
    @DisplayName("deny: [group:plugins] 一次关掉全部外部工具（业务模块 + MCP）")
    void denyPluginsGroupRemovesAllExternalTools() {
        ToolProfile.Selection selection =
                new ToolProfile(ToolTier.FULL, Set.of(), Set.of("group:plugins"), Set.of())
                        .select(MANIFEST, registries());

        assertFalse(selection.kept().contains("querySales"));
        assertFalse(selection.kept().contains("sendMail"));
        // 内置工具不受影响：这个组只含外部来源。
        assertTrue(selection.kept().contains("Bash"));
    }

    @Test
    @DisplayName("group 简写展开成成员清单：deny: [group:runtime] 关掉 shell 那三件")
    void groupShorthandExpands() {
        ToolProfile.Selection selection =
                new ToolProfile(ToolTier.FULL, Set.of(), Set.of(ToolGroups.RUNTIME), Set.of())
                        .select(MANIFEST, registries());

        assertFalse(selection.kept().contains("Bash"));
        assertEquals(Set.of("Bash", "BashOutput", "KillShell"), Set.copyOf(selection.byDeny()));
        // 组展开出来的名字都在清单里（否则会进 unused）：工具改名会先在那份清单里显形。
        assertTrue(selection.unused().isEmpty(), selection.unused().toString());
    }

    @Test
    @DisplayName("group:openclaw 是\"清单减去外部\"：加一个内置工具不用改这个组")
    void openclawGroupIsManifestMinusExternal() {
        ToolProfile.Selection selection =
                new ToolProfile(ToolTier.FULL, Set.of("group:openclaw"), Set.of(), Set.of())
                        .select(MANIFEST, registries());

        assertTrue(selection.kept().containsAll(Set.of("Bash", "toolSearchTool", "echo")));
        assertFalse(selection.kept().contains("querySales"));
    }

    @Test
    @DisplayName("重复的工具名不在这里合并：重名是配置错误，该在清单断言上红")
    void duplicatesAreKept() {
        ToolProfile.Selection selection =
                ToolProfile.unrestricted().select(List.of("echo", "echo"), registries());

        assertEquals(List.of("echo", "echo"), selection.kept());
    }

    @Test
    @DisplayName("画像只回答「这个部署有没有这个工具」：数据轴不在这一层")
    void profileIsOnlyAboutTheDeployment() {
        // messaging 与 coding 都有 querySales，"查表不受影响"是画像的承诺；
        // minimal 没有它，因为它不是地基工具。原来这条用例还断言"画像放行但数据轴会拒"——
        // 数据轴（谁能读哪张表）在 ADR-0030 里整块下线，那一半没有对象了。
        assertTrue(kept(ToolTier.MESSAGING).contains("querySales"));
        assertTrue(kept(ToolTier.CODING).contains("querySales"));
        assertFalse(kept(ToolTier.MINIMAL).contains("querySales"));
    }
}

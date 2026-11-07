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
 * 能力轴：一个部署允许到哪一档，以及筛完之后清单里还剩什么（P09）。
 *
 * <p>这几条钉的都是"清单"这一侧：档位不够的工具**不在清单里**，不是执行时才被拒。
 * 前者模型压根不会去计划它，后者会让模型先计划再撞墙、换个写法再试一次。
 *
 * <p>三个失败姿势（没声明档、allow 与 deny 冲突、筛成空）都按"启动失败且点名"验，
 * 因为它们的共同后果是"配置写错了但系统照跑"，那比起不来更难查。
 *
 * <p>断言用 JUnit 自带的：core 的依赖面是设计的一部分（只有 JUnit 与 ArchUnit，
 * 见 {@code CoreBoundariesTest}），不为了断言好看给领域模块加一条测试依赖。
 */
class ToolProfileTest {

    /** 生产上的形状：每个工具一个值，各模块各登记各的。 */
    private static List<ToolTierRegistry> registries() {
        return List.of(
                ToolTierRegistry.sameFor(Set.of("toolSearchTool", "systemStatus", "get_goal",
                        "recall_session"), ToolTier.MINIMAL),
                ToolTierRegistry.sameFor(Set.of("querySales", "sendMail", "delegate_task"),
                        ToolTier.MESSAGING),
                ToolTierRegistry.sameFor(Set.of("run_script", "propose_script"), ToolTier.CODING),
                ToolTierRegistry.sameFor(Set.of("echo", "serverTime"), ToolTier.FULL));
    }

    private static final List<String> MANIFEST = List.of(
            "toolSearchTool", "systemStatus", "get_goal", "recall_session",
            "querySales", "sendMail", "delegate_task",
            "run_script", "propose_script",
            "echo", "serverTime");

    /** 失败消息里逐条断言"说了该说的东西"，比逐字比对整句稳。 */
    private static String failureMessage(Executable executable) {
        return assertThrows(IllegalStateException.class, executable).getMessage();
    }

    @Test
    @DisplayName("默认档一个都不挡：升级到这套机制不该悄悄少一批工具")
    void unrestrictedKeepsEverything() {
        ToolProfile.Selection selection = ToolProfile.unrestricted().select(MANIFEST, registries());

        assertEquals(MANIFEST, selection.kept());
        assertTrue(selection.byTier().isEmpty());
        assertTrue(selection.byAllow().isEmpty());
        assertTrue(selection.byDeny().isEmpty());
        assertFalse(selection.droppedAnything());
    }

    @Test
    @DisplayName("messaging 档：脚本不在清单里，查表与读消息不受影响")
    void messagingDropsCodingToolsOnly() {
        ToolProfile.Selection selection =
                new ToolProfile(ToolTier.MESSAGING, Set.of(), Set.of()).select(MANIFEST, registries());

        // ADR-0020 的原话：把 run_script 拿掉，读消息与查表都不该受影响。
        assertEquals(List.of("toolSearchTool", "systemStatus", "get_goal", "recall_session",
                "querySales", "sendMail", "delegate_task"), selection.kept());
        // 挡掉的原因要分得清：这四个是档位不够，不是被 deny 排除的。
        assertEquals(List.of("run_script", "propose_script", "echo", "serverTime"), selection.byTier());
    }

    @Test
    @DisplayName("minimal 档只留跟自己有关的：元工具还在，否则连检索都做不到")
    void minimalKeepsMetaTool() {
        ToolProfile.Selection selection =
                new ToolProfile(ToolTier.MINIMAL, Set.of(), Set.of()).select(MANIFEST, registries());

        assertEquals(List.of("toolSearchTool", "systemStatus", "get_goal", "recall_session"),
                selection.kept());
    }

    @Test
    @DisplayName("工具没声明档位：启动失败并点名是哪一个")
    void undeclaredTierFailsAndNamesTheTool() {
        ToolProfile profile = new ToolProfile(ToolTier.MESSAGING, Set.of(), Set.of());

        String message = failureMessage(() -> profile.select(List.of("querySales", "mystery"), registries()));

        assertTrue(message.contains("mystery"), message);
        assertTrue(message.contains("MESSAGING"), message);
        // 报错要说清该去哪补：这是配置错误，不是运行时状况。
        assertTrue(message.contains("ToolTierRegistry"), message);
    }

    @Test
    @DisplayName("allow 只能收窄，不能放宽档位")
    void allowOnlyNarrows() {
        ToolProfile profile = new ToolProfile(ToolTier.MESSAGING,
                Set.of("run_script", "querySales"), Set.of());

        ToolProfile.Selection selection = profile.select(MANIFEST, registries());

        // run_script 写在 allow 里也越不过 messaging 档——否则 allow 成了绕过档位的口子。
        assertEquals(List.of("querySales"), selection.kept());
        assertTrue(selection.byTier().contains("run_script"));
        assertTrue(selection.byAllow().containsAll(List.of("toolSearchTool", "sendMail")));
    }

    @Test
    @DisplayName("deny 在档位与白名单之后生效；两者同时命中同一个工具就是配置错误")
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
        assertTrue(message.contains("档位挡掉 1 个"), message);
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
    @DisplayName("档位是累进的：高档包含低档，低档不含高档")
    void tiersAreCumulative() {
        assertTrue(ToolTier.FULL.allows(ToolTier.CODING));
        assertTrue(ToolTier.CODING.allows(ToolTier.MESSAGING));
        assertTrue(ToolTier.MESSAGING.allows(ToolTier.MINIMAL));
        assertFalse(ToolTier.MESSAGING.allows(ToolTier.CODING));
        assertFalse(ToolTier.MINIMAL.allows(ToolTier.MESSAGING));
        // null 不是"最宽"：它会让一个没填档位的工具在 full 档里静默出现。
        assertFalse(ToolTier.FULL.allows(null));
    }

    @Test
    @DisplayName("sameFor 只认它自己那组名字：写成\"任何名字都算\"会让未声明校验永远通过")
    void sameForDoesNotActAsCatchAll() {
        ToolTierRegistry registry = ToolTierRegistry.sameFor(List.of("echo"), ToolTier.FULL);

        assertEquals(Optional.of(ToolTier.FULL), registry.tierFor("echo"));
        assertEquals(Optional.empty(), registry.tierFor("someone_else"));
    }

    @Test
    @DisplayName("重复的工具名不在这里合并：重名是配置错误，该在清单断言上红")
    void duplicatesAreKept() {
        ToolProfile.Selection selection =
                ToolProfile.unrestricted().select(List.of("echo", "echo"), registries());

        assertEquals(List.of("echo", "echo"), selection.kept());
    }

    @Test
    @DisplayName("两条轴各管各的：档位放行的工具，数据轴照样可以拒")
    void profileDoesNotReplacePermission() {
        // messaging 档把 querySales 留在清单里——ADR-0020 要求"查表不受影响"。
        assertTrue(new ToolProfile(ToolTier.MESSAGING, Set.of(), Set.of())
                .select(MANIFEST, registries()).kept().contains("querySales"));

        // 但一个没有任何表权限的身份，仍然过不了 ToolPermission 那一轴：
        // 档位说的是"这个部署有没有这个工具"，不是"这个人能不能用"。
        Principal guest = Principal.of("u-guest", "guest", Set.of(), Set.of());
        assertFalse(ToolPermission.read("sales_order").grantedTo(guest));

        // 反过来也一样：full 档（工具都在）不会让 guest 拿到发邮件的动作。
        assertFalse(ToolPermission.of("send_email").grantedTo(guest));
    }
}

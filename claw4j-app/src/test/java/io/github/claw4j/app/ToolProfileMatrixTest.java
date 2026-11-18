package io.github.claw4j.app;

import io.github.claw4j.capability.tool.ToolCatalog;
import io.github.claw4j.domain.principal.ToolProfile;
import io.github.claw4j.domain.principal.ToolTier;
import io.github.claw4j.domain.principal.ToolTierRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 四档画像的成员表：拿<b>真实装配出来的清单</b>逐档筛一遍（ADR-0030 裁决一）。
 *
 * <p>为什么不放在 {@code ApplicationAssemblyTest}：那份文件验的是装配结构，用例数已经贴着头；
 * 这里问的是另一件事——"这个部署换个画像之后，模型手上还剩哪些工具"。两件事的失败原因不同，
 * 混在一份文件里以后不知道该看哪一段。
 *
 * <p>断言用"必须含 / 必须不含"而不是整份清单相等：清单里有哪些工具取决于装了什么模块
 * （脚本包要目录、写工具要开关、MCP 要配置），钉死整份清单会让这条用例因为一次无关的装配
 * 变化而红。真正要钉的是<b>边界</b>：
 * <ul>
 *   <li>{@code messaging} 能查数、碰不到机器；</li>
 *   <li>{@code coding} 碰得到机器、能自主行动；</li>
 *   <li>{@code minimal} 只剩地基，连业务工具都没有；</li>
 *   <li>任何一档都留着检索工具（否则模型面对空清单）。</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ToolProfileMatrixTest {

    /**
     * 起上下文要的那份敏感词表：路径按<b>仓库根</b>解析，不按测试进程的工作目录——后者随
     * 构建方式变，而它只是一个"必须存在"的启动前提。
     */
    @DynamicPropertySource
    static void guardWords(DynamicPropertyRegistry registry) {
        registry.add("claw4j.guard.sensitive-words-file",
                () -> ExamplesSubagentsTest.repositoryFile("examples/guard/sensitive-words.txt").toString());
    }

    @Autowired
    private ToolCatalog catalog;

    @Autowired
    private List<ToolTierRegistry> registries;

    /** 清单要按 List 交给 select：重名不合并（重名是配置错误，该在清单断言上红）。 */
    private List<String> manifest() {
        return List.copyOf(catalog.names());
    }

    /** 一个画像下模型看得见的工具名。 */
    private Set<String> visibleIn(ToolTier profile) {
        return new TreeSet<>(new ToolProfile(profile, Set.of(), Set.of())
                .select(manifest(), registries).kept());
    }

    @Test
    @DisplayName("每一档都留着地基工具：把它们挡住，模型连自己刚说过什么都不知道")
    void everyProfileKeepsFoundationTools() {
        // 检索工具不在这份清单里——它不是容器里的 ToolCallback，是工具循环自己注入的那一个，
        // 由 ToolTierRegistry.allProfilesFor 保证每档都在（那条在 core 的 ToolProfileTest 里验）。
        for (ToolTier profile : ToolTier.values()) {
            assertThat(visibleIn(profile))
                    .as("%s 画像里缺了地基工具", profile)
                    .contains("systemStatus", "recall_session", "get_goal");
        }
    }

    @Test
    @DisplayName("messaging：查得到数，碰不到机器，也不能自己安排事情")
    void messagingReachesDataButNotTheMachine() {
        Set<String> messaging = visibleIn(ToolTier.MESSAGING);

        assertThat(messaging)
                .as("业务工具与地基必须在：messaging 的承诺是「能问能查」")
                .contains("querySales", "systemStatus");
        assertThat(messaging)
                .as("碰机器的（shell、文件、出网）与自主行动的（定时任务、子 Agent、技能）都不能在")
                .doesNotContain("Bash", "BashOutput", "KillShell", "Read",
                        "schedule_task", "list_schedules", "cancel_schedule");
    }

    @Test
    @DisplayName("coding：机器、定时任务、子 Agent、技能都在——它们只属于这一档")
    void codingHasMachineAndAutonomy() {
        Set<String> coding = visibleIn(ToolTier.CODING);
        Set<String> messaging = visibleIn(ToolTier.MESSAGING);

        // Task 与 Skill 默认不装配（要配子 Agent 与技能目录），所以拿它们断言会红在装配上
        // 而不是画像上；这里用「默认清单里确实有」的那批来钉边界。
        assertThat(coding)
                .as("按它的口径，cron 只进 coding；shell 也只在 coding")
                .contains("Bash", "schedule_task", "querySales");
        assertThat(messaging)
                .as("同一批工具在 messaging 里一个都不该有——这是「互不包含」的实质")
                .doesNotContain("schedule_task", "Bash");
    }

    @Test
    @DisplayName("minimal：只剩地基，连业务工具都看不见（外部工具桶不进这一档）")
    void minimalHidesExternalTools() {
        Set<String> minimal = visibleIn(ToolTier.MINIMAL);

        assertThat(minimal)
                .as("地基：读自己状态、回忆本会话、目标——一个都不该少")
                .contains("systemStatus", "recall_session", "get_goal");
        assertThat(minimal)
                .as("外部工具（业务模块、MCP）不进 minimal——与它 「minimal still hides them」 一致")
                .doesNotContain("querySales");
        assertThat(minimal)
                .as("机器类当然也不在")
                .doesNotContain("Bash", "Read");
    }

    @Test
    @DisplayName("full 是「不筛」，不是「最全的画像」：它是其余三档的并集超集")
    void fullIsEverything() {
        Set<String> full = visibleIn(ToolTier.FULL);

        assertThat(full)
                .as("full 下清单不该被任何画像挡掉")
                .containsAll(manifest());
        assertThat(full)
                .as("而且要比 coding 多——至少不能少")
                .containsAll(visibleIn(ToolTier.CODING));
    }

    @Test
    @DisplayName("deny: [group:plugins] 在真实清单上把那批外部工具一次关掉")
    void denyPluginsRemovesEveryExternalTool() {
        ToolProfile profile = new ToolProfile(ToolTier.FULL, Set.of(), Set.of("group:plugins"), Set.of());
        ToolProfile.Selection selection = profile.select(manifest(), registries);

        assertThat(selection.kept())
                .as("业务工具是被登记成 external 的，所以这个组能点到它")
                .doesNotContain("querySales");
        assertThat(selection.kept())
                .as("内置工具不受影响：这个组只含外部来源")
                .contains("Bash", "systemStatus");
        assertThat(selection.unused())
                .as("组展开出来的名字都在清单里，没有漂移")
                .isEmpty();
    }
}

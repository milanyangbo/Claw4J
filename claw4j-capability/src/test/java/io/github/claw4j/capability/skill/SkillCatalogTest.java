package io.github.claw4j.capability.skill;

import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 一个完整功能：技能包加载。
 *
 * <p>两条边界：目录配错时整层起不来（而不是模型手上悄悄没有 Skill），
 * 以及渐进式披露——工具描述里只有名字与描述，正文只在被选中之后才回一次。
 * 后者做错不会报错，只是每一轮都多付二十份正文的上下文。
 */
class SkillCatalogTest {

    private static final String BODY_MARKER = "正文里的操作步骤，只有点到这个技能时才该出现";

    @TempDir
    Path temp;

    @Test
    @DisplayName("目录里的每个 SKILL.md 都被读到，名字来自 front matter")
    void loadsEverySkillUnderTheDirectory() throws IOException {
        Path skills = Files.createDirectories(temp.resolve("skills"));
        writeSkill(skills.resolve("weekly-sales"), "weekly-sales", "出上周各区成交额周报");
        writeSkill(skills.resolve("issue-triage"), "issue-triage", "按优先级整理 issue");

        assertThat(SkillCatalog.load(skills)).hasSize(2);
        assertThat(AgentSkills.names(skills)).containsExactlyInAnyOrder("weekly-sales", "issue-triage");
    }

    @Test
    @DisplayName("目录不存在或一个 SKILL.md 都没有时整层起不来：空着与没配不是一回事")
    void failsWhenDirectoryIsMissingOrEmpty() throws IOException {
        Path empty = Files.createDirectories(temp.resolve("empty"));

        assertThatThrownBy(() -> SkillCatalog.load(temp.resolve("missing")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不存在");
        assertThatThrownBy(() -> SkillCatalog.load(empty))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("一个 SKILL.md 都没有");
    }

    @Test
    @DisplayName("定义写坏时整层起不来，不会悄悄少加载一个技能")
    void failsTheWholeLayerWhenADefinitionIsBroken() throws IOException {
        Path skills = Files.createDirectories(temp.resolve("skills"));
        writeSkill(skills.resolve("good"), "good", "正常的技能");
        Path broken = Files.createDirectories(skills.resolve("broken"));
        Files.writeString(broken.resolve("SKILL.md"), "---\ndescription: 忘了写名字\n---\n\n正文\n");

        assertThatThrownBy(() -> AgentSkills.callback(skills))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("工具描述只带名字与描述，不把正文贴进去")
    void descriptionCarriesMetadataButNotTheBody() throws IOException {
        Path skills = Files.createDirectories(temp.resolve("skills"));
        writeSkill(skills.resolve("weekly-sales"), "weekly-sales", "出上周各区成交额周报");

        ToolCallback callback = AgentSkills.callback(skills);

        assertThat(callback.getToolDefinition().name()).isEqualTo(AgentSkills.TOOL_NAME);
        assertThat(callback.getToolDefinition().description())
                .contains("weekly-sales")
                .contains("出上周各区成交额周报")
                .doesNotContain(BODY_MARKER);
    }

    @Test
    @DisplayName("正文只在被点到名字之后回一次，并带上技能自己的目录")
    void returnsBodyOnlyWhenTheModelAsksForThatSkill() throws IOException {
        Path skills = Files.createDirectories(temp.resolve("skills"));
        writeSkill(skills.resolve("weekly-sales"), "weekly-sales", "出上周各区成交额周报");

        String output = AgentSkills.callback(skills).call("{\"command\":\"weekly-sales\"}");

        assertThat(output).contains(BODY_MARKER).contains("Base directory for this skill:");
        assertThat(output).contains(skills.resolve("weekly-sales").toString());
    }

    @Test
    @DisplayName("技能登记的是 use_skill，不给表：只读身份不能顺手拿到“加载做法”这个动作")
    void registersSkillActionWithoutTables() {
        SkillToolAutoConfiguration config = new SkillToolAutoConfiguration();
        ToolCallback callback = config.skillToolCallback(preparedSkillsDirectory().toString());
        ToolPermissionRegistry registry = config.skillPermissionRegistry();

        ToolPermission permission = registry.permissionFor(callback.getToolDefinition().name()).orElseThrow();
        Principal readOnly = Principal.readOnly("reader");
        Principal skillUser = Principal.of("writer", "developer", Set.of("*"), Set.of(AgentSkills.ACTION));

        assertThat(permission.action()).isEqualTo(AgentSkills.ACTION);
        assertThat(permission.tables()).isEmpty();
        assertThat(readOnly.canUse(permission)).isFalse();
        assertThat(skillUser.canUse(permission)).isTrue();
    }

    private Path preparedSkillsDirectory() {
        try {
            Path skills = Files.createDirectories(temp.resolve("registered"));
            writeSkill(skills.resolve("weekly-sales"), "weekly-sales", "出上周各区成交额周报");
            return skills;
        }
        catch (IOException failure) {
            throw new IllegalStateException("准备技能目录失败", failure);
        }
    }

    private static void writeSkill(Path directory, String name, String description) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("SKILL.md"), """
                ---
                name: %s
                description: %s
                ---

                # 步骤

                %s
                """.formatted(name, description, BODY_MARKER));
    }
}

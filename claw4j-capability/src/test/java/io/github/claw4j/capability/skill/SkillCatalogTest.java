package io.github.claw4j.capability.skill;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 技能包加载：目录扫描、坏定义让整层起不来、渐进式披露、工具清单登记。
 *
 * <p>渐进式披露要盯两头：描述里只能有名字与一句话（否则每个带工具的轮次都在付正文的
 * token），正文只在按名字调用之后回来一次。写坏的定义则必须让这一层起不来——
 * 静默少一个技能的表现是"模型怎么不按流程来"，配置那行看起来完全正常。
 */
class SkillCatalogTest {

    @TempDir
    Path skills;

    @BeforeEach
    void writeTwoSkills() throws IOException {
        skill("refunds", "refunds", "处理退款：先查订单，再核对金额。",
                "## 步骤\n\nSECRET-BODY-MARKER-REFUND\n");
        skill("shipping", "shipping", "查询物流状态。",
                "## 步骤\n\nSECRET-BODY-MARKER-SHIPPING\n");
    }

    @Test
    @DisplayName("目录树里每个 SKILL.md 都被扫到，名字来自 front matter")
    void everySkillUnderTheDirectoryIsLoaded() {
        assertThat(AgentSkills.names(skills))
                .containsExactlyInAnyOrder("refunds", "shipping");
    }

    @Test
    @DisplayName("配了目录却不存在：启动失败，不是「这一层不存在」")
    void missingDirectoryFailsInsteadOfBecomingAnAbsentLayer() {
        Path missing = skills.resolve("no-such-directory");

        assertThatThrownBy(() -> SkillCatalog.load(missing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不存在或不是目录");
    }

    @Test
    @DisplayName("目录存在但一个 SKILL.md 都没有：也算配置错误")
    void directoryWithoutAnySkillFails() throws IOException {
        Path empty = Files.createDirectories(skills.resolve("empty"));

        assertThatThrownBy(() -> SkillCatalog.load(empty))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("一个 SKILL.md 都没有");
    }

    @Test
    @DisplayName("缺 name 的定义让整层起不来，而不是被静默跳过")
    void brokenDefinitionFailsTheWholeLayer() throws IOException {
        Path broken = Files.createDirectories(skills.resolve("broken"));
        Files.writeString(broken.resolve("SKILL.md"),
                """
                ---
                description: 只有描述没有名字
                ---

                正文
                """, UTF_8);

        assertThatThrownBy(() -> AgentSkills.callback(skills))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("工具描述里只有元数据：正文一个字都不进去")
    void descriptionCarriesMetadataButNotTheBody() {
        ToolCallback callback = AgentSkills.callback(skills);

        assertThat(callback.getToolDefinition().description())
                .contains("refunds")
                .contains("处理退款：先查订单，再核对金额。")
                .doesNotContain("SECRET-BODY-MARKER");
    }

    @Test
    @DisplayName("按名字调用之后正文才回来一次")
    void invokingASkillByNameReturnsItsBody() {
        ToolCallback callback = AgentSkills.callback(skills);

        String expanded = callback.call("{\"command\":\"refunds\"}");

        assertThat(expanded)
                .contains("SECRET-BODY-MARKER-REFUND")
                .doesNotContain("SECRET-BODY-MARKER-SHIPPING");
    }

    @Test
    @DisplayName("工具名与动作是这一层自己声明的那两个，业务表一张都不挂")
    void toolNameAndPermissionAreDeclaredByThisLayer() {
        ToolCallback callback = AgentSkills.callback(skills);

        assertThat(callback.getToolDefinition().name()).isEqualTo(AgentSkills.TOOL_NAME).isEqualTo("Skill");
        assertThat(AgentSkills.permission().action()).isEqualTo("use_skill");
        assertThat(AgentSkills.permission().tables()).isEmpty();
    }

    private Path skill(String directory, String name, String description, String body) throws IOException {
        Path root = Files.createDirectories(skills.resolve(directory));
        Files.writeString(root.resolve("SKILL.md"),
                "---\nname: " + name + "\ndescription: " + description + "\n---\n\n" + body,
                UTF_8);
        return root;
    }
}

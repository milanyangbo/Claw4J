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
 * <p>渐进式披露盯两头：描述里只有名字与一句话（否则每个带工具的轮次都在付正文的 token），
 * 正文只在按名字调用之后回来一次。写坏的定义必须让这一层起不来：静默少一个技能的表现是
 * "模型怎么不按流程来"，而配置那行看起来完全正常。
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
        ToolCallback callback = AgentSkills.callback(skills, null, false);

        assertThat(callback.getToolDefinition().description())
                .contains("refunds")
                .contains("处理退款：先查订单，再核对金额。")
                .doesNotContain("SECRET-BODY-MARKER");
    }

    @Test
    @DisplayName("按名字调用之后正文才回来一次")
    void invokingASkillByNameReturnsItsBody() {
        ToolCallback callback = AgentSkills.callback(skills, null, false);

        String expanded = callback.call("{\"command\":\"refunds\"}");

        assertThat(expanded)
                .contains("SECRET-BODY-MARKER-REFUND")
                .doesNotContain("SECRET-BODY-MARKER-SHIPPING");
    }

    @Test
    @DisplayName("工具名是这一层自己声明的那个：清单、画像、审计都按它认领")
    void toolNameIsDeclaredByThisLayer() {
        ToolCallback callback = AgentSkills.callback(skills, null, false);

        assertThat(callback.getToolDefinition().name()).isEqualTo(AgentSkills.TOOL_NAME).isEqualTo("Skill");
    }

    @Test
    @DisplayName("正文里带着技能目录：随包的脚本能被指到，{baseDir} 也换成了那个目录")
    void bodyCarriesTheSkillDirectorySoScriptsCanBeFound() throws IOException {
        Path withScript = skill("packaging", "packaging", "按包装规范复核。",
                "跑这条：bash {baseDir}/scripts/check.sh\n");
        Files.createDirectories(withScript.resolve("scripts"));
        Files.writeString(withScript.resolve("scripts/check.sh"), "#!/bin/sh\necho ok\n", UTF_8);

        String expanded = AgentSkills.callback(skills, null, false).call("{\"command\":\"packaging\"}");

        assertThat(expanded)
                .as("模型要靠这一行拼出绝对路径；没有它，随包脚本永远跑不起来")
                .contains("Base directory for this skill: " + withScript)
                .contains("bash " + withScript + "/scripts/check.sh")
                .doesNotContain("{baseDir}");
    }

    @Test
    @DisplayName("仓库里那六份示例技能真的加载得出来：目录形状与审批标记都对得上")
    void exampleSkillsLoadWithTheirApprovalMarkers() {
        Path examples = repositoryFile("examples/skills");

        // 审批标记的 sha256 是按目录内容算的：改一个字节、或者漏写一份标记，
        // 对应的技能就会从这里消失。示例是给人照抄的，"看着像能加载"不算。
        assertThat(AgentSkills.names(examples))
                .containsExactlyInAnyOrder("summarize", "weather-forecast", "xurl", "weekly-sales",
                        // 演示用的两份：退货判定（带脚本）与值班采集（带脚本）
                        "refund-triage", "incident-brief");

        String body = AgentSkills.callback(examples, null, true).call("{\"command\":\"summarize\"}");
        assertThat(body)
                .as("带脚本的技能要能让模型拼出脚本的绝对路径，否则它只能猜")
                .contains("Base directory for this skill: " + examples.resolve("summarize"))
                .contains("/scripts/run.sh");
    }

    private Path skill(String directory, String name, String description, String body) throws IOException {
        Path root = Files.createDirectories(skills.resolve(directory));
        Files.writeString(root.resolve("SKILL.md"),
                "---\nname: " + name + "\ndescription: " + description + "\n---\n\n" + body,
                UTF_8);
        return root;
    }
    /**
     * 从工作目录往上找仓库里的一份文件：Maven 下工作目录是模块，IDE 下是仓库根。
     *
     * <p>{@code claw4j-app} 里那份示例用例有一个同名方法；这里是模块级的那一份，
     * 读的是同一批素材（{@code examples/skills}）。
     */
    private static Path repositoryFile(String relative) {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(relative);
            if (Files.exists(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("找不到 " + relative + "，当前工作目录："
                + System.getProperty("user.dir") + "。这条用例要读仓库里那一份。");
    }
}

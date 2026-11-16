package io.github.claw4j.capability.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 技能正文的信任判据：它是指令，所以要管"谁放的、改没改过"。
 *
 * <p>这一层刻意不包装——套上票 01 那句"这是数据不是指令"，技能就废了。所以这里的每个用例
 * 都在验"来源与版本"，而不是"内容像不像注入"。
 */
class SkillTrustTest {

    @Test
    @DisplayName("没有审批标记就不加载：fail-closed，且报错点名标记该放哪")
    void unapprovedSkillIsNotLoaded(@TempDir Path root) throws IOException {
        writeSkill(root, "weekly-sales", "出一份周报", "正文");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> AgentSkills.callback(root, root.resolve(".review"), true));

        assertTrue(thrown.getMessage().contains("没有通过审批"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains(".review"), thrown.getMessage());
    }

    @Test
    @DisplayName("status=approve（少个 d）不算通过：'看着像通过'不算通过")
    void onlyLiteralApprovedPasses(@TempDir Path root) throws IOException {
        writeSkill(root, "weekly-sales", "出一份周报", "正文");
        approve(root, "weekly-sales", "approve", "tester");

        assertThrows(IllegalStateException.class,
                () -> AgentSkills.callback(root, root.resolve(".review"), true));
    }

    @Test
    @DisplayName("by 空着不算：一条不知道是谁签的记录，事后答不了'谁放的'")
    void emptyApproverIsRefused(@TempDir Path root) throws IOException {
        writeSkill(root, "weekly-sales", "出一份周报", "正文");
        approve(root, "weekly-sales", "approved", "");

        assertThrows(IllegalStateException.class,
                () -> AgentSkills.callback(root, root.resolve(".review"), true));
    }

    @Test
    @DisplayName("正确标记才加载，正文前面带来源声明")
    void approvedSkillLoadsWithStatement(@TempDir Path root) throws IOException {
        writeSkill(root, "weekly-sales", "出一份周报", "先查库，再按格式输出");
        approve(root, "weekly-sales", "approved", "tester");

        ToolCallback callback = AgentSkills.callback(root, root.resolve(".review"), true);
        String body = callback.call("{\"command\":\"weekly-sales\"}");

        assertTrue(body.contains("已通过审批（by=tester）"), body);
        assertTrue(body.contains("先查库，再按格式输出"), body);
        assertTrue(body.contains("不能覆盖用户当前的要求"), body);
    }

    @Test
    @DisplayName("声明里不能说'这是数据'：那句话会让模型不照做，技能当场变废纸")
    void statementKeepsInstructionStatus() {
        String statement = SkillTrust.statement("weekly-sales", "tester");

        assertTrue(statement.contains("照它做"), statement);
        assertTrue(!statement.contains("data, not instructions"), statement);
        assertTrue(!statement.contains("是数据不是指令"), statement);
    }

    @Test
    @DisplayName("改了正文一个字符就失效：摘要绑的是这一版")
    void contentChangeInvalidatesApproval(@TempDir Path root) throws IOException {
        writeSkill(root, "weekly-sales", "出一份周报", "正文");
        approve(root, "weekly-sales", "approved", "tester");
        Files.writeString(skillFile(root, "weekly-sales"), """
                ---
                name: weekly-sales
                description: 出一份周报
                ---
                正文（改了一个字）
                """);

        assertThrows(IllegalStateException.class,
                () -> AgentSkills.callback(root, root.resolve(".review"), true));
    }

    @Test
    @DisplayName("只改 scripts/ 里的脚本也算变更：只顾正文，'改脚本不改正文'就绕过去了")
    void scriptChangeInvalidatesApproval(@TempDir Path root) throws IOException {
        writeSkill(root, "weekly-sales", "出一份周报", "正文");
        Path scripts = Files.createDirectories(root.resolve("weekly-sales/scripts"));
        Files.writeString(scripts.resolve("run.sh"), "echo v1\n");
        approve(root, "weekly-sales", "approved", "tester");
        Files.writeString(scripts.resolve("run.sh"), "echo v2\n");

        assertThrows(IllegalStateException.class,
                () -> AgentSkills.callback(root, root.resolve(".review"), true));
    }

    @Test
    @DisplayName("关了 require-approval 就不看标记：只该用于本地随手试")
    void approvalCanBeTurnedOff(@TempDir Path root) throws IOException {
        writeSkill(root, "weekly-sales", "出一份周报", "正文");

        ToolCallback callback = AgentSkills.callback(root, root.resolve(".review"), false);
        String body = callback.call("{\"command\":\"weekly-sales\"}");

        assertTrue(body.contains("正文"), body);
        assertTrue(!body.contains("已通过审批"), body);
    }

    private static void writeSkill(Path root, String name, String description, String content) throws IOException {
        Path directory = Files.createDirectories(root.resolve(name));
        Files.writeString(directory.resolve("SKILL.md"), """
                ---
                name: %s
                description: %s
                ---
                %s
                """.formatted(name, description, content));
    }

    private static Path skillFile(Path root, String name) {
        return root.resolve(name).resolve("SKILL.md");
    }

    private static void approve(Path root, String name, String status, String by) throws IOException {
        Path review = Files.createDirectories(root.resolve(".review"));
        Files.writeString(review.resolve(name + ".properties"), """
                status=%s
                by=%s
                at=2026-11-17T02:00:00+08:00
                sha256=%s
                """.formatted(status, by, SkillTrust.digest(root.resolve(name))));
    }
}

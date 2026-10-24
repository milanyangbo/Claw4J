package io.github.claw4j.agent.subagent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 目录这一层：配了就得读得出来，读不出来就是配置错误。 */
class SubagentCatalogTest {

    @Test
    @DisplayName("目录里每个 md 是一个专职 Subagent")
    void 读出目录里的每一份(@TempDir Path dir) throws IOException {
        agent(dir, "sales-analyst.md", "sales-analyst", "查销售额。");
        agent(dir, "expense-auditor.md", "expense-auditor", "审报销单。");

        List<SubagentDefinition> definitions = SubagentCatalog.load(dir);

        assertThat(definitions).hasSize(2);
        // 排序后返回：清单里谁先谁后不该随文件系统变，否则"这次派错了"不可复现
        assertThat(definitions)
                .extracting(SubagentDefinition::name)
                .containsExactly("expense-auditor", "sales-analyst");
    }

    @Test
    @DisplayName("配了目录却不存在：启动失败，不是“这一层没启用”")
    void 目录不存在即失败(@TempDir Path dir) {
        Path missing = dir.resolve("not-there");

        assertThatThrownBy(() -> SubagentCatalog.load(missing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不存在或不是目录");
    }

    @Test
    @DisplayName("空目录与“没配”不是一回事：同样是失败")
    void 空目录即失败(@TempDir Path dir) {
        assertThatThrownBy(() -> SubagentCatalog.load(dir))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("一个 *.md 都没有");
    }

    @Test
    @DisplayName("某一份写坏了，整层起不来：不静默少一个")
    void 一份写坏即失败(@TempDir Path dir) throws IOException {
        agent(dir, "broken.md", "broken", "");
        agent(dir, "fine.md", "fine", "好的。");

        assertThatThrownBy(() -> SubagentCatalog.load(dir))
                .isInstanceOf(IllegalStateException.class);
    }

    private static void agent(Path dir, String fileName, String name, String description) throws IOException {
        Files.writeString(dir.resolve(fileName), """
                ---
                name: %s
                description: %s
                ---
                1. 先查数。
                2. 只给结论。
                """.formatted(name, description));
    }
}

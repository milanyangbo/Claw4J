package io.github.claw4j.capability.skill;

import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.principal.ToolPermission;
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
 * 渐进式披露：启动时只有元数据进提示词，正文要等命中了才进来。
 *
 * <p>判据是<b>两个方向都要有断言</b>：描述里<b>有</b>每个 Skill 的名字与描述
 * （没有它模型无从判断"有没有用得上的做法"），描述里<b>没有</b>任何一份正文
 * （有了它就等于二十份正文常驻上下文，而"命中才加载"从外部完全看不出来：
 * 模型照样答得对，只是上下文贵了一截）。
 *
 * <p>不连模型：这里量的是"拼给模型的字符串里有什么"，那是这个类的职责。
 * "模型会不会真的去用它"归 agent 侧端到端。
 */
class AgentSkillsTest {

    private static final String HIT = "sales-brief";

    private static final String MISSED = "expense-audit";

    @Test
    @DisplayName("工具名来自社区包；描述里有每个 Skill 的名字与描述")
    void 描述里有元数据(@TempDir Path root) throws IOException {
        SkillFixtures.write(root);

        ToolCallback callback = AgentSkills.callback(root);

        assertThat(callback.getToolDefinition().name()).isEqualTo(AgentSkills.TOOL_NAME);
        assertThat(callback.getToolDefinition().description())
                .contains("<name>" + HIT + "</name>")
                .contains("<name>" + MISSED + "</name>")
                .contains("生成区域销售简报")
                .contains("审计费用报销单据");
    }

    /** 这一条是"启动时全文加载"那次变异的红线。 */
    @Test
    @DisplayName("描述里没有任何一份 Skill 的正文")
    void 描述里没有正文(@TempDir Path root) throws IOException {
        SkillFixtures.Fixture fixture = SkillFixtures.write(root);

        assertThat(AgentSkills.callback(root).getToolDefinition().description())
                .as("正文只在命中之后才进来；它一旦出现在描述里，"
                        + "“有二十个 Skill 的代价是二十行描述”这件事就不成立了")
                .doesNotContain(fixture.hitMarker())
                .doesNotContain(fixture.missedMarker())
                .doesNotContain("先调用 querySales");
    }

    @Test
    @DisplayName("用名字调用才取回正文，取回的只有那一份，且宣给它的是自己的目录")
    void 命中才加载正文(@TempDir Path root) throws IOException {
        SkillFixtures.Fixture fixture = SkillFixtures.write(root);

        String loaded = AgentSkills.callback(root).call("{\"command\":\"" + HIT + "\"}");

        assertThat(loaded)
                .contains(fixture.hitMarker())
                .contains("先调用 querySales")
                .as("取一份就给一份：把别的 Skill 的正文一起带出来，等于模型没选中也能看到它")
                .doesNotContain(fixture.missedMarker());
        assertThat(loaded)
                .contains("Base directory for this skill:")
                .contains(root.resolve(HIT).toAbsolutePath().normalize().toString());
    }

    @Test
    @DisplayName("取一个不存在的名字是明确的「没找到」，而不是抛异常中断这一轮")
    void 没找到就明说(@TempDir Path root) throws IOException {
        SkillFixtures.write(root);

        assertThat(AgentSkills.callback(root).call("{\"command\":\"no-such-skill\"}"))
                .contains("Skill not found");
    }

    @Test
    @DisplayName("配了目录却不存在、或一个 SKILL.md 都没有，都在启动时失败")
    void 目录不合格即失败(@TempDir Path root) throws IOException {
        assertThatThrownBy(() -> AgentSkills.callback(root.resolve("not-there")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not-there");

        // 空目录与"没配"是两件事：配了却空着，说明技能没部署到这个位置
        Files.createDirectories(root.resolve("empty"));
        assertThatThrownBy(() -> AgentSkills.callback(root))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SKILL.md");
    }

    /**
     * 动作是"加载一份流程指令"，不是"读一张表"：只有读动作的身份
     * 不该自动获得"把一段指令放进上下文"的能力。
     *
     * <p>而零张业务表是一条声明，不是"没有约束"：表级受限的身份在"选中做法"这一步
     * 不该被挡住——它在 Skill 让它去碰别的表那一步才被挡。
     */
    @Test
    @DisplayName("登记的动作是加载 Skill；没有业务表，但动作仍然要过")
    void 权限边界() {
        ToolPermission permission = AgentSkills.permission();
        Principal onlyRead = Principal.of("u-1", "read-only", Set.of(Principal.WILDCARD), Set.of(ToolPermission.READ));

        assertThat(permission.action()).isEqualTo(AgentSkills.ACTION).isNotEqualTo(ToolPermission.READ);
        assertThat(onlyRead.canUse(permission)).isFalse();
        assertThat(Principal.of("u-1", "read-only", Set.of(Principal.WILDCARD), Set.of(AgentSkills.ACTION))
                .canUse(permission)).isTrue();

        assertThat(permission.tables()).isEmpty();
        Principal restricted = Principal.of("guest", "guest", Set.of("region"),
                Set.of(ToolPermission.READ, AgentSkills.ACTION));
        assertThat(restricted.canUse(permission))
                .as("Skill 自己不读库：表级受限的身份在“选中做法”这步不该被挡住")
                .isTrue();
    }

    /** 两份 SKILL.md：一份会被问到，一份不会。正文各带一个随机标记。 */
    static final class SkillFixtures {

        static final class Fixture {

            private final String hitMarker;

            private final String missedMarker;

            Fixture(String hitMarker, String missedMarker) {
                this.hitMarker = hitMarker;
                this.missedMarker = missedMarker;
            }

            String hitMarker() {
                return hitMarker;
            }

            String missedMarker() {
                return missedMarker;
            }
        }

        static Fixture write(Path root) throws IOException {
            String hitMarker = "mk-hit-" + Long.toHexString(System.nanoTime());
            String missedMarker = "mk-miss-" + Long.toHexString(System.nanoTime());

            skill(root, HIT, "生成区域销售简报。当用户要某个区域的销售额、业绩简报、经营概况时使用。", """
                    # 销售简报流程

                    1. 先调用 querySales 查这个区域的销售额。
                    2. 把结果写成一行简报，并原样带上这个标记：%s

                    不要解释口径，只给数与标记。
                    """.formatted(hitMarker));

            skill(root, MISSED, "审计费用报销单据。当用户要审报销单、查差旅是否超标时使用。", """
                    # 费用审计流程

                    这一段是费用审计的做法，只有加载了这份 Skill 才看得到：%s

                    1. 导出单据清单。
                    2. 逐条比对标准。
                    """.formatted(missedMarker));

            return new Fixture(hitMarker, missedMarker);
        }

        private static void skill(Path root, String name, String description, String body) throws IOException {
            Path directory = root.resolve(name);
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("SKILL.md"), """
                    ---
                    name: %s
                    description: %s
                    ---
                    %s
                    """.formatted(name, description, body));
        }
    }
}

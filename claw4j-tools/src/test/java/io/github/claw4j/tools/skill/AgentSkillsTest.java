package io.github.claw4j.tools.skill;

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
 * <p>这是这张票唯一的设计点，它的判据是<b>两个方向都要有断言</b>：
 * <ul>
 *   <li>描述里<b>有</b>每个 Skill 的名字与描述——没有它模型无从判断"有没有用得上的做法"。
 *   <li>描述里<b>没有</b>任何一份正文——有了它就等于二十份正文常驻上下文，
 *       而"命中才加载"这件事从外部完全看不出来：模型照样答得对，只是上下文贵了一截。
 * </ul>
 *
 * <p>不连模型：这里量的是"拼给模型的字符串里有什么"，那是这一个类的职责。
 * "模型会不会真的去用它"归端到端那条（它连真模型）。
 */
class AgentSkillsTest {

    private static final String HIT = "sales-brief";

    private static final String MISSED = "expense-audit";

    @Test
    @DisplayName("工具名是社区包那一个，不是自己另起的")
    void 工具名来自社区包(@TempDir Path root) throws IOException {
        SkillFixtures.write(root);

        assertThat(AgentSkills.callback(root).getToolDefinition().name()).isEqualTo(AgentSkills.TOOL_NAME);
    }

    /**
     * 渐进式披露的正面：元数据在描述里。
     * 没有这一条，"描述是空的"也能让下一条（不含正文）变绿。
     */
    @Test
    @DisplayName("描述里有每个 Skill 的名字与描述")
    void 描述里有元数据(@TempDir Path root) throws IOException {
        SkillFixtures.write(root);

        String description = AgentSkills.callback(root).getToolDefinition().description();

        assertThat(description)
                .contains("<name>" + HIT + "</name>")
                .contains("<name>" + MISSED + "</name>")
                .contains("生成区域销售简报")
                .contains("审计费用报销单据");
    }

    /** 渐进式披露的反面：正文不在描述里。这一条是"启动时全文加载"那次变异的红线。 */
    @Test
    @DisplayName("描述里没有任何一份 Skill 的正文")
    void 描述里没有正文(@TempDir Path root) throws IOException {
        SkillFixtures.Fixture fixture = SkillFixtures.write(root);

        String description = AgentSkills.callback(root).getToolDefinition().description();

        assertThat(description)
                .as("正文只在命中之后才进来；它一旦出现在描述里，"
                        + "“有二十个 Skill 的代价是二十行描述”这件事就不成立了")
                .doesNotContain(fixture.hitMarker())
                .doesNotContain(fixture.missedMarker())
                .doesNotContain("先调用 querySales");
    }

    @Test
    @DisplayName("用名字调用才取回正文，取回的只有那一份")
    void 命中才加载正文(@TempDir Path root) throws IOException {
        SkillFixtures.Fixture fixture = SkillFixtures.write(root);
        ToolCallback callback = AgentSkills.callback(root);

        String loaded = callback.call("{\"command\":\"" + HIT + "\"}");

        assertThat(loaded)
                .contains(fixture.hitMarker())
                .contains("先调用 querySales")
                .as("取一份就给一份：把别的 Skill 的正文一起带出来，"
                        + "等于模型没选中也能看到它")
                .doesNotContain(fixture.missedMarker());
    }

    @Test
    @DisplayName("取一个不存在的名字是明确的“没找到”，而不是抛异常中断这一轮")
    void 没找到就明说(@TempDir Path root) throws IOException {
        SkillFixtures.write(root);

        assertThat(AgentSkills.callback(root).call("{\"command\":\"no-such-skill\"}"))
                .contains("Skill not found");
    }

    @Test
    @DisplayName("宣给模型的目录是这个 Skill 自己所在的目录")
    void 宣给模型的目录落在技能目录内(@TempDir Path root) throws IOException {
        SkillFixtures.write(root);

        String loaded = AgentSkills.callback(root).call("{\"command\":\"" + HIT + "\"}");

        assertThat(loaded)
                .contains("Base directory for this skill:")
                .contains(root.resolve(HIT).toAbsolutePath().normalize().toString());
    }

    @Test
    @DisplayName("配了目录却不存在即启动失败，不是这一层悄悄不存在")
    void 目录不存在即失败(@TempDir Path root) {
        Path missing = root.resolve("not-there");

        assertThatThrownBy(() -> AgentSkills.callback(missing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not-there");
    }

    /** 空目录与"没配"是两件事：配了却空着，说明技能没部署到这个位置。 */
    @Test
    @DisplayName("目录里一个 SKILL.md 都没有即启动失败")
    void 空目录即失败(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("empty"));

        assertThatThrownBy(() -> AgentSkills.callback(root))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SKILL.md");
    }

    @Test
    @DisplayName("登记的动作是加载 Skill，不是读表")
    void 动作是加载Skill而不是读() {
        ToolPermission permission = AgentSkills.permission();

        assertThat(permission.action()).isEqualTo(AgentSkills.ACTION).isNotEqualTo(ToolPermission.READ);
        assertThat(principalWithOnly(ToolPermission.READ).canUse(permission))
                .as("只有读动作的身份不该自动获得“把一份流程指令放进上下文”的能力")
                .isFalse();
        assertThat(principalWithOnly(AgentSkills.ACTION).canUse(permission)).isTrue();
    }

    /**
     * 零张业务表是一条声明，不是"没有约束"：Skill 这一层不碰库。
     * 于是表级受限的身份照样能按 Skill 办事，它在 Skill 让它去碰别的表那一步被挡住。
     */
    @Test
    @DisplayName("登记的权限里一张业务表都没有")
    void 没有业务表() {
        assertThat(AgentSkills.permission().tables()).isEmpty();

        Principal restricted = Principal.of("guest", "guest", Set.of("region"),
                Set.of(ToolPermission.READ, AgentSkills.ACTION));

        assertThat(restricted.canUse(AgentSkills.permission()))
                .as("Skill 自己不读库：表级受限的身份在“选中做法”这步不该被挡住")
                .isTrue();
    }

    private static Principal principalWithOnly(String action) {
        return Principal.of("u-1", "read-only", Set.of(Principal.WILDCARD), Set.of(action));
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

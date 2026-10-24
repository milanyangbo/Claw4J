package io.github.claw4j.capability.skill;

import org.springaicommunity.agent.tools.SkillsTool.Skill;
import org.springaicommunity.agent.utils.Skills;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code SKILL.md} 的加载：一个目录树里每个 {@code SKILL.md} 是一份 Skill。
 *
 * <p>加载的是<b>三样东西</b>：名字与描述（front matter）、正文、以及它所在的目录
 * （{@code basePath}）。前两样的分工正是这张票要做的那件事——名字与描述用来被选中，
 * 正文要等选中了才用得上。
 *
 * <p><b>配了目录却读不出来即启动失败</b>（ADR-0008：不等到调用时才暴露配置错误）。
 * 两个方向都要失败：目录不存在，和目录里一个 {@code SKILL.md} 都没有。
 * 后者尤其容易被当成"这一层不存在"而默默略过——那样模型手上没有 Skill 可用，
 * 表现是"它怎么不按流程来"，而配置那行看起来是对的。
 */
public final class SkillCatalog {

    private SkillCatalog() {
    }

    /**
     * 读出目录里所有 Skill。
     *
     * @throws IllegalStateException 目录不存在、不是目录，或里面没有 {@code SKILL.md}
     */
    public static List<Skill> load(Path directory) {
        Path absolute = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(absolute)) {
            throw new IllegalStateException(
                    "配置了 claw4j.skills.directory 但它不存在或不是目录：" + absolute
                            + "。配了就应当有它——这是配置错误，不是“这一层不存在”。");
        }
        List<Skill> skills = Skills.loadDirectory(absolute.toString());
        if (skills.isEmpty()) {
            throw new IllegalStateException(
                    "技能目录里一个 SKILL.md 都没有：" + absolute
                            + "。空目录与“没配”不是一回事：配了却空着，说明技能没被部署到这个位置。");
        }
        return List.copyOf(skills);
    }
}

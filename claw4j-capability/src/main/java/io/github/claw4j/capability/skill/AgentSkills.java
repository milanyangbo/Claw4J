package io.github.claw4j.capability.skill;

import io.github.claw4j.domain.principal.ToolPermission;
import org.springaicommunity.agent.common.workspace.Workspace;
import org.springaicommunity.agent.tools.SkillsTool;
import org.springframework.ai.tool.ToolCallback;

import java.nio.file.Path;
import java.util.List;

/**
 * Agent Skills 这一层的接缝：把 {@code SKILL.md} 目录变成一个工具回调。
 *
 * <p><b>渐进式披露</b>是这一层唯一的设计点，它落在两个地方：
 * <ul>
 *   <li>工具描述里只有<b>元数据</b>——每个 Skill 的名字与描述，由社区包的
 *       {@code Skill.toXml()} 拼成。模型靠这两行决定"有没有用得上的做法"。
 *   <li><b>正文不进描述</b>。它只在模型真的用名字调用了 {@code Skill} 工具之后，
 *       作为那次调用的结果回来一次。
 * </ul>
 * 于是"有二十个 Skill"的代价是二十行描述，不是二十份正文。
 * 这条边界由 {@code SkillCatalogTest} 里"描述不含正文"与"没点到的正文不回来"两条断言各盯一头。
 *
 * <p><b>为什么不再包一层自己的工具类型</b>：社区包的 {@code SkillsTool.Builder.build()}
 * 直接返回 {@code ToolCallback}，它的工具名、输入 Schema、以及"用名字取正文"的行为
 * 都在那一个类里。再包一层只会多出一个要同步维护的描述字符串，
 * 而"描述里到底有什么"正是这里要盯的东西——多一层就多一个可能偷偷把正文写进去的地方。
 *
 * <p><b>Skill 不提供动作，它编排动作</b>（GLOSSARY）：正文是"先用哪个工具、再输出什么"
 * 这种顺序，动作本身仍然由被调到的那个工具提供。所以这一层登记的权限
 * （{@link #permission()}）是"加载一份做法"这个动作，业务表一张都没有——
 * Skill 自己不读库，它让模型去读，而那一步照样过鉴权。
 */
public final class AgentSkills {

    /**
     * 工具名由社区包定死（{@code SkillsTool} 内部硬编码）。
     * 这里留一份引用，别处不写字面量——清单断言与权限登记都要用到它，
     * 写散了之后改名字的代价是"某个工具忽然没人认领"。
     */
    public static final String TOOL_NAME = "Skill";

    /**
     * 加载一份 Skill 所需的动作名。
     *
     * <p>它是 {@code use_skill} 而不是 {@code read}：读一张表与加载一份做法是两件事，
     * 共用同一个动作名等于把"能读表"和"能把一份流程指令放进上下文"绑在一起。
     * 分开之后，"谁能用什么做法"是一行配置能回答的问题。
     */
    public static final String ACTION = "use_skill";

    private AgentSkills() {
    }

    /**
     * 把一个技能目录变成工具回调。
     *
     * <p>先自己加载一遍（{@link SkillCatalog#load}）再交给 builder，代价是这个目录被读两次。
     * 换来的两样东西值得这点开销：目录配错时错误信息里点名是哪个目录，而不是
     * 社区包那句不带上下文的断言；"空目录"与"没配"在这里是两种明确的失败。
     * 启动期读几份 markdown，不是热路径。
     */
    public static ToolCallback callback(Path directory) {
        Path absolute = directory.toAbsolutePath().normalize();
        SkillCatalog.load(absolute);
        try {
            return SkillsTool.builder()
                    .addSkillsDirectory(absolute.toString())
                    // 模型会看到"这个 Skill 的目录在哪"，那里可能有脚本与数据文件。
                    // 本地执行时 display 是恒等映射（宿主路径即脚本能访问的路径）；
                    // 换成容器沙箱时换掉这个实现即可，模型不会被喂一个它用不到的路径。
                    .workspace(Workspace.local(absolute))
                    .build();
        }
        catch (IllegalArgumentException noUsableSkill) {
            throw new IllegalStateException("技能目录里没有可用的 Skill：" + absolute, noUsableSkill);
        }
    }

    /**
     * 加载一份 Skill 需要什么权限：{@link #ACTION} 动作，加上零张业务表。
     *
     * <p>零张表不是"没有约束"，它是一条明确的声明：<b>Skill 这一层不碰库</b>。
     * 于是表级受限的身份（例如只看得到 region 的 guest）照样能按 Skill 办事，
     * 它会在 Skill 让它去碰别的表的那一步被挡住，而不是在"选中做法"这步就被挡住。
     */
    public static ToolPermission permission() {
        return ToolPermission.of(ACTION);
    }

    /** 目录里有哪些 Skill，供日志与断言使用。 */
    public static List<String> names(Path directory) {
        return SkillCatalog.load(directory).stream()
                .map(skill -> skill.frontMatter().get("name").toString())
                .toList();
    }
}

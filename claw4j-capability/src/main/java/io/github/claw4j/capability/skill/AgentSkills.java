package io.github.claw4j.capability.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agent.common.workspace.Workspace;
import org.springaicommunity.agent.tools.SkillsTool;
import org.springaicommunity.agent.tools.SkillsTool.Skill;
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
 * <p>于是"有二十个 Skill"的代价是二十行描述，不是二十份正文。这条边界由
 * {@code SkillCatalogTest} 的"工具描述里只有元数据"与"按名字调用之后正文才回来一次"两条
 * 断言各盯一头（{@code SkillCatalogTest.java:82} 与 {@code :93}）。
 *
 * <p>不再包一层自己的工具类型：社区包的 {@code SkillsTool.Builder.build()} 直接返回
 * {@code ToolCallback}，工具名、输入 Schema、以及"用名字取正文"的行为都在那一个类里。
 * 再包一层只会多出一个要同步维护的描述字符串，而"描述里到底有什么"正是这里要盯的东西。
 *
 * <p>Skill 不提供动作，它编排动作（GLOSSARY）：正文是"先用哪个工具、再输出什么"这种顺序，
 * 动作本身仍由被调到的那个工具提供。所以这一层登记的权限（{@link #permission()}）是"加载
 * 一份做法"，业务表一张都没有——Skill 自己不读库，它让模型去读，而那一步照样过鉴权。
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

    /** 审批标记默认放在技能目录下的这个子目录：位置固定，好记也好忽略。 */
    public static final String DEFAULT_REVIEW_DIRECTORY = ".review";

    private static final Logger log = LoggerFactory.getLogger(AgentSkills.class);

    private AgentSkills() {
    }

    /**
     * 把一个技能目录变成工具回调。
     *
     * <p>先自己加载一遍（{@link SkillCatalog#load}）再交给 builder，代价是这个目录被读两次。
     * 换来的两样东西值得这点开销：目录配错时错误信息里点名是哪个目录，而不是社区包那句
     * 不带上下文的断言；"空目录"与"没配"在这里是两种明确的失败。启动期读几份 markdown，
     * 不是热路径。
     *
     * @param directory 技能目录，目录树下每个 {@code SKILL.md} 是一份 Skill
     * @return 名为 {@link #TOOL_NAME} 的工具回调
     * @throws IllegalStateException 目录不存在，或里面没有可用的 Skill
     */
    public static ToolCallback callback(Path directory) {
        return callback(directory, null, true);
    }

    /**
     * 把一个技能目录变成工具回调，并按审批标记筛。
     *
     * <p><b>校验与加载用同一份内存快照。</b>原来先 {@link SkillCatalog#load} 校验、再让
     * {@code addSkillsDirectory} 读一次盘，两次读之间文件被改就是"校验一份、用另一份"（TOCTOU）。
     * 改成逐份 {@code addSkill(name, description, content)}，校验过的内容就是进上下文的内容。
     *
     * <p>代价要认：{@code addSkill} 只保留 name / description 两个 front matter 字段，
     * {@code allowed-tools} 之类的字段不进清单（它们本来也不生效，见票 04 的缺口）；
     * 它也不带 {@code basePath}，所以"技能目录在哪"那一行由 {@link #withBaseDirectory} 自己补。
     * 补这一行不是锦上添花：技能可以随包带脚本与数据文件（OpenClaw 的形态，{@code {baseDir}}），
     * 模型拿不到目录就只能靠猜路径，那份脚本永远跑不起来。
     *
     * @param directory 技能目录
     * @param reviewDirectory 审批标记目录；{@code null} 表示技能目录下的 {@code .review}
     * @param requireApproval 没有审批标记时是否拒绝加载。false 只给"我知道自己在做什么"的部署
     * @return 名为 {@link #TOOL_NAME} 的工具回调
     * @throws IllegalStateException 目录不存在、里面没有 Skill，或没有一份通过审批
     */
    public static ToolCallback callback(Path directory, Path reviewDirectory, boolean requireApproval) {
        Path absolute = directory.toAbsolutePath().normalize();
        List<Skill> skills = SkillCatalog.load(absolute);
        Path review = reviewDirectory == null
                ? absolute.resolve(DEFAULT_REVIEW_DIRECTORY)
                : reviewDirectory.toAbsolutePath().normalize();

        SkillsTool.Builder builder = SkillsTool.builder();
        // 本地执行时 display 是恒等映射（宿主路径即脚本能访问的路径）；
        // 换成容器沙箱时换掉这个实现即可，模型不会被喂一个它用不到的路径。
        Workspace workspace = Workspace.local(absolute);
        int loaded = 0;
        for (Skill skill : skills) {
            Path skillDirectory = skill.basePath() == null || skill.basePath().isBlank()
                    ? absolute : Path.of(skill.basePath());
            SkillTrust.Decision decision = requireApproval
                    ? SkillTrust.decision(review, skill.name(), SkillTrust.digest(skillDirectory))
                    : null;
            if (requireApproval && decision == null) {
                // fail-closed，而且点名是哪个技能、标记该放哪：不点名的话，排查只能靠猜。
                log.warn("技能未审批或内容已变更，未加载：{}；标记文件应为 {}", skill.name(),
                        review.resolve(SkillTrust.markerName(skill.name())));
                continue;
            }
            String statement = decision == null ? "" : SkillTrust.statement(skill.name(), decision.by());
            builder.addSkill(skill.name(), SkillTrust.descriptionOf(skill),
                    statement + withBaseDirectory(workspace, skillDirectory, skill.content()));
            loaded++;
        }
        if (loaded == 0) {
            throw new IllegalStateException("技能目录里没有通过审批的 Skill：" + absolute
                    + "（标记放在 " + review + "，status=" + SkillTrust.APPROVED + " 且 sha256 对得上才加载）");
        }
        try {
            return builder
                    .workspace(workspace)
                    .build();
        }
        catch (IllegalArgumentException noUsableSkill) {
            throw new IllegalStateException("技能目录里没有可用的 Skill：" + absolute, noUsableSkill);
        }
    }

    /**
     * 把"这份技能的目录在哪"写进正文，并把 {@code {baseDir}} 换掉。
     *
     * <p>社区包只在 {@code addSkillsDirectory} 那条路上补这一行；我们走 {@code addSkill}
     * （为了校验过的就是进上下文的那一份），于是这一行要自己补——不补，随包的脚本就没法被指到。
     *
     * <p>{@code {baseDir}} 是 OpenClaw 技能正文里的写法（"Run the helper script at
     * {@code {baseDir}/scripts/run.sh}"）。原样替换而不是留一个占位符：模型不该被要求知道
     * 某个框架的模板约定。两个写法同时给，是让"从 ClawHub 抄一份技能过来"能直接跑。
     *
     * @param workspace     路径映射（本地是恒等，容器沙箱里换成容器内路径）
     * @param skillDirectory 这份技能的目录
     * @param content       正文
     * @return 正文前面多一行目录、正文里的 {@code {baseDir}} 已替换
     */
    private static String withBaseDirectory(Workspace workspace, Path skillDirectory, String content) {
        String display = workspace.display(skillDirectory.toString());
        return "Base directory for this skill: " + display + "\n\n"
                + content.replace("{baseDir}", display);
    }

    /** 目录里有哪些 Skill，供日志与断言使用。 */
    public static List<String> names(Path directory) {
        return SkillCatalog.load(directory).stream()
                .map(skill -> skill.frontMatter().get("name").toString())
                .toList();
    }
}

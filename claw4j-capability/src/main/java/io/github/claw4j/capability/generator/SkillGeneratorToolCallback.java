package io.github.claw4j.capability.generator;

import io.github.claw4j.domain.ContextKeys;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.capability.script.ScriptPackageToolProvider;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.util.JsonHelper;

import java.util.Map;
import java.util.Objects;

/**
 * 把生成器接成模型手上的一个工具：{@code propose_script}。
 *
 * <p><b>渐进式披露在这里的体现</b>：工具描述里只有"提交一份脚本、走完五步"这一句，
 * 没有把规则表贴进去。模型第一次看见它时只知道"有这么个能力"，
 * 静态检查拒了它才会看到具体规则——上下文里装的是它<b>这次用得上</b>的信息。
 *
 * <p><b>没有身份就拒绝这次提交</b>（与委派同一条口径）：生成脚本会落盘、
 * 会在沙箱里跑一遍，那是有副作用的事。没有身份的提交会变成一条没人负责的记录，
 * 而审计回放时答不出"谁写的"。
 */
public final class SkillGeneratorToolCallback implements ToolCallback {

    /** 工具名。 */
    public static final String TOOL_NAME = "propose_script";

    /** 这个动作名要写进角色的授权表才会被放行（ADR-0007）。 */
    public static final String GENERATOR_ACTION = "draft_script";

    /**
     * 描述里刻意不写规则表：贴进去的话，每一个带工具的轮次都要付这份上下文，
     * 而模型只在被拒的那一轮才需要它。被拒时规则会出现在回执里。
     */
    private static final String DESCRIPTION = "提交一个新的脚本包：脚本会先过静态检查，"
            + "再在隔离沙箱里自测一次，最后等人审通过才会成为可调用的工具。"
            + "参数是包名（字母数字下划线连字符）、一句描述、以及脚本正文（入口为 run.sh 或 main.py）。"
            + "沙箱里没有网络，也不要去读凭据。";

    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "name": {
                  "type": "string",
                  "description": "脚本包名，只能含字母、数字、下划线、连字符，1-64 位"
                },
                "description": {
                  "type": "string",
                  "description": "一句话说明这个脚本做什么、什么时候该调它"
                },
                "script": {
                  "type": "string",
                  "description": "脚本正文。入口是 run.sh 或 main.py；沙箱内无网络、无业务凭据"
                }
              },
              "required": ["name", "description", "script"]
            }""";

    private final SkillGenerator generator;

    private final ToolDefinition definition;

    public SkillGeneratorToolCallback(SkillGenerator generator) {
        this.generator = Objects.requireNonNull(generator, "generator");
        this.definition = ToolDefinition.builder()
                .name(TOOL_NAME)
                .description(DESCRIPTION)
                .inputSchema(INPUT_SCHEMA)
                .build();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        Principal who = principalOf(toolContext);
        if (who == null) {
            return "没有携带身份，拒绝这次提交：生成脚本会落盘并在沙箱里跑一遍，"
                    + "没有身份的提交会变成一条没人负责的记录（与委派同一条口径）。";
        }
        Map<String, Object> input = parse(toolInput);
        SkillGenerator.Request request = new SkillGenerator.Request(
                text(input, "name"), text(input, "description"), text(input, "script"));
        SkillGenerator.Proposal proposal = generator.propose(who, request);
        return proposal.render() + "\n4) 审计：已记录（暂存区 " + generator.stagingDirectory() + "）\n5) "
                + humanReviewLine(proposal);
    }

    /** 人审这一步要怎么说，取决于它现在处于哪一步。 */
    private String humanReviewLine(SkillGenerator.Proposal proposal) {
        if (proposal.status() == SkillGenerator.Status.REJECTED) {
            return "人审：没走到这一步，脚本没有落盘";
        }
        if (proposal.status() == SkillGenerator.Status.PUBLISHED) {
            return "人审：已通过并注册，模型现在可以调用 " + proposal.packageName();
        }
        return "人审：待人工审核，未通过前它不在工具清单里，模型也调不到它。\n"
                + "   要通过：在 " + ReviewGate.fileOf(generator.reviewDirectory(), proposal.packageName())
                + " 写 " + String.join(" / ", ReviewGate.requiredFields())
                + "，其中 status=" + ReviewGate.APPROVED + "、sha256=" + proposal.sha256();
    }

    private static Principal principalOf(ToolContext toolContext) {
        if (toolContext == null) {
            return null;
        }
        Object value = toolContext.getContext().get(ContextKeys.PRINCIPAL);
        return value instanceof Principal principal ? principal : null;
    }

    private static Map<String, Object> parse(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            throw new IllegalArgumentException("propose_script 需要 {\"name\":..,\"description\":..,\"script\":..}");
        }
        try {
            return new JsonHelper().fromJsonToMap(toolInput);
        }
        catch (RuntimeException failure) {
            throw new IllegalArgumentException("propose_script 的参数不是 JSON 对象：" + toolInput, failure);
        }
    }

    private static String text(Map<String, Object> input, String key) {
        Object value = input.get(key);
        return value == null ? null : value.toString();
    }

    /** 权限登记的形状：动作是草稿，业务表一张都没有（与 {@link ScriptPackageToolProvider#SCRIPT_ACTION} 同理）。 */
    public static ToolPermission permission() {
        return ToolPermission.of(GENERATOR_ACTION);
    }
}

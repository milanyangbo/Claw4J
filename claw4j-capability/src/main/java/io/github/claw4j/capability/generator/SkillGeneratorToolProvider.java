package io.github.claw4j.capability.generator;

import io.github.claw4j.domain.principal.ToolFilter;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 生成器这一层的工具来源：只有一个工具（{@code propose_script}），
 * 但它是三层能力里唯一一个"能长出新的能力"的工具。
 *
 * <p><b>权限动作是 {@code draft_script}</b>：提交一份草稿不等于"它能被跑起来"。
 * 跑脚本走的是 {@code Bash}（动作名 {@code exec_command}），草稿要人签字、进了技能目录之后
 * 才轮得到它；自测那一次执行发生在隔离副本里、不在模型手上，它既不是业务数据也不是对外副作用。
 * 两个动作合并成一个，就等于让"能提议的人"顺手拿到了执行权。
 *
 * <p>业务表一张都没有：生成器碰不到任何表，它只写暂存区。
 */
public final class SkillGeneratorToolProvider implements ToolCallbackProvider {

    private final ToolCallback callback;

    /** 这一个工具**存不存在**（票 07）。被排掉时三个出口一起空，不留一个孤立的登记。 */
    private final ToolFilter toolFilter;

    /**
     * 只包一个工具，不筛。
     *
     * @param generator 五步链路的实现
     */
    public SkillGeneratorToolProvider(SkillGenerator generator) {
        this(generator, ToolFilter.none());
    }

    /**
     * 只包一个工具，按配置决定它在不在。
     *
     * <p>一个工具也值得挂过滤：{@code tool-filter.exclude: [propose_script]} 表达的是
     * "这个部署不要它自己长新能力"，而那是与能力档位不同的一件事——档位管"这一轮给不给看"，
     * 过滤管"它到底存不存在"。
     *
     * @param generator 五步链路的实现
     * @param toolFilter 存在性过滤
     */
    public SkillGeneratorToolProvider(SkillGenerator generator, ToolFilter toolFilter) {
        this.callback = new SkillGeneratorToolCallback(Objects.requireNonNull(generator, "generator"));
        this.toolFilter = Objects.requireNonNull(toolFilter, "toolFilter");
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        return exposed() ? new ToolCallback[]{callback} : new ToolCallback[0];
    }

    /** 给装配断言用：这一层导出的工具名。 */
    public List<String> toolNames() {
        return Arrays.stream(getToolCallbacks())
                .map(callback -> callback.getToolDefinition().name())
                .toList();
    }

    private boolean exposed() {
        return toolFilter.matches(SkillGeneratorToolCallback.TOOL_NAME);
    }

}

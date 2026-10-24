package io.github.claw4j.capability.generator;

import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
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
 * <p><b>权限动作是 {@code draft_script}，不是 {@code run_script}</b>：
 * 提交一份草稿不等于能跑脚本。跑脚本由脚本层那张表管（{@code run_script}），
 * 于是"能提议"与"能执行"是两个动作——自测那一次执行发生在沙箱里、
 * 不在脚本层，它既不是业务数据也不是对外副作用，
 * 但把这两个动作合并成一个，就等于让"能提议的人"顺手拿到了执行权。
 *
 * <p>业务表一张都没有：生成器碰不到任何表，它只写暂存区。
 */
public final class SkillGeneratorToolProvider implements ToolCallbackProvider {

    private final ToolCallback callback;

    public SkillGeneratorToolProvider(SkillGenerator generator) {
        this.callback = new SkillGeneratorToolCallback(Objects.requireNonNull(generator, "generator"));
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        return new ToolCallback[]{callback};
    }

    /** 这一层登记的权限：动作是草稿，表一张都没有。 */
    public Map<String, ToolPermission> permissions() {
        return Map.of(SkillGeneratorToolCallback.TOOL_NAME, SkillGeneratorToolCallback.permission());
    }

    /** 给装配断言用：这一层导出的工具名。 */
    public List<String> toolNames() {
        return Arrays.stream(getToolCallbacks())
                .map(callback -> callback.getToolDefinition().name())
                .toList();
    }

    public static ToolPermissionRegistry permissionRegistry(SkillGenerator generator) {
        return ToolPermissionRegistry.of(new SkillGeneratorToolProvider(generator).permissions());
    }
}

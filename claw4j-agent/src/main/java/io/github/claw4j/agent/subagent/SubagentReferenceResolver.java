package io.github.claw4j.agent.subagent;

import org.springaicommunity.agent.common.task.subagent.SubagentReference;
import org.springaicommunity.agent.common.task.subagent.SubagentResolver;

import java.nio.file.Path;

/**
 * 库的"按引用取定义"这一步：我们只告诉它 {@code *.md} 怎么读。
 *
 * <p>{@code TaskTool.Builder#build()} 会对每条引用问一遍 {@link #canResolve}，
 * 命中的交给 {@link #resolve}。所以校验（缺字段、名字冲突、正文为空）仍然发生在
 * <b>启动期</b>——与换库之前一样，只是现在由库来调用。
 *
 * <p>按 {@code kind} 认领而不是按 uri 后缀：kind 是我们自己那一类（{@code CLAW4J}），
 * 库里内置的 Claude 那几种因此不会被我们接走，反过来我们也不会被它接走。
 */
final class SubagentReferenceResolver implements SubagentResolver {

    @Override
    public boolean canResolve(SubagentReference reference) {
        return reference != null && SubagentDefinition.KIND.equals(reference.kind());
    }

    @Override
    public SubagentDefinition resolve(SubagentReference reference) {
        return SubagentDefinition.parse(Path.of(reference.uri()));
    }
}

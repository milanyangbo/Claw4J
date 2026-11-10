package io.github.claw4j.capability.script;

import org.springframework.ai.chat.model.ToolContext;
import io.github.claw4j.domain.ContextKeys;

/**
 * 一次脚本执行的观察者：谁在什么时候跑了哪个包、结果如何。
 *
 * <p>它是接缝而不是写死的实现，因为"执行要留痕"有两个消费者，且不在同一个模块里：
 * 生成器链路要把"生成、自测、执行"串成一条可回放的记录，而脚本层自己不该知道审计长什么样。
 * 默认实现是 {@link #NO_OP}，没接审计时脚本层照常工作。
 *
 * <p>身份来自 {@link ToolContext}：工具内部没有 Advisor 上下文，身份由 {@code ChatClientAgentPort}
 * 放进 prompt 的 {@code toolContext}，键是 {@link ContextKeys#PRINCIPAL}
 * （{@code ChatClientAgentPort.java:798}）。解析不出身份时记 {@code anonymous} 而不是不记。
 */
@FunctionalInterface
public interface ScriptExecutionListener {

    /** 什么都不做的实现，用于没接审计的装配。 */
    ScriptExecutionListener NO_OP = (packageName, context, outcome, output) -> {
    };

    /**
     * 接收一次执行结果。
     *
     * @param packageName 被执行的包名
     * @param context 工具上下文，身份从这里取
     * @param outcome 结果的机器可读判定：{@code ok}、{@code non-zero}、{@code rejected}、{@code failed}
     * @param output 脚本写到标准输出上的内容（截断后）
     */
    void executed(String packageName, ToolContext context, String outcome, String output);
}

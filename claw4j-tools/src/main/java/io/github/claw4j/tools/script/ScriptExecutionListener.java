package io.github.claw4j.tools.script;

import org.springframework.ai.chat.model.ToolContext;

/**
 * 一次脚本执行的观察者：谁在什么时候跑了哪个包、结果如何。
 *
 * <p>它是<b>接缝</b>而不是一个写死的实现，因为"执行要留痕"这件事有两个消费者，
 * 而且不在同一个模块里：生成器链路（06）要把"生成、自测、执行"串成一条可回放的记录，
 * 而脚本层自己不该知道审计长什么样。默认实现是
 * {@link #NO_OP}——没有接上审计时脚本层照常工作，
 * 于是"没配审计"不会变成"脚本跑不了"。
 *
 * <p><b>拿得到身份是因为这里拿着 {@link ToolContext}</b>：工具内部没有 Advisor 上下文，
 * 身份由 {@code ChatClientAgentPort} 放进 prompt 的 {@code toolContext}
 * （键是 {@code io.github.claw4j.domain.principal.ContextKeys#PRINCIPAL}）。
 * 解析不出身份时记 {@code anonymous} 而不是不记——
 * "有一条不知道是谁的记录"比"没有记录"更接近真相。
 */
@FunctionalInterface
public interface ScriptExecutionListener {

    ScriptExecutionListener NO_OP = (packageName, context, outcome, output) -> {
    };

    /**
     * @param packageName 被执行的包名
     * @param context 工具上下文，身份从这里取
     * @param outcome 结果的机器可读判定：{@code ok}、{@code non-zero}、{@code rejected}、{@code failed}
     * @param output 脚本写到标准输出上的内容（截断后）
     */
    void executed(String packageName, ToolContext context, String outcome, String output);
}

package io.github.claw4j.tools.script;

import org.springframework.ai.chat.model.ToolContext;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 执行观察者们。它存在的唯一理由是<b>装配顺序</b>：
 *
 * <p>脚本层（03）先装配，生成器层（06）后装配，而把审计接上执行这件事
 * 只有生成器那一步才知道。若脚本层在构造时按类型注入一个
 * {@link ScriptExecutionListener}，那会儿审计 bean 还没定义，
 * 于是"接不上"是以静默的方式发生的——不报错，只是从此没有任何执行记录。
 * 这个容器先落地、后来者往里加，顺序就不再是问题。
 *
 * <p>这也是一处有意的选择：<b>宁可让"没接上"表现为空容器而不是装配失败</b>。
 * 审计是留痕手段而不是安全边界——它没有接上时脚本层的行为不该改变，
 * 否则一个日志配置的抖动就会让整个脚本层不可用。
 */
public final class ExecutionListeners implements ScriptExecutionListener {

    private final List<ScriptExecutionListener> listeners = new CopyOnWriteArrayList<>();

    /** 后装配的那一层把自己挂进来。 */
    public void add(ScriptExecutionListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public int size() {
        return listeners.size();
    }

    /**
     * 一个观察者抛异常不影响执行本身：留痕失败不能让"这次调用"变成一次失败，
     * 那是把审计从观察者变成了依赖。
     */
    @Override
    public void executed(String packageName, ToolContext context, String outcome, String output) {
        for (ScriptExecutionListener listener : listeners) {
            try {
                listener.executed(packageName, context, outcome, output);
            }
            catch (RuntimeException failure) {
                // 留痕失败不打断执行；这里没有日志框架之外的东西可用（tools 层不引 Agent）。
                System.err.println("脚本执行留痕失败（不影响本次执行）：" + failure.getMessage());
            }
        }
    }
}

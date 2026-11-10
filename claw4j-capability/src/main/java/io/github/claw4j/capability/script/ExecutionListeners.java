package io.github.claw4j.capability.script;

import org.springframework.ai.chat.model.ToolContext;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 执行观察者的容器，存在的原因是装配顺序。
 *
 * <p>脚本层先装配，生成器层后装配，而"把审计接到执行上"这件事只有生成器那一步知道。
 * 若脚本层在构造时按类型注入一个 {@link ScriptExecutionListener}，那会儿审计 bean 还没定义，
 * 于是"接不上"以静默方式发生：不报错，只是从此没有任何执行记录。容器先落地、后来者往里加，
 * 顺序就不再是问题。
 *
 * <p>这也是有意的取舍：宁可让"没接上"表现为空容器，也不让它装配失败。审计是留痕手段而不是
 * 安全边界，它没接上时脚本层的行为不该改变。
 */
public final class ExecutionListeners implements ScriptExecutionListener {

    private final List<ScriptExecutionListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * 后装配的那一层把自己挂进来。
     *
     * @param listener 接收执行结果的观察者
     */
    public void add(ScriptExecutionListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * 已挂上的观察者数量，给装配断言用。
     *
     * @return 观察者个数
     */
    public int size() {
        return listeners.size();
    }

    /**
     * 依次通知每个观察者；其中一个抛异常不影响执行本身。
     *
     * <p>留痕失败不能让这次调用变成一次失败，那是把审计从观察者变成了依赖。
     *
     * @param packageName 被执行的包名
     * @param context 工具上下文
     * @param outcome 结果的机器可读判定
     * @param output 脚本写到标准输出上的内容
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

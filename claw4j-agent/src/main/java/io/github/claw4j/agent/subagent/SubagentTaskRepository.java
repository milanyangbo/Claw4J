package io.github.claw4j.agent.subagent;

import io.github.claw4j.domain.principal.Principal;
import org.springaicommunity.agent.tools.task.repository.BackgroundTask;
import org.springaicommunity.agent.tools.task.repository.DefaultTaskRepository;
import org.springaicommunity.agent.tools.task.repository.TaskRepository;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * 库的后台任务表，外面包一层"把身份带进那个线程"。
 *
 * <p>库的后台任务在另一个线程上跑（{@code DefaultTaskRepository} 自己的线程池），而身份放在
 * {@link SubagentDelegation} 的 {@link ThreadLocal} 里——**换线程就没了**。没有这一层，
 * 后台派出去的子 Agent 会在执行器里被判成"没有携带身份"并拒绝，现象是"后台任务永远失败，
 * 同步的没问题"，而这条路径不常走，很容易一直没人发现。
 *
 * <p>做法就一句：{@code putTask} 那一刻把当前身份取出来（那时还在工具调用的线程上），
 * 进后台之后再装回去。身份是<b>提交时</b>的，不是"执行时线程碰巧有哪个"——这正是审批与
 * 收窄需要的语义。
 */
public final class SubagentTaskRepository implements TaskRepository {

    private final DefaultTaskRepository delegate = new DefaultTaskRepository();

    @Override
    public BackgroundTask getTasks(String taskId) {
        return delegate.getTasks(taskId);
    }

    @Override
    public BackgroundTask putTask(String taskId, Supplier<String> taskExecution) {
        Objects.requireNonNull(taskExecution, "taskExecution");
        Principal principal = SubagentDelegation.currentPrincipal();
        return delegate.putTask(taskId, () -> SubagentDelegation.callAs(principal, taskExecution));
    }

    @Override
    public void removeTask(String taskId) {
        delegate.removeTask(taskId);
    }

    @Override
    public void clear() {
        delegate.clear();
    }

    /** 关掉那个线程池。容器关闭时调一次，否则后台任务线程会拖住 JVM 退出。 */
    public void shutdown() {
        delegate.shutdown();
    }
}

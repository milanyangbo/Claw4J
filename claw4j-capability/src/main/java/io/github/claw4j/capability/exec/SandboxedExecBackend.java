package io.github.claw4j.capability.exec;

import io.github.claw4j.capability.process.ProcessOutcome;
import io.github.claw4j.capability.process.ProcessStartException;
import io.github.claw4j.capability.process.ShellCommandRunner;
import io.github.claw4j.capability.process.ShellProcess;
import org.springaicommunity.agent.common.exec.ExecBackend;
import org.springaicommunity.agent.common.exec.ExecHandle;
import org.springaicommunity.agent.common.exec.ExecResult;
import org.springaicommunity.agent.common.exec.ExecSpec;
import org.springframework.beans.factory.DisposableBean;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 我们的沙箱，接在 Spring AI 的 {@code ShellTools} 后面。
 *
 * <p><b>为什么是"实现一个后端"而不是"写一个工具"</b>：通用 shell 的工具形态（{@code Bash} /
 * {@code BashOutput} / {@code KillShell}、参数、提示词、输出截断、后台句柄的管理）Spring AI
 * 已经做好了，而且做得比我们细（30000 字符截断、{@code bash_id} 命名、按实例隔离后台 shell）。
 * 我们要加的只有一件事——**这条命令准不准跑**。{@code ExecBackend} 正好是那个缝：
 * 它的 {@code run(ExecSpec)} 收一条命令，我们的判定就挂在那里。
 *
 * <p><b>判定顺序固定</b>：非 root → 白名单 → 环境 → 起进程。先判便宜的、再判要碰环境的，
 * 任何一步拒绝都不该留下副作用（不起进程、不写文件、不占端口）。
 *
 * <p><b>后端替模型定死的东西</b>：工作目录（就是 workspace，{@code workdir} 由模型用
 * {@code cd} 在命令里表达）、环境（继承 + 拒改 {@code PATH}/{@code LD_*}/{@code DYLD_*}）、
 * 超时上限（由 {@code ExecSpec} 自己 clamp，见 {@code DEFAULT/MAX_TIMEOUT_MILLIS}）。
 * 这三样都不在工具参数里，所以模型改不了——这正是把它们放在后端的理由。
 *
 * <p><b>拒绝怎么让模型看见</b>：{@link ExecViolationException} 从 {@code run} 里抛出去，
 * {@code ShellTools} 的同步分支不吞异常，{@code MethodToolCallback} 把它包成
 * {@code ToolExecutionException} 交给工具调用管理器——模型收到的是一句人话，
 * 不是一个它读不懂的堆栈。
 */
public final class SandboxedExecBackend implements ExecBackend, DisposableBean {

    private final ExecProperties properties;

    private final ShellCommandRunner runner;

    private final Map<String, String> inheritedEnvironment;

    private final String accountName;

    /** 后台 shell 由这里持有：{@code ShellTools} 只管自己的那份 id → handle，回收这件事在 {@link #destroy()}。 */
    private final Map<String, ShellProcess> background = new ConcurrentHashMap<>();

    public SandboxedExecBackend(ExecProperties properties, ShellCommandRunner runner) {
        this(properties, runner, System.getenv(), System.getProperty("user.name", ""));
    }

    /**
     * @param inheritedEnvironment 继承给命令的环境；测试传一份固定值，免得断言依赖跑测试的那台机器
     * @param accountName          当前执行账号；测试传 {@code root} 验那条拒绝
     */
    public SandboxedExecBackend(ExecProperties properties, ShellCommandRunner runner,
                                Map<String, String> inheritedEnvironment, String accountName) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.inheritedEnvironment = Map.copyOf(Objects.requireNonNull(inheritedEnvironment, "inheritedEnvironment"));
        this.accountName = accountName;
    }

    @Override
    public ExecResult run(ExecSpec spec) {
        authorize(spec);
        Path directory = runner.workingDirectory();
        try {
            ProcessOutcome outcome = runner.run(spec.command(), directory, environment(spec), spec.timeoutMillis());
            return outcome.status() == ProcessOutcome.Status.TIMED_OUT
                    ? ExecResult.timedOut(outcome.stdout(), outcome.stderr())
                    : ExecResult.completed(outcome.exitCode(), outcome.stdout(), outcome.stderr());
        }
        catch (ProcessStartException failure) {
            // 起不来是"这条命令跑了但没跑成"，不是拒绝：交给工具层当普通失败报，与超时同一档。
            return ExecResult.launchFailed(failure.getMessage());
        }
    }

    @Override
    public ExecHandle start(ExecSpec spec) {
        authorize(spec);
        // 顺手清掉已经结束的后台 shell：它们不会自己从表里消失，留着的唯一作用是占一个 id。
        background.values().removeIf(shell -> !shell.isAlive());
        ShellProcess shell = runner.start(spec.command(), runner.workingDirectory(), environment(spec));
        background.put(shell.id(), shell);
        return new Handle(shell);
    }

    @Override
    public void destroy() {
        background.values().forEach(ShellProcess::kill);
        background.clear();
    }

    private void authorize(ExecSpec spec) {
        Objects.requireNonNull(spec, "spec");
        ExecPolicy.requireNonRoot(accountName);
        if (properties.mode() != ExecProperties.Mode.FULL
                && !ExecPolicy.permits(properties.allow(), spec.command())) {
            throw new ExecViolationException("这条命令没有执行："
                    + (properties.mode() == ExecProperties.Mode.ALLOWLIST ? "allowlist" : "ask")
                    + " 模式下只放开白名单里的命令（白名单：" + allowText() + "）");
        }
    }

    private Map<String, String> environment(ExecSpec spec) {
        return ExecPolicy.environment(inheritedEnvironment, spec.env());
    }

    private String allowText() {
        return properties.allow().isEmpty() ? "空，等于一条都不放行" : String.join("、", properties.allow());
    }

    /** 把 {@link ShellProcess} 说成 Spring AI 要的 {@link ExecHandle}。只转发，不加工。 */
    private static final class Handle implements ExecHandle {

        private final ShellProcess shell;

        private Handle(ShellProcess shell) {
            this.shell = shell;
        }

        @Override
        public String id() {
            return shell.id();
        }

        @Override
        public boolean isAlive() {
            return shell.isAlive();
        }

        @Override
        public String newOutput(String filter) {
            return shell.newOutput(filter);
        }

        @Override
        public int exitCode() {
            return shell.exitCode();
        }

        @Override
        public void kill() {
            shell.kill();
        }
    }
}

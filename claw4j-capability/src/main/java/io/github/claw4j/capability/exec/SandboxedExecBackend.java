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
 * exec 的沙箱，接在 Spring AI 的 {@code ShellTools} 后面。
 *
 * <p>做成一个后端而不是一个工具：通用 shell 的工具形态（{@code Bash} / {@code BashOutput} /
 * {@code KillShell}、参数、提示词、30000 字符截断、{@code bash_id} 命名、按实例隔离的后台 shell）
 * 库已经做好了。这里要加的只有一件事——这条命令准不准跑，而 {@code ExecBackend} 的
 * {@code run(ExecSpec)} 正好是那个缝。
 *
 * <p>判定顺序固定：非 root → 白名单 → 环境 → 起进程。先判便宜的、再判要碰环境的，
 * 任何一步拒绝都不起进程、不写文件、不占端口。
 *
 * <p>后端替模型定死的三样：工作目录（就是 workspace，换目录由模型在命令里 {@code cd} 表达）、
 * 环境（继承，且拒改 {@code PATH} / {@code LD_*} / {@code DYLD_*}）、超时上限（由
 * {@code ExecSpec} 自己 clamp，{@code ExecSpec.java:45}）。三样都不在工具参数里，模型改不了。
 *
 * <p>拒绝让模型看见的方式：{@link ExecViolationException} 从 {@code run} 里抛出去，
 * {@code ShellTools} 的同步分支不吞异常，工具调用管理器把它包成 {@code ToolExecutionException}，
 * 模型收到的是一句人话而不是堆栈。
 */
public final class SandboxedExecBackend implements ExecBackend, DisposableBean {

    private final ExecProperties properties;

    private final ShellCommandRunner runner;

    private final Map<String, String> inheritedEnvironment;

    private final String accountName;

    /** 后台 shell 由这里持有：{@code ShellTools} 只管自己的那份 id → handle，回收这件事在 {@link #destroy()}。 */
    private final Map<String, ShellProcess> background = new ConcurrentHashMap<>();

    /**
     * 用进程自身的环境与账号建后端：生产路径走这个。
     *
     * @param properties {@code claw4j.tools.exec} 的配置
     * @param runner 起进程的执行器
     */
    public SandboxedExecBackend(ExecProperties properties, ShellCommandRunner runner) {
        this(properties, runner, System.getenv(), System.getProperty("user.name", ""));
    }

    /**
     * 用给定的环境与账号建后端。
     *
     * @param properties  {@code claw4j.tools.exec} 的配置
     * @param runner      起进程的执行器
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

    /**
     * 同步执行一条命令：判定通过才起进程。
     *
     * <p>起不来（{@link ProcessStartException}）算一次普通失败，与超时同一档，交给工具层报给模型；
     * 只有判定不通过才是拒绝。
     *
     * @param spec 库给出的命令、超时与本次覆盖的环境
     * @return 执行结果，翻成库的 {@link ExecResult}
     * @throws ExecViolationException 身份是 root，或命令没通过白名单
     */
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

    /**
     * 起一个后台 shell 并自己留一份，回收发生在 {@link #destroy()}。
     *
     * <p>顺带清掉已经结束的后台 shell：它们不会自己从表里消失，留着只占一个 id。
     *
     * @param spec 库给出的命令与本次覆盖的环境
     * @return 后台句柄，由库转交给 {@code BashOutput} / {@code KillShell}
     * @throws ExecViolationException 身份是 root，或命令没通过白名单
     */
    @Override
    public ExecHandle start(ExecSpec spec) {
        authorize(spec);
        // 顺手清掉已经结束的后台 shell：它们不会自己从表里消失，留着的唯一作用是占一个 id。
        background.values().removeIf(shell -> !shell.isAlive());
        ShellProcess shell = runner.start(spec.command(), runner.workingDirectory(), environment(spec));
        background.put(shell.id(), shell);
        return new Handle(shell);
    }

    /**
     * 强制结束所有还活着的后台 shell。
     *
     * <p>{@code KillShell} 是模型的礼貌，不能当回收机制：容器关闭时这里必须收干净。
     */
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

package io.github.claw4j.capability.exec;

import io.github.claw4j.capability.process.ProcessOutcome;
import io.github.claw4j.capability.process.ProcessStartException;
import io.github.claw4j.capability.process.ShellCommandRunner;
import io.github.claw4j.capability.process.ShellProcess;
import io.github.claw4j.capability.sandbox.SandboxProperties;
import org.springaicommunity.agent.common.exec.ExecBackend;
import org.springaicommunity.agent.common.exec.ExecHandle;
import org.springaicommunity.agent.common.exec.ExecResult;
import org.springaicommunity.agent.common.exec.ExecSpec;
import org.springframework.beans.factory.DisposableBean;

import java.nio.file.Path;
import java.util.List;
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
 * 环境（宿主上继承、且拒改 {@code PATH} / {@code LD_*} / {@code DYLD_*}；容器里不继承宿主那份）、
 * 超时上限（由 {@code ExecSpec} 自己 clamp，{@code ExecSpec.java:45}）。三样都不在工具参数里，模型改不了。
 *
 * <p>命令跑在哪儿是<b>另一个决定</b>（P17a）：{@code container} 非空时，判定通过后整段交给它，
 * 生产上那是社区的 {@code DockerCliExecBackend}。这一层不因为换了执行位置就多判或少判一条——
 * 白名单、审批、非 root 与"在哪跑"正交。
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

    /** 问人这一层的端口。没有实现时是 {@link ExecApprovalPort#denyAll()}：需要批的直接拒。 */
    private final ExecApprovalPort approvals;

    /** 未命中时的复核器。没有实现时恒回落人工，所以 {@code AUTO} 与 {@code ASK} 行为一致。 */
    private final ExecReviewer reviewer;

    /** 后台 shell 由这里持有：{@code ShellTools} 只管自己的那份 id → handle，回收这件事在 {@link #destroy()}。 */
    private final Map<String, ShellProcess> background = new ConcurrentHashMap<>();

    /**
     * 命令交给谁跑。{ null} 表示宿主那条 shell 解释器。
     *
     * <p>非空时是社区的 {@code DockerCliExecBackend}：托管容器、后台句柄、超时在容器内杀 PID、
     * 退出回收都在里面。这一层只留判定，不再自己拼 {@code docker run}。
     */
    private final ExecBackend container;

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
        this(properties, runner, inheritedEnvironment, accountName,
                ExecApprovalPort.denyAll(), ExecReviewer.fallback());
    }

    /**
     * 完整的一档：接上审批端口。
     *
     * @param properties  {@code claw4j.tools.exec} 的配置
     * @param runner      起进程的执行器
     * @param inheritedEnvironment 继承给命令的环境
     * @param accountName          当前执行账号
     * @param approvals            审批端口；{@code null} 按"没有设施"处理，需要批的命令直接拒
     */
    public SandboxedExecBackend(ExecProperties properties, ShellCommandRunner runner,
                                Map<String, String> inheritedEnvironment, String accountName,
                                ExecApprovalPort approvals) {
        this(properties, runner, inheritedEnvironment, accountName, approvals, ExecReviewer.fallback());
    }

    /**
     * 完整的一档：审批端口 + 复核器。
     *
     * @param properties           {@code claw4j.tools.exec} 的配置
     * @param runner               起进程的执行器
     * @param inheritedEnvironment 继承给命令的环境
     * @param accountName          当前执行账号
     * @param approvals            审批端口；{@code null} 按"没有设施"处理
     * @param reviewer             复核器；{@code null} 按"恒回落"处理
     */
    public SandboxedExecBackend(ExecProperties properties, ShellCommandRunner runner,
                                Map<String, String> inheritedEnvironment, String accountName,
                                ExecApprovalPort approvals, ExecReviewer reviewer) {
        this(properties, runner, inheritedEnvironment, accountName, approvals, reviewer, null);
    }

    /**
     * 完整的一档，并且指定"命令交给谁跑"。
     *
     * <p>{@code container} 非空时，<strong>判定之后的执行整段交给它</strong>——生产上那是社区的
     * {@code DockerCliExecBackend}（托管容器、后台句柄、超时在容器内杀 PID、退出回收都在里面）。
     * 这一层只保留判定：白名单、审批、非 root、环境，与"在哪跑"正交。
     *
     * <p>为什么不在这层自己拼 {@code docker run}：Docker 有个已知限制——杀掉 docker 客户端
     * 不会杀掉容器里的进程。库里为每条命令在容器内记了 PID，超时时在容器内发 TERM 再 KILL；
     * 自己写一版最容易漏的正是这一条，而且漏的时候没人知道。
     *
     * @param properties           {@code claw4j.tools.exec} 的配置
     * @param runner               宿主上起进程的执行器
     * @param inheritedEnvironment 宿主上继承给命令的环境；容器那条路不用它（容器不继承宿主环境）
     * @param accountName          当前执行账号
     * @param approvals            审批端口；{@code null} 按"没有设施"处理
     * @param reviewer             复核器；{@code null} 按"恒回落"处理
     * @param container            容器执行后端；{@code null} 表示跑在宿主上
     */
    public SandboxedExecBackend(ExecProperties properties, ShellCommandRunner runner,
                                Map<String, String> inheritedEnvironment, String accountName,
                                ExecApprovalPort approvals, ExecReviewer reviewer, ExecBackend container) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.inheritedEnvironment = Map.copyOf(Objects.requireNonNull(inheritedEnvironment, "inheritedEnvironment"));
        this.accountName = accountName;
        this.approvals = approvals == null ? ExecApprovalPort.denyAll() : approvals;
        this.reviewer = reviewer == null ? ExecReviewer.fallback() : reviewer;
        this.container = container;
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
        if (container != null) {
            return container.run(spec);
        }
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
        if (container != null) {
            return container.start(spec);
        }
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
        // 容器跟着这一层一起回收：托管容器由库建、也由库删（它还挂了 shutdown hook 兜底）。
        if (container instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            }
            catch (Exception failure) {
                throw new IllegalStateException("关闭容器后端失败：" + failure.getMessage(), failure);
            }
        }
    }

    private void authorize(ExecSpec spec) {
        Objects.requireNonNull(spec, "spec");
        ExecPolicy.requireNonRoot(accountName);

        ExecPolicy.Settings settings = properties.settings();
        List<String> segments = ExecPolicy.segments(spec.command());
        boolean allowlisted = ExecPolicy.permits(properties.allow(), spec.command());
        boolean durable = approvals.alreadyApproved(segments);

        // heredoc 与内联求值会把"白名单直接放行"抬成"问人"（票 01 的追加项，照它的 requiresAsk）。
        // 只在 RUN 上叠加：已经 DENY 的不该因为"要问人"变得更容易过。
        ExecPolicy.Verdict verdict = ExecPolicy.verdict(settings, allowlisted, durable);
        if (verdict == ExecPolicy.Verdict.RUN && ExecPolicy.forceAsk(settings, spec.command())) {
            verdict = ExecPolicy.Verdict.ASK;
        }

        switch (verdict) {
            case RUN -> {
                // 放行，不打扰人
            }
            case DENY -> throw new ExecViolationException("这条命令没有执行：" + denyReason(settings, allowlisted));
            case ASK -> {
                if (reviewAllows(settings, allowlisted, durable, spec)) {
                    return;
                }
                settle(spec, segments, settings);
            }
        }
    }

    /**
     * 问一次人，按结论处置这一次执行（票 05）。
     *
     * <p>四档分开处置，因为下一步完全不同：批了就按<strong>冻结件</strong>跑；明确拒了就是拒；
     * 这一轮拿不到结论时交回 {@code askFallback}（默认拒）。
     *
     * <p><b>冻结件比对是这一段的要点</b>：批准的对象是那一条命令，不是"这个工具"。拿它和现在要跑的
     * 逐字符比，不一致就拒，而不是"差不多就跑"——批准之后改一个字还能跑，等于批准的是某个时间点的
     * 意图，那正是这条机制要挡的。
     *
     * @param spec     要跑的命令
     * @param segments 拆好的段
     * @param settings 生效策略，兜底档从它取
     * @throws ExecViolationException 明确拒绝、冻结件不一致，或兜底档不放行
     */
    private void settle(ExecSpec spec, List<String> segments, ExecPolicy.Settings settings) {
        ExecApprovalPort.Request request = new ExecApprovalPort.Request(
                spec.command(), displayDirectory(), segments, askReason());
        ExecApprovalPort.Decision decision = approvals.decide(request);
        switch (decision.kind()) {
            case ALLOW_ONCE -> requireFrozenPlan(decision, request);
            case ALLOW_ALWAYS -> {
                requireFrozenPlan(decision, request);
                approvals.remember(request);
            }
            case DENY -> throw new ExecViolationException("这条命令没有执行：" + decision.detail());
            case UNDECIDED -> {
                if (ExecPolicy.fallbackAllows(settings)) {
                    return;
                }
                throw new ExecViolationException(undecidedText(decision));
            }
        }
    }

    /**
     * 批准的那份计划要和现在要跑的一致。
     *
     * <p>没有计划也算不一致：一份只有"批了"、说不出批的是什么的批准，不该放行任何一条命令——
     * 那与空串指纹同一个取向（升级之后要重批一次）。
     *
     * @param decision 结论
     * @param request  现在这次请求
     * @throws ExecViolationException 不一致
     */
    private static void requireFrozenPlan(ExecApprovalPort.Decision decision,
                                         ExecApprovalPort.Request request) {
        FrozenPlan approved = decision.plan();
        FrozenPlan current = request.plan();
        if (approved != null && approved.sameAs(current)) {
            return;
        }
        String was = approved == null ? "（没有记下批的是哪条命令）" : approved.summary();
        throw new ExecViolationException("这条命令没有执行：批准的是另一条命令（approval mismatch）。\n"
                + "批准的那份：" + was + "\n现在这份：" + current.summary()
                + "\n改了命令就要重新批一次——旧的批准不能用在新的命令上。");
    }

    /**
     * 这一轮拿不到结论时那句话。
     *
     * <p>三句都要说清：没有执行、不要重试同一个调用、没有新的输出。少一句，模型就会把同一条命令
     * 原样再发一遍，而那既不会变成批准，又会把真正的进展挤掉。
     *
     * @param decision 结论
     * @return 回给模型的那段文本
     */
    private static String undecidedText(ExecApprovalPort.Decision decision) {
        StringBuilder text = new StringBuilder("这条命令没有执行：");
        text.append(decision.detail().isBlank() ? "拿不到审批结论" : decision.detail()).append('。');
        if (decision.approvalId() != null) {
            text.append("它已经登记成一条审批请求（编号 ").append(decision.approvalId())
                    .append("），批准之后会自动接着做，不用重新说明背景。");
        }
        return text.append("这条命令没有跑，也没有新的输出；不要重试同一个调用——")
                .append("再发一次既不会变成批准，也不会生成新的审批。").toString();
    }

    /**
     * 复核器放不放行这一次。只有 {@code autoReview=true} 且真的是"未命中"时才问它。
     *
     * <p>{@code ask=always} 不走复核器：那一档的含义是"每条都要人看"，让复核器代劳就等于把它变成
     * {@code on-miss}。
     */
    private boolean reviewAllows(ExecPolicy.Settings settings, boolean allowlisted, boolean durable,
                                 ExecSpec spec) {
        if (!settings.autoReview()
                || settings.ask() != ExecProperties.Ask.ON_MISS
                || allowlisted
                || durable) {
            return false;
        }
        return reviewer.review(reviewRequest(spec)).decision() == ExecReviewer.Decision.ALLOW;
    }

    /**
     * 给复核器的入参。
     *
     * <p>两处如实标出"没做到"：{@code argv} 是朴素按空白切的（本层没有 shell 词法），
     * {@code resolvedPath} 恒为 {@code null}（没有 PATH 查找与软链接解析）。接真的复核器之前要先补这两样，
     * 否则它会拿到一份"看起来解析过、其实没有"的输入——那比不给更危险。
     */
    private ExecReviewer.Request reviewRequest(ExecSpec spec) {
        List<String> segments = ExecPolicy.segments(spec.command());
        List<String> argv = segments.isEmpty()
                ? List.of()
                : List.of(segments.getFirst().split("\\s+"));
        return new ExecReviewer.Request(spec.command(), argv, null, displayDirectory(),
                inheritedEnvironment.keySet(), properties.settings().host().name(),
                askReason(), "", null);
    }

    private String denyReason(ExecPolicy.Settings settings, boolean allowlisted) {
        if (settings.security() == ExecProperties.Security.DENY) {
            return "security=deny，exec 一条都不放";
        }
        return "allowlist + ask=off 只放开白名单里的命令（白名单：" + allowText() + "），"
                + "这条没有命中，且该档不给人批准的机会";
    }

    private String askReason() {
        return "这条命令没有命中白名单，当前档位要求人先批（白名单：" + allowText() + "）";
    }

    private Map<String, String> environment(ExecSpec spec) {
        return ExecPolicy.environment(inheritedEnvironment, spec.env());
    }

    /**
     * 命令"看起来在哪个目录里跑"。审批请求、复核器与拒绝信息读的都是它。
     *
     * <p>容器模式下报的是容器里的目录：模型写的命令确实跑在 {@code /workspace}，给它看宿主路径
     * 等于让它拿着一个自己点不到的地方去解释失败。宿主模式照旧报工作区。
     *
     * @return 给模型与审批人看的目录
     */
    private Path displayDirectory() {
        return container == null
                ? runner.workingDirectory()
                : Path.of(SandboxProperties.CONTAINER_WORKDIR);
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

package io.github.claw4j.capability.exec;

import io.github.claw4j.capability.sandbox.SandboxProperties;
import io.github.claw4j.domain.principal.Elevation;
import org.springaicommunity.agent.common.exec.ExecBackend;
import org.springaicommunity.agent.common.exec.ExecHandle;
import org.springaicommunity.agent.common.exec.ExecResult;
import org.springaicommunity.agent.common.exec.ExecSpec;
import org.springframework.beans.factory.DisposableBean;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * exec 的沙箱，接在 Spring AI 的 {@code ShellTools} 后面。
 *
 * <p>做成一个后端而不是一个工具：通用 shell 的工具形态（{@code Bash} / {@code BashOutput} /
 * {@code KillShell}、参数、提示词、30000 字符截断、{@code bash_id} 命名、按实例隔离的后台 shell）
 * 库已经做好了。这里要加的只有一件事——这条命令准不准跑，而 {@code ExecBackend} 的
 * {@code run(ExecSpec)} 正好是那个缝。
 *
 * <p><b>本类不碰进程。</b>宿主那条由库的 {@code LocalExecBackend} 起（整份清空环境、超时先礼后兵、
 * 后台句柄都是它的），容器那条由库的 {@code DockerCliExecBackend} 起。这一层只留判定：
 * 非 root → 白名单 → 审批 → 环境覆盖的合法性。顺序固定，任何一步拒绝都不起进程、不写文件、不占端口。
 *
 * <p>执行位置由 {@code claw4j.tools.exec.host} 决定（票 06），判定与它正交——不因为换了位置就多判
 * 或少判一条。沙箱会话里 {@code gateway} 会被拒：那是"从沙箱提到宿主"，只有 {@code auto} 允许
 * 在没容器时落宿主，不是一条随时可用的逃生门。
 *
 * <p>拒绝让模型看见的方式：{@link ExecViolationException} 从 {@code run} 里抛出去，
 * {@code ShellTools} 的同步分支不吞异常，工具调用管理器把它包成 {@code ToolExecutionException}，
 * 模型收到的是一句人话而不是堆栈。
 */
public final class SandboxedExecBackend implements ExecBackend, DisposableBean {

    private final ExecProperties properties;

    /** 宿主上的执行后端（生产上是库的 {@code LocalExecBackend}）。 */
    private final ExecBackend host;

    /** 继承环境的键名，只给复核器看：值里可能有密钥，进程拿到什么由 {@code host} 决定。 */
    private final Set<String> environmentKeys;

    private final String accountName;

    /** 问人这一层的端口。没有实现时是 {@link ExecApprovalPort#denyAll()}：需要批的直接拒。 */
    private final ExecApprovalPort approvals;

    /** 未命中时的复核器。没有实现时恒回落人工，所以 {@code AUTO} 与 {@code ASK} 行为一致。 */
    private final ExecReviewer reviewer;

    /**
     * 后台句柄由这里记账：{@code ShellTools} 自己攥着 id → handle 的表，{@code destroy()} 看不到它们，
     * 不记的话容器关闭之后会留下没人杀的进程。
     */
    private final Set<ExecHandle> backgroundShells = ConcurrentHashMap.newKeySet();

    /**
     * 命令交给谁跑。{@code null} 表示宿主。
     *
     * <p>非空时是库的 {@code DockerCliExecBackend}：托管容器、后台句柄、超时在容器内杀 PID、
     * 退出回收都在里面。这一层不自己拼 {@code docker run}。
     */
    private final ExecBackend container;

    /**
     * 用给定的宿主后端与执行账号建后端。
     *
     * @param properties      {@code claw4j.tools.exec} 的配置
     * @param host            宿主上的执行后端
     * @param environmentKeys 继承环境的键名，只给复核器看
     * @param accountName     当前执行账号
     */
    public SandboxedExecBackend(ExecProperties properties, ExecBackend host, Set<String> environmentKeys,
                                String accountName) {
        this(properties, host, environmentKeys, accountName, ExecApprovalPort.denyAll(),
                ExecReviewer.fallback(), null);
    }

    /**
     * 完整的一档：接上审批端口。
     *
     * @param properties      {@code claw4j.tools.exec} 的配置
     * @param host            宿主上的执行后端
     * @param environmentKeys 继承环境的键名，只给复核器看
     * @param accountName     当前执行账号
     * @param approvals       审批端口；{@code null} 按"没有设施"处理，需要批的命令直接拒
     */
    public SandboxedExecBackend(ExecProperties properties, ExecBackend host, Set<String> environmentKeys,
                                String accountName, ExecApprovalPort approvals) {
        this(properties, host, environmentKeys, accountName, approvals, ExecReviewer.fallback(), null);
    }

    /**
     * 完整的一档：审批端口 + 复核器。
     *
     * @param properties      {@code claw4j.tools.exec} 的配置
     * @param host            宿主上的执行后端
     * @param environmentKeys 继承环境的键名，只给复核器看
     * @param accountName     当前执行账号
     * @param approvals       审批端口；{@code null} 按"没有设施"处理
     * @param reviewer        复核器；{@code null} 按"恒回落"处理
     */
    public SandboxedExecBackend(ExecProperties properties, ExecBackend host, Set<String> environmentKeys,
                                String accountName, ExecApprovalPort approvals, ExecReviewer reviewer) {
        this(properties, host, environmentKeys, accountName, approvals, reviewer, null);
    }

    /**
     * 完整的一档，并且指定"命令交给谁跑"。
     *
     * @param properties      {@code claw4j.tools.exec} 的配置
     * @param host            宿主上的执行后端
     * @param environmentKeys 继承环境的键名，只给复核器看
     * @param accountName     当前执行账号
     * @param approvals       审批端口；{@code null} 按"没有设施"处理
     * @param reviewer        复核器；{@code null} 按"恒回落"处理
     * @param container       容器执行后端；{@code null} 表示没有沙箱，命令跑在宿主上
     */
    public SandboxedExecBackend(ExecProperties properties, ExecBackend host, Set<String> environmentKeys,
                                String accountName, ExecApprovalPort approvals, ExecReviewer reviewer,
                                ExecBackend container) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.host = Objects.requireNonNull(host, "host");
        this.environmentKeys = environmentKeys == null ? Set.of() : Set.copyOf(environmentKeys);
        this.accountName = accountName;
        this.approvals = approvals == null ? ExecApprovalPort.denyAll() : approvals;
        this.reviewer = reviewer == null ? ExecReviewer.fallback() : reviewer;
        this.container = container;
    }

    /**
     * 同步执行一条命令：判定通过才交给执行后端。
     *
     * <p>起不来、超时都是"这条命令跑了但没跑成"，由库翻成 {@code ExecResult} 的对应状态；
     * 只有判定不通过才是拒绝。
     *
     * @param spec 库给出的命令、超时与本次覆盖的环境
     * @return 执行结果
     * @throws ExecViolationException 身份是 root、命令没通过白名单，或环境覆盖不合法
     */
    @Override
    public ExecResult run(ExecSpec spec) {
        authorize(spec);
        ExecBackend backend = target();
        return backend == host ? host.run(withValidatedOverrides(spec)) : backend.run(spec);
    }

    /**
     * 起一个后台 shell，由本类记账：{@code KillShell} 是模型的礼貌，不能当回收机制。
     *
     * <p>顺带清掉已经结束的句柄：它们不会自己从表里消失，留着只占一个位置。
     *
     * @param spec 库给出的命令与本次覆盖的环境
     * @return 后台句柄，由库转交给 {@code BashOutput} / {@code KillShell}
     * @throws ExecViolationException 身份是 root、命令没通过白名单，或环境覆盖不合法
     */
    @Override
    public ExecHandle start(ExecSpec spec) {
        authorize(spec);
        if (container != null) {
            return target().start(spec);
        }
        backgroundShells.removeIf(handle -> !handle.isAlive());
        ExecHandle handle = host.start(withValidatedOverrides(spec));
        backgroundShells.add(handle);
        return handle;
    }

    /**
     * 这一条命令跑在哪：宿主还是容器（票 06）。
     *
     * <p>四档的语义按 ADR-0030 裁决六，不是"有容器就进容器"：
     * <ul>
     *   <li>{@code auto}：有沙箱进沙箱，没有就落宿主；</li>
     *   <li>{@code sandbox}：必须进容器（没有容器时装配期就已经起不来了，见
     *       {@link ExecProperties#requireUsable()}）；</li>
     *   <li>{@code gateway}：本机。**沙箱开着时被拒**——那是"从沙箱提到宿主"，不是一条随时能用的
     *       逃生门；在沙箱里要出去得走 {@code elevated}（它自己还带审批那一层）。</li>
     *   <li>{@code node}：本仓没有多宿主，{@code requireUsable()} 已经挡在启动期。</li>
     * </ul>
     *
     * @return 这一条命令的执行后端
     * @throws ExecViolationException 沙箱开着却要求跑到宿主（{@code host=gateway}）
     */
    private ExecBackend target() {
        if (container == null) {
            return host;
        }
        // /elevated on|full：这一段要跑到沙箱外。它只在有沙箱时才有意义——
        // 没有沙箱时本来就在宿主上，这一档等于什么都没改。
        if (elevation() != Elevation.OFF) {
            return host;
        }
        return switch (properties.host()) {
            case AUTO, SANDBOX -> container;
            case GATEWAY -> throw new ExecViolationException("这条命令没有执行：claw4j.tools.exec.host=gateway"
                    + " 在沙箱会话里不成立（沙箱开着，命令只能进容器）。要跑在容器里就把 host 改成 auto；"
                    + "「从沙箱提到宿主」是 elevated 的事，不是 host 的一个取值。");
            case NODE -> throw new ExecViolationException("这条命令没有执行：host=node 没有实现（本仓没有多宿主）");
        };
    }

    /**
     * 强制结束所有还活着的后台 shell。
     *
     * <p>库只把句柄交给 {@code BashOutput} / {@code KillShell}，自己不留回收账；{@code KillShell}
     * 是模型的手动动作，不能当回收机制。所以本类必须自己收，否则 JVM 退出后留下没人杀的进程。
     */
    @Override
    public void destroy() {
        backgroundShells.forEach(ExecHandle::kill);
        backgroundShells.clear();
    }

    /**
     * 把本次的环境覆盖洗一遍再交给宿主：拒改 {@code PATH} / 加载器变量那条判据在
     * {@link ExecPolicy#environment} 里，不能因为换了执行后端就少判一次。
     *
     * <p>固定环境由后端的构造给定（整份替换掉，从零开始），这里只给覆盖——库是
     * "先放固定环境、再合并这次给的"，顺序与以前一致。
     *
     * @param spec 调用方给的命令、超时与环境覆盖
     * @return 覆盖已经洗过的同一份规格
     * @throws ExecViolationException 覆盖里出现不许改的键，或值为空
     */
    private static ExecSpec withValidatedOverrides(ExecSpec spec) {
        Map<String, String> overrides = ExecPolicy.environment(Map.of(), spec.env());
        return new ExecSpec(spec.command(), spec.timeoutMillis(), overrides);
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
        // /elevated full：这一段跳过审批。**只把 ASK 抬成 RUN**，DENY 不动——
        // security=deny 是"这个部署一条都不放"，那不是审批能改的东西。
        if (verdict == ExecPolicy.Verdict.ASK && elevation() == Elevation.FULL) {
            verdict = ExecPolicy.Verdict.RUN;
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
                environmentKeys, properties.settings().host().name(),
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

    /**
     * 这一段会话的提权档。没有上下文（不是从工具回调进来的）时按不提权。
     *
     * @return 提权档
     */
    private static Elevation elevation() {
        ExecCallContext context = ExecCallContext.current();
        return context == null ? Elevation.OFF : context.elevation();
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
                ? properties.workspace().toAbsolutePath().normalize()
                : Path.of(SandboxProperties.CONTAINER_WORKDIR);
    }

    private String allowText() {
        return properties.allow().isEmpty() ? "空，等于一条都不放行" : String.join("、", properties.allow());
    }
}

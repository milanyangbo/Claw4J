package io.github.claw4j.capability.exec;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.List;

/**
 * exec 的配置，键前缀 {@code claw4j.tools.exec}。
 *
 * <p><b>本体是三个正交字段</b>：{@code security}（放行到什么程度）、{@code ask}（什么时候问人）、
 * {@code autoReview}（未命中时先给复核器看一眼）。{@code mode} 只是它们的预设，照 OpenClaw 的
 * {@code resolveExecPolicyForMode} 展开——写成 {@code mode: ask} 与写成
 * {@code security: allowlist, ask: on-miss} 是同一件事，后者更清楚，前者更短。
 *
 * <p>两者同时出现时**逐字段覆盖**：{@code mode} 先整体展开，显式写了的 {@code security} /
 * {@code ask} 再盖上去。这样"用预设打底、只改一处"才写得出来——否则想从 {@code full} 收到
 * "每条都问"就得把三个字段全写一遍。
 *
 * <p>{@code mode} 默认 {@link Mode#FULL}，与它的 baseline 一致（*An unconfigured node uses the
 * same {@code full} / {@code off} baseline as the Gateway*，{@code docs/tools/exec-approvals.md:31}）。
 *
 * <p>安全位不在 {@code mode} 上：工具可见性由档位与各层策略决定，"要不要人批"由本类的三段式在
 * 执行那一刻算（ADR-0030）。
 *
 * <p>没有超时配置：超时语义在库的 {@code ExecSpec} 里（不给就 2 分钟，上限 10 分钟），不再多一层
 * 自己的默认值与上限——两个默认值管一件事，模型看到的和实际生效的迟早不一样。
 *
 * @param mode             预设，见 {@link Mode}；显式字段优先于它
 * @param security         放行到什么程度，见 {@link Security}；{@code null} 表示取 {@code mode} 的展开值
 * @param ask              什么时候问人，见 {@link Ask}；{@code null} 同上
 * @param autoReview       未命中时是否先问复核器；{@code null} 同上
 * @param host             在哪跑，见 {@link Host}；只解析与传递，真正切执行位置等容器化
 * @param strictInlineEval 开着时解释器内联求值（{@code python -c} 这类）一律走审批
 * @param askFallback      等不到人时按哪一档处置，见 {@link AskFallback}；默认拒
 * @param workspace        命令只在它里面跑；默认是进程的工作目录
 * @param allow            命令白名单：整条命令等于某一项、或以"某一项 + 空格"开头，且命令里不许有 shell 控制符
 */
@ConfigurationProperties("claw4j.tools.exec")
public record ExecProperties(Mode mode,
                             Security security,
                             Ask ask,
                             Boolean autoReview,
                             Host host,
                             Boolean strictInlineEval,
                             AskFallback askFallback,
                             Path workspace,
                             List<String> allow) {

    /**
     * 五档预设。照 OpenClaw 那一组，**不是阶梯**——它们各自是三个字段的一组取值，
     * 彼此没有包含关系（{@code full} 不包含 {@code ask}，两者的 {@code security} 不同）。
     */
    public enum Mode {

        /** 不装配这个工具。 */
        DENY,

        /** 只跑白名单里的命令，未命中直接拒（不问人）。 */
        ALLOWLIST,

        /** 白名单命中直接跑，未命中问人。 */
        ASK,

        /**
         * 与 {@link #ASK} 展开完全相同，只多一个 {@code autoReview: true}：
         * 未命中先给复核器看一眼，复核器说"低风险"才放行一次。
         *
         * <p>没有复核实现时它**与 {@code ASK} 行为完全一致**（回落人工），这是有意的——
         * 它的默认复核器本来就直接返回 {@code ask}（*Auto mode must never become a static allowlist*）。
         */
        AUTO,

        /** 不判白名单、不问人，与它的 baseline 同档。 */
        FULL
    }

    /** 放行到什么程度。 */
    public enum Security {

        /** 一律不跑。 */
        DENY,

        /** 只跑白名单命中、或未命中时经人批准的那些（问不问由 {@link Ask} 决定）。 */
        ALLOWLIST,

        /** 不看白名单。 */
        FULL
    }

    /** 什么时候问人。 */
    public enum Ask {

        /** 从不问。 */
        OFF,

        /** 未命中白名单才问。{@code security=full} 时没有"未命中"，等于不问。 */
        ON_MISS,

        /** 每条都问。**它盖过 {@code security=full}**：{@code full} + {@code always} 仍然逐条弹。 */
        ALWAYS
    }

    /** 在哪跑。与沙箱（容器/工作区）正交：沙箱管"能碰什么"，这里管"在哪台机器上跑"。 */
    public enum Host {

        /** 由部署决定（当前只有宿主一种）。 */
        AUTO,

        /** 容器内。 */
        SANDBOX,

        /** 控制面所在机器。 */
        GATEWAY,

        /** 指定节点。 */
        NODE
    }

    /**
     * 等不到人时按哪一档处置。照 OpenClaw 的 {@code askFallback}，默认 {@link #DENY}。
     *
     * <p>"等不到人"在我们这里每天都会发生：审批靠在卡片或接口上点，而这一轮不会原地等。所以它不是
     * 异常分支，是主路径——默认值必须是最紧的那一档，否则一次没人看见的审批就等于一次放行。
     *
     * <p>兜底档还要被本次生效的 {@code security} 夹住：{@code allowlist} 档下写
     * {@code askFallback: full} 等于用一个配置项把硬边界拆了，那正是夹取要挡的。
     */
    public enum AskFallback {

        /** 一律不跑。默认。 */
        DENY,

        /** 只跑白名单里的（未命中就是要问人的那一类，所以实际等同 DENY）。 */
        ALLOWLIST,

        /** 放行一次。 */
        FULL
    }

    public ExecProperties {
        mode = mode == null ? Mode.FULL : mode;
        host = host == null ? Host.AUTO : host;
        strictInlineEval = strictInlineEval != null && strictInlineEval;
        askFallback = askFallback == null ? AskFallback.DENY : askFallback;
        workspace = workspace == null ? Path.of(".") : workspace;
        allow = allow == null ? List.of() : List.copyOf(allow);
    }

    /**
     * 只配预设的那一档，其余取默认。测试与"只想写 mode"的调用方用它，免得摆九个参数。
     *
     * @param mode      预设
     * @param workspace 工作目录，可以为 {@code null}
     * @param allow     白名单，可以为 {@code null}
     * @return 配置对象
     */
    public static ExecProperties of(Mode mode, Path workspace, List<String> allow) {
        return new ExecProperties(mode, null, null, null, null, null, null, workspace, allow);
    }

    /**
     * 这个部署能不能按配置跑起来。启动期调一次，不通过就别起来。
     *
     * <p>今天只判 {@code host}，因为它是唯一一个"写进去不一定照做"的键：
     *
     * <ul>
     *   <li>{@link Host#AUTO} 与 {@link Host#GATEWAY}：都落在**这台机器**上。这个应用既是控制面
     *       也是执行面，两者是同一台机器——不是"大致等价"，是同一件事的两种叫法。</li>
     *   <li>{@link Host#SANDBOX}：要有容器后端，而容器化 2026-11-18 整体回滚（等上游给
     *       {@code DockerCliExecBackend.Builder} 加限额参数）。配了**启动失败**，不静默回落宿主——
     *       一个"以为进了容器"的部署，比一个知道自己没进容器的部署危险得多。</li>
     *   <li>{@link Host#NODE}：要多宿主与节点注册，本仓没有这个东西。</li>
     * </ul>
     *
     * @throws IllegalStateException 配置要求一个今天做不到的执行位置
     */
    public void requireUsable() {
        switch (host) {
            case SANDBOX -> throw new IllegalStateException("claw4j.tools.exec.host=sandbox 还没有落地："
                    + "容器化在 2026-11-18 整体回滚（库的 DockerCliExecBackend 没有限额入口，"
                    + "跑进去了也关不住），等上游加参数。把 host 改成 auto（或删掉这一行），"
                    + "命令会跑在宿主上——边界是 claw4j.tools.exec.workspace 加白名单与审批。");
            case NODE -> throw new IllegalStateException("claw4j.tools.exec.host=node 没有实现："
                    + "本仓只有一个执行位置，没有多宿主与节点注册。把 host 改成 auto 或 gateway。");
            case AUTO, GATEWAY -> {
                // 两种写法都落在本机，见上面的类注释。
            }
        }
    }

    /**
     * 生效策略：{@code mode} 展开成三段，显式配的字段逐字段盖上去。
     *
     * <p>"逐字段"而不是"整体替换"：想从 {@code full} 收到"每条都问"，写一个
     * {@code ask: always} 就够，不必把 {@code security} 也写一遍。
     *
     * @return 这次生效的三段式
     */
    public ExecPolicy.Settings settings() {
        ExecPolicy.Settings preset = ExecPolicy.expand(mode);
        return new ExecPolicy.Settings(
                security == null ? preset.security() : security,
                ask == null ? preset.ask() : ask,
                autoReview == null ? preset.autoReview() : autoReview,
                host,
                strictInlineEval,
                askFallback);
    }
}

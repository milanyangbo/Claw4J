package io.github.claw4j.capability.exec;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.List;

/**
 * exec 的配置，键前缀 {@code claw4j.tools.exec}。
 *
 * <p>{@code mode} 默认 {@link Mode#FULL}，与 OpenClaw 的 baseline 一致：它未配置时的报文是
 * "An unconfigured node uses the same {@code full} / {@code off} baseline as the Gateway"
 * （{@code docs/tools/exec-approvals.md:31}）。它的推荐值是 {@code auto}
 * （{@code docs/tools/permission-modes.md:18}），而 {@code auto} 需要原生复核器——这里没有，
 * 所以不提供那一档。要更严就配 {@code allowlist} 或 {@code ask}。
 *
 * <p>安全位不在 {@code mode} 上，在授权表上：{@code exec_command} 这个动作默认只给 {@code admin}。
 * 放宽默认档不等于"人人能跑命令"，没这个动作的身份连工具都调不到（ADR-0007）。
 *
 * <p>没有超时配置：超时语义在库的 {@code ExecSpec} 里（不给就 2 分钟，上限 10 分钟），不再多一层
 * 自己的默认值与上限——两个默认值管一件事，模型看到的和实际生效的迟早不一样。
 *
 * @param mode      四档，见 {@link Mode}
 * @param workspace 命令只在它里面跑；默认是进程的工作目录
 * @param allow     命令白名单：整条命令等于某一项、或以"某一项 + 空格"开头，且命令里不许有 shell 控制符
 */
@ConfigurationProperties("claw4j.tools.exec")
public record ExecProperties(Mode mode, Path workspace, List<String> allow) {

    public enum Mode {

        /** 不装配这个工具。 */
        DENY,

        /** 只跑白名单里的命令，未命中直接拒（不问人）。 */
        ALLOWLIST,

        /**
         * 白名单命中也要人先批，未命中直接拒。OpenClaw 的 {@code ask} 是"命中直接跑、未命中问人"
         * （{@code docs/tools/permission-modes.md:46}），这里少了后半条，见票 01。
         */
        ASK,

        /** 不判白名单、不批，与 OpenClaw 的 baseline 同档。 */
        FULL
    }

    public ExecProperties {
        mode = mode == null ? Mode.FULL : mode;
        workspace = workspace == null ? Path.of(".") : workspace;
        allow = allow == null ? List.of() : List.copyOf(allow);
    }
}

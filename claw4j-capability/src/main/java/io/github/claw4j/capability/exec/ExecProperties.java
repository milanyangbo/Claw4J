package io.github.claw4j.capability.exec;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * {@code exec} 的配置。键前缀 {@code claw4j.tools.exec}。
 *
 * <p><b>{@code mode} 默认 {@link Mode#FULL}</b>，照 OpenClaw 的 baseline：
 * 它未配置时报文写的是"An unconfigured node uses the same {@code full} / {@code off} baseline as the Gateway"。
 * 它的**推荐值**是 {@code auto}，但 {@code auto} 需要一个原生复核器——我们没有，所以不提供那一档；
 * 要更严就配 {@code allowlist} 或 {@code ask}。
 *
 * <p><b>安全位不在 {@code mode} 上，在授权表上</b>：{@code exec_command} 这个动作默认只给 {@code admin}。
 * 把默认档放宽不等于"人人能跑命令"，因为没这个动作的身份连工具都调不到（ADR-0007）。
 *
 * <p><b>没有 {@code enabled}</b>：{@code mode=deny} 就是不装配。两个开关管同一件事，
 * 迟早出现"enabled=true 但 mode=deny"要人猜。
 *
 * @param mode           四档，见 {@link Mode}
 * @param workspace      命令只能在它里面跑；相对 {@code workdir} 也从它算。默认是进程的工作目录——
 *                       照它 {@code workdir} 默认 {@code cwd} 的做法，不额外要求建一个目录
 * @param allow          命令白名单：整条命令等于某一项、或以"某一项 + 空格"开头，且命令里不许有 shell 控制符
 * @param defaultTimeout 调用方没给 timeout 时用它
 * @param maxTimeout     上限，超过直接拒（不夹取）
 */
@ConfigurationProperties("claw4j.tools.exec")
public record ExecProperties(Mode mode, Path workspace, List<String> allow,
                             Duration defaultTimeout, Duration maxTimeout) {

    public enum Mode {

        /** 不装配这个工具。 */
        DENY,

        /** 只跑白名单里的命令，未命中直接拒（不问人）。 */
        ALLOWLIST,

        /** 白名单命中也要人先批，未命中直接拒。它的 {@code ask} 是"未命中问人"，我们这版做不到，见票 01。 */
        ASK,

        /** 不判白名单、不批。它的 baseline。 */
        FULL
    }

    public ExecProperties {
        mode = mode == null ? Mode.FULL : mode;
        workspace = workspace == null ? Path.of(".") : workspace;
        allow = allow == null ? List.of() : List.copyOf(allow);
        defaultTimeout = defaultTimeout == null ? Duration.ofSeconds(10) : defaultTimeout;
        maxTimeout = maxTimeout == null ? Duration.ofSeconds(30) : maxTimeout;
    }
}

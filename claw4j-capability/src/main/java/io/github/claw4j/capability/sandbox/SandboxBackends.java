package io.github.claw4j.capability.sandbox;

import org.springaicommunity.agent.common.exec.ExecBackend;
import org.springaicommunity.agent.exec.docker.DockerCliExecBackend;

import java.nio.file.Path;
import java.util.Objects;

/**
 * exec 侧的容器后端：{@code mode=off} 时返回 {@code null}，表示继续跑在宿主上。
 *
 * <p>2026-11-18 之前这里还有"脚本侧的执行后端工厂"。脚本层退化成技能之后（目录 + {@code SKILL.md}，
 * 由模型用 {@code Bash} 去跑），脚本侧不再有容器后端，这一处只剩 exec 一个判断点。
 *
 * <p>{@code build()} 会真的把托管容器建起来并在起不来时 fail-fast，所以这一处<b>就是</b>可用性探测，
 * 不必再单独探一次。
 *
 * <p>挂载只支持读写：库的 {@code mount(Path, String)} 没有只读选项。所以
 * {@code workspace-access=ro} 在 exec 侧不生效——装配处会打一条 WARN 说出来，不静默
 * （只读要等 P17b 的限额那一层，而那一层 blocked 在上游）。没有 {@code workspace-access=none}
 * 的对应写法：不挂载的话模型看不见工作区，exec 也就没有意义。
 */
public final class SandboxBackends {

    private SandboxBackends() {
    }

    /**
     * exec 侧的容器后端：{@code mode=off} 时返回 {@code null}，表示继续跑在宿主上。
     *
     * @param properties {@code claw4j.tools.sandbox} 的配置
     * @param workspace  宿主上的工作区，挂进容器的 {@value SandboxProperties#CONTAINER_WORKDIR}
     * @return 容器后端；{@code mode=off} 时为 {@code null}
     */
    public static ExecBackend execContainer(SandboxProperties properties, Path workspace) {
        Objects.requireNonNull(properties, "properties");
        properties.requireUsable();
        if (!properties.containerized()) {
            return null;
        }
        Objects.requireNonNull(workspace, "workspace");
        return DockerCliExecBackend.builder()
                .dockerCommand(properties.dockerBinary())
                .image(properties.image())
                .mount(workspace.toAbsolutePath().normalize(), SandboxProperties.CONTAINER_WORKDIR)
                .containerWorkingDirectory(SandboxProperties.CONTAINER_WORKDIR)
                .build();
    }
}

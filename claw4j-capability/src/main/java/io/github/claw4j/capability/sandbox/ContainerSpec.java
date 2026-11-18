package io.github.claw4j.capability.sandbox;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 一次容器启动的全部参数：镜像、宿主要挂进去的目录、挂载方式、容器里那条命令。
 *
 * <p>单独一个 record 而不是在两条执行路径里各拼一份 argv：脚本层与 exec 层各拼一次的话，
 * 加第五条限额（P17b 的 {@code --network none}、内存上限、非 root）时必然只改一边，
 * 结果是"脚本被锁了、{@code Bash} 没锁"——**只在一条路上存在的边界比没有更坏**，
 * 因为它会被当成已经锁好了。
 *
 * <p>argv 是数组，不是一条命令字符串：{@code docker run} 的每个参数各占一个元素，
 * 于是宿主的 {@code /bin/sh} 从不参与解释（ADR-0002）。容器里那条命令要不要 shell 语义
 * 由调用方决定，它只是数组末尾的<strong>一个元素</strong>。
 *
 * @param image     镜像名
 * @param access    工作目录怎么挂
 * @param hostRoot  挂进容器的宿主目录；必须存在且是绝对路径
 */
public record ContainerSpec(String image, SandboxProperties.WorkspaceAccess access, Path hostRoot) {

    public ContainerSpec {
        Objects.requireNonNull(image, "image");
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(hostRoot, "hostRoot");
        if (!hostRoot.isAbsolute()) {
            throw new IllegalArgumentException("挂进容器前的宿主目录必须是绝对路径，拿到的是 " + hostRoot
                    + "：docker 的 --volume 不解析相对路径，传进去只会得到一个同名但空的卷");
        }
    }

    /**
     * 按配置与宿主目录建一份规格。
     *
     * @param properties {@code claw4j.tools.sandbox} 的配置
     * @param hostRoot   这次要挂进容器的宿主目录
     * @return 容器规格
     */
    public static ContainerSpec of(SandboxProperties properties, Path hostRoot) {
        return new ContainerSpec(properties.image(), properties.workspaceAccess(), hostRoot.toAbsolutePath().normalize());
    }

    /**
     * 把要执行的 argv 包成 {@code docker run} 的完整参数（不含 docker 这个程序本身）。
     *
     * <p>选项顺序固定：{@code --rm -i}（跑完就删、接 stdin 以便脚本读管道）、{@code --workdir}、
     * {@code --volume}、{@code --env}…、镜像，最后是里面那条命令。
     *
     * @param inner       容器里要执行的 argv，第一项是程序
     * @param environment 容器里能看到的全部环境变量；空表就不传 {@code --env}
     * @return 交给 docker CLI 的参数
     */
    public List<String> argv(List<String> inner, Map<String, String> environment) {
        Objects.requireNonNull(inner, "inner");
        Objects.requireNonNull(environment, "environment");
        if (inner.isEmpty()) {
            throw new IllegalArgumentException("容器里要执行的 argv 不能为空");
        }
        List<String> argv = new ArrayList<>(List.of("run", "--rm", "-i"));
        argv.add("--workdir");
        argv.add(workdir());
        if (access != SandboxProperties.WorkspaceAccess.NONE) {
            argv.add("--volume");
            argv.add(hostRoot + ":" + SandboxProperties.CONTAINER_WORKDIR
                    + (access == SandboxProperties.WorkspaceAccess.RO ? ":ro" : ":rw"));
        }
        // 环境逐个 --env 传，不继承宿主：继承是 exec 那条路径今天的行为，而"容器里看不到宿主的环境"
        // 正是这一票要验的事之一。顺序取有序表，断言与日志里读起来是稳定的。
        new LinkedHashMap<>(environment).forEach((key, value) -> {
            argv.add("--env");
            argv.add(key + "=" + value);
        });
        argv.add(image);
        argv.addAll(inner);
        return List.copyOf(argv);
    }

    /**
     * 容器里的工作目录。
     *
     * @return 挂了目录时是 {@value io.github.claw4j.capability.sandbox.SandboxProperties#CONTAINER_WORKDIR}，
     *     不挂时是容器根
     */
    public String workdir() {
        return access == SandboxProperties.WorkspaceAccess.NONE
                ? "/"
                : SandboxProperties.CONTAINER_WORKDIR;
    }

    /**
     * 把一个宿主上的绝对路径翻成容器里的路径。
     *
     * <p>只翻落在 {@link #hostRoot} 里的那些：脚本包的入口是绝对路径（不经解释器就没人去 PATH 找），
     * 而它在容器里叫 {@code /workspace/run.sh}。落在之外的路径不翻也不拒——那类参数在
     * {@code SandboxPolicy#resolveInside} 那一步就已经被挡掉了，这里再判一次等于两处各自解释同一条边界。
     *
     * @param hostPath 宿主上的路径
     * @return 容器里的路径；不在 {@link #hostRoot} 下时原样返回
     */
    public String containerPath(Path hostPath) {
        Path normalized = hostPath.toAbsolutePath().normalize();
        if (access == SandboxProperties.WorkspaceAccess.NONE || !normalized.startsWith(hostRoot)) {
            return hostPath.toString();
        }
        Path relative = hostRoot.relativize(normalized);
        return relative.toString().isEmpty()
                ? SandboxProperties.CONTAINER_WORKDIR
                : SandboxProperties.CONTAINER_WORKDIR + "/" + relative;
    }
}

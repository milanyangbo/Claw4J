package io.github.claw4j.capability.sandbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 沙箱的配置，键前缀 {@code claw4j.tools.sandbox}，取值照 ADR-0030 裁决六。
 *
 * <p>它回答的是<b>在哪跑</b>，与"准不准跑"（工具清单、审批）正交。两者混在一起时，
 * 想收紧一边就不得不动另一边，于是"把命令关进容器"与"给这个人少开一个工具"成了同一件事。
 *
 * <p>{@code mode} 默认 {@link Mode#OFF}：**默认零变化**。没有这一条，升级一次应用就把所有人的
 * 命令搬进一个没有网络的容器里，失败会以"某个脚本忽然跑不通"的形式出现。
 *
 * @param mode            要不要进容器，见 {@link Mode}；默认关
 * @param backend         用哪种容器，见 {@link Backend}；现在只实现了 docker
 * @param workspaceAccess 工作目录怎么给容器，见 {@link WorkspaceAccess}；默认只读
 * @param image           容器镜像；默认 {@value #DEFAULT_IMAGE}
 * @param dockerBinary    docker CLI 的名字或绝对路径；默认 {@value #DEFAULT_DOCKER_BINARY}
 */
@ConfigurationProperties("claw4j.tools.sandbox")
public record SandboxProperties(Mode mode,
                                Backend backend,
                                WorkspaceAccess workspaceAccess,
                                String image,
                                String dockerBinary) {

    /** 默认镜像：小、带 {@code sh}。换镜像不该改代码，所以它只是个默认值。 */
    public static final String DEFAULT_IMAGE = "alpine:3";

    /** 默认的 docker CLI 名字：解析成绝对路径是在启动期做的事（见 {@link DockerCli#resolve}）。 */
    public static final String DEFAULT_DOCKER_BINARY = "docker";

    /** 容器里的工作目录。挂在 {@code --workdir} 上，与宿主路径无关。 */
    public static final String CONTAINER_WORKDIR = "/workspace";

    /**
     * 要不要进容器。
     *
     * <p>{@link #NON_MAIN} 是"主对话留在宿主、其余进容器"。它需要知道"这一段算不算主对话"，
     * 而那个问题在脚本层与 exec 层都答不出来（那两层只认一个包、一条命令）。所以它是
     * **判定函数已经写好、装配还没接上**的那一档：配了它启动即失败，而不是悄悄退化成 {@link #ALL}。
     */
    public enum Mode {

        /** 不进容器，与接入这一层之前逐字节一致。默认。 */
        OFF,

        /** 主对话之外的会话进容器。 */
        NON_MAIN,

        /** 全部进容器。 */
        ALL
    }

    /** 用哪种容器。{@link #SSH} 只认值不实现：留一个"配了不生效"的取值比不支持更坏。 */
    public enum Backend {

        /** 本机的 docker 守护进程。 */
        DOCKER,

        /** 远端机器。**未实现**，配了启动即失败。 */
        SSH
    }

    /** 工作目录怎么给容器。 */
    public enum WorkspaceAccess {

        /** 只读挂载：容器能读包/工作区，写不回去。默认。 */
        RO,

        /** 读写挂载。 */
        RW,

        /** 不挂。脚本包用不了这一档（入口就在包里），exec 用得到。 */
        NONE
    }

    public SandboxProperties {
        mode = mode == null ? Mode.OFF : mode;
        backend = backend == null ? Backend.DOCKER : backend;
        workspaceAccess = workspaceAccess == null ? WorkspaceAccess.RO : workspaceAccess;
        image = image == null || image.isBlank() ? DEFAULT_IMAGE : image;
        dockerBinary = dockerBinary == null || dockerBinary.isBlank() ? DEFAULT_DOCKER_BINARY : dockerBinary;
    }

    /**
     * 什么都不配的那一份：全关。
     *
     * @return 默认配置
     */
    public static SandboxProperties defaults() {
        return new SandboxProperties(null, null, null, null, null);
    }

    /**
     * 这一段配置能不能真的生效。启动期调一次，不通过就别起来。
     *
     * <p>两条都按"配了却做不到 → 起不来"处理：{@code backend=ssh} 没有实现，{@code mode=non-main}
     * 还没有接上"这一段算不算主对话"。把它们当默认值忽略掉，正是这批票一直在删的那种失效。
     *
     * @throws SandboxUnavailableException 配了做不到的组合
     */
    public void requireUsable() {
        if (mode == Mode.OFF) {
            return;
        }
        if (backend == Backend.SSH) {
            throw new SandboxUnavailableException("claw4j.tools.sandbox.backend=ssh 还没有实现："
                    + "现在只有 docker 一种后端。把 backend 改成 docker，或者把 mode 关掉（claw4j.tools.sandbox.mode=off）。");
        }
        if (mode == Mode.NON_MAIN) {
            throw new SandboxUnavailableException("claw4j.tools.sandbox.mode=non-main 还没有接上："
                    + "「这一段算不算主对话」要在工具清单那一层判（票 06），脚本层与 exec 层答不出来。"
                    + "现在要么写 all（全部进容器），要么写 off（都不进）。");
        }
    }

    /**
     * 沙箱开没开。装配用它决定要不要探一次 docker，而不是看 {@code backend}——
     * {@code mode=off} 时 {@code backend} 没有意义，那是默认组合。
     *
     * @return {@code mode} 不是 {@link Mode#OFF}
     */
    public boolean containerized() {
        return mode != Mode.OFF;
    }
}

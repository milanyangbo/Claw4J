package io.github.claw4j.capability.sandbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 沙箱的配置，键前缀 {@code claw4j.tools.sandbox}，取值照 ADR-0030 裁决六。
 *
 * <p>它回答的是<b>在哪跑</b>，与"准不准跑"（工具清单、审批）正交。两者混在一起时，
 * 想收紧一边就不得不动另一边，于是"把命令关进容器"与"给这个人少开一个工具"成了同一件事。
 *
 * <p>{@code mode} 默认 {@link Mode#OFF}：<b>默认零变化</b>。没有这一条，升级一次应用就把所有人的
 * 命令搬进一个没有网络的容器里，失败会以"某个脚本忽然跑不通"的形式出现。
 *
 * @param mode            要不要进容器，见 {@link Mode}；默认关
 * @param backend         用哪种容器，见 {@link Backend}；现在只实现了 docker
 * @param workspaceAccess 工作目录怎么给容器，见 {@link WorkspaceAccess}；默认只读
 * @param image           容器镜像；默认 {@value #DEFAULT_IMAGE}
 * @param dockerBinary    docker CLI 的名字或绝对路径；默认 {@value #DEFAULT_DOCKER_BINARY}
 * @param tools           沙箱会话里的工具白/黑名单（第 9 层，票 06）；没配就是"不收窄"
 */
@ConfigurationProperties("claw4j.tools.sandbox")
public record SandboxProperties(Mode mode,
                                Backend backend,
                                WorkspaceAccess workspaceAccess,
                                String image,
                                String dockerBinary,
                                Tools tools) {

    /** 默认镜像：小、带 {@code sh}。换镜像不该改代码，所以它只是个默认值。 */
    public static final String DEFAULT_IMAGE = "alpine:3";

    /**
     * 默认的 docker CLI 名字。
     *
     * <p>本仓不解析它，也不自己拼 {@code docker run}：装配时原样交给库的
     * {@code DockerCliExecBackend.Builder#dockerCommand}，认不认得出这个名字由库决定
     * （见 {@link SandboxBackends#execContainer}）。
     */
    public static final String DEFAULT_DOCKER_BINARY = "docker";

    /** 容器里的工作目录。挂在 {@code --workdir} 上，与宿主路径无关。 */
    public static final String CONTAINER_WORKDIR = "/workspace";

    /**
     * 要不要进容器。
     *
     * <p>{@link #NON_MAIN} 是"主对话留在宿主、其余进容器"。它要先回答"这一段算不算主对话"，
     * 而沙箱这一层只认一条命令、拿不到会话键。所以它是<b>定了取值、装配还没接上</b>的那一档：
     * 配了它启动即失败，而不是悄悄退化成 {@link #ALL}。
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

        /** 远端机器。<b>未实现</b>，配了启动即失败。 */
        SSH
    }

    /**
     * 沙箱会话里的工具策略（第 9 层，票 06）。
     *
     * <p>照 OpenClaw 的 {@code tools.sandbox.tools}：<b>只在沙箱会话里生效</b>，非沙箱会话不看它。
     * 三张表的语义与 {@code claw4j.tools.*} 那份完全一致（deny 先判、allow 空则全放），
     * 因为它是同一套管线的第 9 层，不是另一套规则。
     *
     * <p>{@code bundle-mcp} 是组名而不是工具名：它代表"MCP 提供的那批工具"。**不写它，沙箱会话里
     * 就看不见任何 MCP 工具**——即使 MCP server 连上了、画像也登记了。这条是照它文档里那句
     * "沙箱会话里 MCP/插件工具是额外闸门"来的：外部工具的副作用在沙箱里更难观察，所以默认关上，
     * 要开就显式写 {@code bundle-mcp}。
     *
     * @param allow     白名单；空表示不收窄（但 MCP 仍要 {@code bundle-mcp} 才可见）
     * @param deny      黑名单；先判
     * @param alsoAllow 与 allow 合并的显式放宽
     */
    public record Tools(List<String> allow, List<String> deny, List<String> alsoAllow) {

        /** 代表"MCP 那批工具"的组名。 */
        public static final String MCP_BUNDLE = "bundle-mcp";

        public Tools {
            allow = allow == null ? List.of() : List.copyOf(allow);
            deny = deny == null ? List.of() : List.copyOf(deny);
            alsoAllow = alsoAllow == null ? List.of() : List.copyOf(alsoAllow);
        }

        /** 三张表都空：沙箱会话与普通会话看到同一份清单。 */
        public boolean isEmpty() {
            return allow.isEmpty() && deny.isEmpty() && alsoAllow.isEmpty();
        }
    }

    /** 工作目录怎么给容器。 */
    public enum WorkspaceAccess {

        /** 只读挂载：容器能读包/工作区，写不回去。默认。 */
        RO,

        /** 读写挂载。 */
        RW,

        /** 不挂。exec 用得到（模型看不见工作区，这条命令基本没意义）。 */
        NONE
    }

    public SandboxProperties {
        mode = mode == null ? Mode.OFF : mode;
        backend = backend == null ? Backend.DOCKER : backend;
        workspaceAccess = workspaceAccess == null ? WorkspaceAccess.RO : workspaceAccess;
        image = image == null || image.isBlank() ? DEFAULT_IMAGE : image;
        dockerBinary = dockerBinary == null || dockerBinary.isBlank() ? DEFAULT_DOCKER_BINARY : dockerBinary;
        tools = tools == null ? new Tools(null, null, null) : tools;
    }

    /**
     * 什么都不配的那一份：全关。
     *
     * @return 默认配置
     */
    public static SandboxProperties defaults() {
        return new SandboxProperties(null, null, null, null, null, null);
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
                    + "「这一段算不算主对话」要按会话键判，沙箱这一层拿不到它（票 06 的缺口）。"
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

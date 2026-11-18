# P17b: 四条限额——断网、只读根、内存与 pid 上限、非 root

**What to build:** 给容器加回 ADR-0020 点名的那四条，也就是"有隔离"和"有约束"的实际差别。

P17a 把命令搬进了容器，但一个默认参数的 `docker run` 仍然是：能出网、能写根目录、能吃满宿主内存。
这一票把这四样关掉，并且**让 exec 与脚本两条路径共用同一份容器规格**。

四条：

- `network: none` —— 容器里出不去。脚本体内自己 `curl` 一个公网地址必须失败。
- **只读根 FS** + 一个可写的临时 workdir —— 写 `/` 失败，写 workdir 成功，进程退出后 workdir 不残留。
- **内存与 pid 上限** —— 超了被杀掉，而不是把宿主拖垮。`--memory` 与 `--pids-limit` 是硬上限，
  不是"建议"。
- **非 root 用户** —— 镜像的 `USER` 与 `--user` 二选一，判据是容器内 `id -u` 不为 0。
  今天 `SandboxPolicy#requireNonRoot` 只能"拒绝以 root 起进程"，管不到容器里跑成什么身份。

一条结构判断：**容器规格只写一次。** 两条路径（`ProcessRunnerFactory` 那条、`ExecBackend` 那条）
各拼一份 argv 的话，加第五条限额时必然只改一边，而漏掉的表现为"脚本被锁了、`Bash` 没有"——
一个只在其中一条路上存在的边界，比没有边界更坏，因为它会被人当成已经有了。

**Blocked by:** 上游 `spring-ai-agent-utils` 给 `DockerCliExecBackend.Builder` 加限额入口。
**P17a 已于 2026-11-18 整体回滚**（`tools.sandbox.*`、容器后端、`-Pdocker` 一起删了），
所以回来时顺序是：先重做 P17a（命令真的进容器），再接本票的限额。别把回滚当成"前置已完成"。

**Type:** task

**Status:** blocked（2026-11-18 裁决：等库加参数）

**裁决来源：** ADR-0020 §M9 · P17（`network=none`、只读根 FS、内存/CPU 限额、临时 workdir）；
ADR-0021 §"追平 2：执行隔离"。

## Answer

**等上游，不自建。** 2026-11-18 用户裁决，三选一里选了中间那条。

事实（`javap -p` 读 `0.13.0` 的 `DockerCliExecBackend$Builder`）：builder 只有
`dockerCommand` / `containerId` / `image` / `mount` / `containerWorkingDirectory` /
`environment` / `shellCommand`，**没有任何限额入口**。`containerId(...)` 那条路能接一个
外部建好的容器（带限额），但那样限额由 compose/k8s 写、不在本仓配置里，`SandboxProperties`
就变成一半生效一半在别处——正是本仓最忌讳的那种偏差。

上游要补的七项（按 ADR-0020 点名的四条拆开）：

| 参数 | 对应 `docker run` | 哪个判据要它 |
| --- | --- | --- |
| `network` | `--network`（默认给 `none`） | 断网 |
| `readOnlyRootfs` | `--read-only` | 只读根 |
| `tmpfs` / `workdirSize` | `--tmpfs /tmp:size=…` | 可写临时 workdir |
| `memory` | `--memory` | 内存上限 |
| `pidsLimit` | `--pids-limit` | pid 上限 |
| `user` | `--user` | 非 root |
| `cpus` | `--cpus` | CPU（不列入验收，macOS 上不保证） |

回来时改哪儿：exec 侧只动 `SandboxBackends.execContainer(...)`（把七项映射到 builder）；
脚本侧仍是 `ContainerSpec` 出 argv——那份是 argv 形态，库里给不了（`ExecSpec` 只有字符串），
所以"容器规格只写一次"这条结构判断到时候要重新答一次：要么两边参数各写一份并加一条
"逐字段相等"的单测（票里已有 `#bothPathsShareOneContainerSpec`），要么脚本层也换到库。

**若上游长期不动**：唯一能一次解决"argv + 四条限额"的高质量件是 `docker-java`（3.2k★，活跃，
`execCreateCmd.withCmd(List<String>)` 是 argv、`HostConfig` 有 Memory/PidsLimit/NetworkMode/
ReadonlyRootfs/User）。那时要连 exec 侧一起换，避免两套 docker 访问方式。记在这里，不开票。

## 要动的地方（预计，只剩脚本侧）

| 位置 | 改什么 |
| --- | --- |
| `ContainerSpec`（值对象） | 补网络、只读根、内存、pid、用户、cpus；脚本侧 argv 从这里出 |
| `DockerSandboxRunner` | 不自己拼参数（现在已经不拼，只透传） |
| exec 侧 | **等上游**，本票不动 |
| 新 `SandboxLimitsProperties`（或并入 `SandboxProperties`） | 六项限额；给默认值并写明"默认是上限不是建议" |

## TDD 用例清单

| 判什么行为 | 用例名 | 先红的样子 |
| --- | --- | --- |
| 容器里出不去网 | `ContainerLimitsTest#noEgressFromContainer`（`@Tag("docker")`） | 今天同网络，`curl` 通 |
| 根 FS 只读：写 `/tmp/x` 之外的地方失败 | `#rootFilesystemIsReadOnly` | 今天同文件系统，随便写 |
| workdir 可写，进程退出后不残留 | `#workdirIsWritableAndEphemeral` | 今天写在宿主目录里，留着 |
| 超内存被 OOM 杀掉，退出码非 0 且宿主存活 | `#memoryLimitKillsTheContainer` | 今天无限额，能吃满宿主 |
| 容器内 `id -u` 不为 0 | `#runsAsNonRoot` | 今天只拒绝宿主上的 root |
| 两条路径产出的容器规格逐字段相等 | `#bothPathsShareOneContainerSpec`（单测，假后端） | 无；防的是"脚本锁了、Bash 没锁"。**上游加参数之后才有意义，本票先挂 ⏳** |
| 第五条限额加上去只改一处 | 同上，断言两边都从 `ContainerSpec` 取 | 无。同上，`⏳` |

**标记：整张表未复核（票被上游卡住，还没开始）。** 收票时逐行改成 `✅` / `❌` / `⏳`，规则见 `AGENTS.md` 第 2 条。

## 验收判据

| 判什么 | 判据 |
| --- | --- |
| 断网 | 容器内 `curl --max-time 3 https://example.com` 非 0 退出；同一镜像在 `network=bridge` 下能通（证明是限额生效，不是镜像里没 `curl`） |
| 只读根 | `touch /probe` 失败并返回只读文件系统错误；`touch $WORKDIR/probe` 成功 |
| 内存 | `--memory 64m` 下跑一个吃 256m 的进程：容器非 0 退出、宿主内存不涨 |
| 非 root | `id -u` 输出非 0；进程属主与镜像 `USER` 一致 |
| 两条路径一致 | 同一个 `ContainerSpec` 分别经 `ProcessRunnerFactory` 与 `ExecBackend` 生成 argv，差异只有 argv 头部（**等上游**） |
| 默认不误伤 | 限额默认值下，现有的 `ExecToolTest` / `ScriptPackageTest` 全绿（它们走宿主，`mode=off`） |

## 缺口

- **CPU 限额在 macOS Docker Desktop 上不保证生效**（它只约束到 Linux VM 的配额）。所以判据只锚内存与
  pid；CPU 写进配置但不当验收条件，免得在开发机上得出一条永远不红也不真验的用例。
- **磁盘配额不做**：`--storage-opt size` 只在特定 storage driver 上有效，写了会在多数环境里静默无效
  —— 属于"配了不生效"，不做。
- **容器内的 `PATH` 与宿主不同**：今天脚本层给 PATH-only 环境、exec 路径继承环境，进了容器之后都由镜像
  决定。这是行为变化，要在 `SandboxPolicy` 的类注释里改口，别让后来的人以为还是宿主那套。

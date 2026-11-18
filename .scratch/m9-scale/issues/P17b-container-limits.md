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

**Blocked by:** P17a（没有容器后端就无处加限额）。

**Type:** task

**Status:** ready-for-agent

**裁决来源：** ADR-0020 §M9 · P17（`network=none`、只读根 FS、内存/CPU 限额、临时 workdir）；
ADR-0021 §"追平 2：执行隔离"。

## 要动的地方（预计）

| 位置 | 改什么 |
| --- | --- |
| 新 `ContainerSpec`（值对象） | 一次写全：镜像、网络、挂载、内存、pid、用户、workdir、只读根。两条路径都从这里取 |
| `DockerSandboxRunner` | 从 `ContainerSpec` 生成 argv，自己不拼参数 |
| `SandboxedExecBackend` 的容器分支 | 同样从 `ContainerSpec` 取，不另拼一份 |
| 新 `SandboxLimitsProperties`（或并入 `SandboxProperties`） | 内存、pid、CPU、用户、workdir 大小；给默认值并写明"默认是上限不是建议" |

## TDD 用例清单

| 判什么行为 | 用例名 | 先红的样子 |
| --- | --- | --- |
| 容器里出不去网 | `ContainerLimitsTest#noEgressFromContainer`（`@Tag("docker")`） | 今天同网络，`curl` 通 |
| 根 FS 只读：写 `/tmp/x` 之外的地方失败 | `#rootFilesystemIsReadOnly` | 今天同文件系统，随便写 |
| workdir 可写，进程退出后不残留 | `#workdirIsWritableAndEphemeral` | 今天写在宿主目录里，留着 |
| 超内存被 OOM 杀掉，退出码非 0 且宿主存活 | `#memoryLimitKillsTheContainer` | 今天无限额，能吃满宿主 |
| 容器内 `id -u` 不为 0 | `#runsAsNonRoot` | 今天只拒绝宿主上的 root |
| 两条路径产出的容器规格逐字段相等 | `#bothPathsShareOneContainerSpec`（单测，假后端） | 无；防的是"脚本锁了、Bash 没锁" |
| 第五条限额加上去只改一处 | 同上，断言两边都从 `ContainerSpec` 取 | 无 |

**标记：整张表未复核（票还没开始）。** 收票时逐行改成 `✅` / `❌` / `⏳`，规则见 `AGENTS.md` 第 2 条。

## 验收判据

| 判什么 | 判据 |
| --- | --- |
| 断网 | 容器内 `curl --max-time 3 https://example.com` 非 0 退出；同一镜像在 `network=bridge` 下能通（证明是限额生效，不是镜像里没 `curl`） |
| 只读根 | `touch /probe` 失败并返回只读文件系统错误；`touch $WORKDIR/probe` 成功 |
| 内存 | `--memory 64m` 下跑一个吃 256m 的进程：容器非 0 退出、宿主内存不涨 |
| 非 root | `id -u` 输出非 0；进程属主与镜像 `USER` 一致 |
| 两条路径一致 | 同一个 `ContainerSpec` 分别经 `ProcessRunnerFactory` 与 `ExecBackend` 生成 argv，差异只有 argv 头部 |
| 默认不误伤 | 限额默认值下，现有的 `ExecToolTest` / `ScriptPackageTest` 全绿（它们走宿主，`mode=off`） |

## 缺口

- **CPU 限额在 macOS Docker Desktop 上不保证生效**（它只约束到 Linux VM 的配额）。所以判据只锚内存与
  pid；CPU 写进配置但不当验收条件，免得在开发机上得出一条永远不红也不真验的用例。
- **磁盘配额不做**：`--storage-opt size` 只在特定 storage driver 上有效，写了会在多数环境里静默无效
  —— 属于"配了不生效"，不做。
- **容器内的 `PATH` 与宿主不同**：今天脚本层给 PATH-only 环境、exec 路径继承环境，进了容器之后都由镜像
  决定。这是行为变化，要在 `SandboxPolicy` 的类注释里改口，别让后来的人以为还是宿主那套。

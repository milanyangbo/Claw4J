# P17a: 沙箱开关与 docker 后端——命令真的跑在另一台容器里

**What to build:** 把"沙箱"从"四条约束"推进到"另一台容器"。今天 `SandboxPolicy` 只锁住四样：
PATH-only 环境、30 秒超时、非 root、工作目录不越界——脚本与宿主**同 OS 用户、同网络、同文件系统**，
这是 ADR-0021 里企业属性唯一明确 ✗ 的一条：**有约束，无隔离**。

这一票只做"能跑起来 + 开关 + 探测"，限额在 P17b，目录与生命周期在 P17c。

具体：

- 新增 `claw4j.tools.sandbox.*`：`mode`（`off` / `non-main` / `all`）、`backend`（`docker` / `ssh`）、
  `scope`（`session` / `agent` / `shared`）、`workspaceAccess`（`ro` / `rw` / `none`）。键名照 ADR-0030 裁决六。
- 两条执行路径都落到容器后端上，**各走各的接缝，不新开一条**：
  - 脚本包：`ProcessRunnerFactory.forPackage` 换成容器实现。调用方 `ScriptPackageRunner` 一行不动
    —— 那个 `@FunctionalInterface` 的注释里就写着"换容器、换远端执行器、换成测试里的记录器，改的都是这个实现"。
  - exec：`SandboxedExecBackend`（实现的是 Spring AI 的 `ExecBackend`）在容器模式下把命令交给容器。
- 本票只定义 `mode` 的判定函数（`sandboxed(principal, session)`），**不接第 9 层**
  —— `tools.sandbox.tools` 的白名单过滤是票 06 的事。

三条硬判断，写在实现之前：

1. **不许静默降级。** 配了 `backend=docker` 而探测失败（没有守护进程、镜像拉不到、权限不足），
   **启动失败并点名**，不是"悄悄回落到宿主"。降级的失效方式是静默的：用户以为有隔离，实际没有，
   而且要等到出事才知道。这跟 `SandboxPolicy` 选白名单而不是黑名单是同一个理由。
2. **容器后端必须借 `LocalProcessRunner` 起 docker CLI。** 全仓"只有 `LocalProcessRunner` 碰
   `ProcessBuilder`"这条有架构测试盯着；容器后端自己拼一个 `ProcessBuilder` 会当场撞红，
   也不该为它开一条例外。起 `docker run` 与起任何别的命令没有区别。
3. **镜像名走配置，可被环境变量覆盖。** 照 `compose.yaml` 里 `CLAW4J_POSTGRES_IMAGE` 的惯例
   —— 国内网络下换镜像站不该改代码。默认给一个小的、带 `sh` 的镜像；tag 不许写死在 Java 里。

**Blocked by:** 无。与权限那批票（01–09）没有依赖：它们管"准不准跑"，本票管"在哪跑"。

**Type:** task

**Status:** resolved（2026-11-18；中途回滚过一次，同日按裁决恢复——见文末）

**裁决来源：** ADR-0020 §M9 · P17（四条约束 → 容器级限额）；ADR-0030 §裁决六（`mode` / `backend` /
`scope` / `workspaceAccess` 四个键与取值）；ADR-0021 §"追平 2：执行隔离"。

## 要动的地方（预计）

| 位置 | 改什么 |
| --- | --- |
| 新 `SandboxProperties`（`claw4j-capability/**/script/` 或独立 `sandbox` 包） | 四个键与默认值；`mode=off` 时整块不生效 |
| 新 `DockerSandboxRunner implements ProcessRunner` | 把 `ProcessRequest` 翻成 `docker run` 的 argv，交给 `LocalProcessRunner` |
| `ProcessRunnerFactory` | 加一个 `DOCKER` 工厂（或改成按配置选）；`SANDBOXED` 保持不变 |
| `ScriptPackageAutoConfiguration` / `ExecAutoConfiguration` | 按 `backend` 选实现；`mode=off` 时行为与今天一致 |
| 新 `SandboxAvailabilityProbe` | 启动期探一次 `docker info` 与镜像是否存在，失败即启动失败并给可执行的修复提示 |
| `application.yml` | `claw4j.tools.sandbox` 整段注释 + 默认 `off` |
| `pom.xml` | surefire 的 `excludedGroups` 加 `docker`；照 `real-cli` 那份 profile 加一个放开它的 profile |

## TDD 用例清单

| 判什么行为 | 用例名 | 先红的样子 |
| --- | --- | --- |
| `mode=off` 时走宿主，行为与今天逐字节一致 | `SandboxTest#offKeepsRunningOnHost` ✅ | 现在没有 `mode`，恒走宿主；这条是"默认不改变任何现有行为"的守卫 |
| `mode=all` 时判定为沙箱会话，`non-main` 时主会话不算 | `SandboxTest#modeDecidesPerSession` ✅ | 无 |
| 拼出来的 argv 是数组形态的 `docker run`，不是一整条 shell 字符串 | `SandboxTest#argvIsNotAShellString` ✅ | 无；这条防的是"用 `sh -c` 把参数接起来"，那等于把注入面又放回来 |
| 镜像名、挂载方式、容器工作目录都能从配置进来 | `SandboxTest#containerSpecComesFromProperties` ✅ | 无 |
| docker 不可用且 `backend=docker` → 启动失败，消息里点名哪个探测失败 | `SandboxTest#unavailableDockerFailsFast` ✅ | 现在没有探测，配了也静默走宿主 |
| `backend=ssh` 配了但没实现 → 启动失败（不是静默忽略这个键） | `SandboxTest#unimplementedBackendIsRejected` ✅ | 无。同一条用例顺手钉住 `mode=non-main` 也启动失败（见缺口） |
| 真隔离：命令在容器里跑 | `SandboxTest#commandRunsInsideContainer`（`@Tag("docker")`）✅ 真跑 | 今天同进程空间 |
| 容器里看不到宿主的环境变量 | `SandboxTest#hostEnvironmentIsNotInherited`（`@Tag("docker")`）✅ 真跑 | 今天 exec 路径"继承环境" |
| **（实现时补）exec 侧接的是社区的容器后端，判定在前、执行在后** | `SandboxTest#containerBackendTakesOverAfterThePolicyLayer` ✅ | 自己拼 docker run 的版本会漏掉"杀掉客户端容器里还在跑" |
| **（实现时补）exec 侧真在容器里** | `SandboxTest#execContainerBackendRunsInsideAContainer`（`@Tag("docker")`）✅ 真跑 | 同上 |

**测试分层**（这张表最容易写歪的地方）：仓库规定 `verify.sh` 的跳过数必须为 0，不许 `@Disabled`、
也不许用 `assume`（`pom.xml` 那段注释写了理由）；而 CI 上没有 docker 守护进程。所以分两层：

- **argv 拼装、模式判定、探测与失败语义**用假后端做单测，**进回归**（7 条）。
- **真隔离的用例**打 `@Tag("docker")`，跟 `real-cli` / `real-mcp` 同一套机制排除在 `verify.sh` 之外：
  `pom.xml` 的 `<excludedGroups>` 加 `docker`，并照 `real-cli` 那份 profile 的写法加了一个放开它的
  profile（`groups=docker` + `excludedGroups=no-such-group`）。

**`@Tag` 打在方法上，不是类上**：这个类里还有 7 条不需要容器的用例，类级标签会把它们一起排除出回归。
方法级排除的效果 2026-11-18 实测过（一条带标签的方法在默认 `excludedGroups` 下没有跑，`Skipped` 是 0）。

理由是 `SandboxPolicy` 类注释里那句："没有测试的边界不能写进 README 当承诺"——真要验的那几条
必须存在、必须能一键跑，只是不进默认门禁。

**复核：2026-11-18**
- 默认档：`./mvnw -o test -pl claw4j-capability -Dtest=SandboxTest` → 7 条，失败 0、跳过 0。
- 真容器：`./mvnw -o -Pdocker test -pl claw4j-capability -Dtest=SandboxTest -Dclaw4j.test.sandbox.image=<本地镜像>`
  → 3 条，失败 0（脚本路径 2 条 + exec 路径 1 条，都真的起了容器）。
- 全量门禁：见文末「实现记录」。

## 验收判据

| 判什么 | 判据 |
| --- | --- |
| 真在容器里 | 脚本里 `hostname` 与宿主不同；`/proc/1/cgroup` 或 `/proc/self/mountinfo` 有容器特征 |
| 默认零变化 | `mode` 不配时，`./scripts/verify.sh` 全绿，命令行行为与改动前一致 |
| 失败可见 | 把 `backend` 配成 docker 后停掉 docker，启动即失败，日志里能看出是"守护进程"还是"镜像" |
| 接缝没破 | 架构测试（只有一个类碰 `ProcessBuilder`）仍然绿 |

## 缺口

- **`ssh` 后端不做**：配置里接受这个取值但启动即失败（见用例 `#unimplementedBackendIsRejected`）。
  留一个"配了不生效"的键比不支持更坏——那正是这批票一直在删的东西。
- **`openshell` 不做**（ADR-0021 已明确）。
- **`host=node` 仍是占位**：我们没有多宿主，配进来只会落到本机。这条由票 06 处理，不在本票。
- **镜像内容不由本票负责**：容器里有什么命令取决于用户给的镜像。`curl` 出网失败那条负例（P17b）
  要用的镜像得带 `curl`，所以判据里写清"用哪个镜像跑这条"，不假设默认镜像里有什么。
- **`scope` 没加**：`session` / `agent` / `shared` 是容器复用的语义，归 P17c。见「实现记录」。
- **`workspace-access=ro` 只作用于脚本层**：库的 `mount(Path, String)` 没有只读选项，exec 侧挂载是读写。
  配 `ro` 时启动日志有一条 WARN 说明，不静默；只读要等 P17b/c 的限额那一层。
- **脚本侧超时杀掉的是 docker 客户端**，容器里的进程可能残留（Docker 限制；exec 侧由库的 PID 机制
  处理掉了）。脚本超时上限 30 秒、容器带 `--rm`，但严格说这仍是缺口，P17c 的"退出清理"要收。
- **`non-main` 现在启动失败**：判定函数写好了，按会话区分要等票 06（它要接第 9 层）。

## 实现记录（2026-11-18）

**与票面最大的偏差：exec 侧不再自己拼 `docker run`，改用社区的 `DockerCliExecBackend`。**

- 模块：`org.springaicommunity:spring-ai-agent-utils-docker-cli`，版本跟 `spring-ai-agent-utils` 0.13.0 走。
- 为什么：它已经做了这一层真正难的事——托管容器（`build()` 时建、起不来 fail-fast）、后台句柄在
  `docker_` 命名空间、**超时后在容器内按 PID 发 TERM/KILL**（Docker 的已知限制：杀掉 docker 客户端
  不会杀掉容器里的进程）、`close()` + shutdown hook + label 回收。
- 我们自己的那版只保留一件事：**脚本层的 argv**。脚本参数不能拼回一条命令字符串交给 `sh`
  （ADR-0002），而库的 `ExecSpec` 是命令字符串——所以脚本层保留 `DockerSandboxRunner`
  （`docker run --rm -i ... <entrypoint> <args>`）。分界线写在 `DockerCli` 与 `SandboxBackends` 的类注释里。
- 这条偏差同时是 `AGENTS.md`「先找现成的，再动手写」那一节的来源：我先写了三样（argv、探测、后台句柄），
  用户一句"看看 spring-ai 社区有没有"就找到了它。

**配置键**：`mode` / `backend` / `workspace-access` / `image` / `docker-binary`。
**`scope` 没有加**：它是"容器复用"的语义，属于 P17c；现在每次执行要么是一次性容器（脚本）、
要么是托管容器（exec），加进来只能是"配了不生效"。

**判定函数** `sandboxed(SandboxSession)` 写好了也有用例，但装配只接受 `off` / `all`：
`non-main` 需要"这一段算不算主对话"，而脚本层与 exec 层都拿不到那个信息（它在第 9 层，票 06）。
所以 `non-main` 现在**启动失败**，不是悄悄当成 `all`。

**探针分两处，各管一段**：exec 侧的可用性就是库的 `build()`（容器真的建起来）；
脚本侧是 `DockerCli#requireUsable`（`docker info` + `image inspect`，两条分开报）。

**名额**：capability 模块测试文件数到顶（14）。把 2026-11-09 拆出去的 `McpToolFilterTest`（5 条）
并回 `McpToolTest`（当初拆的理由是"那个类已经 20 个方法"，而门禁上限现在是 26），
腾出的位置给 `SandboxTest`。

**真跑的环境说明**：本机 Docker Hub 不通（`registry-1.docker.io` 超时），默认镜像 `alpine:3` 拉不下来。
真容器那三条用本地已有的 `docker.m.daocloud.io/library/redis:7-alpine` 跑过。镜像要求写进
`application.yml` 注释：POSIX shell + `sleep`（托管容器保活）+ 可写 `/tmp`（记 PID）。

**门禁（2026-11-18）**：`./scripts/verify.sh` 四步全绿——806 条用例，失败 0 / 错误 0 / 跳过 0，
capability 行覆盖率 81.5%，文件数 14 / 最大方法数 26 未越线。

## 回滚与恢复（2026-11-18 晚）

**这一票做完、回滚过一次、又按裁决恢复了。** 现在的状态是 resolved：代码在，命令真的进容器。

- 做完时：`claw4j.tools.sandbox.*` 五个键、`SandboxProperties` / `SandboxBackends` /
  `SandboxAutoConfiguration` / `SandboxUnavailableException`、exec 侧的
  `DockerCliExecBackend` 接线、`@Tag("docker")` 三条真隔离用例、`-Pdocker` profile。
- 为什么回滚：库的 `DockerCliExecBackend.Builder` 只有 7 个入口，**没有任何限额参数**
  （network / 只读根 / memory / pids / user / cpus 全无）。命令能送进容器，却关不住它——
  "看起来有隔离、实际没有"比不隔离更坏。用户裁决：**去掉 docker 相关功能，回滚代码，继续等上游**。
- 删掉的：`claw4j-capability/.../sandbox` 整包、`spring-ai-agent-utils-docker-cli` 依赖（父 pom 与
  capability pom）、`SandboxedExecBackend` 的 `container` 字段与那个 7 参构造、
  `ExecAutoConfiguration` 里的容器装配与 WARN、`application.yml` 的 `sandbox:` 段、
  `SandboxTest`（7 条单测 + 3 条 `@Tag("docker")`）、`-Pdocker` profile 与 `excludedGroups` 里的 `docker`、
  doctor 的 `ConfigSanityCheck`（它查的三个组合全在已删的键上）。
- 没动的：`SandboxedExecBackend` 这个**判定装饰器**留着（名字里的 sandbox 指的是执行前的判定，
  不是容器），`exec` 的其余行为逐字节不变。
- 回来的前提：上游给 `Builder` 加限额参数。见 `P17b` 票面的 `## Answer`（七项）与
  `docs/agents/alignment.md`「先找现成的实现」末段。

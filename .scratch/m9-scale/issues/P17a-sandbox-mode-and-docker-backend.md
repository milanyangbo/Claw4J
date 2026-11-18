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

**Status:** ready-for-agent

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
| `mode=off` 时走宿主，行为与今天逐字节一致 | `SandboxModeTest#offKeepsRunningOnHost` | 现在没有 `mode`，恒走宿主；这条是"默认不改变任何现有行为"的守卫 |
| `mode=all` 时判定为沙箱会话，`non-main` 时主会话不算 | `#modeDecidesPerSession` | 无 |
| 拼出来的 argv 是数组形态的 `docker run`，不是一整条 shell 字符串 | `DockerCommandTest#argvIsNotAShellString` | 无；这条防的是"用 `sh -c` 把参数接起来"，那等于把注入面又放回来 |
| 镜像名、网络、挂载点都能从配置进来 | `#containerSpecComesFromProperties` | 无 |
| docker 不可用且 `backend=docker` → 启动失败，消息里点名哪个探测失败 | `SandboxStartupTest#unavailableDockerFailsFast` | 现在没有探测，配了也静默走宿主 |
| `backend=ssh` 配了但没实现 → 启动失败（不是静默忽略这个键） | `#unimplementedBackendIsRejected` | 无 |
| 真隔离：命令在容器里跑 | `DockerSandboxTest#commandRunsInsideContainer`（`@Tag("docker")`） | 今天同进程空间 |
| 容器里看不到宿主的环境变量 | `#hostEnvironmentIsNotInherited`（`@Tag("docker")`） | 今天 exec 路径"继承环境" |

**测试分层**（这张表最容易写歪的地方）：仓库规定 `verify.sh` 的跳过数必须为 0，不许 `@Disabled`、
也不许用 `assume`（`pom.xml` 那段注释写了理由）；而 CI 上没有 docker 守护进程。所以分两层：

- **argv 拼装、模式判定、探测与失败语义**用假后端做单测，**进回归**。
- **真隔离的用例**打 `@Tag("docker")`，跟 `real-cli` / `real-mcp` 同一套机制排除在 `verify.sh` 之外：
  `pom.xml` 的 `<excludedGroups>` 现在是 `real-cli,real-mcp,eval`，把 `docker` 加进去；再照
  `real-cli` 那份 profile 的写法（`groups` 列出要跑的组、`excludedGroups` 换成一个不存在的组名）
  加一个放开它的 profile。

理由是 `SandboxPolicy` 类注释里那句："没有测试的边界不能写进 README 当承诺"——真要验的那几条
必须存在、必须能一键跑，只是不进默认门禁。

**标记：整张表未复核（票还没开始）。** 收票时逐行改成 `✅` / `❌` / `⏳`，规则见 `AGENTS.md` 第 2 条。

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

# P17c: 工作目录进出、容器复用与退出清理

**What to build:** 前两票让命令跑进容器、把口子收紧了，但没说清楚三件决定它能不能日常用的事：

1. **宿主的目录怎么进容器**（`workspaceAccess`: `ro` / `rw` / `none`）
2. **容器活多久**（`scope`: `session` / `agent` / `shared`）
3. **谁来收尸**（进程退出、异常、超时之后，容器还在不在 `docker ps -a` 里）

第 3 条是这三件里最容易漏的：`docker run` 起来的容器不会因为 JVM 退出而消失。今天
`SandboxedExecBackend` 实现了 `DisposableBean`、在 `destroy()` 里回收后台 shell —— 容器要挂在同一处，
否则每跑一次脚本就在宿主机上留一个死容器，几天之后 `docker ps -a` 是一屏垃圾。

一条结构判断：**`scope` 决定"复用"，`workspaceAccess` 决定"看得见"，两者不互相推导。**
`scope=session` 不等于 `workspaceAccess=rw`，`workspaceAccess=ro` 也不妨碍同一会话复用同一个容器。
今天这两个概念在同一段代码里（后端自己决定工作目录），要拆成两个显式字段。

**Blocked by:** P17a。与 P17b 可并行（两者改的是 `ContainerSpec` 的不同字段；同时开工时先落 P17b 的
`ContainerSpec` 骨架，避免两份）。

**Type:** task

**Status:** ready-for-agent

**裁决来源：** ADR-0030 §裁决六（`scope` / `workspaceAccess` 的取值与"`shared` 时丢弃 per-agent 覆盖"）；
ADR-0020 §M9 · P17（临时 workdir）。

## 要动的地方（预计）

| 位置 | 改什么 |
| --- | --- |
| `ContainerSpec` | 加挂载清单与用户；挂载由 `workspaceAccess` 算出来，不由调用方传 |
| 新 `SandboxSessionRegistry` | 按 `scope` 算容器键（session / agent / 全局），持有句柄、引用计数 |
| `DockerSandboxRunner` | 取容器 → 没有就起 → 执行；`scope=shared` 时全进程一个 |
| `SandboxedExecBackend#destroy()` | 与它已经做的"回收后台 shell"合到一处：先停 shell，再停容器 |
| 脚本侧 `ProcessRunnerFactory` | 同样从 `SandboxSessionRegistry` 取，不自己起容器 |
| `application.yml` | `scope` / `workspaceAccess` 两段注释 + 保守默认（`session` + `ro`） |

## TDD 用例清单

| 判什么行为 | 用例名 | 先红的样子 |
| --- | --- | --- |
| `workspaceAccess=ro` 时容器里写宿主目录失败 | `WorkspaceAccessTest#readOnlyRejectsWrites`（`@Tag("docker")`） | 今天脚本包目录是宿主可写的 |
| `rw` 时写进去，宿主看得见 | `#readWriteIsVisibleOnHost`（`@Tag("docker")`） | 无 |
| `none` 时容器里看不到宿主目录 | `#noneHidesTheWorkspace`（`@Tag("docker")`） | 无 |
| 同一 session 两次调用复用同一个容器 | `#sessionScopeReusesContainer` | 今天每次都起新进程 |
| 不同 session 各一个容器 | `#differentSessionsGetDifferentContainers` | 无 |
| `scope=shared` 时全进程只有一个容器 | `#sharedScopeIsProcessWide` | 无 |
| 引用计数归零才停容器（并发两次调用不会互相踩） | `#containerStopsWhenRefCountHitsZero`（单测，假后端） | 无 |
| 正常退出后 `docker ps -a` 里没有残留 | `#destroyLeavesNoContainer`（`@Tag("docker")`） | 今天没有容器；这条防的是"起得多、收得少" |
| 后台 shell 的容器随 `destroy()` 一起停 | `#backgroundShellDiesWithContainer` | `destroy()` 今天只收 shell |

**标记：整张表未复核（票还没开始）。** 收票时逐行改成 `✅` / `❌` / `⏳`，规则见 `AGENTS.md` 第 2 条。

## 验收判据

| 判什么 | 判据 |
| --- | --- |
| 挂载语义对得上 | `ro`：容器内 `touch $WORKSPACE/x` 失败、`cat` 成功；`rw`：写入后宿主 `ls` 看得见；`none`：`ls $WORKSPACE` 为空 |
| 复用真的发生 | 同一 session 连问两次，两次的 `hostname` 相同；换 session 后不同 |
| 无残留 | 跑完 10 次脚本并 `destroy()`，`docker ps -a --filter name=claw4j-` 为空 |
| 异常路径也收 | 执行中途 kill 掉应用进程 → 下次启动时把上次的 `claw4j-*` 容器清掉（或至少告警点名） |
| 默认安全 | 默认 `session` + `ro` 下，现有的脚本包示例（`summarize` / `weather-forecast`）行为不变 |

## 缺口

- **`scope=shared` 的 per-agent 覆盖丢弃**：ADR-0030 抄的那条说"`shared` 时 `docker` / `browser` /
  `prune` / `ssh` 四段的 per-agent 覆盖被丢弃"。我们是单层配置，没有 `agents[].sandbox.*`，
  **这条在我们这里没有对象**。将来真加了 per-agent 覆盖才需要补，别把它当成漏实现。
- **崩溃残留只能"下次清"**：JVM 被 `kill -9` 时 `destroy()` 不会跑。做法是启动时扫一遍带
  `claw4j-` 前缀的容器并清掉——这也带来一个已知限制：同一台机器上跑两个实例会互删容器。
  多实例共存的方案（按实例 id 加前缀）留到真有多实例需求时再做。
- **不做容器的"预热池"**：每次起容器的冷启动成本（几十到几百毫秒）先认了，不做池化。
- **`elevated` 不在本票**：它"从沙箱跑到宿主"的语义依赖 P17 全部落地 + 票 06，见 ADR-0030 裁决六的顺序。

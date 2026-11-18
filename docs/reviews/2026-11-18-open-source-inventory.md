# capability 四个包的开源对照（2026-11-18）

> **更正（同日，晚）**：下面判"留"的三处判据用错了。当时问的是"OpenClaw 有没有同名部件"，
> 而当天新增的规则（`AGENTS.md`「设计一律照 OpenClaw」）要求问"**它用什么达成同一件事**"。
> 按新规则要重判的：
>
> - **脚本包**：OpenClaw 不自己执行技能里的脚本，交给 agent 用 `exec` 跑（`docs/tools/exec`：
>   `command` 是 shell 字符串，`security=allowlist` 下按管道段判）。"再建一层 `run_script` 收 argv"
>   是否对齐，要重新答。
> - **脚本层的容器 argv**：对应物是 `exec host=sandbox`（容器里 `sh -lc`），容器由宿主编排
>   （`host` 参数），不由应用拼 `docker run`。我们那份 `ContainerSpec` 要重新答。
> - **`ExecPolicy`**：当时写"无对应物"，**是错的**。OpenClaw 的 allowlist 就是这套：
>   `security=allowlist` 下每个管道段都要命中白名单，`;`/`&&`/`||` 与重定向默认拒。
>   结论（留）不变，理由要改成"照它实现"；差异也要记：它按段判、能放行"全是白名单段"的管道，
>   我们见控制符一律拒——**比它更严，属于偏离**。
>
> 其余各条（`LocalExecBackend`、commons-exec、路径边界）不受影响。

`io.github.claw4j.capability` 的 `process` / `sandbox` / `exec` / `script` 四个包，共 41 个类、
4699 行。逐件问一遍：**这件事有没有现成的实现，质量够不够用**。

**一句话**：有两个自研件该换掉（一个有同库里的对等实现，一个 Apache 的实现更成熟）；容器 argv、
命令白名单、脚本包、路径边界四件事 Java 侧没有够格的对等物，留下并记账。

## 怎么查的

按 `docs/agents/alignment.md`「先找现成的实现」的判据：**打开 jar 与源码读**，不看文档印象。
版本与星数取 2026-11-18 当天的数据（Maven Central `maven-metadata.xml` + GitHub API）。

```
unzip -l  spring-ai-agent-utils-0.13.0.jar          # 有没有这个类
javap -p  DockerCliExecBackend$Builder.class        # 它的 API 与默认值
# 其它候选：拉 *-sources.jar，读它怎么起进程、怎么处理环境与超时
```

## 结论表

| 包 | 自研件 | 行数 | 开源对应 | 结论 |
| --- | --- | --- | --- | --- |
| process | `ShellCommandRunner` + `ShellProcess` | 294 | **已引的** `LocalExecBackend`（同一个库） | **换**，删掉 |
| process | `LocalProcessRunner` | 213 | Apache Commons Exec 1.6.0 | **换**，留 90 行入口检查 |
| process | `ProcessRunner` / `ProcessRequest` / `ProcessOutcome` | 120 | 无（本仓接缝与返回类型） | 留 |
| sandbox | `DockerCli` + `ContainerSpec` + `DockerSandboxRunner` | 388 | docker-java 3.2k★ / agent-sandbox 3★ / Testcontainers 8.8k★ | 留，记备选 |
| sandbox | `SandboxProperties` / `SandboxBackends` / `SandboxAutoConfiguration` 等 | 347 | 无（装配与配置） | 留 |
| exec | `ExecPolicy` | 549 | **无**（库的 `ShellTools` 没有白名单入口） | 留 |
| exec | `SandboxedExecBackend` + 审批 / 复核 / 冻结件 | 1341 | 无（本仓策略） | 留 |
| script | `SandboxPolicy`（路径边界） | 160 | 库里 `AllowedDirectories`，但 package-private | 留 |
| script | `ScriptPackage*` + `SandboxedProcessBackend` | 1257 | 库里 `Skills` 是 SKILL.md，不执行 | 留 |

两处"换"合起来约 -460 行（10%），但真正的收益不是行数：**全仓碰 `ProcessBuilder` 的类从两个降到零**，
`ToolsBoundaryTest` 的两个例外与 `BuildContractTest` 的 `SHELL_ENTRY` 豁免可以一起删掉。

## 该换的两个

### 1. `ShellCommandRunner` + `ShellProcess` → 库的 `LocalExecBackend`

同一个依赖（`spring-ai-agent-utils` 0.13.0，688★，2026-10 发布）里就有
`org.springaicommunity.agent.exec.LocalExecBackend`（`LocalExecBackend.java:45`）。逐项对：

| 本仓 | 库里 |
| --- | --- |
| `run`：起进程、两路收流、超时 `destroyForcibly` | `run`：`destroy()` 先礼后兵（5 秒后才 `forcibly`），超时带半截输出返回 |
| `start`：`ShellProcess`，`id` / `isAlive` / `newOutput` / `exitCode` / `kill` | `start`：`LocalExecHandle`，同五个方法，游标语义一致（`LocalExecBackend.java:164`） |
| `ProcessBuilder(SHELL, "-c", cmd)` | `builder().shellCommand("/bin/sh", "-c")`（默认 `/bin/bash`，可配） |
| `environment().clear()` + 整份放入 | `cleanEnvironment(true)` + `environment(...)` |
| 起不来抛 `ProcessStartException` | 返回 `ExecResult.launchFailed(...)`，判定层直接透传 |

接上要动的两处（都很小）：

1. **环境顺序**：库先放固定环境、再 `putAll(spec.env())`（`LocalExecBackend.java:126-127`），
   而 `PATH` / `LD_*` / `DYLD_*` 不许覆盖。做法是把 `ExecSpec` 洗一遍再交进去——
   `new ExecSpec(cmd, timeout, ExecPolicy.environment(Map.of(), spec.env()))`，拒改那条判据留在原处，
   库拿到的永远是一份已经洗过的覆盖。
2. **后台句柄回收**：`ShellTools` 自己攥着 id → handle 的表（`ShellTools.java:56`），
   `destroy()` 看不到它们。保留一个 `Set<ExecHandle>` 就行（三行），别把这层写成"再实现一遍后台管理"。

换完之后 `SandboxedExecBackend` 只剩判定：`authorize(spec)` → `host.run(spec)`。它不再认识进程，
只认识两个 `ExecBackend`（宿主一个、容器一个）。

**动到的文件**：`ExecAutoConfiguration`、`ExecToolTest`（3 处）、`SandboxTest`（1 处）、
`ToolsBoundaryTest`、`BuildContractTest`（`SHELL_SPAWNER` 与 `SHELL_ENTRY` 豁免一起删）。

### 2. `LocalProcessRunner` → Apache Commons Exec 1.6.0

脚本包的 argv 执行器。四个候选，逐个核过：

| 候选 | 质量 | 能不能用 | 判据 |
| --- | --- | --- | --- |
| **commons-exec 1.6.0**（Apache，164★） | 顶级项目，2025-12 发布 | **能** | `Java13CommandLauncher.exec` 走 `Runtime.exec(cmdarray, envp, dir)`（`Java13CommandLauncher.java:50`）——**envp 是整份替换**，正好表达"只给 PATH"；`ExecuteWatchdog` 管超时；`PumpStreamHandler` 按字节拷，编码自己解 |
| zt-exec 1.13.0（919★） | 活跃（2026-07） | **不能** | `applyEnvironment()` 只增删单个键（`ProcessExecutor.java:1234`），没有 clear → 表达不了白名单环境 |
| NuProcess 4.0.0（742★） | 活跃（2026-07） | 能但不必 | 本地库整表给环境，功能可行；为 213 行引 JNI + 异步回调模型，不划算 |
| 自研（现状） | — | — | 213 行里约 90 行是通用能力（收流、超时、掐断），另 20 行是入口检查 |

留下的 90 行只做三件事：三条入口检查（普通文件 / 可执行位 / shebang，`binaries(...)` 那条
不查 shebang）、组装 `CommandLine`、把 watchdog 的结果翻成 `ProcessOutcome`。
`CommandLine` 有一个 `handleQuoting` 开关，其语义只在 Windows 上生效——**要一条用例钉住**
"带空格与分号的参数原样到达"，不能只靠读代码。

## 留下并记账的四个

### 容器 argv（`DockerCli` + `ContainerSpec` + `DockerSandboxRunner`）

脚本层要的是 argv（ADR-0002：参数不能被解释器再看一遍），而库的 `DockerCliExecBackend`
只收一条命令字符串（`ExecSpec`）。三个外部候选都不合用：

- **`markpollack/agent-sandbox` 1.0.0**（3★，2026-11-15 发布）：形状完全对——`ExecSpec` 在每个
  后端都是 argv，`DockerSandbox` 用 Testcontainers 的 `execInContainer(String...)`。但 3 星、
  个人仓、pre-1.0、刚从 `spring-ai-community` 迁出；且 `Builder` 只有 `image` / `customizer` /
  `withFile(s)`——**同样没有限额**，解决不了 P17b。记为观察项，不引。
- **docker-java 3194★**：`execCreateCmd.withCmd(List<String>)` 天然 argv，`HostConfig` 有
  Memory / PidsLimit / NetworkMode / ReadonlyRootfs / User——**能一次解决 argv + 四条限额**。
  代价是多一条 docker 访问方式（exec 侧仍走库的 CLI）。P17b 若等不到上游，这是首选。
- **Testcontainers 8764★**：测试定位（JUnit 生命周期 + Ryuk sidecar），生产跑脚本属 off-label。

### 命令白名单（`ExecPolicy`，549 行）

库的 `ShellTools.Builder` 只有 `execBackend` / `workingDirectory` / `workspace` 三个入口
（`ShellTools.java:298-353`），**没有任何 allowlist 或 approval**。`spring-ai-session-extensions` 的
"tool confirmation" 是工具级确认，不是命令级白名单；OpenClaw 的同名能力是 TS。Java 侧最近的
`jline`（1812★）只做词法，不切 `&&` / heredoc。没有能替的东西——这 549 行是"判断"，不是"实现"。

### 路径边界（`SandboxPolicy#resolveInside`）

库里有同一套判据：`AllowedDirectories.validate` 也是"拒 `..` → normalize 包含 → realpath 逐级回退"
（`AllowedDirectories.java:76`）。但它是 `final class` 且包内可见（`AllowedDirectories.java:37`），
外部只能读不能调。所以保留，并在注释里点名"判据与库一致，不是自创"，免得下次有人再查一遍。

### 脚本包（`ScriptPackage*`）

库的 `Skills` / `SkillsTool` 加载的是 `SKILL.md`（`Skills.java:37` `loadResources`），是进上下文的
说明文本，不执行任何东西。脚本包是"清单 + 可执行入口 + 参数过沙箱"，两回事。

## 与 P17b 的关系

P17b 已裁决为"等上游给 `DockerCliExecBackend` 加限额参数"（票面 `## Answer` 里列了要补的七项）。
本文件只补一条信息：**上游长期不动时，docker-java 是唯一能同时满足 argv 与四条限额的高质量件**，
那时要连 exec 侧一起换，避免两套 docker 访问方式。

## 待办（需要开票才动手）

| 动作 | 影响面 | 判据 |
| --- | --- | --- |
| exec 宿主后端换 `LocalExecBackend`，删 `ShellCommandRunner` / `ShellProcess` | 5 个文件，-294 行 | 现有 `ExecToolTest` / `SandboxTest` 全绿；`ToolsBoundaryTest` 例外 2 → 1；审批与冻结件行为不变 |
| 脚本 argv 换 commons-exec，删 `LocalProcessRunner` | 4 个文件，净 -120 行 | 新增"参数原样到达""超时带半截输出""环境只有 PATH"三条；`ToolsBoundaryTest` 例外 1 → 0 |

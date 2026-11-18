# 01: exec 的宿主后端换成库的 `LocalExecBackend`

**What to build:** 把 `exec` 那条路上的两个自研类删掉，换成 `spring-ai-agent-utils` 里的
`org.springaicommunity.agent.exec.LocalExecBackend`（同一个依赖，不用多引一行）。

要删的：`capability/process/ShellCommandRunner`（186 行）、`ShellProcess`（108 行），
以及 `SandboxedExecBackend` 里为它们写的 `Handle` 适配与后台表。删完之后
`SandboxedExecBackend` 只剩判定：`authorize(spec)` → 交给一个 `ExecBackend`（宿主或容器）。

**为什么换**：`docs/reviews/2026-11-18-open-source-inventory.md`。逐项对过——`run` 的超时与两路收流、
`start` 的后台句柄（`id` / `isAlive` / `newOutput(filter)` / `exitCode` / `kill` 五个方法）、
`cleanEnvironment(true)`、超时时"先 `destroy()` 五秒后再 `forcibly`"全都有。它比我们那版多的是
维护方与已踩过的坑，不是更强的技术。

**质量判据**（`AGENTS.md`「开源的质量怎么判」）：`spring-ai-agent-utils` 0.13.0，688★，
Apache-2.0，2026-10-03 发布，近 12 个月有 release。纯 JDK `ProcessBuilder`，没有传递依赖。

**Blocked by:** 无。

**Type:** task

**Status:** in-progress

## 要动的地方

| 位置 | 改什么 |
| --- | --- |
| `SandboxedExecBackend` | 构造参数从 `ShellCommandRunner` 换成 `ExecBackend host` + `Path workspace`；`run` / `start` 直接转发；`destroy` 收掉自己记账的后台句柄 |
| `ExecAutoConfiguration` | 产出 `ExecBackend`（`LocalExecBackend.builder()`）而不是 `ShellCommandRunner`；`cleanEnvironment(true)` + `environment(System.getenv())` + `shellCommand("/bin/sh", "-c")` |
| `ShellCommandRunner` / `ShellProcess` | 删 |
| `ToolsBoundaryTest` | `ProcessBuilder` 例外 2 → 1（只剩 `LocalProcessRunner`） |
| `BuildContractTest` | `SHELL_SPAWNER` 删；扫 shell 字面量的豁免文件从 `ShellCommandRunner.java` 改成 `ExecAutoConfiguration.java`（它把解释器名字交给库的 builder，不拼命令行） |
| `ExecToolTest` / `SandboxTest` | 4 处构造改成建 `LocalExecBackend` |

## 一个行为差异，要写进注释

库的 `run` 在超时时先 `destroy()`（SIGTERM），5 秒不退才 `forcibly`；我们那版直接 `forcibly`。
对 `Bash` 是更好的行为（给进程一个收尾机会），但要确认超时用例的耗时断言（< 4 秒）仍然成立。

## TDD 用例清单

| 判什么行为 | 用例名 | 先红的样子 |
| --- | --- | --- |
| 命令照旧执行，工具的 `bash_id` 头也在 | `ExecToolTest#runsACommandThroughTheLibraryTool` ✅ | 构造改不过来，编译红 |
| 超时仍然是"工具说 timed out"，且不等满 5 秒 | `ExecToolTest#timeoutKillsTheProcess` ✅ | 库的 `destroy` 先礼后兵，耗时断言可能红 |
| `PATH` / `LD_*` 不许覆盖，普通变量照常合并 | `ExecToolTest#refusesToOverridePathAndLoaderVariables` ✅ | 覆盖校验若跟着旧 runner 一起被删，这条红 |
| 容器非空时判定通过就整段交给它，宿主一次都不跑 | `SandboxTest#containerBackendTakesOverAfterThePolicyLayer` ✅ | 宿主的 `LocalExecBackend` 被调到就会拿到 `hi` 而不是 `in-container` |
| `destroy()` 把还活着的后台 shell 收掉 | `SandboxTest#destroyKillsTrackedBackgroundShells` ✅ | 库的 handle 不归我们记账，`destroy` 之后 `isAlive()` 仍为真 |
| 宿主环境不继承（只给声明的那份） | `ExecToolTest#inheritsEnvironmentAndMarksItself` 里追加断言 ✅ | `cleanEnvironment` 漏了就会看到宿主的 `HOME` |

（`ExecToolTest` 已经在 26 个方法的上限上，所以最后两条不是新方法：一条加了断言，
一条放进了 `SandboxTest`——它验的是后端生命周期，放那边也更对。）

**标记：已复核（2026-11-18）。** 六行全绿；门禁 `./scripts/verify.sh` 四步全绿，
capability 154 条用例、失败/错误/跳过均 0、覆盖率 81.9%、文件数 14 / 最大方法数 26（都到顶，不再加）。

## 验收判据

| 判什么 | 判据 |
| --- | --- |
| 行为不变 | `ExecToolTest` / `SandboxTest` 全绿，审批与冻结件那几条一行不改 |
| 红线收紧 | `ToolsBoundaryTest` 里 `ProcessBuilder` 的例外只剩 `LocalProcessRunner` 一个 |
| 没有留下死代码 | 全仓 `ShellCommandRunner` / `ShellProcess` 零引用（含注释） |
| 门禁 | `./scripts/verify.sh` 四步全绿 |

## 实现记录

- 删除 `ShellCommandRunner`（186 行）、`ShellProcess`（108 行）；`SandboxedExecBackend` 从 437 行降到 381 行，
  `ExecAutoConfiguration` 从 148 行到 162 行（多了一个后端的 javadoc，少了一个私有类）。
- 环境这条路没变：`ExecPolicy.environment` 仍然负责"拒改 `PATH` / `LD_*` / `DYLD_*`"并加 `SHELL_MARKER`，
  只是算出来的覆盖交给 `ExecSpec.env()`，由库在固定环境之上合并。
- 后台句柄：`ShellTools` 自己攥着 id → handle 的表，`destroy()` 看不到；本类留一个
  `Set<ExecHandle>` 记账（宿主那条才需要，容器那条由库回收）。
- 门禁：`./scripts/verify.sh` 四步全绿（2026-11-18），capability 154 条、覆盖率 81.9%。

# 02: 脚本包退化成技能（目录 + 说明），执行交给 `Bash`

**What to build:** 把"脚本包"这一层从**可执行插件**退化成 **OpenClaw 的技能形态**：一个目录、
一份 `SKILL.md`、可选的支撑文件（脚本就在里面）。模型读说明，用 `Bash` 去跑那些文件；
本仓不再提供 `run_script` 这个专用工具，也不再自己起进程。

**Blocked by:** 一次裁决（用户 2026-11-18 说"跟 OpenClaw 对齐"，这张票是它的落地；删掉一层能力，
按 `AGENTS.md` 要人拍）。

**Type:** task

**Status:** draft（等确认）

## 为什么是"退化成文件 + 说明"（2026-11-18 核过 OpenClaw 文档原文）

- **技能就是目录 + `SKILL.md`**："Each skill lives in a directory containing a `SKILL.md` file
  with YAML frontmatter and a markdown body."；"The editor keeps **supporting scripts**, references,
  and assets with the instructions."
- **它没有"跑技能脚本"的执行器**："Indexing never executes skill content"；
  "it does not list directories or **execute scripts**"；工具只有 `skills_list` / `skills_search` /
  `skills_read`，全是只读。
- **脚本由 agent 用 `exec` 跑**："Its files, relative references, and binaries live on the node, so
  **load and execute it with `exec host=node`**."；"The agent should **run those skills via the `exec`
  tool**"。

也就是说：**OpenClaw 的技能不执行任何东西，执行全部收敛到 `exec` 一个入口**。我们的
`ScriptPackage`（清单 + 入口 + 类型化参数 + `run_script` 工具）在它那里没有对应物，是自建的第二个执行入口。
再加上 2026-11-18 的规则「执行类能力必须用现成实现」，这条路没有继续自研的余地：
**要留脚本，就留成文件；要执行，就走 `Bash`（库的 `LocalExecBackend`）。**

## 退化成什么

| 现在 | 之后 |
| --- | --- |
| `claw4j.scripts.directory` 下的包：manifest + 入口脚本 | 同一个目录，改成技能：`SKILL.md` + 脚本（`skills` 层已经在用库的 `SkillsTool`） |
| 工具 `run_script(name, args)`，参数过 `SandboxPolicy#resolveInside` | 没有这个工具。模型读 `SKILL.md`，用 `Bash` 敲命令；命令过白名单 + 审批（exec 那一层） |
| 包清单里声明超时、入口、参数 | 说明里写怎么跑；超时与白名单由 `claw4j.tools.exec.*` 管 |
| 脚本执行在包目录里（argv，无解释器） | 在 workspace 里，经 shell（`sh -c`），由库起进程 |

## 要删的（自研执行链）

| 组 | 类 | 备注 |
| --- | --- | --- |
| `capability/script` | `ScriptPackage`、`ScriptPackageCatalog`、`ScriptPackageRegistry`、`ScriptPackageProperties`、`ScriptPackageRunner`、`ScriptPackageToolCallback`、`ScriptPackageToolProvider`、`ScriptPackageAutoConfiguration`、`SandboxedProcessBackend`、`ProcessRunnerFactory`、`ExecutionListeners`、`ScriptExecutionListener`、`ScriptExecutionException` | 整包下线 |
| `capability/process` | `ProcessRunner`、`ProcessRequest`、`ProcessOutcome`、`ProcessStartException`、`LocalProcessRunner` | 整包下线（`ProcessBuilder` 的红线从"一个例外"变成**零例外**） |
| `capability/sandbox` | `DockerCli`、`DockerSandboxRunner`、`ContainerSpec`、`SandboxBackends.scriptFactory`、`SandboxSession`、`SandboxUnavailableException` | 容器只剩 exec 侧（库的 `DockerCliExecBackend`），脚本侧没有对象 |
| `capability/generator` | `GeneratorSandbox` 的"临时目录副本 + 最小环境 + 超时"自测 | 自测改走 `ExecBackend`（库）或由模型用 `Bash` 跑，不再自己起进程 |
| `SandboxPolicy` | 留下 `requireNonRoot` / `requireTimeoutWithinCap` 也没对象了 | 整类下线；四条约束的判据搬进 `docs/` 留档 |

## 要留的

- `capability/skill`（已经是库的 `SkillsTool`：发现、`SKILL.md` 解析、渐进式披露）。
- `capability/exec`（判定层 `SandboxedExecBackend` + 审批 + 复核 + 冻结件）。
- `claw4j.tools.sandbox.*` 只剩 `mode` / `backend` / `image` / `workspace-access` 里 exec 用得上的那几项；
  `P17b`（四条限额）从此只属于 exec 侧——它本来也 blocked 在上游。

## 影响面（不是小改）

`README.md`、`README-adding-capabilities.md`、`README-capability-tests.md`、`GLOSSARY.md`、
`CONTRIBUTING.md`、`application.yml`（`claw4j.scripts.*`）、`examples/scripts/*`、eval 用例里
`run_script` 那几条、`SubagentDelegationTest` / `ToolFilterBindingTest` / `ApplicationAssemblyTest`
里对 `run_script` 的断言、`docs/adr/0002` 与 `0003` 的阶段结论、P17a/P17b/P17c 的票面。
粗算 40+ 个文件。

## TDD 用例清单

| 判什么行为 | 用例名 | 先红的样子 |
| --- | --- | --- |
| 技能目录里的脚本能被模型用 `Bash` 跑到，输出回到对话里 | `SkillScriptTest#scriptRunsThroughBash`（真起进程） | 现在要靠 `run_script`，`Bash` 也能跑但没人验过这条路径 |
| `run_script` 不再是工具（清单里没有它） | `ApplicationAssemblyTest#noRunScriptTool` | 今天清单里有 |
| 脚本路径的越界不再由 `resolveInside` 挡，而由 exec 的工作目录 + 白名单挡 | `SkillScriptTest#outsideWorkspaceIsRefused` | 今天靠脚本层的绝对路径检查 |
| 全仓没有 `ProcessBuilder`（零例外） | `ToolsBoundaryTest#noClassStartsProcesses` | 今天还有 `LocalProcessRunner` |

## 缺口

- **失去两样静态保证**：① 参数只能是包内相对路径（`resolveInside`）；② 入口必须是带 shebang 的可执行文件。
  换成 `Bash` 之后这两样要靠 workspace 边界与 exec 白名单，强度不同，要在 `docs/` 里改写而不是悄悄消失。
- **`run_script` 的审计事件与语义缓存里的"副作用工具"判定**要跟着改名，否则缓存会把"跑脚本"当成只读。
- **示例要重写**：`examples/scripts/summarize`、`weather-forecast` 改成技能目录 + `SKILL.md`。

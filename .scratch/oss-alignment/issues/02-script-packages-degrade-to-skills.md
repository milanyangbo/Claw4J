# 02: 脚本包退化成技能（目录 + 说明），执行交给 `Bash`

**What to build:** 把"脚本包"这一层从**可执行插件**退化成 **OpenClaw 的技能形态**：一个目录、
一份 `SKILL.md`、可选的支撑文件（脚本就在里面）。模型读说明，用 `Bash` 去跑那些文件；
本仓不再提供 `run_script` 这个专用工具，也不再自己起进程。

**Blocked by:** 一次裁决（用户 2026-11-18 说"跟 OpenClaw 对齐"，这张票是它的落地；删掉一层能力，
按 `AGENTS.md` 要人拍）。

**Type:** task

**Status:** in-progress（2026-11-18 用户裁决"肯定要跟 OpenClaw 对齐"；代码已落地，文档与示例待改）

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

## 实现记录（2026-11-18）

**已做（代码全绿，`./scripts/verify.sh` 四步过；capability 13 文件 / 26 方法 / 覆盖率 81.1%）：**

- 删 `capability/script` 的执行侧：`ScriptPackageRunner`、`ScriptPackageToolProvider`、
  `ScriptPackageToolCallback`、`ProcessRunnerFactory`、`SandboxedProcessBackend`、`ScriptExecutionException`。
- 删 `capability/process` 整包（`ProcessRunner` / `ProcessRequest` / `ProcessOutcome` /
  `ProcessStartException` / `LocalProcessRunner`）。
- 删 `capability/sandbox` 的脚本侧：`DockerCli`、`DockerSandboxRunner`、`ContainerSpec`、
  `SandboxSession`、`SandboxBackends.scriptFactory`、`SandboxProperties.sandboxed(...)`；
  `SandboxAutoConfiguration` 只剩配置绑定（容器探测由库的 `build()` fail-fast 承担）。
- `ScriptPackageAutoConfiguration` 只留账本（`ScriptPackageRegistry`）与观察者容器，不再注册工具与档位。
- `GeneratorSandbox` 改成库的 `LocalExecBackend`（`cleanEnvironment(true)` + `sh -c`）；自测保留，
  但它已经**不是**模型能触发的执行入口。
- 两条架构规则改成**零例外**：`ToolsBoundaryTest#nobodyStartsProcesses`、
  `BuildContractTest#java_side_must_not_spawn_processes` 不再点名任何类。
- 测试：删 `ScriptPackageTest`（19 条，全部针对已删的执行链）；`SandboxTest` 收到 6 条；
  `SkillGeneratorTest` 两处执行断言改成读发布快照的内容。

**第二步（2026-11-18 晚，同一票收尾）：**

- 示例改成技能：`examples/scripts/{summarize,weather-forecast,xurl}` →
  `examples/skills/<name>/`（`SKILL.md` + `scripts/` + 数据），三个审批标记按**新目录**重算
  （摘要覆盖整个目录，含脚本）；`examples/scripts/` 整个目录删掉。
- **技能正文要能指到脚本**：`AgentSkills` 在正文前补 `Base directory for this skill: <目录>`
  并把 `{baseDir}` 换掉（OpenClaw 的写法）。社区包只在 `addSkillsDirectory` 那条路上补这一行，
  我们走 `addSkill`（校验过的就是进上下文的那一份），所以自己补。
- `weekly-digest` 子 Agent 的白名单 `summarize`（工具已不存在）→ `Skill` + `Bash`；
  `ExamplesSubagentsTest` 的手抄工具表同步。
- 删掉没有发射者的执行观察者链：`ExecutionListeners`、`ScriptExecutionListener`、
  `GeneratorAutoConfiguration#generatorExecutionListenerBridge`；
  `GenerationAudit.Stage.EXECUTED` 留着并标注"不再写入"（旧审计文件按名字还原阶段，删了会让它们解析失败）。
- 文档正文（不是加横幅���真改）：`README-adding-capabilities.md` §3、
  `README-capability-tests.md` §3/§5/速查表、`docs/capability-tests.md` §5 与负向那行、
  `docs/design/capability-layers.md` 脚本一节与实录命令、`examples/README.md`、
  `GLOSSARY.md`（技能里的脚本 / 沙箱两条）、`README.md` 能力表与模块表、
  `docs/design/extension-points.md`、`docs/agents/{guardrails,alignment}.md`、
  `CONTRIBUTING.md`、`application.yml`（沙箱与目录两段注释）。
- ADR-0003 / 0013 / 0014 / 0017 / 0020 / 0030 各加一条修订记录（0002 第一步已改）。
- **补上缺失的机器证据**：`ApplicationAssemblyTest#theToolManifestIsOneBeanForBothExits`
  里断言清单不含 `run_script`（类被删了不等于没人再造一个同名工具）。
- `SkillCatalogTest#bodyCarriesTheSkillDirectorySoScriptsCanBeFound`：正文带目录、`{baseDir}` 被替换。

**留给下一张票（不在这张里）：** 生成器的产出物还是"脚本包 + `package.properties`"，
而注册进账本之后没有任何执行入口——这是票 02 留下的缺口，
`.scratch/oss-alignment/issues/03-generator-emits-skills.md`（draft，等裁决改产出物还是下掉整层）。

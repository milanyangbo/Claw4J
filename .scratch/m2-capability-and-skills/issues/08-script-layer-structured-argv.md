# 08: 脚本层改成结构化 argv，不再把命令行交给 shell 解释

**What to build:** 让 ADR-0002 的文字判据在脚本层（ADR-0003 的第 ③ 层）也成立：Java 侧不再构造一条命令行交给 shell 解释，改成给出一个 argv 数组。

**Blocked by:** 03（脚本层已存在）

**Status:** resolved

**Why:** 这张票由 M2 全段的 code-review（基线 `552414f`）开出，不是新需求。事实已在 ADR-0002 的修订记录里记账，这里只重复结论：社区包的 `ExecSpec` 只有一个 `String command` 字段，没有 argv 形态；`LocalExecBackend` 起进程时在这个字符串前面拼自己的 shell 前缀，于是传给它的字符串会被 shell 解释。脚本层现在正是这么调的，唯一挡住注入的是 `ScriptPackageRunner` 里那处手写 `quote()`。`NoHandBuiltShellCommandTest` 扫的是源码里的 shell 字面量，这条路径一个字面量都不含，所以扫描扫不到它——机器闸是绿的，而它要挡的事正在发生。

**Demo:** 一个参数里带 shell 元字符的调用（`;` / `$(...)` / 反引号 / 空格）不会被执行出第二条命令；同时正常的参数（中文城市名、带空格的句子）照样传得进去。

## 必测

- [x] 参数不再经过任何命令解释器：入口脚本与参数以 argv 数组交给进程，谁都不解释它们
- [x] 反面对照：一个带 `; 第二条命令` 或 `$(...)` 的参数，跑出来的输出里没有第二条命令的痕迹（今天靠 `quote()`，改成 argv 之后由"根本没有解释器"挡住）
- [x] 正面对照：正常参数（含空格、含中文）仍然传得进去——只断言"没被执行"的话，一个把所有参数都拒掉的实现也是绿的
- [x] `SandboxPolicy#resolveInside` 的四条（相对路径、越界、符号链接、绝对路径）在换执行方式之后仍然各自有一条断言指着
- [x] 沙箱那五条（工作目录、环境、超时、非 root、参数）仍是绿的，`ScriptPackageRunnerTest` 与 `SandboxedProcessBackendTest` 不因为换实现而改判据
- [x] 生成器那条自测链路（`GeneratorSandbox`）跟着换，且"没有 shebang 的脚本"有明确的处理方式（静态检查加一条 `NO_SHEBANG` 挡在落盘之前，执行时再报一句指名"第一行"的话）
- [x] 变异检验：把 argv 重新拼成一条字符串 → 第二条与第三条红；去掉 shebang 检查 → 三条"缺 shebang"红；把架构规则豁免的类名改错 → `AppArchitectureTest` 红

## 落点

- 接缝在 `ExecBackendFactory`（现在叫这个名字，返回社区包的 `ExecBackend`）。**它必须换掉**：`ExecBackend.run(ExecSpec)` 只有 `String command` 一个入口，argv 过不去。新的接缝自己定，调用方（`ScriptPackageRunner`）只认它。
- 起进程那一处要自己写（`ProcessBuilder(List<String>)`，或平台对应的等价物）。**代价写在下面。**
- `SandboxedExecBackend` 现在兼做"四条沙箱约束的守门人"与"转发给社区后端"两件事，换的时候这两件事要分开：约束是策略，起进程是执行。

## 注意

- **这张票要动红线。** `AppArchitectureTest` 有一条 `noClasses().that().resideInAPackage("io.github.claw4j..").should().dependOnClassesThat().haveFullyQualifiedName("java.lang.ProcessBuilder")`（外加 `Runtime.exec`）。改成自己起进程就必须收窄这条规则。按 `docs/agents/guardrails.md` 的规矩：**放宽那一步不能混在别的改动里**，就在这张票里做，并在下面写清取舍。收窄的方向建议是"只在被点名的那一个'起进程的地方'允许"，而不是把规则删掉——删掉之后"Java 侧不起进程"这件事就没有任何机器证据了。
- 备选方案（如果上面的代价不接受）：**不动代码，把现状如实写进 ADR-0002 与 README**。今天已经这么做了（修订记录里写了"唯一防线是 `quote()`"），可以停留在这个状态，只要 README 里那句"Java 侧不自己拼命令行"同时说清脚本层是例外。选这条就把本票关成 `wontfix`，并把判据（手写 `quote()` 的正确性）单独加一条测试盯着。
- 入口脚本的可执行位与 shebang：今天交给 shell 执行，没有 shebang 也能跑；改成直接执行之后不能。三个 `examples/scripts/*/run.sh` 都有 `#!/bin/sh` 且有可执行位，生成器写出的脚本有可执行位但 shebang 取决于模型写了什么——这一处先定好，别等联调才发现。

**不要做:** 换沙箱形态（仍是应用容器内的子进程，ADR-0003 第 ① 类）；碰网络隔离（那是第 ② 类的事，06 已记账）。

## Answer

按主方案做完了，备选方案（关成 `wontfix`、给手写 `quote()` 补一条测试）没有选。

**接缝整条换掉。** 社区包的 `ExecBackend` / `ExecSpec` / `ExecResult` 从本项目里退出，
`claw4j-tools` 多了一个 `io.github.claw4j.tools.process` 包：`ProcessRequest(argv, timeout)`、
`ProcessOutcome(status, exitCode, stdout, stderr)`、`ProcessRunner`（接缝）、
`LocalProcessRunner`（起进程的那个类）、`ProcessStartException`。
调用方 `ScriptPackageRunner` 只认 `ProcessRunner`——换沙箱这件事仍然只有一处可改。

**约束与执行分开了**（票里要求的那件事）：`SandboxedProcessBackend` 只过四条策略
（身份、超时；工作目录与环境在构造执行器时给定），`LocalProcessRunner` 只负责
"按 argv 起进程、到点掐断、收回两路输出"。第 ② 类沙箱共用同一个执行器。

**红线收窄的形状**：`AppArchitectureTest` 从"全仓不得依赖 `ProcessBuilder`"
改成"除 `LocalProcessRunner` 这一个类外不得依赖"。写在规则注释与 ADR-0002 的修订记录里，
连同"这条红线原先拦住的不是错的东西"那句话。变异验证过：把豁免的类名改错，规则当场红。

**两处顺带的**，都不是新需求而是换执行方式的直接后果：
① 入口脚本必须有可执行位与 shebang——静态检查加 `NO_SHEBANG` 挡在落盘之前，
执行侧对漏过去的给一句指名"第一行"的话（不再有解释器兜底了）；
② `SandboxPolicy#requireNoInjectedEnvironment` 撤销——`ProcessRequest` 里没有环境变量这一项，
"调用方往执行环境里塞变量"在类型上就没有入口。盯住它的换成 `LocalProcessRunnerTest` 里
"执行请求里没有环境变量这一项"那条（用反射盯字段，防的是有人再加一个 env）。

测试：新增 `LocalProcessRunnerTest` 10 条；`ScriptPackageRunnerTest` 13 条（元字符、
命令替换各一条反面，空格与中文一条正面，缺 shebang 一条）；`GeneratorSandboxTest` 9 条。


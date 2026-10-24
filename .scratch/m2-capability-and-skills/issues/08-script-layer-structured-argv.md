# 08: 脚本层改成结构化 argv，不再把命令行交给 shell 解释

**What to build:** 让 ADR-0002 的文字判据在脚本层（ADR-0003 的第 ③ 层）也成立：Java 侧不再构造一条命令行交给 shell 解释，改成给出一个 argv 数组。

**Blocked by:** 03（脚本层已存在）

**Status:** needs-triage

**Why:** 这张票由 M2 全段的 code-review（基线 `552414f`）开出，不是新需求。事实已在 ADR-0002 的修订记录里记账，这里只重复结论：社区包的 `ExecSpec` 只有一个 `String command` 字段，没有 argv 形态；`LocalExecBackend` 起进程时在这个字符串前面拼自己的 shell 前缀，于是传给它的字符串会被 shell 解释。脚本层现在正是这么调的，唯一挡住注入的是 `ScriptPackageRunner` 里那处手写 `quote()`。`NoHandBuiltShellCommandTest` 扫的是源码里的 shell 字面量，这条路径一个字面量都不含，所以扫描扫不到它——机器闸是绿的，而它要挡的事正在发生。

**Demo:** 一个参数里带 shell 元字符的调用（`;` / `$(...)` / 反引号 / 空格）不会被执行出第二条命令；同时正常的参数（中文城市名、带空格的句子）照样传得进去。

## 必测

- [ ] 参数不再经过任何命令解释器：入口脚本与参数以 argv 数组交给进程，谁都不解释它们
- [ ] 反面对照：一个带 `; 第二条命令` 或 `$(...)` 的参数，跑出来的输出里没有第二条命令的痕迹（今天是靠 `quote()` 挡住的，改成 argv 之后这件事由"根本没有解释器"挡住）
- [ ] 正面对照：正常参数（含空格、含中文）仍然传得进去——只断言"没被执行"的话，一个把所有参数都拒掉的实现也是绿的
- [ ] `SandboxPolicy#resolveInside` 的四条（相对路径、越界、符号链接、绝对路径）在换执行方式之后仍然各自有一条断言指着
- [ ] 沙箱那五条（工作目录、环境、超时、非 root、参数）仍是绿的，`ScriptPackageRunnerTest` 与 `SandboxedExecBackendTest` 不因为换实现而改判据
- [ ] 生成器那条自测链路（`GeneratorSandbox`）跟着换，且"没有 shebang 的脚本"有明确的处理方式（要么静态检查加一条规则挡在落盘之前，要么执行时报一句指得出原因的错）——直接执行与交给解释器执行对 shebang 的要求不一样，这是这次改动最容易漏的一处
- [ ] 变异检验：把某个参数重新拼进一条命令行交给 shell → 上面第二条红

## 落点

- 接缝在 `ExecBackendFactory`（现在叫这个名字，返回社区包的 `ExecBackend`）。**它必须换掉**：`ExecBackend.run(ExecSpec)` 只有 `String command` 一个入口，argv 过不去。新的接缝自己定，调用方（`ScriptPackageRunner`）只认它。
- 起进程那一处要自己写（`ProcessBuilder(List<String>)`，或平台对应的等价物）。**代价写在下面。**
- `SandboxedExecBackend` 现在兼做"四条沙箱约束的守门人"与"转发给社区后端"两件事，换的时候这两件事要分开：约束是策略，起进程是执行。

## 注意

- **这张票要动红线。** `AppArchitectureTest` 有一条 `noClasses().that().resideInAPackage("io.github.claw4j..").should().dependOnClassesThat().haveFullyQualifiedName("java.lang.ProcessBuilder")`（外加 `Runtime.exec`）。改成自己起进程就必须收窄这条规则。按 `docs/agents/guardrails.md` 的规矩：**放宽那一步不能混在别的改动里**，就在这张票里做，并在下面写清取舍。收窄的方向建议是"只在被点名的那一个'起进程的地方'允许"，而不是把规则删掉——删掉之后"Java 侧不起进程"这件事就没有任何机器证据了。
- 备选方案（如果上面的代价不接受）：**不动代码，把现状如实写进 ADR-0002 与 README**。今天已经这么做了（修订记录里写了"唯一防线是 `quote()`"），可以停留在这个状态，只要 README 里那句"Java 侧不自己拼命令行"同时说清脚本层是例外。选这条就把本票关成 `wontfix`，并把判据（手写 `quote()` 的正确性）单独加一条测试盯着。
- 入口脚本的可执行位与 shebang：今天交给 shell 执行，没有 shebang 也能跑；改成直接执行之后不能。三个 `examples/scripts/*/run.sh` 都有 `#!/bin/sh` 且有可执行位，生成器写出的脚本有可执行位但 shebang 取决于模型写了什么——这一处先定好，别等联调才发现。

**不要做:** 换沙箱形态（仍是应用容器内的子进程，ADR-0003 第 ① 类）；碰网络隔离（那是第 ② 类的事，06 已记账）。

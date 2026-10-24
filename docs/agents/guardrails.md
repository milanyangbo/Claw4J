# Guardrails

**这台仓库哪些事是强制的、哪些是有意不强制的、以及改一条强制项要怎么办。**

评审时判断"这是缺陷还是已知状态"看这一份就够了：强制的在上半部分，
记账过的例外在下半部分。编码层面的判断项（机器查不了的那部分）在
`CODING_STANDARDS.md`，不在这里；决策与判据在 `docs/adr/`。

## 闸的清单

| 闸 | 跑什么 | 谁跑 | 归哪条 ADR |
| --- | --- | --- | --- |
| `.githooks/pre-commit` | `*ArchitectureTest` 五个 + `NoHandBuiltShellCommandTest` | 每次 commit（只改了文档时跳过） | ADR-0002、ADR-0010 |
| `ArchitectureTest`（各模块） | ArchUnit：依赖方向、框架越界 | pre-commit + CI | ADR-0010 |
| `AppArchitectureTest` | `io.github.claw4j..` 全仓不得依赖 `java.lang.ProcessBuilder`、不得调 `Runtime.exec` | pre-commit + CI | ADR-0002 |
| `NoHandBuiltShellCommandTest` | `claw4j-*/src/main/java` 源码（含注释）里不得出现 shell 字面量 | pre-commit + CI | ADR-0002 |
| `./scripts/assert-no-skipped-tests.sh` | 有测试被 skipped 就判失败 | CI（verify 之后） | ADR-0011 |
| `./mvnw verify -Dclaw4j.dbTests=required` | 全量，连真实 DeepSeek；没 Docker 时数据库测试直接失败而非跳过 | CI | ADR-0011 |

`pre-commit` 的模块清单里**必须带 `claw4j-app`**：`AppArchitectureTest` 与
`NoHandBuiltShellCommandTest` 都住在那儿，少这个模块这两条在提交前就跑不到——
这个坑出过一次（2026-10-24 的 code-review 才发现）。

## 改一条红线，就新开一张票

`docs/adr/` 里带"禁令"字样的决定（0002 的命令行、0007 的鉴权、0010 的分层、
0011 的真模型）各有一条机器检查盯着。**改代码去迁就一条红线，和改一条红线去放代码过，
是两件代价完全不同的事**，而后者在 diff 里长得和前者一样。

判据：如果一次改动里既有新代码、又有一条检查被放宽，就把放宽那一步单独开一张票，
在票里写清"这条红线现在拦住的是对的东西，为什么"。

理由不是流程洁癖。01 号票那个"登记表让位"的形状（`@ConditionalOnMissingBean`
让第二个来源的登记表静默不生效）在 MCP、脚本层、生成器各重演了一次，
每一次都是"为了让这一层成立"顺手加的。顺手放宽的那一次没人会回头看，
开票的那一次至少留下一句人读得懂的取舍。

当下的实例：脚本层想把命令行改成结构化 argv，就得自己起进程，
也就必须收窄 `AppArchitectureTest` 那条 `ProcessBuilder` 禁令。
这件事开成了票 08（`issues/08-script-layer-structured-argv.md`），
**没有**在修另外两个缺陷的同一批里动。

## 已知偏差：做不到的地方怎么记账

判据是：**有人能在不看代码的情况下知道这个偏差是有意的**。

写在代码注释里的偏差，评审的人得先怀疑它是不是缺陷、再去翻票，
翻不到就会当成缺陷报出来；记账过的偏差，评审的人第一眼就知道该查的是
"补偿手段够不够"而不是"这是不是 bug"。2026-10-24 那次 code-review 里，
两个评审代理各把一条已记账的偏差当成缺陷报了——不是它们审错了，
是那些偏差只写在代码与票里，没有一份清单。

写法照 `IsolationSpec.Network.NOT_ISOLATED`：枚举值本身带着"没有隔离"的字样，
还有一条断言盯着它不许撒谎。防的是"改成一个叫 networkIsolated 的布尔量让规格看起来更漂亮"。

| 偏差 | 要求是什么 | 实际给的 | 补偿 | 记在哪 |
| --- | --- | --- | --- | --- |
| 第 ② 类沙箱没有网络隔离与资源上限 | 网络受限、资源有上限 | 临时目录副本 + 只有 PATH + 5 秒，如实写 `NOT_ISOLATED` | 联网的脚本在静态检查那一关被挡（`NETWORK_CLIENT` / `PY_NETWORK`） | 票 06、ADR-0002 修订、ADR-0013 |
| "非 root"取 `user.name` 而非 uid | 非 root 身份执行 | uid 0 而 `user.name` 非 root 时会放行；macOS/CI 上恒非 root，等于从不触发 | 真正的保证是容器镜像的 `USER`；这一条只挡"应用被跑成 root" | 票 03（写明理由：uid 没有跨平台的纯 Java 读法） |
| 脚本层的命令行仍交给解释器 | ADR-0002：Java 侧不构造命令行交给 shell 解释 | 执行接缝 `ExecSpec` 只有 `String command`，本地实现默认加 shell 前缀 | 每个参数先过沙箱路径校验、再按 POSIX 单引号转义；**没有自动检查覆盖这一处** | ADR-0002 修订、README、票 08 |
| MCP 的"跨进程"在 CI 上是进程内 stdio | 跨真实 OS 进程 | 进程内管道版（surefire 下拿子进程 classpath 太脆） | 真子进程的 server 打 `real-cli` tag，`-Preal-cli` 才跑 | 票 02、ADR-0014 |
| 测试里有两个假 `ChatModel` | ADR-0011：不对 `ChatModel` 打桩 | `FixedChatModel`、`SubagentModelStub` | 只在"要验的东西不在模型身上"时用 | 先例记在 M2-real 票 01 |

最后一行本身就是个例子：先例记在了一张票里，第二处照着做却没补记，
于是评审时会显得像违规。记账要跟着**用的地方**走，不是跟着第一次决定的那张票走。

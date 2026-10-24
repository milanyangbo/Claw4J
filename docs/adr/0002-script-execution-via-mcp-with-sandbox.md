# 脚本执行改由 MCP 收口，禁止 Java 直接拼命令行

---
status: accepted
date: 2026-10-22
---

1.x 中 Agent 执行脚本的路径是：`GenericSkill` 用 `listFiles()[0]` 取一个 `.py`，把用户自然语言当 argv 传进去，经 `sh -c` 或 `ProcessBuilder` 执行；`ShellUtils` 另有硬编码 root、关闭主机校验的 SSH 通道；`SkillGeneratorSkill` 还会让模型写出 Python 落盘执行，并允许脚本自行 `pip install --break-system-packages`。这条链路把宿主机 shell 交给了模型。新架构中 Java 侧不再直接调用任何脚本进程。

> 修订记录（2026-10-22）：初版结论是"模型生成的脚本只落盘不执行"。确认 skill-generator 是本项目核心能力后，改为"可以执行，但只在一个隔离沙箱里执行，且全程留审计"。执行位置的约束没有放宽，放宽的是能不能跑。
>
> 修订记录（2026-10-24，M2-06 落地）：初版写的"隔离沙箱（容器，网络受限、有资源上限）"里，**容器**这一项没有落成代码。判据沿用 ADR-0003 第 ① 类那条——CI 的 runner 上没有 Docker 守护进程，依赖容器的沙箱在 CI 上就是一串红灯，而跑不起来的沙箱等于没有测试。第 ② 类沙箱（未审核代码的自测位置）最终落在"临时目录副本 + 最小环境 + 5 秒上限"上（ADR-0013），给的三样是：工作目录隔离、环境变量隔离、超时。**网络隔离与内存/CPU 上限这两项它给不了**：纯 Java 起的子进程没有可移植的限制手段（`unshare` / `sandbox-exec` 都是要再起一条命令，而那正是本 ADR 禁止的事），于是规格里如实写成 `NOT_ISOLATED` 并写明原因，真正的网络隔离由部署层的容器网络策略给；联网的脚本在静态检查那一关就被挡了。README 与 GLOSSARY 不把这两项写成承诺。
>
> 修订记录（2026-10-24，M2-07 落地）：本 ADR 禁的是**Java 侧构造一条命令行交给 shell 解释**。M2-07 接真实外部 CLI 时出现的形态（MCP 端点的 `command` 与 `List<String> args` 分开给、交给 MCP SDK 用 `ProcessBuilder` 起进程）**不违反本 ADR**：两者都只是给出一个 argv 数组，谁都不解释它。更根本的差别是模型的输出进不了那条命令行——工具入参走 MCP 的 JSON-RPC 帧，不会成为 argv 的一部分。界线与凭据那一条（`Script Package` 的子进程只拿得到 PATH，所以依赖 key 的能力走不了脚本层）都写在 ADR-0014 里。
>
> 修订记录（2026-10-24，code-review 之后）：**上面的界线在脚本层（ADR-0003 第 ③ 层）还没成立，本 ADR 的文字判据与那里的代码不一致**。事实记在这里，免得下次再从依赖里挖一遍：社区包 `spring-ai-agent-utils` 的执行接缝形状就是一条命令行——`ExecSpec` 只有一个 `String command` 字段，没有 argv 形态；它的 `LocalExecBackend` 起进程时在这个字符串前面拼上自己的 shell 前缀（POSIX 上是 bash 的 `-c`，Windows 上是那边解释器的 `/c`），于是传给它的字符串**会被 shell 解释**。脚本层当时正是这么调的：`ScriptPackageRunner` 拼出 `"./run.sh" + 逐个加过单引号的参数`，交给这个后端。挡住注入的只有那一处手写 `quote()`：参数先过 `SandboxPolicy#resolveInside`（越界即拒绝），再按 POSIX 单引号转义。`NoHandBuiltShellCommandTest` 扫的是整份源码里的 shell 字面量，这条路径一个字面量都不含，所以它扫不到——机器闸是绿的，但"Java 侧不构造命令行交给 shell"这句话在这里不成立。
>
> 修订记录（2026-10-24，M2-08 落地）：**上面那条不一致已经消掉了，脚本层现在给的是 argv 数组**。做法：社区包那条接缝整条放弃，起进程收回到本项目一个被点名的类 `LocalProcessRunner`（`claw4j-capability` 的 `io.github.claw4j.capability.process` 包），它只接受 `ProcessRequest(argv, timeout)`——没有 `String command` 这种入口，环境变量也只在构造时由沙箱给一次。于是：① 参数不再需要任何转义，"挡住注入"不再依赖一处手写代码写得对不对；② 入口脚本必须自己可执行、第一行给出解释器（缺 shebang 由静态检查挡在落盘之前，漏过去的由执行侧给一句指名"第一行"的话）；③ 沙箱那四条策略（工作目录、环境、超时、非 root）与"起进程"分成两类代码，换沙箱形态时不用重写约束。
>
> **代价（红线被收窄了这一次）**：`AppArchitectureTest` 的"全仓 `io.github.claw4j..` 不得依赖 `java.lang.ProcessBuilder`"收窄成"除了 `LocalProcessRunner` 这一个类"。收窄而不是删除的理由：原先那条规则逼着脚本层把一条命令行交给社区包，而那条路是经命令解释器执行的——规则守住了"本项目不起进程"，却把"参数被解释器当成语法"这扇门留着，唯一挡它的是一处手写引号。也就是说，**这条红线原先拦住的不是错的东西，它拦住了"自己起进程"，却把真正要防的那件事放过去了**。删掉规则更省事，但那样"Java 侧不起进程"就没有任何机器证据，所以留着规则、只开一个点名的例外。按 `docs/agents/guardrails.md` 的规矩，这一步单独开票（`.scratch/m2-capability-and-skills/issues/08-script-layer-structured-argv.md`），没有混在修另外两个缺陷的那一批里。
>
> 顺带删掉一条检查：`SandboxPolicy#requireNoInjectedEnvironment` 撤销了——请求里不再有环境变量这一项，"调用方往执行环境里塞变量"在类型上就没有入口（结构上的保证比运行时再判一次更靠得住，它不会被人忘掉调用）。盯住这件事的是 `LocalProcessRunnerTest` 里那条"执行请求里没有环境变量这一项"。

## Considered Options

**保留进程调用，只做参数白名单**
否决。参数白名单挡不住脚本自身的副作用（如自动装包、读写任意路径），而白名单本身会成为长期维护负担，且面试时讲不出设计依据。

**全部能力用 Java `@Tool` 重写，不留脚本**
部分采纳，作为首选路径。但外部 CLI 依赖（`ffmpeg`、`gh`、邮件客户端）用 Java 重写的成本不划算，强行重写会拖慢主线。

**脚本经 MCP stdio server 暴露（采纳）**
脚本退化为 MCP server 的内部实现；Java 侧只见工具协议，只见 `@McpTool` 的名字与参数。连接建立时 `listTools()` 为空即 fail-fast，不等到调用时才暴露配置错误。执行在容器内，网络受限、非 root。

## Consequences

- Java 侧不再出现 `Runtime.exec` / `ProcessBuilder` / `sh -c`；给出 argv 数组让 SDK 起进程是另一回事（见上面的修订记录与 ADR-0014）。
- 敏感配置（数据库连接串、API key）从 `SKILL.md` 的 YAML 块移回 Spring 配置，走环境变量与 profile。
- **skill-generator 保留，但换执行位置**：模型生成的脚本仍可被执行自测，只是执行发生在隔离沙箱（容器，非 root、网络受限、有超时与资源上限），不在应用进程、不在宿主机。自测结果写入审计，脚本成为正式 Script Package 前默认需要人工确认。这条是项目核心能力，不能因为原实现危险就整体砍掉，正确的做法是把它关进笼子。
- 模型生成脚本的自测链路固定为：生成 → 静态检查（禁止危险 import 与越权文件操作）→ 沙箱内自测 → 审计留痕 → 人审通过后才注册。
- MCP 会话丢失（`McpTransportSessionNotFoundException`）必须重新初始化连接而非重试死会话，这是连接层要处理的第一个故障。

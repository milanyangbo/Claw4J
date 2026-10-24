# 03: Script Package 受限执行

**What to build:** 让 Agent 用上一个**脚本**能力：脚本本体不进上下文，只有输出进；执行的边界是"只能碰自己的沙箱目录、有超时、非 root、拿不到任何业务凭据"。ADR-0003 把 Script Package 定为三层，ADR-0002 禁止 Java 侧直接拼命令行——这张票是三层里最后一层的第一次落地。

**Blocked by:** 01

**Status:** resolved

**Demo:** 一个真实脚本包端到端跑通：问一句话 → 脚本被调用 → 输出进回答。同时演示"脚本想碰沙箱目录之外的东西"被挡住，且挡住这件事有一条断言指着。

- [x] 沙箱约束：工作目录锁在脚本自己的目录内、有超时、非 root
- [x] 执行环境里拿不到业务库凭据与 API key —— "换个执行位置"不等于"换了个能拿到凭据的位置"
- [x] 脚本本体不进上下文、只有输出进（GLOSSARY 的 Script Package 定义）
- [x] ArchUnit 规则：Java 侧不出现 `Runtime.exec` / `ProcessBuilder` / `sh -c`（ADR-0002），靠机器查而不是靠 review 记
- [x] 脚本层不授予任何业务表权限，登记内容是"无业务表 + 只能访问自己的沙箱目录"；**留空不等于无需权限，而是拒绝**
- [x] 端到端（`AgentPort`）与流式路径各一条
- [x] **沙箱的物理形态在这张票定**，判据是"能不能在 CI 里跑起来"：跑不起来的沙箱等于没有测试，没有测试的边界不能写进 README 当承诺
- [x] 变异检验：放宽工作目录 / 去掉超时 / 漏掉权限登记 → 各自对应的测试红

**注意:** 执行后端用社区包里的 `ExecBackend` 接口（`org.springaicommunity:spring-ai-agent-utils`，0.13.0，根 POM 已在 dependencyManagement 声明但尚无模块引入；`ExecSpec` / `ExecResult` 在它的 `-common` 里）。换沙箱等于换实现，调用方不动——这是这条边界**唯一**可测的地方，不要把"本地执行"写死在调用方。

**不要做:** Skill Generator 那个沙箱（那是另一个执行位置，06）；真实外部 CLI。

---

## Answer

落在 `claw4j-tools` 的 `io.github.claw4j.tools.script`。模型看到的仍然是"工具名 + 一个可选参数"，
脚本本体不进上下文——调用方唯一的返回路径是 stdout，拿不到脚本文件的任何一部分。

### 物理形态：应用容器内的受限子进程

判据"能不能在 CI 里跑起来"直接否决了容器方案：**CI 的 runner 上没有 Docker 守护进程**
（数据库那一类是靠 `CLAW4J_POSTGRES_IMAGE` 指定的镜像），依赖容器的沙箱在 CI 上就是一串红灯。
社区包的 `LocalExecBackend` 自带 `workingDirectory` 与 `cleanEnvironment`，不需要 Docker 也不需要 Python。

换沙箱的接缝是 `ExecBackendFactory`（默认 `SandboxedExecBackend::forPackage`）：
`ScriptPackageRunner` 只认 `ExecBackend`，换形态只改注入进来的那个实现
（`ScriptPackageRunnerTest.后端可替换` 用注入的记录器证明了这条缝存在）。

它**挡得住**的五样，每样一条测试：**工作目录**（`pwd` 验）、**参数**（`..` / 绝对路径 / 符号链接一律拒绝）、
**环境变量**（从零开始只给 `PATH`）、**超时**（上限 30 秒，不给或超上限就拒绝、不夹取）、**非 root**（root 身份拒绝执行）。

它**挡不住**的，写进了 README 与 GLOSSARY，不拿去当承诺：脚本**体内**自己去读绝对路径、自己去连网。
进程沙箱锁得住入口与参数，锁不住脚本自己的行为——那要靠 ADR-0003 的第 ② 类沙箱（06）。

### 两条机器检查，各管一半

- ArchUnit（新增 `AppArchitectureTest`，它是唯一能同时看见所有模块的地方）：
  `io.github.claw4j..` 主代码不许调用 `Runtime` 的 `exec`、不许碰 `ProcessBuilder`。
- 源码扫描（新增 `NoHandBuiltShellCommandTest`）：`claw4j-*/src/main/java` 里不许出现手写的
  shell 调用字面量。这一条挡的是"把命令行当一个字符串传下去"——那种写法起进程的不是本项目，
  API 那条规则看不见它，而宿主机的命令行照样交出去了。

两条都做过变异验证：分别注入 `ProcessBuilder` 与 `Runtime.exec`，规则各红一次。

写 ArchUnit 时踩到一个**会写出一条永远不生效的规则**的坑，记在这里：
`callMethod(Runtime.class, "exec")` 的第三个参数是可变形参类型，写成 `"exec"` 等于
"无参的 exec"，而 `exec` 没有一个无参重载——规则不报错、永远匹配不上，显示成绿却什么也没拦。
改用 `callMethodWhere` 加显式谓词。

### 权限登记：动作是 `run_script`，表一张都没有

"表是空的"不等于"不需要权限"——这两句在登记表上长得一模一样，区别只在于有没有人显式登记过；
没登记过的工具一律按拒绝（ADR-0007）。

动作刻意**不是** `read`：只读角色能查数是"只读"的本意，能执行脚本就不是了——
脚本能写文件、能往外连，那是比读一张表宽得多的口子。

### 参数校验：不做"像不像路径"的预判

早先那版只对"看着像路径"的参数（含 `/` 或以 `.` 开头）做校验，那是个会漏的判据：
包目录里放一个名叫 `leak.txt`、指向外面的符号链接，模型只要给这个**裸文件名**就能读出来。
改成每个参数一律过 `SandboxPolicy.resolveInside`：普通字符串本来就落在包目录内，
校验一定通过，代价是零。

### 偏差

1. **沙箱跨的是进程边界不是容器边界**（与 02 的"进程内 stdio"同一个取向）：换容器的接缝留在
   `ExecBackendFactory`，换的时候调用方一行不动。
2. **"非 root"落地为"拒绝以 root 身份执行"**，不是降权：降权要在 fork 前 setuid，
   纯 Java 拿不到那条路（除了再起一个外部命令，那就又把宿主机的命令行交出去了）。
   容器镜像的 `USER` 才是真正保证非 root 的那一层，这里的作用是"就算有人把应用跑成了 root，
   脚本层也不会跟着变成 root"。判据取 `user.name` 而不是 uid：uid 没有跨平台的纯 Java 读法。
3. **后台执行（`ExecBackend.start`）直接拒绝**：`ExecHandle` 没有超时，留着它等于
   "脚本层有超时"被一个不在这张票上的开关悄悄打开。
4. **脚本层默认不启用**：开关是"有没有配 `claw4j.scripts.directory`"，不是"classpath 上有没有这个类"。
   配了却不存在即启动失败——那是配置错误，不是"这一层不存在"。

### 变异检验

| 变异 | 结果 |
| --- | --- |
| 去掉 `workingDirectory`（放宽目录） | 3 条红（目录锁、环境、超时——后两条是因为 `./run.sh` 找不到了） |
| 去掉 `requireTimeoutWithinCap` | `不给超时被拒绝` 红 |
| `cleanEnvironment(false)` | `执行环境里没有凭据` 红 |
| 权限登记成 `read()` | `权限登记的形状` 与 `只读角色跑不了脚本` 红 |
| 参数不过 `resolveInside` | `越界参数被挡住` 与 `裸文件名上的符号链接被挡住` 红 |
| 注入 `ProcessBuilder` / `Runtime.exec` | ArchUnit 规则各红一次 |
| 主代码里写 shell 调用字面量 | 源码扫描红（写这条规则时它自己先红了一次，逼着改了 Javadoc 的措辞） |

229 个测试全绿、skipped 0。

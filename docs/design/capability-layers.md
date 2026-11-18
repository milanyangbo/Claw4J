# 能力三层：对 Agent 呈现为同一件事

> 设计说明：为什么这么做、否决了什么。要看结论去 [README](../../README.md)，要看决策去 [ADR](../adr/)。

能力按"要不要鉴权、有没有外部副作用、依不依赖外部运行时"分三层（ADR-0003），
**三层对 Agent 呈现为一回事——都是"工具名 + 参数 Schema"**，Agent 侧不出现"这是哪一层"的分支：

| 层 | 形态 | 状态 |
| --- | --- | --- |
| 一层 | Java `@Tool`（`SystemStatusTools.systemStatus`，以及示例插件里的 `SalesTools.querySales`） | M1 已落地，M2-07 补齐 `system-status`；示例业务工具在独立模块 `claw4j-example-sales` |
| 二层 | MCP server 的 `@McpTool` | M2-02 已落地（连接生命周期），M2-07 接到真的 OS 进程，见下 |
| 三层 | 技能里的脚本（受限执行） | M2-03 落地时叫 Script Package；2026-11-18 按 OpenClaw 的形态并进技能，见下 |

三层之外还有两件事挂在同一套工具契约上：**Agent Skills**（M2-04，命中才加载全文）与
**Subagent**（M2-05，派出去跑、只回结论）。原来还有第三件 **Skill Generator**（M2-06），
2026-11-18 整层删了，理由在下面那一节。

MCP 这一层的连接生命周期由 `claw4j-capability` 的 `io.github.claw4j.capability.mcp` 管，两条语义是硬要求：

- **连接建立时工具清单为空即失败**（ADR-0002）。连接是**通的**——协议握手成功、server 起来了——
  只是它一件能力都没有。这种状态不报错的话，现场是"模型说它没这个能力"，
  而真正的原因是命令写错或版本对不上。
- **会话丢失（`McpTransportSessionNotFoundException`）重新初始化连接，而不是重试死会话**。
  对一条已经不在的会话重试，会让一次断连变成永久不可用。

这两条是 ADR-0008 的三条必测之一，所以测试里的 MCP server 是 **Java 自实现的进程内 stdio server**
（`InProcessStdioMcpServer`，在 `claw4j-capability` 的**测试源码**里——它是替身，不该进发行物）：
不依赖 Docker、不依赖 Python，于是这两条进得了默认构建。
**它跨的是协议边界不是 OS 进程边界**。真实部署换的是 `McpServerEndpoint.openTransport()`
给出来的传输，上面的生命周期代码一行都不用改——M2-07 就是这么接上真的 OS 进程的：

```java
// 命令与参数分开给，交给 MCP SDK 起进程（不经任何命令解释器）
// 第二个参数是「这个 server 的哪些工具存在」：排掉的工具根本不会变成工具，
// 也就不进清单、不进档位、加不回来（ADR-0030 裁决二）
ProcessStdioMcpEndpoint.of("claw4j-github", "python3",
        List.of("examples/mcp/gh_mcp_server.py"), Map.of("PATH", System.getenv("PATH")))
    .endpoint(ToolFilter.none());
```

`examples/mcp/` 下有三个零第三方依赖的 Python server：`gh_mcp_server.py` 包 `gh`、
`himalaya_mcp_server.py` 包 `himalaya`（它导出会发邮件的 `mail_send`，接它的端点要用
`include` 点着名字放进来——**能发邮件是"这个工具存在"，不是"这个人有权限发"**，
后者由清单与审批管）、`demo_mcp_server.py` 不依赖任何 CLI。
同一个端点接一个 **Java** 版也是同样的写法：`claw4j-example-mcp-server` 是一个
`java -jar` 就能跑的 Streamable HTTP server（`/mcp`），与那三个 Python 的对端完全平级——
宿主只认协议，不认对端用什么语言写、用什么传输。它的工具刻意不与 Python 那份重名：
`runtimeSnapshot`（进程身份与调用计数）、`cronNext`（真算一遍触发时间）、
`simulateSlowCall`（卡请求超时的边界）。
子进程的环境**只能加、不能减**：显式声明的变量一定在（需要 `PATH` 就写一行），而父进程的其余
变量也会被继承——SDK 起进程时只 `putAll` 不清空（见 `ProcessStdioMcpEndpoint` 类注释）。
要隔离得靠部署层的容器策略；脚本层那一路是另一回事，它自己 `clear()` 后再填。

**另一种传输是 HTTP。** 对方的 server 已经跑着，我们只当一个 JSON-RPC 客户端：
`transport: http`（Streamable HTTP，2025 版协议的标准）与 `transport: sse`（旧版 HTTP+SSE）
走的是 `HttpMcpEndpoint`——不起进程、没有什么"对端的环境"，凭据放在请求头里。
两者对上面那套连接生命周期（会话丢失重开、连不上时后台退避重试）完全透明：
换传输只是换 `openTransport()` 返回的对象，这与 stdio 那条是同一个接缝。

接**别人写的** server 不需要写 Java。`claw4j.capability.mcp.servers.<名字>` 下一段配置就是全部：
`application-local.yml.example` 末尾那段注释着的配置里就有一个 npm 上的 stdio server 与一个公网 HTTP 端点。
键名、默认值与失败姿势在 [extension-points.md](extension-points.md) 的第二节；
条件按**配置**求值（不是按容器里有没有 bean），所以配了才有这一层、不配就一层都没有。

这条链路真跑一次的测试分两组，都默认不跑（一组依赖本机装了什么，一组依赖能不能出网，
都不属于回归基线）：

```bash
./mvnw -Preal-cli -pl claw4j-capability test -Dtest=RealCliMcpEndToEndTest   # 真的 OS 子进程
./mvnw -Preal-cli -pl claw4j-capability test -Dtest=RealThirdPartyMcpTest    # 第三方：stdio + 公网 HTTP
```

**握手超时与调用超时是两件事，接慢启动器时两个都得调大。** `npx -y
@modelcontextprotocol/server-everything` 在本机实测**每次启动要 36 秒**（缓存已热也一样，
它要先解析包）。症状是 `Client failed to initialize by explicit API call`，server 侧却一切正常。
2026-10-28 做过三组对照：

| `request-timeout` | `initialization-timeout` | 结果 |
| --- | --- | --- |
| 默认 30s | 90s | 失败：`within 30000ms` —— **只调握手那个没用**，先超时的是请求超时 |
| 120s | 1s | 失败：`within 1000ms` —— 握手那个也不能压小 |
| 120s | 90s | 成功，启动 64 秒，五个 server 全连上 |

所以 `claw4j.capability.mcp.request-timeout`（单次调用，默认 30s）与 `.initialization-timeout`
（握手，默认 60s）要按启动器的真实启动时间一起给足，不必改代码。

MCP 层**不授予任何业务表权限**：它登记的权限里一张表都没有，鉴权只看动作。
没登记权限的工具一律按拒绝处理（ADR-0007）。按工具覆盖权限时（`servers.<名字>.tools.<工具名>`），
覆盖只影响被点名的工具；点名了 server 没导出的工具名，启动就失败。

## 技能里的脚本：脚本本体不进上下文，只有输出进

> **2026-11-18 改的**：原来是"脚本包"——一个目录一个工具，背后是一套自研的 argv 执行器。
> 按 OpenClaw 的形态改成**技能目录里的支撑文件**：技能本身不被执行（`skills_read` 只读正文），
> 脚本由模型用 `exec`（这里是 `Bash`）去跑。差别不是命名：原来那版是**第二个执行入口**，
> OpenClaw 只有一个。删除清单与影响面在
> [`.scratch/oss-alignment/issues/02-script-packages-degrade-to-skills.md`](../../.scratch/oss-alignment/issues/02-script-packages-degrade-to-skills.md)。

技能这一层的形态是"目录 + `SKILL.md` + 支撑文件"，脚本放在 `scripts/` 下：

```text
examples/skills/summarize/
  SKILL.md            # 什么时候用、怎么跑、期望什么输出、不要做什么
  sample.md           # 数据文件（与正文一起进审批摘要）
  scripts/run.sh
  scripts/summarize.py
```

**正文负责"路径"，工具负责"执行"**。模型看到的第一行是
`Base directory for this skill: <技能目录>`，正文里写 `bash {baseDir}/scripts/run.sh sample.md`，
`{baseDir}` 在交给模型之前就替换成那个目录（OpenClaw 的写法，抄一份过来能直接跑）。
执行那一步是 `Bash`：宿主用库的 `LocalExecBackend`、容器用库的 `DockerCliExecBackend`
（都在 `spring-ai-agent-utils` 里），本项目一行执行器都没有。容器那一档要
`claw4j.tools.sandbox.mode=all`，限额还没做（归 P17b，等上游加参数）。

| 这件事归谁 | 归谁 |
| --- | --- |
| 脚本在哪、要什么参数、输出算什么 | `SKILL.md`（人写的正文，会被审批） |
| 怎么起进程、收流、超时、回收 | 库（`LocalExecBackend` / `DockerCliExecBackend`） |
| 这条命令能不能跑 | exec 三段式：白名单 / 审批 / 复核（ADR-0023） |

**代价要记账**：脚本包那层"入口必须在包目录内、参数只能是包内相对路径"的校验，随包一起删了。
现在一条 `bash …/run.sh /etc/hosts` 能不能读到 `/etc/hosts`，取决于 exec 那一层的边界
（`claw4j.tools.exec.workspace`、`security=allowlist`、审批、有没有进容器），不取决于技能目录。
**容器的限额还没做**（P17b 等上游）：进容器解决的是"另一份文件系统"，不是"关得住"。
**这是变松的地方**，别在文档里写成"脚本只能读自己目录"。

**审批比原来紧**：技能目录的摘要覆盖目录下所有文件（含 `scripts/`），改一个脚本字节，
`.review/<name>.properties` 的 `sha256` 就作废。原来脚本是另一个账本，正文与脚本各审各的。

**`ProcessBuilder` 的红线现在是零例外**：`ToolsBoundaryTest` 与 `BuildContractTest` 里那两条规则
不点名任何类——全仓没有一个类起进程了。

启用这一层只需要一个目录（配了却不存在即启动失败，那是配置错误不是"这一层不存在"）：

```yaml
claw4j:
  skills:
    directory: examples/skills     # 目录树下每个 SKILL.md 是一份技能
```

## Agent Skills：命中才加载全文，加载完自己编排工具

前两节说的是"能力从哪来"，这一节说的是**模型怎么知道该按什么顺序用它们**。
`SKILL.md` 写的是"什么时候用得上、按什么流程用"，它**不提供动作，只编排动作**
（GLOSSARY 里 Skill 与 Script Package 的分工就在这里：一个是做法，一个是脚本）。

关键设计是**渐进式披露**：

| 时机 | 进上下文的是什么 | 判据 |
| --- | --- | --- |
| 启动 | 每个 Skill 的 `name` + `description`（几行 XML） | 模型靠这两行判断"有没有用得上的做法" |
| 命中 | 只有被选中那一份的正文，作为那次调用的结果回来一次 | 有二十个 Skill 的代价是二十行描述，不是二十份正文 |

这条边界两头都钉了断言——只钉"正文没进来"是不够的，"谁的正文都没进来"
（比如这一层压根没生效）也能全绿，所以同一份转储上还有一条正向断言盯着观测通道本身：

- 元数据与正文分离：工具描述里有元数据、**没有任何一份正文**；用名字调用才取回正文，且只取回那一份；
- 端到端：整条链路里两个 Skill 的元数据都在，**没被选中那份的正文不在任何一轮请求里**。

工具类型**没有自己再包一层**：社区包的 `SkillsTool.Builder.build()` 直接返回 `ToolCallback`，
它的工具名、输入 Schema、"用名字取正文"都在那一个类里。再包一层只会多出一个要同步维护的
描述字符串，而"描述里到底有什么"正是这里要盯的东西。

权限登记的是 **`use_skill` 动作，业务表一张都没有**——Skill 自己不读库，它让模型去读，
而那一步照样过鉴权。所以能读的角色（`read-only` / `guest`）也都给了 `use_skill`：
加载一份做法既不读表也不写东西。把它与 `read` 分开列，是为了让"谁能用什么做法"
成为一行配置能回答的问题，而不是"能读就顺便能用"。

启用这一层同样是显式配目录（配了却一个 `SKILL.md` 都没有即启动失败）：

```yaml
claw4j:
  skills:
    directory: skills      # 每个含 SKILL.md 的子目录是一份 Skill
```

**这里没有的**：让模型自己产出一份做法。这份 SKILL.md 仍然是人写的——
曾经有过一条"模型产出新能力"的链路（Skill Generator），2026-11-18 整层删了（见下一节）。

## Subagent：派出去跑，只把结论带回来

前两节说的是"能力从哪来、按什么顺序用"，这一节说的是**长任务别把中间过程摊在会话里**。
委派本身是父 Agent 的一个工具（`Task`），执行时新开一个独立上下文窗口（ADR-0012）：

```
父这一轮：用户问 → 模型决定派出去 → Task(结果只有一个字符串：子的结论) → 组织成回答
子那一轮：只拿到 task 这一句 → 自己调工具若干次 → 只把最后那句交回去
```

**做深的是上下文隔离，不是"起了第二个 Agent"**——后者配一个新 `ChatClient` 就有。
隔离盯三样，每样都有断言：

| 隔离 | 父那边看到什么 | 盯它的测试 |
| --- | --- | --- |
| 子的中间轨迹 | 不进父上下文（子的工具调用与结果留在子窗口） | 有测试守着：只回传结论；端到端那条同样只有结论 |
| 子的系统提示词 | 只在子窗口出现一次；父的工具描述里只有名字与描述 | 同上（两条都带正向对照：那样东西在子侧确实出现过） |
| 父的对话历史 | 子看不见，它只拿到委派时那段 `task` | 委派接口只有一个字符串入参 |

委派**不等于把全部能力交出去**：子能碰哪些工具由定义文件里的 `tools` / `disallowedTools`
决定，白名单点名了却没装上的工具按失败处理（不是"少给一个"）。
`Task` 工具的描述里只有每个 Subagent 的名字与一句话它管什么——
与 Skill 同一套渐进式披露，十份流程说明不会常驻在父的提示词里。

两条安全口径值得单独说，因为它们都有测试指着：

- **委派不提权**：子用的是**父那个身份本身**（`PrincipalCapturingAdvisor` 断言是同一个对象），
  能碰的工具是父清单的子集。父那一层已经把某个工具收掉了，子这边就看不见它——
  判据落在清单上，不在"调了之后被拒"上。
- **子也吃 3 / 6 预算**：子链的 `ToolCallingManager` 与父同一个构造入口。
  委派不能变成绕过预算的口子，否则一个坏掉的脚本在子里循环时父这边看起来毫无动静。

`Task` 这一层现在只回答"能不能把活派出去"，不再登记动作：动作那一栏随权限登记表在
ADR-0030 里整块下线。委派不碰库，碰库的是子要调的那个工具，而子能碰哪些工具由它自己那层
清单决定（父那份的子集）。

启用这一层同样是显式配目录（目录下每个 `*.md` 是一个专职 Agent，配了却读不出来即启动失败）：

```yaml
claw4j:
  subagents:
    directory: agents      # 每个 *.md 一份定义：name / description / tools / disallowedTools + 正文
```

**这里没有的**：嵌套委派（定义里写 `Task` 按定义错误失败）、并行多个 Subagent、
跨进程 Subagent（A2A，M3）。子 Agent 沿用父的模型实例，不再引第二套 key。

## Skill Generator：已整层删除（2026-11-18）

这里原来写的是"模型自己产出新能力"那条五步链路（生成 → 静态检查 → 隔离自测 → 审计 → 人审），
它是 ADR-0013 的主体。**2026-11-18 删掉了**，理由是同一天发生的那次形态变化：

- 脚本包退化成技能之后，生成器的产出物（`package.properties` + 入口脚本 + 注册进账本）
  **没有任何执行入口**——`run_script` 删了，账本只被生成器自己读。一个通过人审的包谁也跑不起来，
  而链路（五步、审计、人审标记）看起来全绿。这是"承诺比代码走得远"，比没有这个功能更坏。
- 它的形状在 OpenClaw 里也没有对应物：那边是 **Skill Workshop**（agent 自己写技能，
  写进技能目录即刻生效、保留版本、可回滚），产出物**就是技能本身**，没有"包 + 账本"这层中间物。

删掉的是：`capability/generator` 整包（11 个类，约 2500 行）、`capability/script` 整包（账本）、
`propose_script` 工具、`claw4j.generator.*` 与 `claw4j.scripts.*` 两段配置、
`ExternalContent.Source.SCRIPT`，以及各自的测试。

**要重建的话**（票 `.scratch/oss-alignment/issues/03-generator-emits-skills.md`）：按 Skill Workshop
的形状做——产物是一份 `SKILL.md` + `scripts/`，写进 `claw4j.skills.directory`，
审批复用 `SkillTrust`（目录摘要）、执行复用 `Bash`。**不要**再建第二套执行入口。

## 默认工具：六个来自 `spring-ai-agent-utils` 的，一行实现都不写

`Read` / `Grep` / `Glob` / `TodoWrite` / `WebFetch` / `WebSearch` 启动就在清单里，
`Write` / `Edit` 由 `claw4j.tools.files.write-enabled` 单独打开。工具代码一行没写，
全部是社区包的类（`AgentToolsAutoConfiguration` 与 `WebFetchAutoConfiguration` 只做装配）。

**为什么用库而不是自己写**：读文件、按正则搜、按 glob 找、记一份计划、抓一个网页，这五件
"什么 agent 都会用一次"的操作，自己写只会多出五个与库描述不同步的 Schema，而 Schema 与描述
正是模型选工具的唯一依据。这个仓库自己的工具（`querySales`、`Skill`、`Task`、`systemStatus`）
都是**有业务语义**的，那些才是该自己写的东西。

| 工具 | 动作 | 档位 | 默认 |
| --- | --- | --- | --- |
| `Read` / `Grep` / `Glob` | `read_files` | `CODING` | 开 |
| `Write` / `Edit` | `write_files` | `CODING` | **关** |
| `TodoWrite` | `plan` | `MINIMAL` | 开 |
| `WebFetch` / `WebSearch` | `web_fetch` / `web_search` | `CODING` | `WebFetch` 开；`WebSearch` 要 `BRAVE_API_KEY` |

三条边界值得记住：

- **读不等于改**。写默认关，不是因为难写，是因为改错一次就是一次脏工作区，而"只读三件套"
  已经够回答"这个仓库里有什么"。
- **档位是 `CODING` 不是 `MESSAGING`**。`MESSAGING` 的承诺是"只做消息与业务只读"；读服务器磁盘
  与出网都不是那条线上的事。默认角色配置里只有 `admin`（`actions: ["*"]`）调得动它们——
  `read-only` 拿 `read` 不该顺带拿到 `cat .env`。
- **路径边界只管位置，不管内容**。`..`（含软链接 + `..`）与软链接跳出去都拒，但 `.env`
  这类文件在边界内就照读。这与 `Bash`（`exec_command`，同样只给 `admin`）是同一档暴露。

`WebFetch` 单独在 `claw4j-agent`：它抓完要模型按 prompt 提炼，需要一个 `ChatClient`，而那是
agent 模块造的。它用的是**现场建的、不带 Advisor 也不带工具**的一个（不进容器）——注册成 bean
的话容器里就有两个 `ChatClient`，主聊天客户端的 `@ConditionalOnMissingBean` 会让位，整条
Advisor 链与工具循环静默消失。

**一个踩过的坑，值得所有"默认目录"记着**：`Path.of(".").toAbsolutePath()` 在 Servlet 应用里
不是启动目录。内嵌 Tomcat 起来时会把 `user.dir` 改成它的 docbase
（实测 `/var/folders/…/T/tomcat-docbase.8080.…`），于是 `files.directory` 与 `exec.workspace`
的 `.` 都落到那个临时目录上——表现是 `Bash` 的 `pwd` 不是仓库根、`Read` 说仓库里的文件在
允许目录之外，两个都像权限问题。修法是一个 `EnvironmentPostProcessor`
（`LaunchDirectoryEnvironmentPostProcessor`）在环境准备好的时候把 `user.dir` 记成
`claw4j.launch-directory`，两处默认值都指向它。

### 唯一会挂起一轮的工具：`AskUserQuestionTool`

库里的这个工具要一个同步的 `QuestionHandler`，也就是**它本来就要等**。按 OpenClaw 的
`ask_user` 接过来（`docs/tools/ask-user.md`）：问题推到「这一轮来自的那段对话」，**在同一轮里等人答**，
默认 900 秒、钳 30–3600，等不到返回 `no_answer` 让模型按自己的判断继续。与审批的分别写在
ADR-0027 的修订记录里：审批照旧「登记 + 唤醒会话」，不挂起；提问挂起，但挂在**进程内**——
重启即丢，丢了的按 `no_answer` 收场。

三条边界：

- **一条会话最多挂一个问题**。同时挂两个，用户下一句话该算哪个说不清；顶掉前一个会让先那一轮永远等不到。
- **答复的人权限要与提问人一致**。不一致时那句话不当答案，照常走一轮——不然谁都能替提问人做决定。
- **没有提问面的通道不装这个能力**。`QuestionGate` 给 `UNSUPPORTED`，模型改用普通文字问；
  这正是 OpenClaw 对 ACP / iMessage / WhatsApp 那类面的处理。HTTP 有面（客户端轮询
  `GET /api/chat/questions` 再 `POST /api/chat/answer`），四条 IM 通道也有（回一句话就是答案）。

## 外部内容与指令的边界（Prompt 注入）

模型看到的每一段文字都该能回答"这是谁说的"。这一层做的是**贴来源**，不是"判断内容对不对"。

三条不同的东西，三种处理：

| 进来的东西 | 它是 | 怎么办 |
| --- | --- | --- |
| 网页、搜索结果、脚本输出、知识库段落、子 Agent 的结论 | **数据** | 包 `<<<EXTERNAL_UNTRUSTED_CONTENT id="随机">>>` 边界 + 来源行。随机 id 让伪造不可预测；内容里形似边界标记的文本（含全角、零宽、用空格分隔的变体）在包之前先换成 `[[MARKER_SANITIZED]]` |
| 技能正文（`SKILL.md`） | **指令** | **不包装**——套上"这是数据不是指令"等于让技能失效。改成管来源：`status=approved` + `by` + `sha256`，摘要覆盖目录里所有文件，校验与加载用同一份内存快照 |
| 用户消息里的 `[System]` / 行首 `System:` | 伪造的系统标记 | 就地中和成 `(System)` / `System (untrusted):`，**不删内容**（删了"用户说了什么"就不可追溯） |

两条容易做错的：

- **提示句只讲边界，不列行动清单。** 写"不要执行任何工具"会让模型对正常请求也摆防御姿态
  （OpenClaw 的源码注释原话：*action lists here made models refuse legitimate user requests*）。
- **本地文件与命令输出不包装。** 那是"自己的工作区"，把每份代码都包成不可信内容，模型对正常结果
  也开始打折扣。代价记账在 `docs/agents/guardrails.md`；补偿是全局特殊 token 净化（所有工具返回值，
  含 `Read` / `Bash` / MCP）与授权表。

出站还有一层：`OutboundText` 把包装头、数据块标签、纯文本工具调用块（`[tool:x]`）剥掉再发。
它不是洁癖——OpenClaw 的威胁模型把包装逃逸（T-EVADE-002）的补充缓解写成一句
*Output-side validation in addition to input-side wrapping*：模型把边界复述出去，等于给下一个攻击者
一份可复制的模板。

`web_fetch` 还有一道出网边界（`WebFetchGuard`）：只允许公网 http/https，按**解析后的 IP** 判
（所以 `127.0.0.1.nip.io` 也拒），因为库自带的"域名安全检查"只是问一份第三方黑名单、不判 IP，
在国内恒 403。重定向不逐跳校验，是已知缺口。

四张票在 `.scratch/prompt-injection/issues/`（01 包装、02 出站、03 出网、04 技能）。

## 一次把三层都接上：真实模型的实录

`examples/` 下放的是能真的接进来的能力（带脚本的技能、`SKILL.md`、Subagent 定义、Python MCP server），
不是文档片段。目录说明见 [`examples/README.md`](../../examples/README.md)，这里是一次接上全部：

```bash
java -jar claw4j-app/target/claw4j-app-0.1.0-SNAPSHOT.jar \
  --claw4j.skills.directory=examples/skills \
  --claw4j.subagents.directory=examples/subagents \
  --spring.profiles.active=local
```

> MCP 那三个 server 现在写在 `application-local.yml` 里（`local` 一起带上），
> 当时还是单独的 `mcp-demo` profile —— 同一个东西换了个归属，命令是同一件事。

下面这几段是**那次运行的真实 curl 输出**，不是占位符（M2-07 收尾时录的，DeepSeek + 本地 Postgres）：

```bash
# 一层：Java @Tool 读自己的运行时状态
curl -s -X POST localhost:8080/api/chat -H 'Content-Type: application/json' \
  -H 'X-Claw4j-User: ops9' -H 'X-Claw4j-Role: admin' \
  -d '{"text":"这个进程现在堆内存上限是多少？线程数呢？"}'
# {"chatId":"http:ops9","text":"堆内存上限 8192 MB（8 GB），当前已用 73 MB；线程数 42。"}

# 二层：跨一个真的 OS 进程，Python server 那头回的话
curl -s -X POST localhost:8080/api/chat -H 'Content-Type: application/json' \
  -H 'X-Claw4j-User: ops9' -H 'X-Claw4j-Role: admin' \
  -d '{"text":"用 demo_echo 这个工具，把“跨进程调用成功”这句话原样带回来，只回这句话"}'
# {"chatId":"http:ops9","text":"跨进程调用成功"}

# 三层（当时的形态）：脚本包在受限子进程里跑，回来的只有它的标准输出。
# 2026-11-18 起这一段跑法变了：技能正文 -> Bash 跑 scripts/run.sh，回来的同样是标准输出
curl -s -X POST localhost:8080/api/chat -H 'Content-Type: application/json' \
  -H 'X-Claw4j-User: ops9' -H 'X-Claw4j-Role: admin' \
  -d '{"text":"深圳未来三天的天气怎么样"}'
# {"chatId":"http:ops9","text":"深圳未来三天天气：\n\n| 日期 | 最高温 | 最低温 | 降水概率 |\n|---|---|---|---|
#  | 10-24 | 32℃ | 22℃ | 0% |\n| 10-25 | 32℃ | 22℃ | 0% |\n| 10-26 | 30℃ | 23℃ | 19% |\n\n
#  前两天晴好、气温偏高；26 日略降温，有小概率降水，出门可备把伞。"}
```

Skill 与 Subagent 在同一趟里也各被用上一次。周报那条是模型先加载了 `weekly-sales` 这份做法、
再按它写的格式输出（连"环比这一版没做"都是照着正文里那条边界说的）：

```bash
curl -s -X POST localhost:8080/api/chat -H 'Content-Type: application/json' \
  -H 'X-Claw4j-User: ops9' -H 'X-Claw4j-Role: admin' \
  -d '{"text":"按周报格式给我一份上周各区的成交额"}'
# {"chatId":"http:ops9","text":"上周成交额（2026-10-12 ~ 2026-10-19）\n  华东：128,400.00\n
#   华南：26,000.00\n  华北：31,500.00\n  合计：185,900.00\n\n口径说明：成交额 = sum(order_item.amount)，
#   已排除 status = 'CANCELLED' 的订单；时间窗为半开区间 [2026-10-12, 2026-10-19)。\n\n
#   环比这一版没做——要算环比得再查上周之前一周的数据，需要的话我可以补。"}

curl -s -X POST localhost:8080/api/chat -H 'Content-Type: application/json' \
  -H 'X-Claw4j-User: ops9' -H 'X-Claw4j-Role: admin' \
  -d '{"text":"让 sql-reviewer 看一眼这条 SQL 有没有问题：select sum(amount) from business.order_item"}'
# {"chatId":"http:ops9","text":"sql-reviewer 的结论：**有问题，这条 SQL 算出来的不是成交额。**\n\n主要问题三条：
#  \n\n1. **漏了排除已取消订单**（最严重）。`order_item` 上没有 status，状态在 `sales_order` 上……
#  实测全表 sum = 413,676.00，其中 50,000.00 来自已取消订单，真实成交额应为 363,676.00……"}
```

委派那一条值得注意的是**子 Agent 真的自己跑去查了库**（它只有 `querySales` 一个工具，
白名单写死在定义里），而父这一轮的上下文里看不到它查了几次。

鉴权在这趟里同样生效——同一个天气问题换个角色：

```bash
curl -s -X POST localhost:8080/api/chat -H 'Content-Type: application/json' \
  -H 'X-Claw4j-User: guest9' -H 'X-Claw4j-Role: guest' \
  -d '{"text":"深圳未来三天天气怎么样"}'
# {"chatId":"http:guest9","text":"没有权限：角色 guest 不允许执行 run_script 动作（工具 weather-forecast）。"}
# ↑ 当时的动作名（脚本包那层还在）。今天的等价物是 `Bash` 的 `exec_command`，拒绝的样子一样。
```

上面这五条里，MCP 那条依赖本机装了 python3（那三个 Python server 零第三方依赖），
天气那条要能出网。`gh` / `himalaya` / `xurl` 那几个没在这趟里演示，
它们要本机装了对应 CLI——**演示归演示，回归基线不依赖这些**（ADR-0014）。

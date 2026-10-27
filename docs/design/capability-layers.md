# 能力三层：对 Agent 呈现为同一件事

> 设计说明：为什么这么做、否决了什么。要看结论去 [README](../../README.md)，要看决策去 [ADR](../adr/)。

能力按"要不要鉴权、有没有外部副作用、依不依赖外部运行时"分三层（ADR-0003），
**三层对 Agent 呈现为一回事——都是"工具名 + 参数 Schema"**，Agent 侧不出现"这是哪一层"的分支：

| 层 | 形态 | 状态 |
| --- | --- | --- |
| 一层 | Java `@Tool`（`SalesTools.querySales`、`SystemStatusTools.systemStatus`） | M1 已落地，M2-07 补齐 `system-status` |
| 二层 | MCP server 的 `@McpTool` | M2-02 已落地（连接生命周期），M2-07 接到真的 OS 进程，见下 |
| 三层 | Script Package（受限执行） | M2-03 已落地，M2-07 迁完三个保留场景，见下 |

三层之外还有两件事挂在同一套工具契约上：**Agent Skills**（M2-04，命中才加载全文）与
**Subagent**（M2-05，派出去跑、只回结论），再加上 **Skill Generator**（M2-06，
模型产出的脚本要走完五步才轮到人）。它们各自在下面有一节。

MCP 这一层的连接生命周期由 `claw4j-capability` 的 `io.github.claw4j.capability.mcp` 管，两条语义是硬要求：

- **连接建立时工具清单为空即失败**（ADR-0002）。连接是**通的**——协议握手成功、server 起来了——
  只是它一件能力都没有。这种状态不报错的话，现场是"模型说它没这个能力"，
  而真正的原因是命令写错或版本对不上。
- **会话丢失（`McpTransportSessionNotFoundException`）重新初始化连接，而不是重试死会话**。
  对一条已经不在的会话重试，会让一次断连变成永久不可用。

这两条是 ADR-0008 的三条必测之一，所以测试里的 MCP server 是 **Java 自实现的进程内 stdio server**
（`InProcessStdioMcpServer`）：不依赖 Docker、不依赖 Python，于是这两条进得了默认构建。
**它跨的是协议边界不是 OS 进程边界**。真实部署换的是 `McpServerEndpoint.openTransport()`
给出来的传输，上面的生命周期代码一行都不用改——M2-07 就是这么接上真的 OS 进程的：

```java
// 命令与参数分开给，交给 MCP SDK 起进程（不经任何命令解释器）
ProcessStdioMcpEndpoint.of("claw4j-github", "python3",
        List.of("examples/mcp/gh_mcp_server.py"), Map.of("PATH", System.getenv("PATH")))
    .endpoint(ToolPermission.read());
```

`examples/mcp/` 下有三个零第三方依赖的 Python server：`gh_mcp_server.py` 包 `gh`、
`himalaya_mcp_server.py` 包 `himalaya`（它导出会发邮件的 `mail_send`，所以接它的端点登记的是
`send_email` 而不是只读）、`demo_mcp_server.py` 不依赖任何 CLI。
子进程的**环境默认为空**，需要 `PATH` 就显式写一行——应用的模型 key 与数据库口令不会漏过去。

这条链路真跑一次的测试打的是 `real-cli` tag，默认不跑（它依赖本机装了 python3 与那些 CLI，
不是每台机器都有，所以不进默认构建）：

```bash
./mvnw -Preal-cli -pl claw4j-capability test
```

MCP 层**不授予任何业务表权限**：它登记的权限里一张表都没有，鉴权只看动作。
没登记权限的工具一律按拒绝处理（ADR-0007）。

## Script Package：脚本本体不进上下文，只有输出进

脚本层这一层（`io.github.claw4j.capability.script`）的不是"能跑脚本"，而是**跑在什么里面**。
模型看到的仍然只是"工具名 + 一个参数"，看不到脚本写了什么；它拿到的返回值只有标准输出。

执行后端是社区包的 `ExecBackend` 接缝（`org.springaicommunity:spring-ai-agent-utils`），
**换沙箱等于换一个实现，调用方一行不动**——这是这条边界唯一可测的地方：

```java
new ScriptPackageRunner(packages)                 // 默认：受限子进程
new ScriptPackageRunner(packages, myFactory)      // 换沙箱：换的是这个
```

**沙箱的物理形态是"应用容器内的受限子进程"**，判据只有一条：**能不能在一台干净的机器上跑起来**
（不依赖 Docker 守护进程、不依赖 Python）。跑不起来的沙箱等于没有测试，
没有测试的边界不能写进 README 当承诺。
（`LocalExecBackend` 自带 `workingDirectory` 与 `cleanEnvironment`，不需要 Docker 与 Python。）

它真的挡住的五样，每样都有一条测试指着：

| 约束 | 做法 | 盯它的测试 |
| --- | --- | --- |
| 工作目录 | 进程起在脚本包自己的目录里 | 有测试用 `pwd` 验 |
| 参数 | 每个参数都过路径校验：`..`、绝对路径、符号链接一律拒绝 | 有测试逐条验拒绝 |
| 环境变量 | 环境从零开始，只给 `PATH`；调用方塞变量会被拒 | 有测试用 `env` 验 |
| 超时 | 上限 30 秒，不给超时或超上限就拒绝执行（不夹取） | 有测试验上限与两种拒绝 |
| 非 root | 账号是 root 时拒绝执行 | 有测试验 root 被拒 |

它**挡不住**的，同样说清楚：脚本**体内**自己去读一个绝对路径、自己去连一次网。
进程沙箱锁得住入口与参数，锁不住脚本自己的行为；那种越界要靠另一台容器
（ADR-0003 的第 ② 类沙箱，给 Skill Generator 产出的未审核代码用，M2-06）。

**Java 侧不自己拼命令行**（ADR-0002），这条由两台机器盯着，不靠 review 记：

- ArchUnit：`io.github.claw4j..` 主代码不许调用 `Runtime.exec`、不许碰 `ProcessBuilder`
  ——**例外只有一个被点名的类**：`LocalProcessRunner`。加第二个之前先读票 08 里那段取舍；
- 源码扫描：`claw4j-*/src/main/java` 里不许出现手写的 shell 调用字面量。
  后者挡的是"把命令行当一个字符串传下去"——那种写法起进程的不是本项目，
  于是 API 那条规则看不见它，而宿主机的命令行照样交出去了。

脚本层交给进程的是**一个 argv 数组**，不是一条命令行（票 08）：
入口脚本来自包清单（绝对路径），参数一律先过沙箱的路径校验（越界即拒绝），
然后作为数组里的元素原样交给进程。数组形态没有命令解释器这一步，
所以参数里带 `; echo pwned` 或 `$(...)` 也只是"一个字符串"，不是语法——
"挡住注入"不再依赖任何一处转义写得对不对。代价是入口脚本必须自己可执行、
第一行给出解释器（缺了就在静态检查那一关被挡下，见 Skill Generator 那节）。
起进程的全仓只有 `LocalProcessRunner` 一个地方，工作目录与环境变量由沙箱在构造它时给定。

脚本层**不授予任何业务表权限**，而且它要的动作不是 `read` 而是 **`run_script`**：
只读角色能查数是"只读"的本意，能执行脚本就不是了——脚本能写文件、能往外连，
那是比读一张表宽得多的口子。没登记权限的工具一律按拒绝（ADR-0007）。

启用这一层要显式配目录（配了却不存在即启动失败，那是配置错误不是"这一层不存在"）：

```yaml
claw4j:
  scripts:
    directory: skills      # 每个子目录一个包，包里用 package.properties 说明自己
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

**这里没有的**：让模型自己产出一份做法。这份 SKILL.md 仍然是人写的；
模型产出的是**脚本包**，而且要先过下面那条五步链路（Skill Generator）。

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

- **委派不提权**：子那一侧照样过 `AuthorizationAdvisor`，用的是**父那个身份本身**
  （`PrincipalCapturingAdvisor` 断言是同一个对象）。guest 派出去的 Subagent 去查
  `sales_order` 时被挡在**执行之前**——断言同时盯着"那个工具一次都没被调用"。
- **子也吃 3 / 6 预算**：子链的 `ToolCallingManager` 与父同一个构造入口。
  委派不能变成绕过预算的口子，否则一个坏掉的脚本在子里循环时父这边看起来毫无动静。

`Task` 自己登记的权限是 **`delegate_task` 动作，业务表一张都没有**：
委派这个动作不碰库，碰库的是子要调的那个工具，那一步按它自己的登记表鉴权。
所以 `read-only` 与 `guest` 也都能委派——给不改变它们看得到什么。

启用这一层同样是显式配目录（目录下每个 `*.md` 是一个专职 Agent，配了却读不出来即启动失败）：

```yaml
claw4j:
  subagents:
    directory: agents      # 每个 *.md 一份定义：name / description / tools / disallowedTools + 正文
```

**这里没有的**：嵌套委派（定义里写 `Task` 按定义错误失败）、并行多个 Subagent、
跨进程 Subagent（A2A，M3）。子 Agent 沿用父的模型实例，不再引第二套 key。

## Skill Generator：模型产出的脚本，五步之后才轮到人

这是本项目"开放扩展"这个定位**唯一能自证的部分**：模型可以自己产出一个新的可执行能力，
但全程关在笼子里（ADR-0002 修订后的定案、ADR-0013）：

```
生成 → 静态检查 → 隔离沙箱自测 → 审计留痕 → 人审通过才注册
```

五步的**顺序就是安全边界的顺序**，不是流水线编排的偏好：

| 步 | 做什么 | 放在这个位置的理由 |
| --- | --- | --- |
| 1 生成 | 脚本落盘到暂存区 | —— |
| 2 静态检查 | 纯函数，按规则表查写法 | 在落盘**之前**：危险代码不该先被写下来再被删掉，那样磁盘上出现过它，而没人会记录 |
| 3 沙箱自测 | 临时副本里跑一次，跑完即删 | 在**沙箱**里而不是暂存区里：跑的是未审核代码，它不能改自己待审的那份内容 |
| 4 审计 | 生成 / 自测 / 执行各记一条 | 三步**各自**记：最后补的那条只能写"发生了什么"，写不出"被挡下的是谁提交的" |
| 5 人审 | 人写一个审批标记，签下"谁、什么时候、审的是哪一版" | 唯一没有代码替你做的那一步 |

**静态检查是未审核代码进门前的唯一一道机器关，也是最便宜的一环**：
自测要起进程、要花时间，而这一关既不落盘也不执行就能挡下绝大多数危险写法。
它是纯函数（不读文件、不看时间、不取随机数），于是"挡住了什么"能被一张表钉死——
危险清单逐条一个用例，正常脚本另有一组用例（只测"危险被挡下"的话，全拒的实现也会绿）。

它认的是**写法**（装包、提权、连网、读凭据、删东西、动态执行、缺 shebang），认不出意图；
把危险动作藏在变量里它看不出来。挡的是明着写的那部分——
而那部分在真实事故里占绝大多数（1.x 那次让脚本自行 `pip install --break-system-packages`
就是明着写的）。

**未人审的脚本拿不到被调用的资格**：工具清单由 `ScriptPackageRegistry` 现取，
"待审"在模型那边就是"根本没有这个工具"——不是靠"模型不去调它"维持的，它看不见。
人审比对的还是**脚本摘要**：签字之后脚本被改（同名重新生成一次），旧签字自动作废，
"审过一次"不会变成一张长期通行证。

**上线的是一份快照，不是暂存区里那个目录**：签字生效时把候选目录整份复制到发布目录，
清单与执行都指向那份副本。暂存区是模型能反复重写的地方（同名再提一次就整份覆盖），
把执行目录直接指向它，等于让"人审的是哪一版"这件事交给文件系统回答——
文件系统答不上来，跑的会是最后一版没人签过字的脚本。
这条由两条用例钉着，一正一反：同名重提之后跑出来的**仍然是人审过的那一版**；
新版重新签过字之后**要能真的上线**（快照不是把闸门焊死）。

自测的沙箱（ADR-0003 的第 ② 类）与 Script Package 那个（第 ① 类）是两套代码，
判据是**跑的东西不同**：① 跑人审过的包，② 跑模型刚写出来的脚本。
它给的三样与①同一口径但更紧：临时目录（跑完即删）、只有 `PATH`、5 秒上限。
**网络隔离这一项它给不了**，规格里如实写成 `NOT_ISOLATED` 并写明原因——
纯 Java 起的子进程没有可移植的网络限制手段，而为了限制脚本再去起一条命令
正是 ADR-0002 禁止的那件事。真正的网络隔离由部署层的容器网络策略给；
在干净机器上跑不起来的形态不写进 README 当承诺，所以联网的脚本在**静态检查**那一关就被挡了。

失败时回给模型的是**脚本的原话**（stderr），不做包装——与自纠错同一个道理：
包装过的话里没有可改的信息，`syntax error near unexpected token` 指得出行，
"自测未通过"指不出任何东西。

生成器这个工具自己登记的权限是 **`draft_script` 动作，业务表一张都没有**；
它不等于 `run_script`：提交一份草稿不等于能跑脚本，把两个动作合成一个，
就等于让"能提议的人"顺手拿到了执行权。所以只有 admin 能提。

```yaml
claw4j:
  scripts:
    directory: skills          # 生成器要往这里注册，没配脚本层就没有生成器
  generator:
    staging-directory: .claw4j/staging   # 候选包落在这
    review-directory: .claw4j/reviews    # 人审标记放这（默认在暂存区**旁边**，不在里面）
    audit-file: .claw4j/audit.tsv        # 只追加
```

**这里没有的**：自动生成 Java `@Tool`；自动人审（这一步必须是人）。

## 一次把三层都接上：真实模型的实录

`examples/` 下放的是能真的接进来的能力（脚本包、`SKILL.md`、Subagent 定义、Python MCP server），
不是文档片段。目录说明见 [`examples/README.md`](../../examples/README.md)，这里是一次接上全部：

```bash
java -jar claw4j-app/target/claw4j-app-0.1.0-SNAPSHOT.jar \
  --claw4j.scripts.directory=examples/scripts \
  --claw4j.skills.directory=examples/skills \
  --claw4j.subagents.directory=examples/subagents \
  --spring.profiles.active=mcp-demo --claw4j.mcp.demo.demo-server=true
```

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

# 三层：脚本包在受限子进程里跑，回来的只有它的标准输出
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
```

上面这五条里，MCP 那条依赖本机装了 python3（那三个 Python server 零第三方依赖），
天气那条要能出网。`gh` / `himalaya` / `xurl` 那几个没在这趟里演示，
它们要本机装了对应 CLI——**演示归演示，回归基线不依赖这些**（ADR-0014）。

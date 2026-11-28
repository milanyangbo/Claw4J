# examples/：三层能力的演示素材

这里放的是**能被真的接进来的能力**，不是文档里的示例片段。目录不参与构建，
但里面的每一个脚本、每一份 `SKILL.md`、每一个 MCP server 都是照着各层的真实契约写的：
配上去就能用，配错了会在启动时说清哪里错。

## 目录与它属于哪一层

| 目录 | 层 | 启用方式 |
| --- | --- | --- |
| `agents/` | 人设（Persona）：这个部署是谁 | `claw4j.agent.directory=examples/agents`（目录里只有 `assistant.md` 时 `default` 可以不写） |
| `skills/` | Skill（含技能里的脚本） | `claw4j.skills.directory=examples/skills` |
| `subagents/` | Subagent | `claw4j.subagents.directory=examples/subagents` |
| `mcp/` | 二层：MCP server（跨进程） | `claw4j.capability.mcp.servers`（三个 server 都写在 `application-local.yml` 里） |
| `guard/` | 不属于能力层：入站拦截的词表 | `claw4j.guard.sensitive-words-file`，**默认就指向这里** |
| `knowledge/` | 知识库：一份可检索的文档目录 | `claw4j.rag.directory=examples/knowledge`（`local` 已配） |

`guard/sensitive-words.txt` 是唯一一个默认生效的：一行一个词，`#` 开头是注释，命中任意一条
整轮拒答。不想拦就把键清空（拦不拦得住单看这份文件，见
[`docs/design/inbound-guard.md`](../docs/design/inbound-guard.md)）。

`agents/` 与其余几层不是一回事：那几层是"给这个 agent 加能力"，人设是"这个 agent 是谁"。
同一个 jar 换一份人设，就从通用助理变成专职助手——目录里现在只有一份 `agents/assistant.md`（通用企业助理，`tier: full`），
业务口径在 `agents/glossary/metrics.md`（**引用，不是抄写**：改一处，所有用它的助手下一轮都变；加了新人设也不用抄一遍）。

四层里**只有 MCP 那一层是协议层面的语言无关**（对端可以是任何语言写的程序）；
技能（含技能里的脚本）、Subagent 的语言无关来自文件格式与本项目的接缝，Spring AI 没有对应抽象。
这条分界在根 README 的「集成」与 [`docs/capability-tests.md`](../docs/capability-tests.md) §8 里写清楚了，
别把它们当成同一件事。每一个工具怎么验（命令、要涨的计数、期望值）也在那份测试手册里。

## 知识库样本（`knowledge/`）

一套虚构公司的完整制度文档，111 份 / 13.1 万字符：销售与合同、产品与技术、售后现场、结算开票、
内部流程、历史归档。刻意做成"真实密度"而不是"标准答案集"——里面混着大量与客户问题无关的内容
（人事、IT、采购、安全），混着 6 份已废止的旧版，混着一份几乎每次检索都会命中的术语表。

规模上分四档，差别是有意的：

| 档 | 字符数 | 数量 | 大约切几段 |
| --- | --- | --- | --- |
| 短文档 | 不到 900 | 53 份 | 1 段（标题 + 一条规则就说完了） |
| 中篇 | 900–1700 | 34 份 | 2 段 |
| 较长 | 1700–2800 | 21 份 | 2–3 段 |
| 长文档 | 3400–4100 | 3 份 | 4–6 段 |

3 份长文档是新写的（售后作业手册、仓储作业规范、折扣与特价审批）。
另外 37 份（占三分之一）是既有文档扩写的，从平均 500 字符补到 1500–2500：
售后政策、保修、押金、客户分级、信用额度、产品目录、停产、以旧换新、发票冲红、保价、安装、
保修转移、服务合同、固件回滚、订单取消、常见问题、发运、渠道、换货、报价、价格异议、投诉、
远程诊断、备件、返修、上门、维修价目、退款、订单变更、收款方式、开票、采购、来料检验、
合同评审、线索管理、质量、定制。

扩写时原有文字**一字未动**，只在后面追加小节——`gold_spans` 绑的是原句，改了措辞就得同步改 gold。

为什么非要长的：文档只有一段时，"同一份文档的第二段"这个场景压根不存在，
而它恰恰是切块把词劈开、长上下文被截断这类故障的发生地。第一轮扩语料时踩过这个坑——
78 份文档只切出 93 段，等于 12 道题全在考同一件事。

为什么这么设计：只有几份文档时，检索等于把答案端上来，测不出"从一堆看着都相关的段落里挑对那一段"。
语料越接近真实知识库的样子，这条能力才验得出意义。

逐条验法（问什么、期望什么、答错时先看哪一行日志）在
[`README-capability-tests.md`](../README-capability-tests.md) 的 B6。

## 技能里的脚本（原来的"脚本层"）

```bash
java -jar claw4j-app/target/claw4j-app-0.1.0-SNAPSHOT.jar \
  --claw4j.skills.directory=examples/skills \
  --claw4j.subagents.directory=examples/subagents
```

三个技能带着脚本：`weather-forecast`（查天气）、`summarize`（摘要）、`xurl`（包 `xurl` CLI）。
**它们不是工具**——2026-11-18 起按 OpenClaw 的形态，技能是"目录 + `SKILL.md` + 支撑文件"，
本身不被执行，脚本由模型用 `Bash` 去跑（正文写清怎么跑、路径由 `{baseDir}` 给出）。
以前那个 `run_script` 工具与它背后的自研执行链已经删掉，原因与影响面见
[`.scratch/oss-alignment/issues/02-script-packages-degrade-to-skills.md`](../.scratch/oss-alignment/issues/02-script-packages-degrade-to-skills.md)。

它们各自只依赖 Python 标准库（`xurl` 那个还要本机装了 `xurl`）。这不是洁癖：
`Bash` 的子进程**只拿得到声明的那几个环境变量**（默认只有 `PATH`），拿不到任何 API key，
所以凡是依赖凭据的能力在这一层都跑不通——`summarize` 因此做成抽取式而不是调大模型，
这个取舍写在它自己的 `scripts/run.sh` 里。需要凭据的能力该走 MCP 那一层。

审批标记在 `.review/`，摘要覆盖**整个目录**（含脚本）：改一个脚本字节，旧标记就作废。

## MCP 层（二层，真的跨进程）

```bash
# 在仓库根目录起：三个 server 的脚本路径是相对的，按当前工作目录解析
java -jar claw4j-app/target/claw4j-app-0.1.0-SNAPSHOT.jar --spring.profiles.active=local
```

三个 server 各一段 YAML，写在 `application-local.yml` 里（随仓库的是模板
`application-local.yml.example`）。**这里没有对应的 Java 类**——曾经有一个 `McpDemoConfiguration`，
它干的事只是拼三个绝对路径，而"相对路径按当前工作目录解析"本来就成立，
所以那份配置自己就够，类被删掉了；那份配置也不该单独开一个 `mcp-demo` profile，
"接哪几个 server"是这台机器的事，跟 local 的其余内容同一个归属。

Java 这边起的是**真的子进程**，用它的 stdin/stdout 说 MCP——不是进程内的替身。

- `demo_mcp_server.py`：不依赖任何 CLI，`demo_echo` 回话、`demo_now` 报子进程 pid 与时间戳（每次不同）
- `gh_mcp_server.py`：包 `gh` CLI，导出 `github_issue_list` / `github_pr_checks` / `github_api`（只读）
- `himalaya_mcp_server.py`：包 `himalaya`，导出 `mail_list_unread` 与 **`mail_send`**。后者有副作用，
  所以配置里按工具覆盖权限（兜底 `read`，只有 `mail_send` 是 `send_email`）——
  整条声明成只读就会变成"只读角色能发邮件"

这三个都是 stdio：宿主去 fork 它们的进程。**另外还有一个 HTTP 的**，不在这个目录里，
是仓库模块 `claw4j-example-mcp-server`（`java -jar` 起，`/mcp`，端口 8082）：
它的工具是 `runtimeSnapshot` / `cronNext` / `simulateSlowCall`，与上面那份 Python 的不重名、
也不干同一件事——那份验 stdio 往返，这份验 HTTP、服务端状态与请求超时。
接它的配置在 `application-local.yml` 里（`java-http`），**先起它再起宿主**。

`mcp_stdio.py` 是这三个共用的最小骨架（约 100 行，零第三方依赖）：读 JSON-RPC 帧、分派、写回。
用它而不是官方 SDK，是为了让"Java 侧连的到底是个什么"从头到尾看得见。

这三个是**我们自己写的** server，所以要演示的是"跨进程协议边界"。**别人写的** server 不用写代码：
`application-local.yml` 末尾那段注释着的配置（一个 npm 上的 stdio server、一个公网 Streamable HTTP 端点）
就是全部动作，见根 README 的「集成 → 2. 一个 MCP server」。

## Skill 与 Subagent（纯 markdown）

```bash
java -jar claw4j-app/target/claw4j-app-0.1.0-SNAPSHOT.jar \
  --claw4j.skills.directory=examples/skills \
  --claw4j.subagents.directory=examples/subagents
```

（`skills` 这一行对 `weekly-digest` 是必需的：它的白名单里有 `Skill`——要用 `summarize`
那份正文才知道脚本在哪。少了它，启动时会按"白名单点名了没有的工具"失败。只想试子 Agent 的话，
把 `--claw4j.subagents.directory` 指到一个只放 `capacity-triage.md` 的目录也行。）

- `skills/weekly-sales/SKILL.md`：一份做法（口径 + 步骤 + 输出格式 + 不要做的事）。
  平时只有一行描述在模型眼前，命中才把全文加载进来——它不提新工具，只决定现有工具怎么用。
- `skills/summarize` / `weather-forecast` / `xurl`：带脚本的技能，做法与脚本在同一个目录里。
- `subagents/` 里五份定义，覆盖三种委派形状，照着改比从零写快：
  - `capacity-triage.md`：容量初诊。白名单只给 `systemStatus`，**零额外配置就能加载**
    （不用数据库、不用别的目录配好）——想先试通这一层，用这一份。
  - `metric-glossary.md`：口径解释。同样只有一个只读工具，但它不查数，只回答"这个指标怎么算"。
  - `sql-reviewer.md`：SQL 复核。白名单只有 `querySales`，要示例销售插件在才有这个工具。
  - `weekly-digest.md`：周报汇总。白名单**跨层**（`querySales` + `Skill` + `Bash`），
    于是这一份要求同时配 `claw4j.skills.directory`，否则启动时按"白名单点名了没有的工具"失败。
  - `incident-triage.md`：值班初筛。**不写 `tools`**（继承全部），用 `disallowedTools`
    减掉 `Skill` 与 `querySales`——这是"能力多但要收窄"的那种写法。

  三种形状分别是：白名单单工具、白名单跨层、继承全部再减。front matter 只有
  `name` / `description` / `tools` / `disallowedTools` 四个键，正文是它自己的系统提示词。
  白名单里点名了容器里没有的工具、或者点名了委派工具自己（`Task`），都是**启动失败**。
  这一组定义由 `claw4j-agent` 的 `ExamplesSubagentsTest` 在每次构建时真的读一遍
  （工具名拼错、`name` 与文件名不一致都会红），所以它们是"能加载"的，不是"看起来能加载"。

两者都是 markdown，与语言无关：换个人用 Go 写同名的东西，读的还是这两份文件。

验证这条路真的通（默认不跑，见下）：

```bash
./mvnw -Preal-cli -pl claw4j-capability test -Dtest=RealCliMcpEndToEndTest
```

## 这一组不进 CI

真实外部 CLI 依赖本机装了什么（python3 / `gh` / `himalaya` / `xurl`），
CI 的 runner 上没有这些，硬跑的结果是"换台机器就红"，那比没有这条测试更糟。
所以 `RealCliMcpEndToEndTest` 打的是 `real-cli` tag，父 pom 的 surefire 默认排除它，
`-Preal-cli` 才跑。回归基线是其余那些测试。

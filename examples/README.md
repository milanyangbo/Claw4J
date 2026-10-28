# examples/：三层能力的演示素材

这里放的是**能被真的接进来的能力**，不是文档里的示例片段。目录不参与构建，
但里面的每一个脚本、每一份 `SKILL.md`、每一个 MCP server 都是照着各层的真实契约写的：
配上去就能用，配错了会在启动时说清哪里错。

## 目录与它属于哪一层

| 目录 | 层 | 启用方式 |
| --- | --- | --- |
| `scripts/` | 三层：Script Package | `claw4j.scripts.directory=examples/scripts` |
| `skills/` | Skill | `claw4j.skills.directory=examples/skills` |
| `subagents/` | Subagent | `claw4j.subagents.directory=examples/subagents` |
| `mcp/` | 二层：MCP server（跨进程） | `--spring.profiles.active=mcp-demo` + 各自的开关 |

四层里**只有 MCP 那一层是协议层面的语言无关**（对端可以是任何语言写的程序）；
脚本包、Skill、Subagent 的语言无关来自文件格式与本项目的接缝，Spring AI 没有对应抽象。
这条分界在 README 的「非 Java 的东西怎么变成能力」里写清楚了，别把它们当成同一件事。

## 脚本层（三层）

```bash
java -jar claw4j-app/target/claw4j-app-0.1.0-SNAPSHOT.jar \
  --claw4j.scripts.directory=examples/scripts \
  --claw4j.skills.directory=examples/skills \
  --claw4j.subagents.directory=examples/subagents
```

三个包：`weather-forecast`（查天气）、`summarize`（摘要）、`xurl`（包 `xurl` CLI）。

它们各自只依赖 Python 标准库（`xurl` 那个还要本机装了 `xurl`）。这不是洁癖：
脚本层的子进程**只拿得到 `PATH`**（`SandboxPolicy`），拿不到任何 API key，
所以凡是依赖凭据的能力在这一层都跑不通——`summarize` 因此做成抽取式而不是调大模型，
这个取舍在它自己的 `run.sh` 里写明了。需要凭据的能力该走 MCP 那一层。

## MCP 层（二层，真的跨进程）

```bash
java -jar claw4j-app/target/claw4j-app-0.1.0-SNAPSHOT.jar \
  --spring.profiles.active=mcp-demo \
  --claw4j.mcp.demo.demo-server=true \
  --claw4j.mcp.demo.github=true
```

`mcp-demo` profile 打开的是 `McpDemoConfiguration`：它把 `examples/mcp/` 下的
Python server 接成端点，每个 server 一个开关（默认全关）。
Java 这边起的是**真的子进程**，用它的 stdin/stdout 说 MCP——不是进程内的替身。

- `demo_mcp_server.py`：不依赖任何 CLI，`demo_echo` 回话、`demo_now` 报子进程 pid 与时间戳（每次不同）
- `gh_mcp_server.py`：包 `gh` CLI，导出 `github_issue_list` / `github_pr_checks` / `github_api`（只读）
- `himalaya_mcp_server.py`：包 `himalaya`，导出 `mail_list_unread` 与 **`mail_send`**（有副作用，所以端点登记的是 `send_email` 而不是只读）

`mcp_stdio.py` 是这三个共用的最小骨架（约 100 行，零第三方依赖）：读 JSON-RPC 帧、分派、写回。
用它而不是官方 SDK，是为了让"Java 侧连的到底是个什么"从头到尾看得见。

这三个是**我们自己写的** server，所以要演示的是"跨进程协议边界"。**别人写的** server 不用写代码：
`mcp-third-party` profile 里那两段配置（一个 npm 上的 stdio server、一个公网 Streamable HTTP 端点）
就是全部动作，见根 README 的「接一个第三方 MCP server：写配置，不写代码」。

## Skill 与 Subagent（纯 markdown）

```bash
java -jar claw4j-app/target/claw4j-app-0.1.0-SNAPSHOT.jar \
  --claw4j.skills.directory=examples/skills \
  --claw4j.scripts.directory=examples/scripts \
  --claw4j.subagents.directory=examples/subagents
```

（`scripts` 这一行不是可有可无的：`weekly-digest` 的白名单里点了脚本包 `summarize`，
少了它，启动时会按"白名单点名了没有的工具"失败。只想试子 Agent 的话，
把 `--claw4j.subagents.directory` 指到一个只放 `capacity-triage.md` 的目录也行。）

- `skills/weekly-sales/SKILL.md`：一份做法（口径 + 步骤 + 输出格式 + 不要做的事）。
  平时只有一行描述在模型眼前，命中才把全文加载进来——它不提新工具，只决定现有工具怎么用。
- `subagents/` 里五份定义，覆盖三种委派形状，照着改比从零写快：
  - `capacity-triage.md`：容量初诊。白名单只给 `systemStatus`，**零额外配置就能加载**
    （不用数据库、不用别的目录配好）——想先试通这一层，用这一份。
  - `metric-glossary.md`：口径解释。同样只有一个只读工具，但它不查数，只回答"这个指标怎么算"。
  - `sql-reviewer.md`：SQL 复核。白名单只有 `querySales`，要示例销售插件在才有这个工具。
  - `weekly-digest.md`：周报汇总。白名单**跨层**（`querySales` + 脚本包 `summarize`），
    于是这一份要求同时配 `claw4j.scripts.directory`，否则启动时按"白名单点名了没有的工具"失败。
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

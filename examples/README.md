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

验证这条路真的通（默认不跑，见下）：

```bash
./mvnw -Preal-cli -pl claw4j-tools test -Dtest=RealCliMcpEndToEndTest
```

## 这一组不进 CI

真实外部 CLI 依赖本机装了什么（python3 / `gh` / `himalaya` / `xurl`），
CI 的 runner 上没有这些，硬跑的结果是"换台机器就红"，那比没有这条测试更糟。
所以 `RealCliMcpEndToEndTest` 打的是 `real-cli` tag，父 pom 的 surefire 默认排除它，
`-Preal-cli` 才跑。回归基线是其余那些测试。

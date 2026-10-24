# 外部 CLI 走 MCP，跨一个真的 OS 进程

---
status: accepted
date: 2026-10-24
---

ADR-0003 的二层保留场景（`github`、`unread-mail`、`send-email`）要包的是 **外部 CLI**：`gh` 与 `himalaya`。
包法固定为"一个 MCP server 进程 + stdio"，Java 侧只见工具名与参数 Schema。
M2-02 先把这条链路在**进程内**跑通（连接生命周期、fail-fast、会话丢失重连都进 CI），
M2-07 把它接到**真的 OS 进程**上：换掉的是端点给出的传输实现，上面的连接代码一行未动。

## Considered Options

**继续用进程内 stdio，不接真实进程**
否决。进程内那条能证明"连接生命周期的逻辑对"，证明不了"这条逻辑在真实边界上还成立"。
真实进程带来的三件事是进程内模拟不出来的：fork 失败（命令不存在）、协议版本对不上、
以及**环境只有 PATH** 这件事（见下）。少了它们，"接一个新 server 只是加配置"就只是一句口号。

**Java 侧直接调 CLI（不经 MCP）**
否决。`gh` 这类 CLI 的子命令有一整套参数，用 Java 重写等于把 CLI 的演进也搬进本项目；
更要紧的是没有 MCP 就没有"工具清单为空即失败"，命令装错了的表现是模型说"我做不到"。

**HTTP 传输的 MCP server**
否决。多一个端口就多一份要管的网络可达性与凭据，而这几个 CLI 都是本机工具。
stdio 的好处是"进程起来就连上、进程没了就是断连"，不需要额外的基础设施。

## Consequences

### 结构化 argv 不是"拼命令行"

ADR-0002 禁的是**Java 侧构造一条命令行交给 shell 解释**（1.x 的 `sh -c "…"`、
把用户自然语言当 argv 传给 `.py`）。这里 Java 侧给出的是 `{@code command}` 与
`{@code List<String> args}` 两样分开的东西，交给 MCP SDK 的 `StdioClientTransport`
（它内部用 `ProcessBuilder` 起进程），**谁都不解释它**。

真正消灭掉的是另一条路：**模型的输出进不了这条命令行**。
工具的入参走 MCP 的 JSON-RPC 帧，由 SDK 序列化后写进 stdin，永远不会成为 argv 的一部分。
1.x 的 `GenericSkill` 把模型（用户）的话直接当 argv 传进脚本——那才是"把命令行交给模型"，
换成 MCP 之后这条路在协议上就不存在了。

### 环境变量只给显式列出的那些

子进程的默认是**空环境**（`ServerParameters` 的 env 不给就是空表），
于是应用进程的模型 key、数据库口令不会漏过去。需要 `PATH`（不然连 `python3` 都找不到）
就显式写一行——"给出去了什么"在这份配置里是看得见的，而不是"继承了一切"这种说不清的状态。

这一条顺带定死了脚本层的另一条边界：**Script Package 的子进程只拿得到 PATH**
（`SandboxPolicy`），所以**依赖 API key 的能力走不了脚本层**。
`examples/scripts/summarize` 因此做成抽取式摘要而不是调大模型——
不是不想做生成式，是这一层传不进凭据，也不该传进去。需要凭据的能力走 MCP。

### 演示不进 CI

真实外部 CLI 依赖本机装了什么（`python3` / `gh` / `himalaya` / `xurl`），
CI 的 runner 上没有这些。`RealCliMcpEndToEndTest` 打 `real-cli` tag，
父 pom 的 surefire 默认排除，`-Preal-cli` 才跑。

用 tag 排除而不是在测试里 `assumeTrue`：assume 的结果是 **skipped**，
而跳过的绿和通过的绿在报表里一模一样——本仓库有条闸把 skipped 判成失败
（`scripts/assert-no-skipped-tests.sh`），用 assume 就是给那条闸开口子。
排除是"这次没选它"，报表里根本不出现。

能被 CI 拦住的那些仍然进 CI：命令为空、参数有空项、命令不存在时连接必须失败
而不是"连上了但工具清单为空"（后者正是 02 号票要挡的姿势）。

### 1.x 的 8 个保留场景全部落位

| 层 | 场景 | 落点 |
| --- | --- | --- |
| 一层 Java `@Tool` | `chat-summary` | `SalesTools.querySales`（Text-to-SQL + 自纠错） |
| | `system-status` | `SystemStatusTools.systemStatus`（JVM + 容器 cgroup 限额） |
| 二层 MCP | `github` | `examples/mcp/gh_mcp_server.py` 包 `gh`（只读三个工具） |
| | `unread-mail` / `send-email` | `examples/mcp/himalaya_mcp_server.py` 包 `himalaya`（含有副作用的 `mail_send`） |
| 三层 Script Package | `weather-forecast` / `summarize` / `xurl` | `examples/scripts/` 下三个包，只收口执行环境 |

`system-status` 从 `psutil` + Python 迁成纯 JDK 读数：它要的指标本来就在本进程里，
为了读几个数字先装一个第三方库、再起一个 Python 进程（1.x 那个脚本缺 `psutil` 时
还会自己 `pip install`）是没有必要的代价。容器限额读不到时如实写"未设限"，
不编一个数字——`memory.max` 里写 `max`、v1 里写 9223372036854771712 都是"没设限"，
当成数字用出去会让模型照着"限额 9.2EB"解释一次 OOM。

## 复核（M2 加完三层之后）

- **预算**：`BudgetAcrossLayersTest` 把一层（provider 与单回调两种形态）、
  二层（真协议连接）、三层（脚本包）的工具放进同一份清单，第 7 次调用仍被总数上限拦住。
  变异（去掉 `maxTotalToolCalls`）让它红。
- **鉴权**：`PermissionsAcrossLayersTest` 在装配出来的容器上验"每个工具都已登记"，
  漏登记的后果是拒绝。变异（给某一层的权限表加 `@ConditionalOnMissingBean`，
  即 01 号票那个坑的形状）让它红。

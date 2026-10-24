# 能力分三层：Java Tool / MCP Server / Script Package

---
status: accepted
date: 2026-10-22
---

Agent 可用的能力不统一实现，按"是否需要鉴权、是否有外部副作用、是否依赖外部运行时"分成三层：业务动作写成 Java `@Tool`；依赖外部 CLI 或跨语言的能力包成 MCP server 的 `@McpTool`；保留一个脚本层走社区包 `SkillsTool` + `ShellTools`，维持"写个脚本就能扩展"的敏捷性。三层对 Agent 呈现为一回事——都是工具名加参数——但授权与执行环境不同。

## Considered Options

**全部用 Java `@Tool` 重写，取消脚本层**
否决。`ffmpeg`、`gh`、邮件客户端这类外部 CLI 用 Java 重写的成本远高于收益；且脚本层是这个项目"开放扩展"的定位所在，砍掉就退化成普通 Spring Boot 应用。

**全部走脚本层，沿用 1.x 的做法**
否决。官方对 `SkillsTool` 的安全说明是脚本在本机无沙箱执行。执行环节换由社区包承担，不解决任何问题，只是把 `ShellUtils` 的责任转嫁出去。

**三层并存，按能力性质分流（采纳）**
代价是三套实现机制并存，新人需要理解分层的判据；换来的是每层只用自己最合适的手段，且权限边界可以分层收紧。

## Consequences

- Agent 侧不感知层级：三层都注册为工具，名字与参数 Schema 是唯一契约。
- **1.x 的 29 个场景只保留 8 个**，其余 21 个不迁移（`blogwatcher`、`crypto-price`、`keno-winning-numbers`、`trello`、`notion` 等与主线场景无关的）。保留清单：

  | 层 | 场景 | 迁移后的形态 |
  |---|---|---|
  | 一层 Java `@Tool` | `chat-summary` | Text-to-SQL：`JdbcTemplate` + `validateSchema()` 自纠错 |
  | | `system-status` | JVM/容器指标读取 |
  | 二层 MCP `@McpTool` | `github` | Python MCP server 包 `gh` CLI |
  | | `unread-mail` / `send-email` | Python MCP server 包 `himalaya` |
  | 三层 Script Package | `weather-forecast` / `summarize` / `xurl` | 保留脚本本体，只收口执行环境 |

  保留 8 个之外的，即使是可用脚本也一并删除：留在仓库里只会稀释主线，而面试时没有时间逐个讲。
- 鉴权只对第一层做表级/动作级校验，做在 Advisor 链上、且双接口实现（见 ADR-0007）。脚本层不授予任何业务权限，它只能访问自己的沙箱目录。
- **脚本执行有两个位置，必须分清**：① 既有 Script Package 在**应用容器**内执行，限制工作目录、超时与非 root 用户（官方给出的建议做法，不是我们自己发明的约束）；② Skill Generator 产出的脚本在**独立隔离沙箱**内自测，网络受限、资源有上限。① 的容器里跑着应用本身，所以不给它任何业务凭据；② 的沙箱里跑着未审核代码，所以它拿不到任何凭据。
- MCP 层是唯一跨进程边界的层，连接建立时要 `listTools()` 探测为空即 fail-fast，会话丢失要重新初始化而不是重试。
- Skill Generator 产出的脚本可以执行，但只在上述第 ② 类沙箱内执行并留审计，人审通过后才注册为 Script Package（见 ADR-0002 修订记录）。本条与 ADR-0002 保持一致。

> 修订记录（2026-10-24，M2-07 落地）：保留清单里的 8 个场景**全部落位**，落点与两处偏差见 ADR-0014 的表。两处偏差都是被执行环境逼出来的，不是改主意：① `system-status` 由 `psutil` + Python 迁成纯 JDK 读数（它要的指标本来就在本进程里，为了读几个数字先起一个 Python 进程不划算）；② `summarize` 由"调大模型生成摘要"改成分层抽取式——脚本层的子进程只拿得到 PATH，**依赖 API key 的能力在这一层跑不通**，需要凭据的能力走 MCP。
>
> 跨进程那一条也在这时落到了真的 OS 进程上：02 号票先把连接生命周期在进程内跑通（进 CI），07 号票把端点给出的传输换成一个真的子进程（`gh`、`himalaya` 各包成一个 Python MCP server，见 `examples/mcp/`）。连接代码一行未动。真实外部 CLI 那一组测试默认不跑（依赖本机装了什么），理由与做法见 ADR-0014。

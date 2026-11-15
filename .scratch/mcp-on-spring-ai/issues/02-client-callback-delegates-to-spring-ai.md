# 02: 客户端工具调用改用 Spring AI 的 SyncMcpToolCallback

**What to build:** `McpToolCallback` 不再自己解析参数、自己判 `isError`、自己拼返回文本：
把这件事交给 `SyncMcpToolCallback`，我们只留一个**稳定句柄**——因为这个对象的活法是
"连一次、用很多轮、中途可能被换掉一条连接"，而 `SyncMcpToolCallback` 把 `McpSyncClient`
存成 final 字段，拿不住一件会换的东西。

**Blocked by:** 无。

**Type:** task

**Status:** ready-for-agent

**Demo:** 一个 MCP 工具在对话里被调用两次，之间那条连接因为会话丢失被换掉——两次都成功，
且模型两次看到的是同一个工具名。

## 裁决来源

- `docs/adr/0003-three-tier-capability-model.md`：MCP 工具对模型呈现为普通 `ToolCallback`。
- `McpConnection` 类注释（会话丢失 → 关旧连接、开新连接、重放一次，ADR-0002 同源）：
  client 是**可替换**的，这是本票保留外壳的唯一理由。
- 原则：**MCP 协议调用本身（参数序列化、`isError` 判定、异常到 `ToolExecutionException` 的映射）
  用 Spring AI 的实现，不再抄一遍。**

## 要改的文件

| 文件 | 改什么 |
| --- | --- |
| `mcp/McpToolCallback.java` | `call(String, ToolContext)` 改成每次从连接取**当前** client，构造 `SyncMcpToolCallback`（`prefixedToolName` 显式给原名）并委托；删 `argumentsOf()` / `render()` / `ARGUMENTS` |
| `mcp/McpConnection.java` | `current()` 的可见性从 private 放到包内，给上面那一行用；不放 public（它是"当下这条"，不是契约） |
| `mcp/McpToolProvider` | 不动：`install()` 仍然按工具建回调，只是回调变成了薄壳 |
| 测试 | 见下，两条断言要改（`McpToolTest` 已满 20 条：**合并**一条，不加） |

## 代价（写在这里，不留给评审去发现）

**模型看到的东西变了。** 今天返回的是把 `TextContent` 拼起来的纯文本（`"5"`、`"ECHO:..."`）；
`SyncMcpToolCallback` 返回的是 `jsonHelper.toJson(response.content())`，即
`[{"type":"text","text":"5"}]` 这种 JSON 数组。于是：

- `McpToolTest` 里三条精确断言（`isEqualTo("5")`、`isEqualTo("tick")`、`isEqualTo("ECHO:...")`）
  要改成"文本在结果里"的断言；
- 每轮对话的 token 会略增（多几个结构字符），但换来的是与 Spring AI 生态一致的返回形状。

**退场条件：** 若真实模型用例（`RealThirdPartyMcpTest` / `ChatPipelineTest`）因为这段 JSON
开始读不懂工具结果，就把返回渲染这一件事收回自写（只收渲染，协议调用仍走 Spring AI），
并在本票补一节说明。这一条不接受"看起来还行"就留着。

## 验收判据（TDD 用例清单）

| 判什么行为 | 用例名 | 先红的样子（反例） |
| --- | --- | --- |
| 工具名就是 server 导出的原名，没有 Spring AI 的前缀 | `McpToolTest#providerExposesEveryToolFromTheServer`（既有） | `prefixedToolName` 不传，`SyncMcpToolCallback` 默认加前缀，只有这一条红 |
| 模型给的参数原样成为远端那次调用的 arguments | `McpToolTest#argumentsReachTheRemoteToolUnchanged`（既有） | 委托时把 `toolInput` 丢了，只有这一条红 |
| 空输入按空参数处理，不报解析错 | `McpToolTest#blankInputBecomesEmptyArguments`（既有） | — |
| 远端 `isError` 变成 `ToolExecutionException`，不是一段看似成功的文本 | `McpToolTest#remoteToolErrorBecomesAToolExecutionException`（既有） | 去掉委托改回"原样返回文本"，只有这一条红 |
| 会话丢失 → 换一条连接 → **同一个回调**再调一次仍然成功 | `McpToolTest#lostSessionIsReopenedAndTheCallIsReplayed` 之后补一段：重放成功后用同一个 `ToolCallback` 再 call 一次 | 回调在构造时缓存 client（就是 `SyncMcpToolCallback` 的默认用法），第二次调用红 |
| 返回文本里能找到工具真正说的话 | `McpToolTest#clientCallGoesThroughTheExposedTool` 与三条精确断言改成 `contains` | 返回空串，只有这两条红 |

## 门禁

`./scripts/verify.sh` 全绿、跳过 0；`claw4j-capability` 保持 10 个测试文件、单文件 ≤20 条 `@Test`
（新增的那一段并进已有方法，不新开方法）。

## 不要做

- 不换掉 `McpConnection` / 不引 Spring AI 的 `spring.ai.mcp.client.*` 自动配置：它交的是
  client **实例**（`mcpSyncClients()` 里 `initialize()` 只调一次，stdio 进程死在 `connect()`，
  关掉不可重开），而退避重试要求手上的东西能被重建。这一条写在票里，免得下次评审再报一次。
- 不改工具名前缀策略（模型与鉴权认领工具名靠它，加了前缀两张表都对不上）。
- 不把 `ToolContext` 里的 MCP meta 透出去（本项目没有消费方）。

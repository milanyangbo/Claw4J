# 02: 客户端工具调用改用 Spring AI 的 SyncMcpToolCallback

**What to build:** ~~`McpToolCallback` 不再自己解析参数、判 `isError`、拼返回文本，改为委托
`SyncMcpToolCallback`。~~ → **结论：做不到，见 `## Answer`。保留自写调用，理由记在下面。**

**Blocked by:** 无。

**Type:** research

**Status:** resolved

## 裁决来源

- `docs/adr/0003-three-tier-capability-model.md`：MCP 工具对模型呈现为普通 `ToolCallback`。
- `McpConnection` 类注释（会话丢失 → 关旧连接、开新连接、重放一次，与 ADR-0002 同源）：
  client 是**可替换**的。
- 原则：MCP 协议调用本身（参数序列化、`isError` 判定、异常映射）尽量用 Spring AI 的实现，
  不抄第二遍。

## Answer

**不可行。** `SyncMcpToolCallback.call()` 直接调 `mcpClient.callTool(request)`
（`spring-ai-mcp-2.0.2` `SyncMcpToolCallback.java:139`），它手上那条 `McpSyncClient`
是构造时给进去的 final 字段。于是：

1. **它绕开了 `McpConnection.callTool`**——"会话丢了 → 关旧、开新、把这次调用重放一次"
   这条策略在连接里，而 Spring AI 的适配器根本不走那条路；
2. **它抓不住一件会换的东西**——重开之后回调手里仍是一条死会话。

实测证据：把回调改成委托之后，`McpToolTest#lostSessionIsReopenedAndTheCallIsReplayed` 直接红：

```
ToolExecutionException: Session gone not found on the server
```

那句异常是 Spring AI 的 `catch (Exception e) → new ToolExecutionException(...)` 包出来的，
说明会话丢失根本没到达 `McpConnection`。也就是说：**重连与"用框架适配器"在这里是互斥的**。

绕不过去的两条路，都不该走：

| 变通 | 为什么不行 |
| --- | --- |
| 给适配器一个代理 client | `McpSyncClient` 的构造函数是**包内可见**（`McpSyncClient(McpAsyncClient, Supplier)`），包外无法继承或代理 |
| 在回调里 catch `ToolExecutionException`，看 cause 是不是会话丢失，再让连接重开一次 | 等于把 ADR-0002 的重放策略抄到第二个地方，而且重开失败时 `onLost` 那条上报路径接不上（server 再也不会被重新连上） |

因此保留自写的 `McpToolCallback`：它守的不是"参数怎么解析"（那确实只有十几行），
而是**调用路径必须经过那条可以被换掉的连接**。这一条与"Spring AI 交实例、不交工厂"
是同一件事的两面，也是这个包看起来复杂的真正原因。

## 这次留下的东西

- `McpToolTest#lostSessionIsReopenedAndTheCallIsReplayed` 改成走 `ToolCallback` 而不是
  `connection.callTool`，并补了一条"**同一个回调**再调一次仍然成功、且没有多开连接"。
  它现在是一条锁定"回调不许缓存 client"的用例：谁哪天把 client 挪到字段里，这条会红。
- 自写回调一行没动（`argumentsOf` / `render` / `isError` 三件事仍在自己手里）。

## 不要做

- 不引 `spring.ai.mcp.client.*` 自动配置：同一个原因的另一面（它交 client 实例，
  `initialize()` 只调一次，stdio 进程死在 `connect()` 里，关掉不可重开）。
- 不改工具名前缀策略。
- 不为了"看起来用了框架"把重放策略拆成两处。

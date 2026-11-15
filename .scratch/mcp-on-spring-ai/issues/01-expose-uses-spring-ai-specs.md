# 01: 暴露侧的工具规格改用 Spring AI 的转换器

**What to build:** `McpExposure` 不再自己拼 `McpSchema.Tool` 与 `CallToolResult`，改为
"把鉴权做成一层 `ToolCallback` 装饰器 + 交给 `McpToolUtils.toSyncToolSpecification`"。
暴露出去的工具名、权限判定、拒绝的语义（是一条 `isError` 结果，不是异常）一条都不变。

**Blocked by:** 无。

**Type:** task

**Status:** resolved

**Demo:** 外部客户端连上 `claw4j.mcp.server.enabled=true` 的部署，`listTools` 拿到的清单与今天逐字相同；
调一个越权工具，收到的仍是 `isError=true` 且文本里带着"没有权限…需要读取表…"，而不是一次协议层故障。

## 裁决来源

- `docs/adr/0003-three-tier-capability-model.md`：三层能力对模型呈现为同一件事，MCP 那一层只是适配。
- 本票不改裁决，只换适配的实现者：**Spring AI 已有的转换器不再抄一遍**。
- 三道门（装配期四个校验、调用期 `grantedTo`、审批不进这条路）的位置与语义见
  `McpExposure` 类注释，全部保留。

## 要改的文件

| 文件 | 改什么 |
| --- | --- |
| `mcp/expose/McpExposure.java` | 删 `specification()` / `call()` / `argumentsOf()` / `result()` / `clientNameOf()` / `MAPPER`；加一个私有装饰器（判权 + 日志 + 异常包一句中文），`specifications()` 的装配期校验原样保留 |
| `mcp/expose/McpExposure.java` | 装饰器从 `ToolContext` 取 exchange 用 `McpToolUtils.getMcpExchange`，不再自己从形参拿 |
| 测试 | 见下（`McpToolTest` 已满 20 条，用例落到 `McpServerRetryTest` 之外的地方要先合并一条） |

## 代价（写在这里，不留给评审去发现）

- `McpToolUtils` 把成功的返回包成 `CallToolResult(isError=false)`、把异常包成
  `isError=true` 且文本就是 `getMessage()`。于是"工具执行失败"那句前缀必须由装饰器自己加，
  否则 `textOf(result).contains("执行失败")` 这条断言会先红。
- `McpToolUtils` 会用它的默认 Jackson mapper 去读 `definition.inputSchema()` 这串 JSON。
  今天用的是 MCP 自己的 mapper。两边都能读，但清单里 schema 的往返形状要以
  `McpServersConfigTest` 那条 `inputSchema` 断言为准，红了就是这里。

## 验收判据（TDD 用例清单）

本票是**换实现者，不改行为**，所以不新增用例：`McpToolTest` 里那 20 条就是它的判据
（该文件已满 20 条，也不该再长）。每一条都用"改坏一次，看是不是只有它红"验过：

| 判什么行为 | 用例名（既有，不改） | 先红的样子（反例） |
| --- | --- | --- |
| 交出去的就是清单里点名的那几个 | `McpToolTest#exposedToolsReachTheClient` | 装饰器 `getToolDefinition()` 换成带前缀的名字，只有这一条红 |
| 越权是一次 `isError` 结果，工具一次没跑 | `McpToolTest#narrowTablesRefuseTheCallAndTheToolNeverRuns` | 把装饰器里 `grantedTo` 改成恒真，只有这一条红 |
| 工具自己炸了也是一条结果，带"执行失败" | `McpToolTest#toolFailureBecomesAnErrorResult` | 装饰器不包那句前缀，只有这一条红 |
| 参数原样到工具手里 | `McpToolTest#clientCallGoesThroughTheExposedTool` | 装饰器吞掉 `toolInput` 换成 `"{}"`，只有这一条红 |
| 装配期四道门一条都不放宽 | `#nonReadToolIsRefusedAtAssembly`、`#approvalToolIsRefusedAtAssembly`、`#unregisteredToolIsRefusedAtAssembly`、`#unknownNameIsSkipped`、`#emptyExposureFailsAtAssembly` | 删掉任一条装配期校验，对应用例红 |

## 门禁

`./scripts/verify.sh` 全绿、跳过 0；`claw4j-capability` 保持 10 个测试文件、单文件 ≤20 条 `@Test`。

## 不要做

- 不改 `McpExposureProperties`、不改权限语义、不加"按客户端名给不同表"。
- 不把审批接进这条路（外部客户端没有人能点按钮）。
- 不为了少几行把装配期校验挪到调用期。

## 实现记录

- `McpExposure` 202 → 191 行（**预计 -50，实际 -11**：删掉的 `Tool`/`CallToolResult` 构造、
  参数序列化、结果拼装约 50 行，新加的鉴权装饰器连注释约 40 行）。留着的价值不在行数，
  而在"规格的形状由 Spring AI 决定"，我们只决定拒绝时说哪句话。
- 装饰器用 `McpToolUtils.getMcpExchange(toolContext)` 拿客户端自报的名字，
  判权、日志、异常那三件事留在自己手里。
- 一处**没有**改动也别改：内部工具仍用单参 `call(String)` 调用。把带 MCP exchange 的
  `ToolContext` 递给工具，会让计量/审计那层包装读到它不认识的上下文（`MeteredToolCallback`
  会去里面找 principal / runId），结果是"有人调过但查不出是谁"；context 只在判权时用。
- 变异验证两次：`grantedTo` 恒真 → 只有 `narrowTablesRefuseTheCallAndTheToolNeverRuns` 红；
  去掉"执行失败"前缀 → 只有 `toolFailureBecomesAnErrorResult` 红。

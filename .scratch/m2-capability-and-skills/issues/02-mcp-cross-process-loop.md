# 02: MCP 跨进程闭环

**What to build:** 让 Agent 用上一个**跨进程**的工具：能力以 MCP server 的形式存在，Java 侧只见工具协议（工具名 + 参数 Schema），不见实现。ADR-0003 把 MCP 定为二层，ADR-0002 要求 Java 侧不再直接拼命令行——这张票是这两条第一次落成代码。

**Blocked by:** 01

**Status:** resolved

**Demo:** 用一个 Java 自实现的 stdio MCP server 跑通端到端：问一句话 → 调到 MCP 工具 → 拿到结果并组织成回答。同步与流式两条路径都能演示，且整套演示不需要 Docker、不需要 Python。

- [x] 引入 `spring-ai-starter-mcp-client`（版本由 BOM 管理，2.0.2）。**坐标在票内核验，不要照抄文档** —— M1-06 在 groupId 上栽过一次
- [x] 一个 Java 自实现的 stdio MCP server，兼作测试替身与演示实现：不依赖 Docker、不依赖 Python，于是下面两条进得了 CI
- [x] 连接建立时 `listTools()` 为空即 fail-fast（ADR-0002 / ADR-0008：不等到调用时才暴露配置错误）
- [x] 会话丢失（`McpTransportSessionNotFoundException`）重新初始化连接，而不是重试死会话
- [x] 端到端（`AgentPort`）：模型调到 MCP 工具并拿到结果；流式路径同样成立
- [x] MCP 工具也进 01 那条工具清单断言，也进调用预算（3 / 6）—— 新增来源不得把预算打开
- [x] MCP 层不授予任何业务表权限；未登记权限一律按拒绝
- [x] 变异检验：去掉 fail-fast → 对应测试红；去掉重新初始化 → 对应测试红

**注意:** ADR-0008 把"MCP 连接的 fail-fast 探测与会话丢失重连"列为三条必测之一。测试用的 server 必须是 Java 进程内 stdio，否则这条必测会退化为"只在有 Docker 的机器上测过"。

**不要做:** 真实外部 CLI（`gh`、`himalaya`）的 server 不在这张票（07）；Python MCP server 不在这张票。

## Answer

192 个测试全绿、skipped 0（`set -a && . ./.env && set +a; ./mvnw clean verify -Dclaw4j.dbTests=required`，
再 `./scripts/assert-no-skipped-tests.sh` 报 `tests=192 skipped=0`）。

### 一、坐标是核过的，不是抄的

`spring-ai-starter-mcp-client` 在 `spring-ai-bom:2.0.2` 的 `dependencyManagement` 里逐条对过
（`spring-ai-starter-mcp-client` / `spring-ai-mcp` / `spring-ai-mcp-annotations` 都在），
再看它传递引什么：`spring-ai-mcp → mcp → mcp-core(MCP Java SDK 2.1.0) + mcp-json-jackson3`；
`mcp-spring-webflux` 与 `mcp-spring-webmvc` 在链路里是 optional，不会把 web 栈带进 `claw4j-tools`。

**没引 server starter**：本票的 server 是 Java 自实现的，`StdioServerTransportProvider` 与
`McpServer` 来自传递引的 `mcp-core`，`@McpTool` 扫描来自 `spring-ai-mcp-annotations`，都够了。

### 二、"跨进程"跨的是协议边界，不是 OS 进程——这条偏差要记账

票面写的是"跨进程"。落地的是**进程内 stdio**：`InProcessStdioMcpServer` 起一个真的
`McpSyncServer`，两端用一对管道当 stdin/stdout，帧格式与官方 `StdioClientTransport`
完全一致（行分隔的 JSON-RPC，消息体内不许有裸换行）。**OS 进程那条边界没有真起。**

为什么这么选：SDK 的 `StdioClientTransport` 只接受 `ServerParameters`，它一定会
`ProcessBuilder.start()`；子进程路线要拿到一条可靠的 classpath，而 surefire 默认给的是
manifest-only 的 booter jar——那条路在 CI 上是脆的。而 spec 明令要避免的正是
"必测项只在有 Docker 的机器上测过"。

代价与接缝都写进了代码与 README：`McpServerEndpoint.openTransport()` 就是换真实传输的那一处，
换它不动上面任何一行生命周期代码（`gh` / `himalaya` 那批 server 在 07）。

### 三、两条必测各自长什么样

**fail-fast**：判据只有一条——"清单是空的"。SDK 对"server 没声明 tools 能力"另有
`Server does not provide tools capability` 一说，那是同一件事实的两种表示，
所以在 `McpConnection.listTools()` 里归一成空清单，由 `McpToolProvider` 一处判失败。
它是单例 bean，于是"建的时候"＝"应用启动的时候"。

**会话丢失**：`McpConnection.callTool` 只认 `McpTransportSessionNotFoundException`
（沿 cause 链找一层，因为它可能已被 Reactor 包过），关掉旧连接、重新开一条、把这次调用重放
**一次**——重放之后仍然丢，那是 server 真的不在了，交给上层去失败。
阈值不设循环：把"超时""协议错误"也当成"重开一次"，会把一次真故障变成一串无意义的重连。

### 四、顺手修掉的一个会让权限静默消失的坑

`MCP 层不授予任何业务表权限`要求 MCP 也登记一张 `ToolPermissionRegistry`。
而 `ToolsAutoConfiguration.salesToolPermissionRegistry` 上挂着
`@ConditionalOnMissingBean(ToolPermissionRegistry.class)`——语义是"容器里有了就让位"，
于是两个来源各登记各的时，后处理的那个**静默不注册**，它的工具从此一律按"未登记"被拒。
这与 01 号票修掉的"工具静默消失"是同一个形状，换了个 bean 名重演一遍。
两边都改成按"本层能不能成立"判（有没有执行器 / 有没有端点）。

### 五、框架那份 MCP 工具来源是关掉的

引了 starter，Spring AI 自己的 `McpToolCallbackAutoConfiguration` 也会注册一个
**空的** `ToolCallbackProvider`（`mcpToolCallbacks`）。它带来的实际故障是：
`ToolsWiringTest` 按类型注入"那个 provider"的地方直接装配失败（M2 之前容器里只有一个来源）。
app 的 `application.yml` 里 `spring.ai.mcp.client.enabled: false` 关掉它，
并补了一条断言盯着——"配置文件里少一行"是最容易在合并中丢掉的改动，
丢了之后的表现跟 MCP 看起来毫无关系。

### 六、变异检验

| 变异 | 结果 |
| --- | --- |
| 去掉 fail-fast（`if (tools.isEmpty())` → `if (false)`） | tools 1 红（工具清单为空那条） |
| 去掉重新初始化（丢会话时直接抛） | tools 1 红（会话丢失那条，报 `Session ... not found`） |
| MCP 工具登记上业务表（`ToolPermission.read("sales_order")`） | tools 1 红（不含任何业务表那条） |
| 给"未登记"那条用例把 MCP 工具登记上权限 | agent 1 红（没登记权限的 MCP 工具被拒绝） |

### 留给后面

- 03（Script Package）接进来时是第三个来源，`McpToolProvider` 与它的 provider 会并存；
  工具重名会在这里直接失败（同名即配置错误），跨来源重名的判定仍是"第一张登记表赢"，没定论。
- 生产侧目前**没有挂任何 MCP server**（没有端点 bean，就没有 MCP 工具）。
  07 接入 `gh` / `himalaya` 时，加一个 `McpServerEndpoint` bean 即可。
- `REQUEST_TIMEOUT` 是常量（30s），没做成配置项；等有真实外部 server 再决定要不要外露。

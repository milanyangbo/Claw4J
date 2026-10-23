# 02: MCP 跨进程闭环

**What to build:** 让 Agent 用上一个**跨进程**的工具：能力以 MCP server 的形式存在，Java 侧只见工具协议（工具名 + 参数 Schema），不见实现。ADR-0003 把 MCP 定为二层，ADR-0002 要求 Java 侧不再直接拼命令行——这张票是这两条第一次落成代码。

**Blocked by:** 01

**Status:** ready-for-agent

**Demo:** 用一个 Java 自实现的 stdio MCP server 跑通端到端：问一句话 → 调到 MCP 工具 → 拿到结果并组织成回答。同步与流式两条路径都能演示，且整套演示不需要 Docker、不需要 Python。

- [ ] 引入 `spring-ai-starter-mcp-client`（版本由 BOM 管理，2.0.2）。**坐标在票内核验，不要照抄文档** —— M1-06 在 groupId 上栽过一次
- [ ] 一个 Java 自实现的 stdio MCP server，兼作测试替身与演示实现：不依赖 Docker、不依赖 Python，于是下面两条进得了 CI
- [ ] 连接建立时 `listTools()` 为空即 fail-fast（ADR-0002 / ADR-0008：不等到调用时才暴露配置错误）
- [ ] 会话丢失（`McpTransportSessionNotFoundException`）重新初始化连接，而不是重试死会话
- [ ] 端到端（`AgentPort`）：模型调到 MCP 工具并拿到结果；流式路径同样成立
- [ ] MCP 工具也进 01 那条工具清单断言，也进调用预算（3 / 6）—— 新增来源不得把预算打开
- [ ] MCP 层不授予任何业务表权限；未登记权限一律按拒绝
- [ ] 变异检验：去掉 fail-fast → 对应测试红；去掉重新初始化 → 对应测试红

**注意:** ADR-0008 把"MCP 连接的 fail-fast 探测与会话丢失重连"列为三条必测之一。测试用的 server 必须是 Java 进程内 stdio，否则这条必测会退化为"只在有 Docker 的机器上测过"。

**不要做:** 真实外部 CLI（`gh`、`himalaya`）的 server 不在这张票（07）；Python MCP server 不在这张票。

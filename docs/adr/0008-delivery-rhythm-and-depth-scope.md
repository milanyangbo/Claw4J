# 交付节奏：四里程碑、通道优先级、四项做深与测试策略

---
status: accepted
date: 2026-10-22
---

能力面铺得越广，每个点越讲不清。这次重建**只挑四项做到能讲清取舍的深度，其余点到为止**，并且按四个里程碑推进，每个里程碑结束都必须处于"能演示"的状态。

## Considered Options

**十个能力点各做一层皮（RAG / MCP / A2A / Skills / Subagent / Todo / Session / 可观测 / 评测 / 安全）**
否决。每个都只有一层皮时，面试追问第二层就答不上来，反而暴露深度不足；且没有任何一项能形成闭环演示。

**四项做深（采纳）**
① **Advisor 链**：自定义双接口 Advisor，含流式路径——2.x 的灵魂；② **Tool Calling + MCP**：含 40/150 次调用上限、`partial results` 降级、`McpTransportSessionNotFoundException` 重连；③ **Agent Skills + Subagent 编排**：渐进式披露 + 上下文隔离；④ **Session 记忆 + 可观测**：轮次压缩的 Trigger 与 Strategy 必须成对、token 计量、Micrometer。

## Consequences

### 里程碑

| | 交付内容 | 完成判据 |
|---|---|---|
| **M0** | 多模块骨架（ADR-0004）、Boot 4.1.2 + JDK 21、`docker compose` 起 Postgres(pgvector) + Redis、一次真实 `ChatClient` 调用、CI 绿 | 问一句话能拿到回答 |
| **M1** | Channel(HTTP+SSE) + Session 记忆 + 业务库 `@Tool`（Text-to-SQL）+ Advisor 链鉴权（ADR-0007）+ 测试覆盖 | 能在 IM 里问"上周华东区成交额"并拿到数 |
| **M2** | Agent Skills（渐进式披露）+ Subagent 编排 + MCP server + 三层能力打通 + Skill Generator 沙箱自测 + 飞书长连接通道（含异步 ACK 与事件去重） | 模型能自己选技能、委派子 Agent、跑通 MCP，且能在飞书里发消息问答 |
| **M3** | A2A 端点 + 可观测（token 计量、Micrometer）+ 评估器进 CI + 语义缓存 | 能被别的 Agent 通过 AgentCard 发现并调用 |

M0 结束就必须能演示，不等 M2。

### 通道优先级

真实 IM 通道的选型（飞书长连接为主、企业微信个人不可行）见 **ADR-0009**。交付顺序更新为：**HTTP+SSE（M1）→ 飞书长连接（M2）→ A2A（M3）→ Teammors WebSocket（最后，可不做）**；Telegram 作为可选的第二 Channel 实现，用于证明 SPI 可替换。

1.x 那套私有加密 WebSocket（`XMessageClient`，685 行）排在最后，因为它的加解密与心跳协议讲不出通用价值，而维护成本与缺陷密度（每请求 `new OkHttpClient()`、跨线程 `ArrayList`、public 可变字段）都是实打实的。它的 ECDH/AES 代码**保留在 `legacy/` 里作为对照**，用来讲"我们如何把通道抽象掉了"。时间不够就不做，不影响叙事。

### 测试策略

- 开发期用 `spring-boot-docker-compose` 拉起 pgvector + Redis（`@ServiceConnection` 自动注入连接信息）；集成测试用 **Testcontainers** 起干净实例。两者连的都是本机 Docker，分工不同：compose 常驻会给测试带来脏数据串扰，Testcontainers 每次销毁重建。
- 三条必测：① 每个自定义 Advisor 的单测，**含流式路径**（防 ADR-0007 那个静默跳过的坑）；② Text-to-SQL 自纠错链路用固定 schema 跑通；③ MCP 连接的 fail-fast 探测与会话丢失重连。
- 单元测试**全部 mock 模型调用**，不烧 token、不依赖网络（ADR-0006）。集成测试走真实端点，用 profile 隔离且默认不进 CI。
- Spring AI 评估器（`RelevancyEvaluator` / `FactCheckingEvaluator`）挂进 CI 当质量门禁。

### 工具调用预算：用框架的上限，但不是它的默认值

`DefaultToolCallingManager.Builder` 自带每工具 40 次、整轮 150 次的上限
（`DEFAULT_MAX_CALLS_PER_TOOL` / `DEFAULT_MAX_TOTAL_TOOL_CALLS`），
默认行为是 `THROW`——`ToolCallingAdvisor` 接住 `ToolCallLimitExceededException` 后
直接变成一代回答给客户端，不再回头问模型。Tool Calling 这一项做深要讲的就是这套机制。

但 Text-to-SQL 的自纠错每多一次就是一次模型往返加一次数据库往返，
40 次足够把一次提问烧到答不出来。所以 M1-05 把它收紧到每工具 3 次、整轮 6 次
（见 `ToolCallBudget`）：正常一次查到，留两次给自纠错，第三次还错就不再给机会。

**这个数不写在 `spring.ai.tools.limits.*` 里**，因为那条配置到不了这条链：
`ChatClient` 由我们自己建，生效的是五参 `ChatClient.builder(...)` 里传进去的那个
`ToolCallingManager`。哪天改成注入 Boot 的 `ChatClient.Builder` bean，
数字会静默退回 40/150——所以 `TextToSqlLoopTest` 里有一条断言专门钉着它。

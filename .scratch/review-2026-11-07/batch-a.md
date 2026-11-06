# batch A：m0-skeleton + m1-business-data-loop

## m0-skeleton

### 01 仓库骨架与 legacy 隔离
- 判定：自研
- 代码证据：`pom.xml:15-32`（今天 8 个 module）、`legacy/README.md:3`（不参与任何构建，且 `pom.xml` 的 modules 里搜不到 legacy）、`docs/adr/0015-one-core-artifact.md:8`（domain 整体并入 core）
- OpenClaw：`pnpm-workspace.yaml:1-6`（单一 pnpm workspace，`packages/*` + `extensions/*` + `examples/*`），仓库根无 `legacy/` 目录
- 一句：票面的七模块今天变成 8 个（domain→core、channel-* 合一、tools→capability，示例拆两个并新增 eval），ADR-0015 只解释了 8→7 那一步，属正常演进。

### 02 本地依赖编排（docker compose 起 Postgres 与 Redis）
- 判定：已过期
- 代码证据：`compose.yaml:15-16`（服务只剩 postgres）、`infra/postgres/init.sql:2-3`（vector 扩展 + business schema）、`claw4j-app/src/main/resources/application.yml:24-26`（默认不拉 compose）、`claw4j-app/pom.xml:87`（依赖仍预留）、`docs/adr/0005-one-postgres-for-business-session-and-vectors.md:95-101`（Redis 于 2026-10-26 摘除）
- OpenClaw：`docker-compose.yml:1-3`（只起 `openclaw-gateway`，状态落文件系统），没有数据库/缓存容器可对照
- 一句：Postgres(pgvector) 与"compose 可自动拉起"仍在，Redis 服务整块已删；代码里未找到 `@ServiceConnection`（只在 `docs/adr/0008-delivery-rhythm-and-depth-scope.md:39` 留着这句话），票面这一条今天只能算历史。

### 03 ChatClient 装配与 mock 模型跑通全链路
- 判定：不一致
- 代码证据：`claw4j-app/src/main/resources/application.yml:56`（"一个 key 都没有就启动失败（ADR-0011，没有 mock 兜底）"）、`docs/adr/0011-tests-run-against-real-models.md:8-9`（MockChatModel / ScriptedChatModel 一并删除）、`claw4j-agent/src/main/java/io/github/claw4j/agent/AgentAutoConfiguration.java:130-169`（ChatClient 装配仍在）、`claw4j-core/.../Principal.java:16`
- OpenClaw：`docs/gateway/protocol.md:13-16`（自有 Gateway/WSS runtime），未找到 Spring AI 装配或"无 key 自动 mock"的对应设计
- 一句：ChatClient 与零框架 Principal 都还在，但票面最核心的"没有 key 也能起并跑通一次问答"被 ADR-0011 反向改成"没 key 起不来"，是正面冲突而非演进。

### 04 HTTP+SSE 通道端到端打通
- 判定：自研
- 代码证据：`HttpPrincipalResolver.java:22-24`（三个请求头）、`:36-47`（缺用户头/未登记角色 401）、`HttpChatController.java:91-125`（立刻返回 SseEmitter + future 收尾）、`:142-147`（池满 503）、`:186-196`（finalChunk 驱动 done）、`HttpChatChannel.java:92-128`（业务线程池 + 中断取消）
- OpenClaw：`docs/gateway/protocol.md:13-16`（控制面是 Gateway WebSocket），未找到 HTTP+SSE 聊天端点
- 一句：四个类的职责、401/503、`http:<user>` 回落与 finalChunk 契约今天都成立；票面点名的 `HttpChannelEndToEndTest`/`AdvisorNotSkippedOverHttpTest` 已不在，端到端改由 app 层承担（`ApplicationAssemblyTest.java:243`）。

### 05 CI 与质量门禁
- 判定：不一致
- 代码证据：`CONTRIBUTING.md:175`（"仓库没有接 CI，也没有 pre-commit hook"）、`docs/agents/review.md:43`（同口径，且写明"有意为之"）、`.github/` 与 `.githooks/` 均不存在（Glob 无结果）、`scripts/verify.sh:25`（八个模块的本地门禁）、`claw4j-core/pom.xml:98-113`（enforcer bannedDependencies 仍在）
- OpenClaw：`.github/workflows/ci.yml:1`（有 CI 工作流）
- 一句：ArchUnit + enforcer + 覆盖率闸仍在，但票面的"CI 只构建 claw4j-*"与"pre-commit hook"两条今天都不存在，而且是后来明确取消的，不是漏做。

## m1-business-data-loop

### 01 脚本化假模型与 `agent → tools` 依赖边
- 判定：已过期
- 代码证据：`claw4j-agent/pom.xml:27-31`（仍依赖 claw4j-capability）、`ChatPipelineTest.java:118-133`（两轮工具循环、工具执行两次仍在）、`ChatPipelineTest.java:818`（替身已换成 FixedChatModel）、`docs/adr/0011-tests-run-against-real-models.md:9`（ScriptedChatModel 删除）
- OpenClaw：`docs/tools/index.md:47`（工具有 profile/allow/deny 过滤），未找到"脚本化假模型/脚本耗尽报错"的对应物
- 一句：依赖边与"先调工具、再据工具结果作答"的可测性都还在，但票面点名的 `ScriptedChatModel`/`ScriptedChatModelTest` 与"脚本耗尽明确失败"这一形态已不存在，桩工具也按计划被 05 换掉。

### 02 业务 schema 与种子 + Testcontainers 干净实例
- 判定：自研
- 代码证据：`V1__business_schema.sql:7-40`（region/customer/sales_order/order_item 四张表）、`V2__business_seed.sql:42`（128400 的来源）、`SalesQueryTest.java:32-36`（每类一个容器）、`:57-68`（断言 128400）、`claw4j-example-sales/pom.xml:55-60`（flyway-core + postgresql 方言）
- OpenClaw：`docker-compose.yml:1-3`（只跑 gateway）、`docs/concepts/session.md:360`（状态落 SQLite），没有可对照的业务 schema/种子
- 一句：四张表、相对日期种子、128400 数字契约与"每类一容器"今天都成立；迁移脚本按 ADR-0005 的要求落在示例插件（`docs/adr/0005...md:50`），只是归属迁移，不是行为变化。

### 03 鉴权 Advisor —— 在每一次工具迭代上都校验
- 判定：自研
- 代码证据：`AuthorizationAdvisor.java:116`（`ORDER = ToolSearchToolCallingAdvisor.DEFAULT_ORDER + 100`）、`:184-221`（同步/流式都逐轮判）、`:555-562`（拒绝时连 toolCalls 一起换掉）、`SalesExampleAutoConfiguration.java:101-107`（querySales 登记四张表）、`AgentAutoConfiguration.java:336-345`（bean 装配）
- OpenClaw：`docs/tools/index.md:47`（模型只看到"通过 profile/allow-deny 筛选后"的工具，是配置期可见性），未找到每次工具迭代前的执行前鉴权
- 一句：order 落点、拒绝话术与"工具一次都没被调用"的判据今天都在；循环常量从 `ToolCallingAdvisor` 换成 `ToolSearchToolCallingAdvisor`，口径未变。

### 04 PII 脱敏 Advisor
- 判定：自研
- 代码证据：`PiiRedactionAdvisor.java:46`（循环外、与记忆同档）、`:66-98`（流式扣尾巴后统一打码）、`PiiRedactor.java:22-25`（手机号/身份证带数字定界）、`ChatClientAgentPort.java:393`（入站先打码）、`InboundSecurityTest.java:282-329`（切块边界 + ListAppender 日志断言）
- OpenClaw：`docs/security/network-proxy.md:30`（只把 URL 里的凭据从快照/日志遮蔽），未找到对模型回答做出站 PII 脱敏
- 一句：票面五条验收今天全在，还多了 ADR-0011 抓到的"入站先打码"；OpenClaw 没有对应能力，不算偏差。

### 05 Text-to-SQL 闭环 —— 问一句拿到库里的数
- 判定：自研
- 代码证据：`SalesTools.java:82-96`（@Tool + 失败返回原因）、`:39-51`（固定 schema 写进描述）、`SalesSqlGuard.java:28`（MAX_ROWS=50）、`JdbcReadOnlySqlExecutor.java:64-68`（只读事务）、`:79-85`（statement_timeout + 多取一行判断截断）、`ToolCallBudget.java:51,60`（3/6）、`SalesExampleAutoConfiguration.java:40`（afterName）、`ApplicationAssemblyTest.java:243`（端到端 128400）
- OpenClaw：`docs/tools/index.md:47`（只有通用工具可见性策略），未找到 Text-to-SQL 业务工具
- 一句：两层只读、行上限、3/6 调用预算、afterName 静默失效修复今天都成立；`@Tool` 从 claw4j-tools 落到 `claw4j-example-sales` 是有注释说明的插件化（`SalesExampleAutoConfiguration.java:32-38`），能力没有回退。

### 06 会话记忆 —— 用 `spring-ai-session` 落 Postgres
- 判定：一致
- 代码证据：`SessionKeys.java:14`（前缀 + owner + 通道 + chatId + 段）、`:24-35`（显式引用 OpenClaw 的 session key 形状与 dmScope）、`SessionMemoryConfiguration.java:99-115`（JdbcSessionRepository + 独立池）、`V3__session_schema.sql:12-18`（claw4j_session.ai_session）、`ChatClientAgentPort.java:800-805`（sessionId 走 Advisor 上下文，不用 chatId 当主键）
- OpenClaw：`docs/concepts/session.md:11-13`（每条入站按来源路由到 session）、`:139-165`（`dmScope: per-channel-peer` = 通道 + 发送者隔离）、`:360`（会话行/transcript 落 SQLite）
- 一句：会话键按"身份 + 通道 + 地址"派生、chatId 只作寻址键，这两条与 OpenClaw 同口径；存储层（spring-ai-session + Postgres schema）是我们的技术栈，不构成语义差异。

### 07 真实模型联调与文档收尾
- 判定：已过期
- 代码证据：`pom.xml:336-364`（profile 只剩 real-cli 与 eval，`real-model` 已删）、`docs/adr/0006...md:159-161`（"-Preal-model 一并删除"）、`README.md:366`（模型切换说明仍在）、`GLOSSARY.md:293-317`（Text-to-SQL/只读事务/脱敏术语仍在）、`README.md:412`（门禁跑的是假模型 + 真容器）
- OpenClaw：`docs/concepts/model-providers/official-provider-plugins.md:23-25`（provider transport 可配），多供应商能力有对应物
- 一句：真实闭环与文档落点仍在，但票面"真实调用用 profile 隔离、默认不进 CI"这一条今天不存在（既无 CI 也无 `-Preal-model`），且门禁本身已改为假模型，与 ADR-0011 相反。

## 本批小结
- 一致 1 张（m1-06：会话键口径与 OpenClaw 的 channel+peer 隔离同形）
- 自研 6 张（m0-01 多模块骨架与 legacy 隔离、m0-04 HTTP+SSE 演示通道、m1-02 业务 schema/种子、m1-03 逐轮执行前鉴权、m1-04 出站 PII 脱敏、m1-05 Text-to-SQL 业务工具）
- 故意不同 0 张
- 不一致 2 张
  - m0-03：票面"无 key 也能起并跑通"与今天"无 key 启动失败"正面冲突（`application.yml:56`、ADR-0011:8-9）
  - m0-05：票面承诺的 CI 与 pre-commit hook 今天都不存在（`CONTRIBUTING.md:175`、`.github/` 缺失）
- 已过期 3 张
  - m0-02：Redis 服务已按 ADR-0005 删除，只剩 Postgres
  - m1-01：`ScriptedChatModel` 与"脚本耗尽报错"已删除，替身改为 FixedChatModel
  - m1-07：`-Preal-model` 隔离已删除，真实调用不再有 profile 边界
- 最值得修的三处（按代价排序）
  1. 给 m0-03 / m1-01 / m1-07 三张票各加一行 superseded 指针（指向 ADR-0011 / ADR-0006 修订），否则后来者会照票面把 mock 兜底加回来。
  2. 统一 ADR-0011 与 `README.md:412` / `docs/agents/guardrails.md:38` 的口径——"测试连真模型"与"门禁跑假模型"今天同时成立，任选一边写清楚。
  3. 决定 CI 到底接不接：不接就在 m0-05 票面写明"已取消"；接的话要同时补 Docker 与 `DEEPSEEK_API_KEY`，成本最高。

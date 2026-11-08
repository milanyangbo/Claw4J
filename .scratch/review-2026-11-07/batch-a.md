# batch A：m0-skeleton + m1-business-data-loop

## 本批适用的两条已裁决口径

这两条是**决定**，不是欠账，也不是"待修项"。本批里凡是与它们相抵的判定，都按这两条读。

**1. 不接 CI。** 没有 GitHub Actions、没有 pre-commit hook，提交前的入口只有本地
`./scripts/verify.sh`（`CONTRIBUTING.md:175`；`docs/agents/review.md:43` 原话是
"有意为之，入口只有 `./scripts/verify.sh`。别再提第二遍"）。
OpenClaw 有 `.github/workflows/ci.yml`，这一条我们不跟：它的用例要 runner 上有 Docker
守护进程、要 `secrets.DEEPSEEK_API_KEY`，接上等于把"本机能跑"换成"上游能跑"，
换不来任何东西。**m0-05 票面承诺的 CI 与 pre-commit hook 作废，不再补。**

**2. 不用 mock 模型。** 生产装配里没有"没 key 就降级成假模型"这条路（ADR-0011）：

- 一个 key 都没有 → 启动失败；
- `claw4j.model.primary` 指的那一家没配 key → 启动失败，**不会**悄悄拿备用的顶上
  （`application.yml:56-58`、`RoutingModelConfiguration.java:66-73`，异常里写明该配哪个变量）；
- 唯一不报错的是"备家没配 key"：那一家不装配，主家照常工作（`RealChatModelCondition`
  的判定表第三行）。这不是兜底，是"没配就不装"。

要区分开：测试里的 `FixedChatModel` 是**替身**，不是这条兜底——它只在 `src/test` 下，
生产装配里根本没有它。门禁跑替身 + 真容器（真模型只剩一条用例：`ChatPipelineTest:265`），
真模型那条完整的线在 `docs/capability-tests.md` 与 `./scripts/eval.sh`。
那条真模型用例**要 key**：没有 `DEEPSEEK_API_KEY` 时它是红、不是跳过
（`ChatPipelineTest:266-268`）——这是 ADR-0011 留下的唯一一条"无 key 即失败"。
所以门禁不需要外网，但需要那把 key；要恢复"所有走模型的测试连真模型"是另一件事，不在这两条之内。

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
- 判定：故意不同（票面已被 ADR-0011 推翻，见文首第 2 条）
- 代码证据：`claw4j-app/src/main/resources/application.yml:56`（"一个 key 都没有就启动失败（ADR-0011，没有 mock 兜底）"）、`docs/adr/0011-tests-run-against-real-models.md:8-9`（MockChatModel / ScriptedChatModel 一并删除）、`claw4j-agent/src/main/java/io/github/claw4j/agent/AgentAutoConfiguration.java:130-169`（ChatClient 装配仍在）、`claw4j-agent/.../model/RoutingModelConfiguration.java:66-73`（主模型没 key 就抛，不拿备用顶）、`claw4j-core/.../Principal.java:16`
- OpenClaw：`docs/gateway/protocol.md:13-16`（自有 Gateway/WSS runtime），未找到 Spring AI 装配或"无 key 自动 mock"的对应设计
- 一句：ChatClient 与零框架 Principal 都还在；票面"没有 key 也能起并跑通一次问答"作废——今天一个 key 都没有、或 primary 那家没 key，启动就失败（票面顶部已加 superseded 指针）。**不要再按票面把 mock 兜底加回来。**

### 04 HTTP+SSE 通道端到端打通
- 判定：自研
- 代码证据：`HttpPrincipalResolver.java:22-24`（三个请求头）、`:36-47`（缺用户头/未登记角色 401）、`HttpChatController.java:91-125`（立刻返回 SseEmitter + future 收尾）、`:142-147`（池满 503）、`:186-196`（finalChunk 驱动 done）、`HttpChatChannel.java:92-128`（业务线程池 + 中断取消）
- OpenClaw：`docs/gateway/protocol.md:13-16`（控制面是 Gateway WebSocket），未找到 HTTP+SSE 聊天端点
- 一句：四个类的职责、401/503、`http:<user>` 回落与 finalChunk 契约今天都成立；票面点名的 `HttpChannelEndToEndTest`/`AdvisorNotSkippedOverHttpTest` 已不在，端到端改由 app 层承担（`ApplicationAssemblyTest.java:243`）。

### 05 CI 与质量门禁
- 判定：故意不同（票面承诺已取消，见文首第 1 条）
- 代码证据：`CONTRIBUTING.md:175`（"仓库没有接 CI，也没有 pre-commit hook"）、`docs/agents/review.md:43`（同口径，且写明"有意为之""别再提第二遍"）、`.github/` 与 `.githooks/` 均不存在（Glob 无结果）、`scripts/verify.sh:25`（八个模块的本地门禁）、`claw4j-core/pom.xml:98-113`（enforcer bannedDependencies 仍在）
- OpenClaw：`.github/workflows/ci.yml:1`（有 CI 工作流）
- 一句：ArchUnit + enforcer + 覆盖率闸仍在；票面的 CI 与 pre-commit hook **作废，不补**——门禁入口只有一个本地脚本，接 CI 要先在 runner 上备 Docker 与 key，换不来任何东西。

## m1-business-data-loop

### 01 脚本化假模型与 `agent → tools` 依赖边
- 判定：已过期
- 代码证据：`claw4j-agent/pom.xml:27-31`（仍依赖 claw4j-capability）、`ChatPipelineTest.java:118-133`（两轮工具循环、工具执行两次仍在）、`ChatPipelineTest.java:847`（`FixedChatModel` 就声明在这个文件里）、`docs/adr/0011-tests-run-against-real-models.md:9`（ScriptedChatModel 删除）
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
- 一句：真实闭环与文档落点仍在；票面"真实调用用 profile 隔离、默认不进 CI"两条今天都没有——`-Preal-model` 已删，CI 也不接（文首第 1 条）。门禁跑替身 + 真容器（真模型只剩一条用例）是现状（文首第 2 条末段），不是"与 ADR-0011 相反"的缺陷；真模型那条线走 `docs/capability-tests.md` 与 `./scripts/eval.sh`。

## 本批小结
- 一致 1 张（m1-06：会话键口径与 OpenClaw 的 channel+peer 隔离同形）
- 自研 6 张（m0-01 多模块骨架与 legacy 隔离、m0-04 HTTP+SSE 演示通道、m1-02 业务 schema/种子、m1-03 逐轮执行前鉴权、m1-04 出站 PII 脱敏、m1-05 Text-to-SQL 业务工具）
- 故意不同 2 张——**两张都是票面被裁决推翻，不是缺一块**：
  - m0-03：不用 mock 模型，配了模型没 key 就启动失败（文首第 2 条、ADR-0011）
  - m0-05：不接 CI（文首第 1 条、`CONTRIBUTING.md:175`）
- 不一致 0 张
- 已过期 3 张
  - m0-02：Redis 服务已按 ADR-0005 删除，只剩 Postgres
  - m1-01：`ScriptedChatModel` 与"脚本耗尽报错"已删除，替身改为 FixedChatModel
  - m1-07：`-Preal-model` 隔离已删除，真实调用不再有 profile 边界
- 两处收尾（2026-11-08 已做）
  1. m1-01 / m1-07 各加了一节「修订」指针（m0-03 早前已加，指向 ADR-0011），
     写明 `ScriptedChatModel` 与 `-Preal-model` 都已删除、不要再按票面加回来。
  2. ADR-0011 顶部加「部分失效」指针（照 0016 的惯例，正文不动）：失效的是
     "测试一律连真实 DeepSeek"这条——门禁跑替身 + 真容器，偏离记在
     `docs/agents/guardrails.md:38`；"删掉 mock 兜底"那条仍然成立。
     顺带在指针里点明 Considered Options 里的「真模型只跑 IT，`-Preal-model` 隔离」
     是**当时被否决的选项**，不是对现状的描述——它是被否掉的那条，不是写错的那条。

## 本批不成立的两类建议（写下来免得再提）

- **"补 CI / 加 pre-commit hook"**：不接，见文首第 1 条。
- **"给测试加 mock 兜底 / 让没 key 的部署也能起"**：不加，见文首第 2 条。

# 06: 会话记忆 —— 用 `spring-ai-session` 落 Postgres

**What to build:** 多轮上下文。第一轮问"上周华东区成交额"，第二轮问"那华南呢"，第二轮要继承上一轮的时间窗与指标口径，而不是重新开始。

**开工前必读（2026-10-23 二次核实，推翻前一版注记）**：上一版这里写"坐标在 2.0.2 里不存在、
要退到内置 ChatMemory"，**那个结论是错的**。当时只翻了 `org.springframework.ai` 这个 groupId 下的
2.0.2 BOM，而 `spring-ai-session` 是 **spring-ai-community 的独立项目**，groupId 是
**`org.springaicommunity`**——根 pom 里 import 的 `spring-ai-session-bom:0.10.0` 一直在管它。

真坐标（已拉到本地仓库核实过 jar 里的类）：`org.springaicommunity:spring-ai-starter-session-jdbc:0.10.0`
- `org.springframework.ai.session.jdbc.JdbcSessionRepository` + `PostgresJdbcSessionRepositoryDialect`
- `org.springframework.ai.session.advisor.SessionMemoryAdvisor`（`BaseAdvisor`，同步/流式两侧都有）
- `org.springframework.ai.session.InMemorySessionRepository`（本票要禁用的那个）
- 自带 `schema-postgresql.sql`（`AI_SESSION` / `AI_SESSION_EVENT`）
- 它依赖 `spring-ai-model` / `spring-ai-client-chat` 的 2.0.1，被 2.0.2 的 BOM 提上来即可

所以**不需要退到 `spring-ai-starter-model-chat-memory`**，按票面原意做。
ADR-0005 只补了 groupId 与这段修订说明，没有改决策。

一个必须自己接的口子：官方自动配置（`JdbcSessionRepositoryAutoConfiguration`）拿的是容器里那个主
`DataSource`，会话表会落到 public。ADR-0005 要的是"同库不同 schema"，所以**自己声明
`SessionRepository` bean**（它的自动配置带 `@ConditionalOnMissingBean`，会退让），
指向一个 `currentSchema=claw4j_session` 的独立连接池。

**Blocked by:** 01, 02

**Status:** resolved

**Demo:** 同一个 `chatId` 连着问两轮，第二轮的"那华南呢"能答出华南的数，不需要把"上周""成交额"重讲一遍。

- [x] 用 `spring-ai-session` 的 JDBC starter，**不回退**到"自己写个 Map 存着"——自己写的版本讲不出多实例与事务，且偏离 ADR-0005
- [x] 会话表与业务表同库不同 schema（ADR-0005）
- [x] 禁用 `InMemorySessionRepository`（多实例失忆）
- [x] **`chatId` 只作通道侧寻址键，不当会话主键**。这两个概念在 M0 专门分开过（`sessionKey → chatId` 那次改名就是为了这一天），混用会让"换通道"和"换会话"变成同一件事
- [x] AgentPort 端到端断言：第二轮的追问继承第一轮口径。**开工前必然红**——现在每轮都是全新的
- [x] 流式路径同样继承
- [x] 不烧 token、不依赖网络

**注意**：M1 只做到"记得住上一轮"。轮次压缩（Trigger 与 Strategy 成对）是容量问题，spec 明确不在这一轮拍，不要顺手做。

## 落地记录（2026-10-23）

**改了什么**

- `claw4j-agent` 引 `org.springaicommunity:spring-ai-starter-session-jdbc`；
  新增 `io.github.claw4j.agent.session`：`SessionMemoryConfiguration`（装配）、`SessionKeys`（会话 id 派生）、
  `SessionSchema`（URL 上的 `currentSchema`）、`SessionMemoryProperties`、`FaultTolerantSessionService`（降级）。
- `ChatClientAgentPort` 把会话 id 与 userId 放进 Advisor 上下文（`SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY`）。
- 迁移 `V3__session_schema.sql`：`claw4j_session` schema 下两张表（抄官方脚本，建表写全限定名——
  Flyway 连的是默认 search_path，不写前缀会落到 public）。
- `PiiRedactionAdvisor.ORDER` 从 `DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER - 100` 提到
  `DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER`：链的收尾按 order 降序，脱敏必须比记忆**先**收尾，
  会话表里存的才是掩码后的文本。
- `ScriptedChatModel` 的脚本由"写死的响应"升级成函数（`thenAnswer`）：
  "第二轮接得上"这件事只能由模型看到的东西决定，写死脚本时"有记忆"和"没记忆"拿到同一个答案。

**变异检验（去掉实现，测试必须红）**

| 去掉的东西 | 红什么 |
|---|---|
| 不传会话 id | 7 条全红（Advisor 直接抛"没有 session id"） |
| 拿 `chatId` 当会话主键 | 只有"换通道不失忆"红（其余 6 条照样绿） |
| 记忆 order 大于脱敏 | "会话里存的是脱敏后的文本" + 装配里的 order 断言红 |
| 记忆 Advisor 不挂链 | 6 条端到端红（"换个用户不串味"仍绿——它是负向判据） |

**顺带修的**：`SalesDataRepositoryTest` 原来断言迁移记录总数是 2，加了 V3 就红。
改成钉"V2 只执行过一次"——它真正要防的是种子被重放，总数随新增迁移变化与这件事无关。

**判据要能证伪**：断言不能是"第二轮有回答"——无记忆的实现也有回答。必须是"第二轮的答案算的是华南、且时间窗仍是上周"，即数值等于 02 号票种子里华南那个数。

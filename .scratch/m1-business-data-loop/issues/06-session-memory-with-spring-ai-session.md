# 06: 会话记忆 —— 用 `spring-ai-session` 落 Postgres

**What to build:** 多轮上下文。第一轮问"上周华东区成交额"，第二轮问"那华南呢"，第二轮要继承上一轮的时间窗与指标口径，而不是重新开始。

**开工前必读（2026-10-23 核实）**：票面"用 `spring-ai-session` 的 JDBC starter，不回退"这个坐标
在 Spring AI 2.0.2 里**不存在**。把 2.0.2 的 BOM 翻了一遍（170 个 artifact），没有任何
`spring-ai-session` 模块，ADR-0005 写的 `spring-ai-starter-session-jdbc` 同样查无此物。
（`ToolCallLimits` 的 javadoc 里提到 `spring-ai-session` 是另一个项目。）

可用的替代品是 **`spring-ai-starter-model-chat-memory` + `spring-ai-starter-model-chat-memory-repository-jdbc`**
（BOM 里有，本地仓库也已经有 jar），配 `MessageChatMemoryAdvisor`。
它满足票面"不回退"的真正诉求——会话落 JDBC、多实例不失忆，
与 ADR-0005 的"与业务表同库不同 schema"也不冲突。

所以开工第一步是**先改 ADR-0005 与本票的坐标**（留修订说明，不要静默换掉），再动手。这不是回退。

**Blocked by:** 01, 02

**Status:** ready-for-agent

**Demo:** 同一个 `chatId` 连着问两轮，第二轮的"那华南呢"能答出华南的数，不需要把"上周""成交额"重讲一遍。

- [ ] 用 `spring-ai-session` 的 JDBC starter，**不回退**到"自己写个 Map 存着"——自己写的版本讲不出多实例与事务，且偏离 ADR-0005
- [ ] 会话表与业务表同库不同 schema（ADR-0005）
- [ ] 禁用 `InMemorySessionRepository`（多实例失忆）
- [ ] **`chatId` 只作通道侧寻址键，不当会话主键**。这两个概念在 M0 专门分开过（`sessionKey → chatId` 那次改名就是为了这一天），混用会让"换通道"和"换会话"变成同一件事
- [ ] AgentPort 端到端断言：第二轮的追问继承第一轮口径。**开工前必然红**——现在每轮都是全新的
- [ ] 流式路径同样继承
- [ ] 不烧 token、不依赖网络

**注意**：M1 只做到"记得住上一轮"。轮次压缩（Trigger 与 Strategy 成对）是容量问题，spec 明确不在这一轮拍，不要顺手做。

**判据要能证伪**：断言不能是"第二轮有回答"——无记忆的实现也有回答。必须是"第二轮的答案算的是华南、且时间窗仍是上周"，即数值等于 02 号票种子里华南那个数。

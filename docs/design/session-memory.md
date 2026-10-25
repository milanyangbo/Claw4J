# 会话记忆：记得住上一轮，而且换条通道也记得住

> 设计说明：为什么这么做、否决了什么。要看结论去 [README](../../README.md)，要看决策去 [ADR](../adr/)。

第一轮问"上周华东区成交额多少"，第二轮问"那华南呢"——第二轮不该要求把"上周""成交额"重讲一遍。
记忆挂在链的最外层（`SessionMemoryAdvisor`，order 全链最小），用
**`org.springaicommunity:spring-ai-starter-session-jdbc`**（groupId 不是 `org.springframework.ai`，
它是 spring-ai-community 的独立项目，ADR-0005 里记了这个坑）落在 Postgres，
会话表在 `claw4j_session` schema，业务表在 `business`，同库不同 schema。

三点值得留意：

1. **会话属于人，不属于通道**。会话 id 只由 `Principal.userId()` 派生，**不掺 `chatId`**：
   `chatId` 是通道侧的寻址键（HTTP 的请求头、飞书的 chat_id），换条通道它就变了。
   把它编进会话主键，"换通道"和"换会话"就成了同一件事——存储明明是共享的，人却要重讲一遍。
   代价是同一个人在两段对话里问两件事，记忆会混在一起；多话题并行是产品问题，等有真实数据再定。
2. **会话有自己的连接池，URL 上带 `currentSchema`**。`spring-ai-session` 的 SQL 里表名不带前缀，
   表落到哪由连接的 `search_path` 决定；而它的自动配置拿的是主 `DataSource`，表会落到 public。
   所以这里自己声明了 `SessionRepository`（官方那份带 `@ConditionalOnMissingBean`，会退让），
   指向会话 schema。会话池**不注册成 bean**——容器里有两个 `DataSource` 时，
   那些 `@ConditionalOnSingleCandidate(DataSource.class)` 的自动配置会整套安静地不装配。
3. **记忆的收尾排在脱敏之后**。链的收尾按 order 降序，order 越小越晚收尾，
   于是写进会话表的是**掩码后**的文本。反过来写，出站是干净的、会话表里躺的是明文——
   一份比出站更持久的泄露，而且没人会去看。

**记不住不等于答不了。** 会话库挂了的时候，记忆降级成"这一轮没有历史"并打一条 WARN，
而不是让整条链 500：鉴权是安全边界，它不能因为"记不住"就连"没权限"这句话也说不出来
（`SessionMemoryDegradationTest` 钉着）。三个 Advisor 的失败姿势不一样——
鉴权失败只能拒绝、脱敏失败只能拒绝、记忆失败只能降级。

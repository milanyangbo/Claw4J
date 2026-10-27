# 03: 跨会话召回：问"上次那批单子怎么定的"能想起来

**What to build:** 把 `spring-ai-session` 的 `CrossSessionRecallTools` 接上，让模型能检索
**同一个用户在其他会话里说过的话**，并把这个工具登记进鉴权表。

**Blocked by:** 01 —— 召回是"往提示词里再塞一段历史"，成本要看得见，否则没法判断它值不值。

**Status:** ready

**Demo:** 会话 A 里问"华东区的口径是什么"，得到答案；换一个会话 B 问"上次华东区的口径怎么定的"，
回答里带上会话 A 的内容。

- [ ] `userId` **按当前请求构造**，不是单例 bean。`CrossSessionRecallTools.builder(sessionService, userId)`
      把 userId 绑在实例上，共享一个 bean 意味着**所有人召回的都是同一个人的历史**——
      这是这张票唯一真正的安全边界
- [ ] 工具名 `recall_session`，动作 `recall_session`，`tables` 为空（它读的是会话，不是业务表）。
      只给 read-only 与 admin，guest 不给
- [ ] 召回结果截断后再进提示词：`pageSize` 有上限，且**整段结果**也要按字符裁一次——
      召回十条长对话能一口气把上下文吃满
- [ ] 断言两件事：另一个会话的内容能被这轮看到；**换一个 userId 就看不到**
- [ ] 变异：把 userId 从"当前 principal"改成构造时写死的常量 → 第二条断言变红

**落点:** `claw4j-agent` 的 `session` 包（`CrossSessionRecallTools` 的包装与权限登记）。
工具回调自己实现（`FunctionToolCallback`），不依赖它有没有 `@Tool` 注解——
注解在第三方 jar 里，有没有都得试一次才知道，自己写 schema 反而确定。

**注意:** 这是**关键词检索，不是向量语义**。spec 里核实过 jar：`CrossSessionRecallTools`
按词匹配 `SessionEvent`。所以它召回不了"换个说法问同一件事"，那要 pgvector（票 05 的载体），
不在本票。文档里不要写成"语义召回"。

**不要做:** 向量召回；跨用户召回（管理员查别人的会话）；把召回结果写回当前会话。

## 实现记录

（未开始）

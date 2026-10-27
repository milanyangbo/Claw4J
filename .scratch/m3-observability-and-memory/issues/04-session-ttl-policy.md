# 04: 会话 TTL：过期不等于删除

**What to build:** 把会话寿命从框架默认值改成显式策略，并让"过期"真的导致删除——
现在 TTL 只是一个存在表里、再没人看的字段。

**Blocked by:** 02 —— 顺序不能反：压缩过的摘要写在会话事件里，如果清理任务先跑，
它删掉的可能正是刚拿来摘要的原文；而摘要比原文活得久没有意义。

**Status:** ready

**Demo:** 把 TTL 调成 1 分钟（只在本机），建一个会话、不再动它，清理任务跑过一轮之后
`select count(*) from claw4j_session.session` 里这个会话没了。

- [ ] `spring.ai.session.time-to-live` 显式配成 30 天（框架默认是 null = 永不过期，
      "没配"与"永不过期"在配置上长得一样，这是最容易一直没人发现的那种默认值）
- [ ] 清理任务：定时调 `SessionService.deleteExpiredSessions(now)`，间隔与 TTL 同一份配置
      （默认每小时一次）。**只配 TTL 不配清理 = 这一票什么都没做**
- [ ] `FaultTolerantSessionService` 透传 `deleteExpiredSessions` 并沿用降级语义：
      库不可用时清理失败要落 WARN，不能让一个后台任务把应用拖挂
- [ ] 清理计数进指标：`claw4j.session.expired`（删了几条）
- [ ] 断言"过期的真被删了、没过期的还在"，而不是断言"定时任务被注册了"
- [ ] 变异：把清理任务的调用删掉 → 第一条变红；把 TTL 改回 null → "没过期的还在"那条红

**落点:** `claw4j-agent` 的 `session` 包（`SessionMemoryConfiguration` 加
`@EnableScheduling` 与一个清理 bean，`SessionMemoryProperties` 加 `time-to-live` 与
`cleanup-interval`）。`application.yml` 同步。

**注意:** 定时任务要用容器里的 `TaskScheduler`，不要自己 `new Timer()`——
自己造的线程池不会被优雅关闭，测的时候还会让 JVM 退不掉。

**不要做:** 按用户维度批量删；归档到另一张表（过期就是删，留一份"已删除的会话"等于
数据留存期永远没有终点，这与本项目要讲的《数据安全法》口径相反）。

## 实现记录

（未开始）

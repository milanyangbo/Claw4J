# 02: 长对话压缩：把"记得住"换成"记得住且塞得进去"

**What to build:** 给 `SessionMemoryAdvisor` 配上**成对的** `TokenCountTrigger` 与
`RecursiveSummarizationCompactionStrategy`，让长对话在到达阈值时自动把早年事件压成摘要，
而不是一路全量拼进提示词。

**Blocked by:** 01 —— 阈值按 token 定，而"到底花了多少 token"这套口径是 01 建的。
反过来，压完有没有效果也要靠 01 的 `tokens{prompt}` 看：压对了这个数应该降下来。

**Status:** ready

**Demo:** 同一个会话里连问十轮（每轮都让它查一次库，事件堆得快），到第十轮时
`getEvents` 的条数不再线性增长、且多出一条摘要事件；另外 `/actuator/metrics/claw4j.model.tokens`
里 `kind=prompt` 的单次值不再随轮数一直涨。

- [ ] `SessionMemoryAdvisor.Builder` 上同时设置 `compactionTrigger` 与 `compactionStrategy`。
      **只设一个等于没设**：只有 Trigger 是"判断该压了然后不压"，只有 Strategy 是"永远不触发"
- [ ] 阈值按**比例**算，不写死绝对 token 数：`context-window × trigger-ratio`（默认 65536 × 0.5）。
      写死 24000 的时候，换到 128k 的模型上等于每两轮压一次，而换到 32k 的模型上等于永远不压
- [ ] 摘要用一个**单独建的 `ChatClient`**，不挂记忆 Advisor：`SessionMemoryAdvisor` 自己
      在链上，摘要走它等于"压缩触发压缩"，一旦摘要本身又把事件写回会话就是个环
- [ ] `maxEventsToKeep` 留一个窗口（默认 20 条）：全压掉的话"刚才说的那件事"也会没
- [ ] 压缩失败不拖垮这一轮：`onSummarizationFailure` 落 WARN + `claw4j.session.compaction.failures`
      计数，这一轮照常回答
- [ ] 断言压缩**真的发生了**：事件条数下降 + 出现一条摘要事件。不断言"配置里有两个对象"
- [ ] 变异：把 `compactionStrategy` 从 Builder 上摘掉 → 第 1 条与第 6 条一起变红；
      把阈值改成 1 → 第一次对话就压，`maxEventsToKeep` 那条红

**落点:** `claw4j-agent` 的 `session` 包。`SessionMemoryConfiguration` 里配 Trigger/Strategy，
`SessionMemoryProperties` 加三项（`context-window`、`trigger-ratio`、`events-to-keep`）。
`application.yml` 的 `claw4j.session.*` 同步。测试加进已有的 `SessionMemoryTest`。

**注意:** 落地前先反编译一遍 `SessionMemoryAdvisor`，确认**触发时机**（before 还是 after、
同步还是调度器上）——这三个问题决定了摘要会不会阻塞这一轮的回答。别照着名字猜。

**不要做:** 把摘要换成"便宜模型"路由（ADR-0006 的设想），这一版用当前主模型。
理由：摘要只在阈值上发生，频率低；而为它做一路模型分流要先回答"哪一路算便宜"，
那是配置问题不是这张票的范围。这一条记账在 ADR-0006 的实现记录里。

## 实现记录

（未开始）

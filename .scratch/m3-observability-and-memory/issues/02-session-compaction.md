# 02: 长对话压缩：把"记得住"换成"记得住且塞得进去"

**What to build:** 给 `SessionMemoryAdvisor` 配上**成对的** `TokenCountTrigger` 与
`RecursiveSummarizationCompactionStrategy`，让长对话在到达阈值时自动把早年事件压成摘要，
而不是一路全量拼进提示词。

**Blocked by:** 01 —— 阈值按 token 定，而"到底花了多少 token"这套口径是 01 建的。
反过来，压完有没有效果也要靠 01 的 `tokens{prompt}` 看：压对了这个数应该降下来。

**Status:** resolved

**Demo:** 同一个会话里连问十轮（每轮都让它查一次库，事件堆得快），到第十轮时
`getEvents` 的条数不再线性增长、且多出一条摘要事件；另外 `/actuator/metrics/claw4j.model.tokens`
里 `kind=prompt` 的单次值不再随轮数一直涨。

- [x] `SessionMemoryAdvisor.Builder` 上同时设置 `compactionTrigger` 与 `compactionStrategy`。
      **只设一个等于没设**：只有 Trigger 是"判断该压了然后不压"，只有 Strategy 是"永远不触发"
      ——**这一条被推翻了**，见下方实现记录
- [x] 阈值按**比例**算，不写死绝对 token 数：`context-window × trigger-ratio`（默认 65536 × 0.5）。
      写死 24000 的时候，换到 128k 的模型上等于每两轮压一次，而换到 32k 的模型上等于永远不压
- [x] 摘要用一个**单独建的 `ChatClient`**，不挂记忆 Advisor：`SessionMemoryAdvisor` 自己
      在链上，摘要走它等于"压缩触发压缩"，一旦摘要本身又把事件写回会话就是个环
- [x] `maxEventsToKeep` 留一个窗口（默认 20 条）：全压掉的话"刚才说的那件事"也会没
- [x] 压缩失败不拖垮这一轮：`onSummarizationFailure` 落 WARN + `claw4j.session.compaction.failures`
      计数，这一轮照常回答
- [x] 断言压缩**真的发生了**：事件条数下降 + 出现一条摘要事件。不断言"配置里有两个对象"
- [x] 变异：把 `compactionStrategy` 从 Builder 上摘掉 → 第 1 条与第 6 条一起变红；
      把阈值改成 1 → 第一次对话就压，`maxEventsToKeep` 那条红

**落点:** `claw4j-agent` 的 `session` 包。`SessionMemoryConfiguration` 里配 Trigger/Strategy，
`SessionMemoryProperties` 加三项（`context-window`、`trigger-ratio`、`events-to-keep`）。
`application.yml` 的 `claw4j.session.*` 同步。测试加进已有的 `SessionMemoryTest`。

**注意:** 落地前先反编译一遍 `SessionMemoryAdvisor`，确认**触发时机**（before 还是 after、
同步还是调度器上）——这三个问题决定了摘要会不会阻塞这一轮的回答。别照着名字猜。

**不要做:** 把摘要换成"便宜模型"路由（ADR-0006 的设想），这一版用当前主模型。
理由：摘要只在阈值上发生，频率低；而为它做一路模型分流要先回答"哪一路算便宜"，
那是配置问题不是这张票的范围。这一条记账在 ADR-0006 的实现记录里。

## 实现记录（2026-10-27）

**票面第一条要求是错的，落地时被推翻。** 原计划"给 `SessionMemoryAdvisor.Builder` 配上
`compactionTrigger` 与 `compactionStrategy`"。实际 `javap -c` 之后发现：0.10.0 的
`SessionMemoryAdvisor` **从不调用 `SessionService.compact`**，那两个字段存进去之后没有任何代码读。
配上去的结果是"配了、不报错、不生效"——比缺功能更难发现。

所以改成：

- 新增 `SessionCompactor`（`session` 包）。它持有 trigger 与 strategy，调
  `SessionService.compact`，把结果里的 `archivedEventCount` 与 `tokensEstimatedSaved`
  记成 `claw4j.session.compaction.archived` / `tokens_saved`，失败记 `failures`。
- 触发点在 `ChatClientAgentPort#promptOf`：进模型之前压。选这里的理由是**下一句就是拼提示词**，
  压完立刻被读到；放 after 里压是给下一轮用，而下一轮可能已经超了。
- `SessionMemoryAdvisor` 上**刻意不配**那两个参数。配了只会让人以为压缩已经生效。
- `SessionMemoryProperties` 加三项：`context-window` / `trigger-ratio` / `events-to-keep`，
  阈值 = `contextWindow × triggerRatio`。`events-to-keep` 下限 3——
  `RecursiveSummarizationCompactionStrategy` 的 `overlapSize` 默认 2 且必须小于它，
  配成 1 或 2 会在 `build()` 抛框架的话，不如在本项目这边兜住。
- 会话还不存在时（第一轮）抛的 `IllegalArgumentException` **单独 catch、不记失败**：
  那是时序不是故障。把它记成失败，`failures` 从第一天起就不是 0，从此没人看这个数。

**变异验证**（两条都精确命中）：

| 变异 | 结果 |
| --- | --- |
| `SessionCompactor` 里把 `sessions.compact(...)` 改成 `result = null` | `longConversationGetsCompacted` 红："压完事件条数必须下降" |
| 删掉 `catch (IllegalArgumentException)` 分支 | `missingSessionIsNotAFailure` 红：failures 从 null 变成 1 |

**测试里的 `MapSessionService.compact` 也改了**：原来直接返回 null。压缩测试要验的是
"这套流程跑完事件真的变少了"，返回 null 的话测的是一个空壳，所以照
`DefaultSessionService` 的形状补上（不存在就抛、先问 trigger、压完替换事件集）。

**未做**：摘要换便宜模型（ADR-0006 的设想）。要先把"哪一路算便宜"变成配置，
是配置问题不是这张票的范围。

# 票 08：检索的可观测性——搜到没有、空在哪一步

**Status:** open

## 由来

票 06 真机验证时发现：`claw4j.tool.calls?tool=toolSearchTool` 恒为 0。查下来不是"没调用"，是
**没被计量**——检索工具由 `DisclosureToolCallingAdvisor` 内部用 `MethodToolCallbackProvider` 现建，
从不经过 `AgentAutoConfiguration#collectToolCallbacks` 那层 `MeteredToolCallback` 包装。所以它没有计数、
没有 `tool.toolSearchTool` 的 span、也没有 `tool_action` 审计行。

用户 2026-11-26 问：能不能优化，或者换一种更体现检索的监控（比如"检索有没有成功"）。

## 裁决

无需 ADR：这是既有观测口径（P07 审计 / P14 trace / `claw4j.*` 指标）的延伸，不改变任何行为与契约。

## 为什么不把它塞进 `claw4j.tool.calls`

技术上三行就能做到（`new MeteredToolCallback(searchCallback, registry)`）。不做，因为**那个指标的语义会被污染**：
`claw4j.tool.calls` 是"哪个工具被问得多"，它决定下一个优化谁、哪个该收权限。检索工具不是业务工具，
它是"找工具"这个动作本身；混进去之后排行第一的永远是它，而它一个业务动作都没做。

审计同理：审计回答"谁干了什么"，检索不是一次动作，是找动作的手段。让它进 `tool_action`，等于
每一轮都多两行没有副作用的记录，`I2 四种变体` 那类验收也要跟着重算。

**结论：另起一族指标，不套 `MeteredToolCallback`。** 将来真要 span（票 07 的向量检索要看延迟），
在索引那一层单独开，不借工具回调那条链。

## 指标设计

| 指标 | 类型 | 标签 | 回答什么问题 |
| --- | --- | --- | --- |
| `claw4j.tool.search.calls` | counter | `result=hit\|miss\|error`、`narrowed=true\|false` | 检索了几次、几次空手、几次是**收窄之后**空手（分组配错的信号） |
| `claw4j.tool.search.results` | distribution summary | 无 | 每次返回几个候选：是"经常 0"还是"经常 5 个满"（`max-results` 够不够） |

- **`query` 不进标签**：它是模型每轮现写的自然语言，基数无上限。要看原文去 DEBUG 日志。
- **`narrowed` 而不是 `category`**：分组名来自配置（有界），但"用了哪一组"对排障没有增量——
  真正要区分的是"空手是不是收窄造成的"。真要查哪一组，`category` 可以后续再加。
- **`miss` 的定义是"索引返回了空列表"**，不是"模型没用上"。后者（retrieval → adoption）见缺口。
- **延迟先不量**：正则索引是微秒级，量了也是噪声。票 07 的向量/混合上线时一起加 `claw4j.tool.search.latency`。

## 落在哪

新增 `claw4j-agent/observability/MeteredToolIndex implements ToolIndex`，包装任意索引，四个方法全转发：

```
mode=off 且全塞       → 索引根本不建，指标恒为 0（正确：一次检索都没发生）
其余                  → 每次 search 记一次 calls，按结果打 result / narrowed
```

装配点：`AgentAutoConfiguration#toolIndex`，**包在最外层**（`Metered(Namespaced(raw))`）：

- 包最外层才记得到"真正返回给模型几个候选"；包在里层记的是收窄之前那一批（宽搜 15 条、过滤后 0 条，
  里层看到的是 15）。
- `mode=off` 时索引仍然被建出来（bean 照样要装配），但一次 `search` 都不会发生，指标自然是 0。

没有 `MeterRegistry` 时照常转发、不记：与 `MeteredToolCallback` 同一条口径（装不装 actuator 只影响
报不报得出去）。

## TDD 用例清单

并入 `ModelAndToolSelectionTest`（票 06 之后 19 条，本票 +4 → 23，仍 ≤ 26）。
不开新文件：`claw4j-agent` 14 个测试文件已满额，`ObservabilityTest` 也已经是 26 条。

| 判什么行为 | 用例 | 先红的样子 |
| --- | --- | --- |
| 搜到与空手分开计 | `ModelAndToolSelectionTest#searchCounterSeparatesHitFromMiss` | 只有总数、没有 result 标签时，`hit` 那条查不到，断言红 |
| 收窄导致的空手能认出来 | `ModelAndToolSelectionTest#narrowedMissIsTagged` | 不分 narrowed 时两种情况混在一个数里，断言 `narrowed=true` 的 miss 红 |
| 索引抛异常照常记为 error 并往上抛 | `ModelAndToolSelectionTest#searchFailureCountsAsErrorAndPropagates` | 吞掉异常（模型以为"没搜到"）时红 |
| 返回条数进分布 | `ModelAndToolSelectionTest#resultCountRecordsHowManyCameBack` | 记成命中次数而不是条数时，sum 对不上，断言红 |

装配断言并入票 06 那条 `ApplicationAssemblyTest#toolSurfaceDefaultsToAutoAndWrapsGrouping`：
再加一句"最外层是计量包装"。

## 缺口（本票不做）

- **adoption（检索回来的工具到底有没有被调用）**：要按会话记住上一轮检索集合，再在后续轮次里比对
  实际工具响应名。语义也纠缠（返回 5 个用了 1 个算不算成功），容易做成一个说不清的数。
  等有真实数据、能定义清楚再开票。
- **query 原文**：不进标签（高基数）。要查就 `logging.level.io.github.claw4j.agent.toolaccess=DEBUG`。
- **延迟**：等票 07。
- **验收文档**：`README-capability-tests.md` 的 I9（五个 `claw4j.audit.*` 计数）旁边可以加这两条，
  但按票 06 的约定，验收文档要等票 07 落地后按最终形态一起改，不单独动这一处。

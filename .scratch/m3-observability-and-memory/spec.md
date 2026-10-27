Status: ready-for-agent

# M3：能看见成本，能记住更久，工具多到可以检索

## Problem Statement

M2 已经能在飞书里问答，但这条链路现在是**黑盒**：

1. **花掉多少 token 没人知道。** `ChatResponse.metadata.usage` 每一轮都有，代码里一次都没读过。
   一次提问在工具循环里可能打 3~6 次模型，成本是单次的数倍，而这个数字目前只存在于
   供应商账单里，且账单是下个月才来的。ADR-0008 把"token 计量 + Micrometer"列为 M3 四项之一，
   理由不是"监控是好东西"，是**没有它就没法判断某次改动让成本涨了还是降了**。
2. **长对话会把整段历史全量拼进提示词。** `SessionMemoryAdvisor` 的
   `compactionTrigger` / `compactionStrategy` 一个都没设，而这两个开关**只在这个 Builder 上，
   不是 yml 配置项**——所以压缩从未执行过，`FaultTolerantSessionService.compact` 那条穿透
   方法走不到。后果不是报错，是提示词一路涨到上下文上限。
3. **跨会话想不起来。** `CrossSessionRecallTools` 没有注册成工具，所以"上次那批单子怎么定的"
   这类问题做不到——记忆只在同一个 session 内连续。
4. **同一个问题被反复问、反复算。** "上周华东成交额"这类问题每天问十遍，十遍都打模型、
   都查库、都算一遍。
5. **工具清单会长到塞不进提示词。** 现在工具少（个位数），全部塞进提示词是对的；
   一旦上到几十个，光工具定义就占掉可观的比例，而且模型选错的概率上升。
   ADR-0001 记下的 `spring-ai-tool-search-advisor` 就是为这件事准备的，一直没评估。

## Solution

六张票，顺序有依赖：

| 票 | 事 | 依赖 |
| --- | --- | --- |
| 01 | 可观测：token 计量 + Micrometer + actuator 端点 | 无（后面两张都靠它看效果） |
| 02 | 会话压缩：`TokenCountTrigger` + `RecursiveSummarizationCompactionStrategy` | 01（同一套 token 口径） |
| 03 | 跨会话召回：`CrossSessionRecallTools` 注册成工具并登记权限 | 01（否则不知道召回花了多少） |
| 04 | 会话 TTL 从框架默认值改成显式策略 | 02（压缩过的摘要不能比原文先过期） |
| 05 | 语义缓存：同义问题直接命中，不打模型 | 01（命中率与省下的 token 都要看得见） |
| 06 | 工具检索：工具多到一定数量时按需检索，不再全塞 | 01 |

## 边界与判据

### 票 01 可观测

- 计量位置在 **`ChatModel` 这一层**，不在 `AgentPort`。一轮对话里工具循环每转一圈就调一次
  `ChatModel`，只在 `AgentPort` 记一次会漏掉中间每一轮的 token——而成本恰恰在那里。
- 装饰在**每个 route 上**，不是包住整个 `RoutingChatModel`：这样 `route` 这个标签天然带出
  "这次是主模型答的还是降级后答的"，降级次数 = `route="backup"` 的调用数，不需要在
  `RoutingChatModel` 里再埋一个计数器（少一处耦合）。
- 四条指标，多一条都不加：

  | 指标 | 类型 | 标签 |
  | --- | --- | --- |
  | `claw4j.model.tokens` | Counter | `route`、`model`、`kind`(prompt/completion) |
  | `claw4j.model.calls` | Counter | `route`、`outcome`(ok/error) |
  | `claw4j.model.duration` | Timer | `route`、`stream`(true/false) |
  | `claw4j.tool.calls` | Counter | `tool`、`outcome`(ok/error/denied) |

- **流式的 token 只认最后一帧**：部分供应商只在末帧给 usage，逐帧累加会把总量算成 0。
  拿不到 usage 时不记 token，只记 calls——**不拿估算值冒充真实值**。
- `claw4j.tool.calls` 的 `denied` 来自鉴权拒绝。这条是"谁能干什么"的运行时证据，
  比读一遍 yml 可靠。
- 端点：`/actuator/metrics` 暴露，`prometheus` 可选。health 保持默认。
- 指标前缀 `claw4j.`，`common tags` 里带 `application=claw4j`。

### 票 02 会话压缩

- Trigger 与 Strategy **成对出现**，这是 ADR-0008 第 ④ 条的原话：
  只有 Trigger 没 Strategy 是"判断该压缩了，然后不压缩"；只有 Strategy 没 Trigger 是"永远不触发"。
  两者都配了却没有 `SessionService.compact` 的实现，是第三种静默——要断言压缩**真的发生了**
  （事件数变少 + 出现一条摘要事件），不是断言"配置里有两个对象"。
- `TokenCountTrigger` 的阈值按**上下文窗口的比例**定，不写死一个绝对数：写死 6000 的时候，
  换到 128k 的模型上等于每两轮压一次。
- 摘要走**便宜的那一路**（ADR-0006 里"摘要压缩用便宜模型"的原始设想）。这需要在
  `SessionMemoryAdvisor.Builder` 之外再拿一个 `ChatModel`——`RecursiveSummarizationCompactionStrategy`
  自己不持有模型，它产出 `CompactionPlan`，由调用方执行。**这一条要先读实现再写**，
  不照着名字猜（见 Further Notes）。
- 压缩失败不能拖垮对话：`onSummarizationFailure` 落日志 + 计数，这一轮照常回答。

### 票 03 跨会话召回

- `CrossSessionRecallTools` 是**关键词检索，不是向量语义**（spec 里核实过 jar）。
  它绑定的 `userId` 必须是**当前这个 Principal 的**，不能是构造时写死的：
  一个共享的 bean 拿错 userId，就是把别人的历史召回给这个人。
  这一条决定了它多半不能做成单例 bean，而要做成"每次请求按当前身份构造"的工具。
- 召回结果进提示词等于**把别的会话的内容带进这一轮**，所以它必须有权限：
  动作名 `recall_session`，只给能读业务数据的人（read-only / admin），不给 guest。
- 注册成工具之后，它自动进 `claw4j.tool.calls` 的统计（票 01）。

### 票 04 会话 TTL

- 现状是 `SessionServiceProperties.timeToLive` 的框架默认值（null = 永不过期）。
- 显式策略：默认 30 天，可配。**必须配 `deleteExpiredSessions` 的定期清理**，否则 TTL 只是
  表里一个再没人看的字段——过期不等于删除。
- 清理任务跟压缩过的摘要走同一条寿命：摘要比原文活得更久没有意义（原文删了、摘要还在，
  召回出来的是二手转述）。顺序：先压后删，别让清理删掉刚生成摘要的原文。

### 票 05 语义缓存

- 命中条件三条，缺一不可：**同一个授权范围**、**相似度过阈值**、**没过期**。
  第一条是安全边界：不同角色问同一个问题，答案可能不同（guest 读不到 sales_order）。
  缓存 key 里必须含权限指纹，否则就是把 admin 的答案给 guest。
- 相似度用 pgvector（ADR-0005 已定载体，库里就是 pgvector 镜像），**不引 Redis**：
  Redis 的用途是"跨实例的短命键值"，而这个缓存要和授权、会话在同一个事务边界里，
  放到另一个存储只会多一个不一致的来源。ADR-0005 的修订记录说"引回 Redis Stack"，
  本票要**明确推翻它并留痕**，不能一边写"用 Redis"一边用 pgvector。
- 未命中才走模型；命中时回答里要能看出是缓存（不是伪装成刚算的），但**不打断用户**——
  在回答尾部加一句极短的标注，或者只在指标里体现。二选一，票里定。
- embedding 来源：DeepSeek 没有 embedding 接口，通义有 OpenAI 兼容的 `text-embedding-v3`。
  复用 `claw4j.model.qwen.*` 那套 key，不新增一组凭据。
- 缓存只在**无副作用**的回答上生效：带工具调用且工具是写操作（`draft_script` 那类）的
  一轮不缓存。判断依据是这一轮**实际调用的工具集合**，不是问题文本。

### 票 06 工具检索

- `spring-ai-tool-search-advisor` 在 2.0.2 BOM 里（`spring-ai-tool-search-advisor`、
  `spring-ai-tool-search-tool-vectorstore`、`-lucene` 都在）。先做**评估**：
  在工具数达到 N 之前，全塞进提示词是正确且更简单的选择，把阈值变成配置项即可。
- 交付判据不是"引了依赖"，是**同一批工具、同一个问题，检索前后都能选对工具**，
  并且提示词里的工具定义 token 数下降（这个数字票 01 能测）。
- 检索的向量索引不引入新存储：复用票 05 的 pgvector。

## Testing Decisions

**什么叫好测试**：只断言外部可观察的行为。这次四条关键行为是——
指标的数值、提示词里的事件条数、召回结果进没进这一轮的上下文、同一个问题第二次有没有打模型。

- 票 01 用 `SimpleMeterRegistry` 直接读计数器，**不 mock `MeterRegistry`**：
  它是内存实现，断言 `registry.get("claw4j.model.tokens").tag("kind","prompt").counter().count()`
  比抓 mock 的调用参数更接近真实。
- 票 02 断言"压缩之后这一轮提示词里的事件条数减少、且有一条摘要事件"，不断言用了哪个 Strategy 类。
- 票 03 断言"另一个会话里的内容能被这轮的提示词看到"**以及**"换个 userId 就看不到"。
  后一条是安全断言，缺了等于没测。
- 票 05 断言"同一个问题第二次打模型的次数为 0"，以及"换一个角色问同样的话，缓存不命中"。
- 每条都要能在删除一行实现之后变红，票里逐条写明**哪一行**。

**不写的东西**：不写"指标里有这个指标名"这种只证明代码执行过的用例；
不写把 `MeterRegistry` mock 掉再验证"调用了几次 add"的用例。

## Out of Scope

- **A2A 端点**（ADR-0008 的 M3 验收判据"能被别的 Agent 通过 AgentCard 发现并调用"）：
  本轮不做。ADR-0008 要加一条修订记录，把这条从 M3 验收里移走，
  否则"M3 结束了"这个说法不成立。
- **评估器进 CI**（`RelevancyEvaluator` / `FactCheckingEvaluator`）：不做。
- **Telegram 第二通道、飞书富文本（卡片 / 按钮回调 / 群 @ 解析 / 消息更新）**：不做，飞书文字问答够用。
- **Redis**：语义缓存用 pgvector，Redis 不引回（推翻 ADR-0005 修订记录里的说法，见票 05）。
- **跨实例的事件去重**：ADR-0009 已定性为限制而非缺陷。
- **把 token 计量落库做成本报表**：指标进 Micrometer 就够了，落库是另一个产品需求。

## Further Notes

- **先读实现再写**：票 02 的 `RecursiveSummarizationCompactionStrategy` 与票 03 的
  `CrossSessionRecallTools` 都只有名字级的认知（spec 里核实到"有哪些类"，
  没核实到"怎么用"）。落地前 javap 一遍构造与 Builder，别照着名字猜——
  本仓库已经因为"看着像"写过一条永不命中的 ArchUnit 规则。
- **票 01 是其他五张的眼睛**：它先落地，后面每张票都用它的指标证明自己做成了。
- 真模型仍是回归基线（ADR-0011）；这几张票里唯一需要真模型的是票 05 的 embedding，
  其余都能用假模型 + 内存 meter 测。

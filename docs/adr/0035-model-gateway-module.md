# 模型层独立成 claw4j-model：能力按 OpenClaw 的部件拆开

---

status: accepted
date: 2026-11-22

---

四条需求：模型注册与路由、语义与 Prompt 缓存、限流熔断降级、模型切换。四条凑在一起最容易被写成一个大对象
——「模型网关」。它在 Java 侧看着整洁，在 OpenClaw 侧没有对应物：那边是 `src/agents/` 下一组各管一件事的
小文件（`model-fallback-candidates` / `-attempt` / `-cooldown` / `-rate-limit` / `-sticky` / `-runner`），
加 `packages/model-catalog-core` 一张目录表。这篇 ADR 记的是**拆法、每一样照哪个部件、哪几样明确不做**。

## Context

### 现在的模型层在哪

全部在 `claw4j-agent/model`（12 个类，约 1500 行）+ `agent/cache` + `observability/MeteredChatModel`：

| 现在有的 | 位置 |
| --- | --- |
| 供应商表（一行一家：端点、凭据句柄、模型名、两个超时、温度） | `ModelProperties` |
| 主备降级（主失败退备；流式只在首块前降） | `RoutingChatModel` |
| 哪类失败值得换一家（两个布尔） | `ModelFailures` |
| 每条 route 的调用数 / token / 耗时 | `MeteredChatModel` |
| 语义缓存（框架 `SemanticCacheAdvisor` + Redis Stack） | `CacheAutoConfiguration` |

两处结构性问题：`ChatModel` 是 `@Primary` 单例，「按用途选模型」没有入口；`context-window` 是模型元数据，
却挂在 `claw4j.advisor.session` 下，降级到备用模型后压缩阈值仍是主模型的。

### 查过的东西（按 `AGENTS.md`「先找现成的」顺序）

1. **本项目已引的库**：`spring-ai-deepseek`、`spring-ai-openai`、`spring-ai-retry`、`spring-ai-redis-semantic-cache`。
2. **Spring AI / `spring-ai-community`**：**没有**模型路由或降级模块。`org.springframework.ai` 下
   `spring-ai-model-router` / `-model-routing` / `-failover` / `-model-fallback` 四个坐标在 Maven Central
   均 404；`spring-ai-community` 组织下与模型相关的只有 decision-extensions / replicate / moonshot / mcp-agent；
   `spring-ai-model-2.0.2.jar` 的 `chat/model/` 只有 `ChatModel`、`StreamingChatModel`、`ChatResponse`。
3. **OpenClaw**：`docs/concepts/model-failover.md`、`docs/concepts/models.md`、`docs/concepts/retry.md`、
   `docs/gateway/config-agents/models.md`、`docs/concepts/model-providers/custom-providers.md`、
   `src/agents/model-fallback-*.ts`、`src/agents/failover/*`、`src/agents/auth-profiles/usage-failure-state.ts`、
   `packages/gateway-protocol/src/failover-reasons.ts`、`packages/model-catalog-core/src/model-catalog-types.ts`。
4. **重试不引库**：Spring Framework 7 自带 `org.springframework.core.retry.RetryPolicy`，`javap` 核过
   `maxRetries / delay / jitter / multiplier / maxDelay / timeout / includes / excludes / predicate` 齐全，
   OpenClaw 那三组数能一对一写出来。**熔断不引 Resilience4j**：它不是通用熔断器，是「凭据冷却 + 探测」
   与「准入闸」两件具体东西，套 `CircuitBreaker` 还得再造一层状态喂它。

## 一、对照表

| # | OpenClaw 的机制（位置） | 我们现在 | 差在哪 | 改哪边 |
| --- | --- | --- | --- | --- |
| 1 | 目录行是**模型**，带 `contextWindow` / `maxTokens` / `cost` / `compat`（`model-catalog-types.ts:258-292`） | 一行一家只有端点、模型名、超时、温度 | 没有模型级元数据 | 配置加 `models[]` |
| 2 | provider 是 **id 键的 map**，字段 `baseUrl` / `api` / `auth` / `models[]`（`custom-providers.md:36-52`） | list + `name` 字段 | 形状不同，重名靠校验 | 改 id 键 map（修订 ADR-0031） |
| 3 | 选择顺序 `primary → fallbacks → auth 轮换`（`models.md`「Selection order」） | `primary` + 表顺序即备选 | 备选链是隐式的 | 显式候选链 |
| 4 | 失败原因是闭合 **17 项**（`failover-reasons.ts:1-19`） | 两个布尔 | 没有「为什么失败」 | 词表 + 分类器 |
| 5 | reason → 动作有两张现成集合：瞬时探测槽（`rate_limit`/`overloaded`/`unknown`/`empty_response`/`no_error_details`/`unclassified`/`timeout`）与保留探测预算（`model_not_found`/`route_unavailable`/`format`/`auth`/`auth_permanent`/`session_expired`/`tls_certificate`）（`failover-policy.ts:12-45`） | 无 | 无 | 照抄两张集合 |
| 6 | `format` **不重试**（`failover/retry-evidence.ts:190-197`） | 同一家再试一次 | 动作相反 | 删同家重试，改由框架回落 |
| 7 | 冷却：限流**只用供应商给的时长**，没给就不冷却；其它失败 1 次 30s / 2 次 1min / 3+ 次 5min；`billing` base 10min 封顶 24h；`auth_permanent` base 10min 封顶 60min；计数窗口 24h（`model-failover.md:371,415-421`、`auth-profiles/usage-failure-state.ts:21-45`） | 无 | 无冷却 | 四类冷却 |
| 8 | 冷却作用域可只到 model（`cooldownModel`），billing/停用锁整家（`model-failover.md:405-411`） | 无 | 无作用域 | 键 = `provider` 或 `provider/model` |
| 9 | 探测：`MIN_PROBE_INTERVAL_MS = 30_000`、`PROBE_MARGIN_MS = 120_000`、键上限 256，只探 primary，三态 `skip / attempt / suspend`（`model-fallback-cooldown.ts:9-70`） | 无 | 无探测 | 照三个数 |
| 10 | 重试：限流 16 次总尝试 / 其它瞬时 8 次·90s 窗口 / 1s 起跳 ×2 / 封顶 60s·30s / TPM 2 次·15s 窗口（`failover-retry-controller.ts:34-41,74`） | 各家 SDK 自己重试 | 预算不在我们手里 | Spring `RetryPolicy` 写这三组数 |
| 11 | 准入闸：可用候选优先、饱和的排最后并按 `retryAt` 升序；状态在内存、最后一次重试底线后 1 分钟过期（`model-fallback-rate-limit.ts:40-63`、`model-failover.md:45`） | 无 | 无闸 | 按路由键的小状态（过期时长文档级） |
| 12 | 候选链：`requested → configured-fallback（按序去重）→ configured-primary（末尾兜底）`；fallbacks **不过** allow（`model-fallback-candidates.ts:295-409`） | 主 + 表顺序 | 三段结构不同 | 照抄三段 |
| 13 | 上下文溢出后按容量重排**失败位置之后**的候选，稳定分区不丢候选；需求下界取错误里的实际 token 或「失败模型容量 + 1」（`model-fallback-candidates.ts:58-96`） | 无 | 无 | 照抄，不引入估算器 |
| 14 | 白名单 `modelPolicy.allow`：精确 ref + 尾部 `*` 三形态（`provider/*`、`provider/namespace/*`、`provider/family-*`），不配或 `[]` 放行；fallbacks 不受限（`config/model-policy-ref.ts:29-49`、`config-agents/models.md:128`） | 无 | 无 | `claw4j.model.policy.allow` |
| 15 | 角色：primary / `utilityModel` / `decisionModel`；utility 四态解析 `explicit / disabled / auto / 继承`（`utility-model-setting.ts:12-35`） | 只有 `@Primary` | 无角色 | `main` / `utility` 两个角色 |
| 16 | Prompt 缓存：目录 `compat` 标记 + `params` 透传（`cacheRetention` / `promptCacheTtl`）+ 计量区分 cache-read / cache-write（`model-calls-and-metrics.md:43,91`） | 只记 prompt / completion | 无 cache 计量 | 只做透传与计量 |
| 17 | 语义缓存 | 框架 `SemanticCacheAdvisor` | **它没有对应物** | 不动，留在 `claw4j-agent/cache` |

## Decision

### 一、新增 `claw4j-model`，模型层整体从 agent 下沉

依赖只有 `claw4j-core`。真值解析这一件单独说：原计划是把 `SpringSecretResolver` 下沉到 core，
**做不到**——core 被 enforcer 与 ArchUnit 钉成"零框架"，而它要用 `Environment`。落地改成
core 里放一个不引 Spring 的 `PropertySecretResolver`（吃一个 `属性名 → 值` 的函数），
调用方递 `environment::getProperty` 进去；`SpringSecretResolver` 随之删除。

包按部件切，不按「服务」切：

```
catalog/   ModelCatalogProperties（providers → models）、ModelRef、ContextWindow、Cost、Compat
routing/   ModelRole、CandidateChain
failover/  FailoverReason、FailoverClassifier、CooldownStore、ProbeThrottle、FallbackRunner
admission/ RouteGate
usage/     UsageCost（只记 token，不算钱）、CacheUsage
config/    供应商装配、ModelAutoConfiguration
```

`claw4j-agent/cache`（语义缓存）**不搬**：它要用 `EmbeddingModel`，搬进模型层会让模型层反过来依赖缓存实现，
还凭空多一套 Redis/Jedis 依赖。

### 二、配置形状照 OpenClaw，接入层按 Spring AI

provider 从 list 改成 **id 键的 map**；模型行加 `context-window` / `context-tokens` / `max-tokens` /
`cost{cache-read, cache-write,...}` / `compat`；`auth: "none"` 允许无凭据端点（显式声明，不做自动探测）。

```yaml
claw4j:
  model:
    roles:
      main:    { primary: deepseek/deepseek-chat, fallbacks: [qwen/qwen-plus] }
      utility: { primary: qwen/qwen-plus }        # "" = 关闭；不配 = 继承 main
    providers:
      deepseek:
        base-url: https://api.deepseek.com
        api: deepseek                             # 见下：协议名不是对齐项
        api-keys: [secret:deepseek_api_key]
        models:
          - { id: deepseek-chat, context-window: 65536, context-tokens: 65536, max-tokens: 8192 }
      qwen:
        base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
        api: openai-completions
        api-keys: [secret:qwen_api_key]
        models: [{ id: qwen-plus, context-window: 131072, context-tokens: 131072 }]
    policy:
      allow: []                                   # 空 = 放行；尾部 * 三形态
```

**`api` 的取值不对齐 OpenClaw。** 它那 12 种协议（`packages/llm-core/src/model-data.ts`：
`openai-completions` / `openai-responses` / `anthropic-messages` / `google-*` / `ollama` …）是 TS 生态的；
连它自己的 DeepSeek 也写 `api: "openai-completions"`（`extensions/deepseek/openclaw.plugin.json:31-34`）。
我们保留 `spring-ai-deepseek` 原生适配器（`reasoning_content` 与 options 归它管），把 `api` 当成
「选哪个 Spring AI 适配器」的键。**这是不参与对齐，不是偏差**：接入层的实现语言不同。

**`auth: none` 有一处真偏差（落地时发现）。** OpenClaw 的 `auth: "none"` 是"不发 `Authorization`"；
Spring AI 的 OpenAI 适配器走 `openai-java` 的 `ClientOptions`，`build()` 要求"至少一个凭据来源"，
而"自己实现认证器"的入口是 `@JvmSynthetic`（Java 侧看不见）。所以我们的 `auth: none` 实际发的是
空值 `Authorization: Bearer`：不校验头部的端点（Ollama / vLLM / 内网网关）照常工作，
严格语义要等适配器给口子。

### 三、失败分类：词表照抄，规则库不搬

`FailoverReason` 17 项字面量照抄，不翻译不合并。分类器只做四层判据：Spring AI 的
`Transient/NonTransientAiException`、OpenAI 家族状态码、`JacksonException`（含工具参数解析）、JDK 网络异常；
判定优先级照它的顺序（provider hook → 上下文溢出 → billing 文本 → 周期额度 → 通用原因 → code → 状态码），
**但不搬它那套正则库**（`message-patterns` / `provider-patterns` / `context-overflow-tables` +
五份 `failover-classification.*.cases.ts`）：那是给几十家 provider 的文案准备的，我们只有两个 SDK。

`format` 的处理与它一致：**路由层不重试**。工具参数解析失败在框架层就已经被处理——
`MethodToolCallback` 把解析异常包成 `ToolExecutionException`，`DefaultToolCallingManager` 交给
`ToolExecutionExceptionProcessor`（默认 `alwaysThrow=false`）转成一段文本**回灌给模型**，
模型在同一轮自己重发工具调用。这条现在没吃到，是因为若干自定义 `ToolCallback`
（`ScheduleTools` / `GoalTools` / `WebFetchGuard` / `SubagentDelegation` …）自己用 Jackson 解析入参、
把原生异常抛到了路由层。

### 四、候选链与上下文重排

候选链 `requested → configured-fallback（按序、去重）→ configured-primary（末尾兜底）`；
fallbacks 不受 `policy.allow` 过滤（它的注释原话：*explicit user intent*）。

上下文溢出时按容量重排**失败位置之后**的候选：稳定的「够得着在前」分区，**不丢候选**；
需求下界取 `extractObservedOverflowTokenCount(错误文本)`，取不到就用「失败模型容量 + 1」。
不引入 token 估算器——它也没有。

### 五、冷却、探测、重试、准入闸

| 项 | 取值 | 出处 |
| --- | --- | --- |
| 限流冷却 | 只用供应商给的时长（`retry-after-ms` / `Retry-After` / 错误文本）；没给就不冷却 | `model-failover.md:371` |
| 其它失败 | 30s → 1min → 5min 封顶；计数窗口 24h | `usage-failure-state.ts:21-45` |
| `billing` / `auth_permanent` | base 10min；封顶 24h / 60min | 同上 |
| 冷却作用域 | `provider` 或 `provider/model`；billing/停用锁整家 | `model-failover.md:405-411` |
| 探测 | 30s 一次；有候选链时距到期 < 2min 才探；只探 primary；键上限 256 | `model-fallback-cooldown.ts:9-11` |
| 重试 | 限流 16 次总尝试；瞬时 8 次 / 90s；1s 起 ×2；封顶 60s / 30s；TPM 2 次 / 15s；成功一次即重置 | `failover-retry-controller.ts:34-41,74` |
| 准入闸 | 可用优先、饱和按 `retryAt` 排最后；内存态，最后一次重试底线后 1 分钟过期（**文档级**） | `model-fallback-rate-limit.ts:40-63`、`model-failover.md:45` |

冷却与闸都是**进程内**状态，重启即清空。OpenClaw 的凭据冷却落盘（`usageStats`），我们连那个也不落：
单进程部署里多一张表只换来「重启后少撞一次」。

### 六、白名单

`claw4j.model.policy.allow`：精确 ref + 尾部 `*` 三形态；不配或 `[]` 放行；fallbacks 不受它限制。
一处**与它不同**：它那边「自动选择被排除」会退到目录里第一个被允许的模型并打 WARN；
我们的 primary / roles 都是写死的显式选择，被排除就**启动失败并列出允许集**——没有「自动选」这个场景。

### 七、角色：`main` / `utility`

只有两个角色，每个 = 一条独立候选链，共享冷却、闸、预算。`utility` 四态解析照抄：
`explicit`（配了 ref）、`disabled`（配 `""`）、`auto`（没配 primary，推导主供应商的小模型）、
`继承`（完全没配 → 用 main 的链）。**本模块只提供入口，不接任何调用方**：哪个下游切到 utility
是下游各自的事。

### 八、Jackson 3 全量迁移

全仓 13 个文件（agent 9、rag 3、channel 1）从 Jackson 2 迁到 Jackson 3，删 `claw4j-rag/pom.xml` 里
显式的 `jackson-databind`。映射：`com.fasterxml.jackson.databind.*` → `tools.jackson.databind.*`；
`JsonProcessingException` / `JsonParseException` → `tools.jackson.core.JacksonException`（**unchecked**）；
`com.fasterxml.jackson.annotation.*` **不动**（`jackson-databind-3.1.7.pom`：「3.x retains dep to annotations 2.x」）；
`java.time` 内置，不用注册 `JavaTimeModule`。

失败分类落在 `FailoverClassifier`（`ModelFailures` 已删）。`isUnparseableContent` **仍然认两个包名**，但理由换了一个：`openai-java-core`（`spring-ai-openai`
带进来）自己就用 Jackson 2 解析 HTTP 响应体，解析失败时把 `JsonProcessingException` 包进
`OpenAIInvalidDataException` 抛出来。所以「classpath 上不再有 Jackson 2」做不到，能做到的是
**本仓的代码不再 import 它的 databind**（由一条扫描用例守着）。缩成一个包名会让这类失败落到
「认不出来 → 不重试」上，是静默的行为变化。同时把自定义 `ToolCallback` 的解析失败
包成 `ToolExecutionException`，让第三节那条回落路径真正生效（票 02）。

### 九、明确不做

| 不做 | 理由 | 代价 |
| --- | --- | --- |
| 令牌桶 / 主动配额 | 它的主动限流在 Gateway 边界（socket / 认证 / webhook / 控制面写），模型层只有饱和闸 | 没有「每分钟最多 N 次」的硬上限 |
| Prompt 缓存层 | 供应商自动前缀缓存，客户端加一层与它对冲 | 前缀不稳时命中率低，只能靠指标发现 |
| 语义缓存扩展 | 它没有对应物（`git ls-tree | grep -i cache` 只有 UI transcript 与构建缓存） | 现有实现是已知偏差，退场条件见文末 |
| 计费 | 目录里**保留** `cost` 三档字段（`input` / `output` / `cache-read`，含 `cache-write`），解析但不算账 | 有字段没行为，等有账单需求再接 |
| operator 角色级 `modelPolicy`（`{sourceAgent, deny}`） | 我们没有 operator 概念 | 多租户时另开票 |
| per-agent 模型覆盖（`agents.entries.*.model`） | 人设目录还没有模型字段 | 行业微调模型要按 agent 切时另开票 |
| 别名表、多模态 fallback、候选链缓存、`requestSizeCeiling` | 用不到 / 纯性能 / 已简化为 `reason == context_overflow` | 需要时按它的形状补 |

## Consequences

- 「谁拿到哪个模型」从 `@Primary` 单例变成按角色取；本模块只提供入口。
- 配置形状变化：`providers[].model`（单值）→ `providers.<id>.models[].id`。这是对 ADR-0031 的修订，
  迁移脚本不写——本项目未发布。
- 降级路径多了两层状态（冷却、闸），都是进程内、重启即清空。
- 流式「首块之后不换模型」这条我们比它严：它靠 transcript 续跑，我们重发会重复输出整条回答。
- 工具参数坏 JSON 的兜底从「整轮重生成」变成「框架回灌 + 模型重发工具调用」，
  代价是模型不看错误硬重发时会撞 `ToolCallBudget` 的调用上限——那正是熔断该干的活。

## 退场条件

- 上游 Spring AI 出现官方 failover / routing 模块 → 切过去，`routing/` 与 `failover/` 整体删除。
- 需要对外提供 API 或多租户配额 → 主动限流移到通道层，`admission/` 只保留饱和闸。
- 出现账单需求 → 把 `cost` 字段接上 `usage/`，按它的三档计价口径算。
- OpenClaw 出现语义缓存或 Prompt 缓存组件 → 按它的形状替换现有实现。

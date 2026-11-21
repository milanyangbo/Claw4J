# 票 12：角色入口 main / utility，以及缓存命中计量

状态：**完成**（2026-11-22）。裁决：`docs/adr/0035-model-gateway-module.md` 第七节、对照表第 16 行。

## 一、目标

两件都在"入口与观测"这一层，不改任何下游调用方：

1. 给出「按角色取模型」的入口，让 `utility` 不再是一个无处安放的概念；
2. 把供应商的**缓存命中数**记出来——不自建缓存层，只让命中率可见。

## 二、范围

| # | 改什么 | 要点 |
| --- | --- | --- |
| 1 | `ModelRole`：`main` / `utility` | 只有两个；行业微调模型就是"main 候选里的一行" |
| 2 | `ModelRouter.forRole(role)` | 每个角色一条独立候选链（票 10），共享冷却、闸、预算 |
| 3 | `@Primary ChatModel` = `forRole(main)` | 现有注入点一行不改 |
| 4 | utility 四态解析 | `explicit`（配了 ref）/ `disabled`（配 `""`）/ `auto`（没配 primary，用主供应商的轻量模型）/ `继承`（完全没配 → main 的链） |
| 5 | 指标带 `role` 标签 | 否则看不出这次是哪条链答的 |
| 6 | `MeteredChatModel` 记 `cache-read` / `cache-write` 两类 token | 指标名不变，`kind` 多两个取值；拿不到就不记（不估算、不写 0） |
| 7 | 取数分两条 | 优先 `Usage.getCacheReadInputTokens()`（OpenAI 适配器已填）；为 null 再读 `getNativeUsage()`——DeepSeek 适配器**不填**这两个字段，值在 `DeepSeekApi.Usage.promptTokensDetails().cachedTokens()` |
| 8 | 不新增缓存组件 | 不加 TTL 表、不加清理任务；语义缓存（`claw4j-agent/cache`）不动 |

## 三、对照（OpenClaw）

| 它的形态 | 位置 |
| --- | --- |
| 角色 primary / `utilityModel` / `decisionModel`；utility 未配时继承默认链 | `docs/concepts/models.md`「Selection order」下文 |
| utility 四态解析：`explicit` / `disabled`（空串）/ `auto` / 继承 | `src/agents/utility-model-setting.ts:12-35` |
| 缓存计量区分 cache-read / cache-creation；缺失时不推算 | `docs/gateway/opentelemetry/model-calls-and-metrics.md:43,91` |
| 缓存能力是目录 `compat` 标记 + `params` 透传，不是组件 | `packages/model-catalog-core/src/model-catalog-types.ts:80-90`、`config-agents/models.md:123,132` |

## TDD 用例清单

| 判什么行为 | 用例名 | 先红的样子 |
| --- | --- | --- |
| utility 配了就用自己那条链 | `ModelRoleRoutingTest#utilityUsesItsOwnChain` | 仍走 main |
| utility 配 `""` 视为关闭，不回落 main | `ModelRoleRoutingTest#emptyUtilityDisablesRoute` | 静默继承 |
| utility 完全没配则继承 main | `ModelRoleRoutingTest#unsetUtilityInheritsMain` | 启动失败或返回 null |
| 默认注入的 `ChatModel` 仍是 main | `ModelRoleRoutingTest#primaryBeanIsMainRole` | 注入到 utility |
| 指标带 `role` 标签 | `ModelRoleRoutingTest#metricsCarryRoleTag` | 只有 route |
| DeepSeek 的命中数从 native usage 取到 | `CacheUsageTest#deepSeekCacheHitFromNativeUsage` | 恒为 null |
| OpenAI 兼容侧从 `getCacheReadInputTokens()` 取到 | `CacheUsageTest#openAiCompatibleCacheReadRecorded` | 忽略该字段 |
| 两边都没有就不记，不写 0 | `CacheUsageTest#missingCacheUsageRecordsNothing` | 记一条 0 |

## 缺口

- **本模块只给入口，不接消费方**：压缩、子代理、RAG 改写都不改；谁切过来由各自的票决定。
- `auto` 档的"主供应商轻量模型"先取目录里同家最便宜的一行；没有就退回继承，不做它那套小模型推导。
- `decision` 角色不接：本仓没有判型决策的消费方。

## 实施记录

- `ModelRole`（main / utility）+ `ModelRoles.forRole`；`@Primary ChatModel` = `forRole(main)`。
- utility 四态：explicit / disabled（空串，**不回落**）/ auto（主供应商里 input 单价最低的一行）/
  继承（完全没配）。关掉的角色返回一个"一用就抛"的实现，让"谁还在用它"当场暴露。
- 每条角色链独立排候选，但共享冷却、闸与探测（同一个 bean）。
- 指标：`claw4j.model.calls` 多一个 `role` 标签。
- 缓存计量：`claw4j.model.tokens` 的 `kind` 多 `cache-read` / `cache-write` 两个取值；
  OpenAI 兼容侧读 `getCacheReadInputTokens()`，DeepSeek 侧读 native usage 的
  `promptTokensDetails().cachedTokens()`；两边都没有就不记（不写 0）。
- 缺口（照票面）：**只给入口，不接消费方**——压缩、子代理、RAG 改写都没改；`decision` 角色不接。
- 端点进闸键时目前用供应商 id 兜底（目录不知道 base-url），等闸要细分端点时再从配置补。

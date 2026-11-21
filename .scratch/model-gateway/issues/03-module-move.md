# 票 03：建 claw4j-model，模型层整体搬下去（行为零变化）

状态：**待做**。裁决：`docs/adr/0035-model-gateway-module.md` 第一节。

## 一、目标

只做搬迁：`claw4j-agent/model` 的 12 个类与 `observability/MeteredChatModel` 迁到新模块 `claw4j-model`，
对外行为一条不改。搬完这一步，后面九张票才有地方落。

**`claw4j-agent/cache`（语义缓存）不搬**：它要用 `EmbeddingModel`，搬进模型层会让模型层反过来依赖缓存实现，
还凭空多一套 Redis/Jedis 依赖。本票只改它两处 import。

## 二、范围

| # | 改什么 | 要点 |
| --- | --- | --- |
| 1 | 新建 `claw4j-model`（依赖 `claw4j-core`） | 包按 `catalog/routing/failover/admission/usage/config` 建，本票只放 `config` 与 `usage` |
| 2 | `SpringSecretResolver` 从 `claw4j-capability/secret` 迁到 `claw4j-core/secret` | 58 行、只用 `Environment`；channel / capability / rag / agent 四处 import 一起改 |
| 3 | 搬 `ModelProperties` / `ModelConfiguration` / `RoutingModelConfiguration` / `RoutingChatModel` / `ModelFailures` / `ModelRoutingException` / 两个 Condition / `EmbeddingProperties` / `EmbeddingAutoConfiguration` / `BatchingEmbeddingModel` | 不改方法体，只改包名与 import |
| 4 | `MeteredChatModel` 搬进 `io.github.claw4j.model.usage` | 它包在每条 route 上，属模型层 |
| 5 | `AgentAutoConfiguration#chatClient` 注入新模块的 `ChatModel` | `@Primary` 仍是 `routingChatModel` |
| 6 | `claw4j-app` / `claw4j-eval` 的 `AutoConfiguration.imports` 跟着改 | 漏一条的表现是"启动后没有 ChatModel" |
| 7 | `claw4j-agent/cache` 只改 import | `EmbeddingAutoConfiguration` / `QwenModelCondition` 换包 |

## 三、对照（OpenClaw）

| 它的形态 | 我们搬完的样子 |
| --- | --- |
| 目录、降级、用量是三块独立包，不寄生在 agent 运行时里 | 模型层独立成模块，agent 只消费 `ChatModel` |

## TDD 用例清单

| 判什么行为 | 用例名 | 先红的样子 |
| --- | --- | --- |
| agent 模块不再拥有模型实现类 | `ModelModuleBoundaryTest#agentHasNoModelImplementation` | 留一个类在 `..agent.model..`，archunit 命中 |
| `claw4j-model` 只依赖 core | `ModelModuleBoundaryTest#modelDependsOnCoreOnly` | 新模块里 import `..capability..` |
| 搬完仍只有一个 `@Primary ChatModel`，主备顺序不变 | `ModelRouteOrderTest#primaryStillFirstThenConfiguredOrder` | 把 `routes.add` 的主备顺序对调 |
| 语义缓存仍在 agent，且能注入 `EmbeddingModel` | `SemanticCacheTest#cacheStillAssemblesFromModelModule` | 缓存上下文起不来 |
| 应用照旧起得来、照旧回话 | `ApplicationAssemblyTest#startsAndAnswersWithConfiguredProviders` | 删 imports 里那一行 |

## 缺口

- 本票不动配置键（仍是 list + 单值 model），形状变更在票 04 与 05。
- `RoutingChatModel` / `ModelFailures` 原样搬过去，它们的重写在票 06、08、10。

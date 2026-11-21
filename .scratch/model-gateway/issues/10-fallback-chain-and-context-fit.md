# 票 10：候选链、上下文适配重排，替掉 RoutingChatModel

状态：**待做**。裁决：`docs/adr/0035-model-gateway-module.md` 第四节。

## 一、目标

现在的 `RoutingChatModel` 是"一个列表 + 一层循环 + 内层重试"，不知道冷却、不知道失败原因、不知道哪个候选是
点名的。本票把它写成**三段候选链 + 逐候选尝试**，并在上下文溢出时按容量重排后面的候选。

## 二、范围

| # | 改什么 | 要点 |
| --- | --- | --- |
| 1 | 候选链三段：`requested → configured-fallback（按序、去重）→ configured-primary（末尾兜底）` | fallbacks 不受 `policy.allow` 过滤（显式意图） |
| 2 | 候选带 `origin`：`requested` / `configured-fallback` / `configured-primary` | 探测只探 `requested`；失败摘要按 origin 归类 |
| 3 | 逐候选执行前查冷却（票 07）与闸（票 09） | 跳过也要进尝试列表，带 reason |
| 4 | 上下文溢出时重排**失败位置之后**的候选 | 稳定的"够得着在前"分区，不丢候选 |
| 5 | 需求下界 | 先取错误文本里的实际 token 数，取不到用"失败模型 `context-tokens` + 1"；**不引入估算器** |
| 6 | 重算下一个可用候选后**索引直接跳过** | 已知不可用的中间候选不再逐个空转 |
| 7 | 耗尽时抛 `ModelFallbackExhaustedException` | 带每条 attempt 的 `provider/model/reason` + 最早冷却到期时间 |
| 8 | 流式只在一个字节都没吐出去时降级 | 保留现有严格规则（我们比它严：重发会重复输出） |
| 9 | 删 `RoutingChatModel` | 不留兼容壳 |

## 三、对照（OpenClaw）

| 它的形态 | 位置 |
| --- | --- |
| 候选链三段 + 去重 + `routeOrigin` | `src/agents/model-fallback-candidates.ts:295-409` |
| 上下文重排：仅 `reason === context_overflow`、只动失败之后、稳定分区、不丢候选 | 同上 `:58-96` |
| 需求下界 = 错误里的实际 token 或"失败模型容量 + 1" | 同上 `:82-88` |
| 索引跳转：`i = nextIndex - 1` | `src/agents/model-fallback-runner.ts:699-701` |
| 耗尽摘要带逐次尝试与最早冷却到期 | `docs/concepts/model-failover.md:149` |

## TDD 用例清单

| 判什么行为 | 用例名 | 先红的样子 |
| --- | --- | --- |
| 三段顺序正确，且 primary 末尾兜底不重复调用 | `FallbackChainTest#requestedThenFallbacksThenPrimary` | 只两段或 primary 出现两次 |
| 溢出后把容量够的候选排到前面 | `FallbackChainTest#contextFitReordersTail` | 按配置顺序硬试 |
| 重排不丢候选、不动失败位置之前的部分 | `FallbackChainTest#reorderKeepsAllCandidates` | 过滤掉容量不足的 |
| 取不到需求 token 时用失败模型容量 + 1 | `FallbackChainTest#requirementFallsBackToCapacityPlusOne` | 不重排或返回 null |
| 被冷却跳过的候选仍出现在尝试列表 | `FallbackChainTest#skippedCandidateIsRecorded` | 静默跳过 |
| 全员失败时摘要含每条 reason 与最早恢复时间 | `FallbackChainTest#exhaustionCarriesReasonsAndSoonestExpiry` | 只抛最后一个异常 |
| 流式吐过首块后不再换模型 | `FallbackChainTest#streamDoesNotSwitchAfterFirstChunk` | 客户端收到两遍内容 |

## 缺口

- 用户显式选择的"严格性"（选了别的模型就不降级）本仓还没有"用户选模型"入口，本轮只保留 `origin`。
- `requestSizeCeiling` 不做：简化成 `reason == context_overflow` 即触发。

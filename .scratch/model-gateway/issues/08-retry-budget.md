# 票 08：重试预算交给 Spring 的 RetryPolicy

状态：**待做**。裁决：`docs/adr/0035-model-gateway-module.md` 第五节。

## 一、目标

现在重试是散的：DeepSeek 用 `SHORT_RETRY_TEMPLATE`，通义 `maxRetries(0)`，路由层再叠"同一家最多两次"。
三处各一套数，没人知道一次抖动会打几次。本票把它收成一份预算。

## 二、范围

| # | 改什么 | 要点 |
| --- | --- | --- |
| 1 | 用 `org.springframework.core.retry.RetryPolicy` 构建预算 | 已核 API：`maxRetries / delay / jitter / multiplier / maxDelay / timeout / includes / excludes / predicate` |
| 2 | 两组数 | 限流 15 次重试；其它瞬时 8 次 / 90s 窗口；起跳 1s、×2、10% 抖动、封顶 60s（限流）/ 30s |
| 3 | TPM 那档：2 次 / 15s 窗口 | 有 replay-safe 候选时收紧 |
| 4 | `retry.provider.max-retry-delay` 默认 60s | 供应商给的 `Retry-After` 超过它就不再等，交回上层走冷却与推进 |
| 5 | 成功一次重置计数与退避 | 有用的模型工作不该消耗下一次中断的预算 |
| 6 | 供应商 bean 的 `retryTemplate` 统一指向这一份 | 删 `SHORT_RETRY_TEMPLATE` 与 `maxRetries(0)` 两处特例 |
| 7 | 删 `RoutingChatModel` 的内层"同家两次"循环 | 归一到预算，避免两层相乘 |

## 三、对照（OpenClaw）

| 它的形态 | 位置 |
| --- | --- |
| 限流 16 次总尝试；TPM 2 次 / 15s；瞬时 8 次 / 90s；成功即重置 | `src/agents/embedded-agent-runner/run/failover-retry-controller.ts:34-41,74`、`docs/concepts/retry.md` |
| 退避 1s 起、指数、封顶 60s / 30s | 同上 `:37-39` |
| `maxRetryDelayMs` 默认 60s；超了直接走 fallback | `docs/concepts/model-failover.md:37` |

## TDD 用例清单

| 判什么行为 | 用例名 | 先红的样子 |
| --- | --- | --- |
| 限流最多 16 次总尝试 | `RetryBudgetTest#rateLimitAllowsSixteenAttempts` | 无限重试 |
| 瞬时 8 次且只花在 90s 内 | `RetryBudgetTest#transientAllowsEightAttemptsInNinetySeconds` | 不设窗口 |
| 退避从 1s 起、翻倍、封顶 | `RetryBudgetTest#backoffStartsAtOneSecondAndDoubles` | 固定间隔 |
| 成功一次后预算回满 | `RetryBudgetTest#successResetsBudget` | 跨轮累计 |
| 长时间 `Retry-After` 超过上限立刻交回 | `RetryBudgetTest#retryAfterBeyondCapSurfaces` | 睡满供应商给的时长 |
| 两家供应商共用同一份预算配置 | `RetryBudgetTest#providersShareOneRetryPolicy` | 各拿一份不同 Template |

## 缺口

- "有 replay-safe 候选时限流只重试 2 次"依赖票 10 的候选链；本票先把 2 次 / 15s 这档做成可配置，票 10 接上。
- 流式请求不重试（吐过内容不能重来），与现状一致。

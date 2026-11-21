# 票 06：失败原因 17 项词表，两个布尔退场

状态：**待做**。裁决：`docs/adr/0035-model-gateway-module.md` 第三节。

## 一、目标

`ModelFailures` 现在只回答两个布尔。冷却、闸、指标都需要知道**为什么失败**。本票照抄 OpenClaw 的 17 项词表，
把「换不换家 / 同家要不要再试」改成「按 reason 查表」。

## 二、范围

| # | 改什么 | 要点 |
| --- | --- | --- |
| 1 | `FailoverReason` 17 项 | 字面量照抄：`auth` `auth_permanent` `format` `rate_limit` `overloaded` `billing` `server_error` `route_unavailable` `timeout` `tls_certificate` `context_overflow` `model_not_found` `session_expired` `empty_response` `no_error_details` `unclassified` `unknown` |
| 2 | `FailoverClassifier.classify(Throwable)` | 四层判据：Spring AI 的 `Transient/NonTransientAiException`、OpenAI 家族状态码、`JacksonException`、JDK 网络异常；沿 `getCause()` 走完整条链 |
| 3 | 判定优先级照它的顺序 | 上下文溢出 → `billing` 文本 → 周期额度 → 通用 → code → 状态码；**不搬它那套正则库** |
| 4 | reason → 动作两张表 | 瞬时探测槽：`rate_limit` `overloaded` `unknown` `empty_response` `no_error_details` `unclassified` `timeout`；保留探测预算：`model_not_found` `route_unavailable` `format` `auth` `auth_permanent` `session_expired` `tls_certificate` |
| 5 | `format` **不重试** | 与票 02 配套：删除同家重试后，这里只保证分类正确 |
| 6 | `MeteredChatModel` 失败指标加 `reason` 标签 | 冷却与闸的输入 |
| 7 | 删 `ModelFailures` | 不留兼容壳 |

## 三、对照（OpenClaw）

| 它的形态 | 位置 |
| --- | --- |
| 闭合 17 项，且做成协议 union schema 给生产方与消费方共用 | `packages/gateway-protocol/src/failover-reasons.ts:1-19`、`schema/failover-reason.ts:16-17` |
| 两张 reason 集合（瞬时槽 / 保留探测预算） | `src/agents/failover-policy.ts:12-45` |
| `format` 在 `status` 缺失或 5xx 时不重试 | `src/agents/failover/retry-evidence.ts:190-197` |
| `model_not_found` 类失败跳过其它凭据直接推进候选 | `docs/concepts/model-failover.md:16-20` |

## TDD 用例清单

| 判什么行为 | 用例名 | 先红的样子 |
| --- | --- | --- |
| 429 与 5xx 分得开 | `FailoverClassifierTest#rateLimitAndServerErrorAreDistinct` | 都落 `unclassified` |
| 401 是 `auth`，额度类 403 是 `billing` | `FailoverClassifierTest#authAndBillingAreDistinct` | 一刀切 |
| 工具参数坏 JSON 归 `format`（若真到这一层） | `FailoverClassifierTest#unparseableArgumentsAreFormat` | 落 `unknown` |
| 认不出来的一律 `unclassified`，不猜 | `FailoverClassifierTest#unknownFailureIsUnclassified` | 抛异常或返回 null |
| 17 项每一项都能被分类器产出或被显式标注为无产生方 | `FailoverClassifierTest#everyReasonHasProducerOrGap` | 有 `reason` 谁也产不出来且没记缺口 |
| 失败指标带 `reason` 标签 | `ObservabilityTest#failureCarriesFailoverReason` | 只有 `route` / `outcome` |

## 缺口

- `session_expired` / `context_overflow` 目前没有产生方（后者在票 10 接上）；词表先留着。
- `billing` 与 `auth` 的文本判据只做 DeepSeek/通义能见到的两三条，不搬正则库。

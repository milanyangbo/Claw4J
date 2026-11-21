# 票 09：准入闸——饱和的端点不排队，绕开

状态：**完成**（2026-11-22）。裁决：`docs/adr/0035-model-gateway-module.md` 第五节。

## 一、目标

限流之后重试会把同一批请求反复打向已经饱和的端点。OpenClaw 的做法不是"限自己发多少"（那是边界层的事），
而是记下**这个端点+账号+模型什么时候能再用**，新请求绕开它。

## 二、范围

| # | 改什么 | 要点 |
| --- | --- | --- |
| 1 | `RouteGate`：键 = `endpoint + account + model`，值 = 最早可用时间 | endpoint 用 `base-url`，account 先取 provider id（多钥匙在后续票细分） |
| 2 | 只有明确的配额/限流才开闸 | 普通 5xx 不开；5xx 是冷却的事 |
| 3 | 状态在内存，最后一次重试底线后 **1 分钟**过期 | 过期时长只有文档依据（`model-failover.md:45`），代码里没有常量可抄 |
| 4 | 排序语义：**可用候选优先，饱和的排最后并按 `retryAt` 升序** | 不是全局按时间排 |
| 5 | 全部饱和时选最早恢复的那个，等它 | 闸自己不发请求、不重试 |
| 6 | 成功流量不延长恢复节奏 | 只有新的失败底线才更新到期时间 |
| 7 | 闸不跨进程、不落盘 | 与它一致 |

## 三、对照（OpenClaw）

| 它的形态 | 位置 |
| --- | --- |
| 按 endpoint + account + model 共享的进程内闸，最后一次重试底线后 1 分钟过期 | `docs/concepts/model-failover.md:31-45` |
| 新的一轮绕开饱和模型；全饱和时最早恢复的进闸 | 同上 `:73-76` |
| 排序实现：可用优先，饱和按 `retryAt` 排最后 | `src/agents/model-fallback-rate-limit.ts:40-63` |

## TDD 用例清单

| 判什么行为 | 用例名 | 先红的样子 |
| --- | --- | --- |
| 某端点限流后下一次请求直接发给别的候选 | `RouteGateTest#saturatedRouteIsSkipped` | 仍先打饱和的 |
| 闸在最后一次底线后 1 分钟过期 | `RouteGateTest#gateExpiresAfterOneMinute` | 永不过期 |
| 全部饱和时选最早恢复的，不是第一个 | `RouteGateTest#allSaturatedPicksEarliestRetry` | 按配置顺序 |
| 普通 5xx 不开闸 | `RouteGateTest#serverErrorDoesNotOpenGate` | 一次 5xx 排除一分钟 |
| 成功流量不延长闸 | `RouteGateTest#successDoesNotExtendGate` | 每成功一次续期 |
| 闸自己不重试、不发请求 | `RouteGateTest#gateNeverRetriesByItself` | 闸内循环重发 |

## 缺口

- 闸的过期时长是**文档级依据**，本票按 1 分钟实现并在配置里留键，若将来指到它的实现常量再对齐。
- "交互式优先于后台、100–250ms 抖动放行"不做：需要调用方标注优先级，`ChatModel` 这一层没有入口。

## 实施记录

- `RouteGate`：键 = 端点 + 账号（供应商 id）+ 模型；只有 `rate_limit` / `billing` 开闸；
  记录过期 = 最早可用时刻 + 1 分钟（文档级依据，代码里没有常量可抄，已写进注释）。
- `orderByAvailability`：可用优先、饱和按恢复时刻升序，稳定排序；`soonestRecovery` 给耗尽摘要用。
- `recordSuccess` 刻意空实现：让"成功不续期"成为读得到的规则，而不是"忘了写"。
- 缺口：交互式优先 / 100–250ms 抖动放行不做（`ChatModel` 层没有优先级入口）。

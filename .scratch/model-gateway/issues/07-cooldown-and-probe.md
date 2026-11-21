# 票 07：冷却四类与探测

状态：**完成**（2026-11-22）。裁决：`docs/adr/0035-model-gateway-module.md` 第五节。

## 一、目标

主模型限流或鉴权失败后，现在每个请求都要先撞一次再退备。本票加两个状态：**冷却**（到什么时间之前别用这条路由）
与**探测**（冷却快到期时每 30 秒放一个请求过去）。

## 二、范围

| # | 改什么 | 要点 |
| --- | --- | --- |
| 1 | `CooldownStore`：键 = `provider` 或 `provider/model`，值 = `cooldownUntil` + 来源 + 失败计数 | 作用域照它的 `cooldownModel` 规则 |
| 2 | 限流冷却**只认供应商给的时长** | `retry-after-ms`、`Retry-After`（秒 / HTTP 日期）、错误文本里的 `try again in 6s`；**没给就不冷却** |
| 3 | 其它失败阶 | 1 次 30s、2 次 1min、3+ 次 5min 封顶；计数窗口 24h |
| 4 | `billing` / `auth_permanent` 单独退避 | base 10min；封顶 24h / 60min |
| 5 | 重复/并发限流**不加长**冷却 | 更晚的到期时间可延长，更早的不能缩短 |
| 6 | `ProbeThrottle`：同键 30s 最多一次；有候选链时距到期 < 2min 才探；只探 primary；键上限 256 | 照三个数 |
| 7 | 三态决策 `skip / attempt(markProbe) / suspend` | 返回三态而不是布尔，跳过要带得出 reason |
| 8 | 全在进程内，重启清空 | 我们连凭据冷却也不落盘（ADR 后果一节的取舍） |

## 三、对照（OpenClaw）

| 它的形态 | 位置 |
| --- | --- |
| 限流冷却只来自供应商时长；没给就下一轮仍可用 | `docs/concepts/model-failover.md:371` |
| 30s / 1min / 5min 封顶；`billing` base 10min 封顶 24h；`auth_permanent` base 10min 封顶 60min；窗口 24h | `src/agents/auth-profiles/usage-failure-state.ts:21-45` |
| 冷却可只锁 model；billing/停用锁整家 | `docs/concepts/model-failover.md:405-411` |
| 探测 30s、余量 2min、键上限 256 | `src/agents/model-fallback-cooldown.ts:9-11` |

## TDD 用例清单

| 判什么行为 | 用例名 | 先红的样子 |
| --- | --- | --- |
| 鉴权失败第一次冷却 30s，第三次 5min 封顶 | `CooldownStoreTest#ladderGrowsAndCaps` | 线性增长或不冷却 |
| 限流带 `Retry-After: 2` 只冷却 2s | `CooldownStoreTest#rateLimitUsesProviderRetryAfter` | 用固定 60s |
| 限流但没给时长 → 不冷却 | `CooldownStoreTest#rateLimitWithoutTimingDoesNotCoolDown` | 自造默认值 |
| 更早的到期时间不能缩短已有冷却 | `CooldownStoreTest#earlierDeadlineCannotShorten` | 被覆盖 |
| `billing` 冷却 10 分钟起，不是 30s | `CooldownStoreTest#billingUsesTenMinuteBase` | 走普通阶 |
| 模型级冷却不影响同家兄弟模型 | `CooldownStoreTest#modelScopedLeavesSiblingUsable` | 整家跳过 |
| 探测 30s 最多一次；距到期 2min 以上不探 | `ProbeThrottleTest#probesThrottledAndMarginRespected` | 每请求都探 |

## 缺口

- 多把钥匙的钥匙级冷却依赖票 04 的 `api-keys` 解析，本票先按 provider 与 provider/model 两档。
- 冷却不落盘：重启后要重新撞一次失败才知道，这是认下来的代价。

## 实施记录

- `CooldownStore`：普通失败 30s / 1min / 5min 封顶；限流只认供应商时长（没给不冷却）；
  `billing` 10min 起翻倍封顶 24h；`auth_permanent` 10min 起封顶 60min；计数窗口 24h；
  **只延长不缩短**（`merge` 取更晚的那个）。
- `ProbeThrottle`：同键 30s 一次；有候选链且距到期 > 2min 不探；键上限 256（LRU）。
- 两个都在 `ModelConfiguration` 里装成 bean，由 `ModelRouter`（票 10）消费。
- `Retry-After` 的解析放在 `ModelRouter`（"retry after 6" / "try again in 6s" 两种写法），
  冷却表只接受一个 `Duration` —— 谁解析、谁定时长分开，测试才好写。
- 冷却键：`provider/model`（模型级）；`billing` / 凭据级锁整家留给多钥匙那张票。

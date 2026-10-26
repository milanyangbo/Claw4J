# 03: 主备降级只发生在可重试失败上

**What to build:** 主模型失败时，只有"换一家可能好"的失败才退到备：
连接失败、超时、429、5xx 降级；401 / 403 / 400 / 422 与本地构造错误**不降级**，直接抛出并说清
是哪一家、什么错。现在是一个 `catch (RuntimeException)` 全接住，于是 key 失效会变成
"每家都失败一遍再把几个异常捆在一起抛"，一次确定的错误被拖成 N 次网络往返。

**Blocked by:** None (can start immediately)

**Status:** resolved

**Demo:** 把主模型的 key 换成一个失效的值，问一句话，立刻拿到"deepseek 返回 401，key 无效"
这类说明，而不是等两轮超时；备那一家一次都没被调用。

- [x] 主模型超时 / 连接失败 / 429 / 5xx → 换备，用户照常拿到回答
- [x] 主模型 401 / 403 / 400 / 422 → 不降级，抛出的信息点明是哪一家、什么错
- [x] 本地构造错误（如空消息）不触发降级
- [x] "哪些失败算可重试"只有一处判断，且被一条表驱动测试钉住（加一家供应商不用重新讨论一遍）
- [x] 流式路径保持"首块之前才降级"，首块之后不切换（现有语义不变，只换分类）
- [x] 变异：把分类改回 `catch (RuntimeException)` → 401 那条变红

**落点:** `RoutingChatModel` 只消费一个异常分类函数，自己不识别异常类型；测试加进 `ModelRoutingTest`。

**注意:** 分类散到各家适配器里就等于没有分类——加第三个供应商时会各写一套。

**不要做:** 同一家内部重试；熔断；按供应商维护错误码白名单表。

## 落点

- 新增 `ModelFailures.worthTryingAnother`：沿 `getCause()` 走完整条链，依次认
  Spring AI 的 `Transient` / `NonTransientAiException`、OpenAI 兼容家族的状态码
  （429 / 5xx 可重试）、`OpenAIRetryableException`、`JsonProcessingException`（不可重试）、
  JDK 网络异常。认不出的失败按不可重试处理，理由写在类注释里。
- `RoutingChatModel`：`call` 与 `streamFrom` 都只消费这一处判断。不可重试时抛
  `ModelRoutingException` 并点名"模型 X 调用失败，这类错误换一家也不会好"；
  `ModelRoutingException` 加了带 cause 的构造器，两种情形（全挂 / 首个就不可重试）共用，
  区别在 `getSuppressed()` 与 `getCause()`。
- 测试 `ModelRoutingTest` 8 → 10 条：新增"401 不降级且备一次不碰"与"可重试/不可重试判定表"
  （15 种失败，含被框架包了一层的情形）。原有几条换成真实异常类型
  （`UnexpectedStatusCodeException` 的 429/500/503、`ConnectException`）——之前用的是
  `IllegalStateException("429 too many requests")`，那种写法在新判据下恰好会被判成不可重试，
  也就是说它们此前"靠字符串骗过了分类"。
- 变异：把判断改回无条件降级 → 只红 `unauthorizedDoesNotFallBack` 一条。
- ADR-0006 的"降级条件刻意做得粗暴：任何异常都降级"已按实现更新（加 2026-10-26 修订段），
  `pitfalls.md` 新增第八节记这个坑。

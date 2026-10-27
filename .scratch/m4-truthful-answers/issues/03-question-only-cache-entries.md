# 03: 纯问答也会进语义缓存

**What to build:** 无。这条票记的是一个**决定不做**的结论，以及为什么。

**Status:** resolved（wontfix）

**问题**：2026-10-27 的试验里，"你好，你能做什么"进了 `claw4j_cache.semantic`（id=11），
跟着留一小时。它不是"查得到数的答案"，内容还会随版本变化。

## 为什么不处理

**1. 框架不是"不区分"，是根本没有这个概念。**

反编译 Spring AI 2.0.2 的 `SemanticCacheAdvisor.adviseCall`，写缓存只有这一句：

```java
ChatClientResponse resp = chain.nextCall(request);
if (resp.chatResponse() != null) {
    cache.set(query, resp.chatResponse(), ctx);
}
```

判据只有"模型返回了非空响应"。`SemanticCacheAdvisor.Builder` 也只有三个方法
（`cache` / `order` / `scheduler`），**没有"该不该缓存"的钩子**——
网上流传的 `timeToLive`、`similarityThreshold`、`isolateBySystemPrompt` 是
`pro.redisson` 那个第三方实现的 builder，不是 Spring AI 官方的，别照它写。

自己加一条 `if (这一轮调用过工具)`，就是在一个框架明确留白的地方塞私有语义：
下次框架加了官方钩子，我们这条就成了需要拆的旧代码。

**2. 收益只有"少存几行"。** 没有工具调用意味着没有外部状态参与，
缓存它在语义上没错，只是没用。而"没用"的代价是几行数据 + 一次向量检索。

**3. 真有困扰有更直接的旋钮**：调小 `claw4j.cache.time-to-live`（比如 PT10M）。
把"缓存多久"和"什么样的回答能缓存"拆成两个开关，比合成一条特例好调。

**如果将来要改**：改动点是 `CacheAdvisor.remember` 里加一个"这一轮至少调用过一个工具"的
前置判断。它需要和 `ToolCallLog` 一起测，否则失效姿势是静默的
（"什么都没缓存"或"什么都缓存"）。现在没有这个需求，不加。

## 需要说清的一处：`sideEffectFree` 与这条票不是同一件事

`CacheAdvisor.sideEffectFree` 拦的是**调用了非只读工具**的一轮不写缓存
（`draft_script` / `run_script` / `recall_session` 都不满足"全是 read"）。
它在线，且**不打算撤**：

- 它不是"为某个场景打的补丁"，是一条通用规则——"只读的轮次结果可以复用"。
  这条规则在任何业务场景下都成立，不会随问法变多而需要补枚举。
- 撤掉它会产生**功能错误**而不是多存几行：`run_script` 那一轮的回答（"已执行成功"）
  被写进缓存，同一权限的人一小时内再问同一句，会拿到"已执行成功"而没有执行。
- 它不在框架的判据里，但它**用的是框架的形状**：框架的 `SemanticCache.set()`
  本来就只接受 `(query, ChatResponse, ctx)`，"这一轮要不要 set"由调用方决定。
  类里这条判断就是那个决定的实现。

两者的区别：这条票说的是"**没有工具调用的轮次**该不该缓存"——答案是"该"（跟框架）；
`sideEffectFree` 说的是"**有副作用的轮次**该不该缓存"——答案是"不该"（我们的安全规则）。

## 同一类的第二笔

2026-10-27 的真链路里，`guest` 那轮被鉴权拒绝的回答
（"没有权限：角色 guest 不允许访问表 …"）也被写进了缓存（id=15，在 guest 自己的指纹下）。
如果有人当天给 guest 加了权限，一小时内他还会拿到那句"没有权限"。同样不处理——
自愈时间就是 TTL（1 小时），而"拒绝"被缓存本身不会泄露任何数据。

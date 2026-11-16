# 04: 重排从来没有生效过——真实响应里多一个 `object` 字段，解析就抛异常

**What to build:** `RerankDocumentPostProcessor` 解析真实响应时不再因为多出来的字段抛异常。
现在是「解析失败 → 退回原顺序」，所以它看起来一直正常，实际上**一次都没排过序**。

**Blocked by:** 无（票 01 让重排能装配起来，才看得见这条；两票可以一起发）。

**Type:** bug

**Status:** resolved

**Demo:** 起实例（`rag.enabled=true`，重排默认开着），问「东西买了能退吗」，日志里**没有**
`重排失败（UnrecognizedPropertyException）`；`RerankDocumentPostProcessor` 打 debug 时能看到
按返回下标重排后的顺序与原顺序不同。

## 现场（票 01 的实例日志原文）

```
WARN i.g.c.a.rag.RerankDocumentPostProcessor : 重排失败（UnrecognizedPropertyException），
这一轮按候选原顺序继续：Unrecognized field "object" (class
io.github.claw4j.agent.rag.RerankDocumentPostProcessor$Response), not marked as ignorable
(one known property: "results")
```

端点返回的 JSON 除了 `results` 还有顶层 `object`（OpenAI 兼容风格的 `{"object":"list",...}`），
而 `Response` 是只声明了 `results` 的 record，Jackson 2 默认 `FAIL_ON_UNKNOWN_PROPERTIES=true`
→ 抛异常 → `process()` 的 catch 把整轮降级成"原顺序返回"。

**为什么一直没被发现**：降级路径是刻意设计的（重排是排序优化，挂了结果必须还在），
于是失败只留一行 WARN，回答看起来完全正常、出处也有。票 01 之前更甚——重排根本装不上，
连这行 WARN 都不会出现。**没有任何用例断言"真的排了序"**：`Stub` 起的假端点返回的是自己
构造的干净 JSON，永远能解析成功。

## 要改的文件

| 文件 | 改什么 |
| --- | --- |
| `.../rag/RerankDocumentPostProcessor.java` | `Response` 加 `@JsonIgnoreProperties(ignoreUnknown = true)`（**不要**改成全局关掉 `FAIL_ON_UNKNOWN_PROPERTIES`：那会把这个类对真实契约的敏感度一起关掉，其它字段拼错就查不出来了） |
| 同上 | 类注释里"失败一律原样返回"那段补一句：**多出来的字段不算失败**，但缺 `results` 仍算 |
| `.../rag/KnowledgeRetrievalTest.java` | 现有 rerank 用例的 `Stub` 加一个"带 `object` 字段"的响应体，断言顺序真的被改过（不是原顺序） |

## 验收判据（用例清单）

| 判什么行为 | 用例 | 先红的样子 |
| --- | --- | --- |
| 响应多一个顶层字段仍能解析并按 `results` 重排 | `KnowledgeRetrievalTest` 里现值那条改成带 `object` 的响应体 | 去掉注解得红：顺序回到原样，且日志一行 WARN |
| 缺 `results` 仍按原顺序返回（原来那条语义不变） | 既有用例，不改 | —— |
| 4xx/5xx、超时仍按原顺序（语义不变） | 既有用例，不改 | —— |

## 顺带确认（不是本票要改的）

`rerankCandidates()` 默认 10、`topK` 5，这两个数没有参与本 bug。响应的 `results` 里若只有前 N 条，
`reorder()` 会把没被点到的按原顺序补在后面，条数不变——这条逻辑没动。

## 实现记录

- `Response` 与 `Result` **两个** record 都加 `@JsonIgnoreProperties(ignoreUnknown = true)`。
  只改 `Response` 不够：把用例改成真实响应形状之后，先红的是 `Result` ——
  真实返回的每条结果里还带 `document`（我们请求时给了 `return_documents=false`，它照样回一个 `null`）。
  两处都容忍"我们不关心的字段"，但缺 `results`、类型不对仍走失败路径。
- **先把用例改成真实形状再修**：`rerankReordersAndCapsInput` 的 Stub 响应体换成
  `{"object":"list","id":…,"model":…,"usage":{…},"results":[{"index":2,…,"document":null},…]}`，
  跑出来红在 `Unrecognized field "document"`（`Result`），修完绿。这就是票里缺的那条"断言真的排了序"。
- 加了一行 DEBUG（`process()` 里）：`重排完成：候选 N 条，服务返回 M 条，前三条 [ids] → [ids]`。
  理由写在注释里：不留痕时"跑过了"与"根本没跑"长得一模一样——本票就是这么漏掉的。
- 真实例验证：`重排完成：候选 2 条，服务返回 2 条，前三条 [7504cc21…, e1b31545…] → [7504cc21…, e1b31545…]`，
  没有 `重排失败` 的 WARN。`M` 非 0 说明响应真的被解析并用上了。

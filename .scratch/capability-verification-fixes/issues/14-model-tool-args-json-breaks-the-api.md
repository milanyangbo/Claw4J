# 14: 模型把工具参数写成坏 JSON，接口直接回 500 且没有一句话

**What to build:** 这一轮模型输出不可用时，要么重试一次把它救回来，要么回一句人能读的话；
不能把 `Internal Server Error` + 空 body 甩给调用方。

**Blocked by:** 无。

**Type:** bug（错误分类 + 错误面）

**Status:** resolved

**Demo:** 反复发 `README-capability-tests.md` §2 那条 `demo_echo` 命令（五到十次），
不再出现 `{"status":500,"error":"Internal Server Error"}`；偶发失败时回答里有可读说明。

## 现场

```
WARN  RoutingChatModel : 模型 deepseek 调用失败，这类错误换一家也不会好，直接报出：
      java.lang.IllegalStateException: Conversion from JSON to java.util.Map failed
...
tools.jackson.core.exc.StreamReadException: Unexpected character ('专' (code 19987 / 0x4e13)):
      was expecting comma to separate Object entries
```

实测五次里出一次（`http=500`），其余四次正常回 `echo: 跨进程往返不丢字-42`。

## 根因（两层）

1. **分类判据认识错了 Jackson 的包名。** `ModelFailures` 用的是
   `com.fasterxml.jackson.core.JsonProcessingException`（Jackson **2**），而这里抛的是
   `tools.jackson.core.exc.StreamReadException`（Jackson **3**，Spring Boot 4 的默认）。
   于是那条 `instanceof` 分支从来没命中过，靠的是函数末尾"认不出来就不重试"的默认值——
   **结果对，理由错**，而且下次想按类别区分时无从下手。
2. **"解析不了"的推理本身不成立。** javadoc 写的是"同一份解析不了的响应换一家也解析不了"。
   对**响应体**成立，对**模型生成的工具参数**不成立：那些字节每次重新生成都不同，
   同一次重试就有很大概率是合法 JSON。所以这类失败是**可重试的**，而且不该只寄望于"换一家"。

第 2 层导致的是**: 单供应商部署里没有任何人会重试**，一次抖动就是一个 500。

## 修法

- `ModelFailures` 认识 Jackson 3（`tools.jackson.core.JacksonException`），并把
  "解析模型生成内容失败"标成**可重试**；javadoc 同时改掉那条错误推理。
- `RoutingChatModel`：对**解析类**失败**同一家再试一次**（只一次）。限流 / 网络 / 5xx 不重试
  同一家——那些 SDK 自己会重试，且 429 立刻重打只会更糟。
- app 层兜底：`ModelRoutingException` → **503** + 一句可读的话（含 `runId`），
  不再是 500。app 是唯一同时认识 channel 与 agent 的地方，映射放这里不破坏依赖方向。

## 验收判据

| 判什么 | 判据 |
| --- | --- |
| Jackson 3 的解析失败被认成可重试 | `ModelFailures` 的分类表里加一行 `StreamReadException` → `true` |
| 只重试一次、且只对解析类 | 用假模型两次都抛解析失败 → 总调用次数 2（不是 3、不是 1） |
| 限流不被同家重试 | 假模型抛 `TransientAiException` → 同一次 `call` 里不重复打同一家 |
| 兜底不是 500 | 单供应商 + 解析失败两次 → HTTP 503，body 里有 `runId` 与"再发一次" |

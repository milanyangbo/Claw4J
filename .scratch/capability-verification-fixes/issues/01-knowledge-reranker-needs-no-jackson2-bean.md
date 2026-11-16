# 01: 知识库按默认配置起不来——`knowledgeReranker` 注入了一个不存在的 bean

**What to build:** `RagAutoConfiguration#knowledgeReranker` 不再向容器要 Jackson 2 的
`ObjectMapper`。开着知识库（`claw4j.rag.enabled=true`）且重排保持默认（开）时，应用必须能启动；
重排器照旧拿到一个可用的 JSON 编解码器。

**Blocked by:** 无。

**Type:** bug

**Status:** resolved

**Demo:** 真 key + 真 Postgres，`--claw4j.rag.enabled=true --claw4j.rag.directory=examples/knowledge`
（**不加** `--claw4j.rag.rerank.enabled=false`）起实例，进程起来、`/actuator/health` 是 UP、
启动日志里有 `知识库重排已启用：…`；问一句「东西买了能退吗」，答里带 `[1]` 出处。

## 现场

```
Parameter 3 of method knowledgeReranker in io.github.claw4j.agent.rag.RagAutoConfiguration
required a bean of type 'com.fasterxml.jackson.databind.ObjectMapper' that could not be found.
```

`Spring Boot 4` 只为 Jackson 3（`tools.jackson.databind.json.JsonMapper`）提供 bean，Jackson 2 的
`ObjectMapper` 没有。而 `claw4j-agent` 的 classpath 上 Jackson 2 **是在的**（2.21.7，随
spring-ai 传递进来），所以：

- 编译过、测试过；
- 另外四个用 Jackson 2 的类（`SessionGoalJson`、`CallFingerprint`、`PrincipalSnapshot`、
  `JdbcInboundStore`）都自己 `new ObjectMapper()`，从来没向容器要过，所以从没暴露这个问题；
- **只有** `knowledgeReranker` 把它写成了 bean 方法参数 → 它是全仓唯一一处"向容器要 Jackson 2"。

## 为什么门禁是绿的

`KnowledgeRetrievalTest#advisorIsRegisteredAfterMemory`（该文件 440-455 行）是唯一一条会跑到
`RagAutoConfiguration` 完整装配的用例，而它写死了：

```java
.withPropertyValues("claw4j.rag.enabled=true", "claw4j.rag.directory=" + docs,
        "claw4j.rag.rerank.enabled=false")   // ← 正好绕开出问题的那颗 bean
```

重排的其它用例走 `Stub#reranker()`，自己 `new ObjectMapper()` 塞进构造器，也绕过装配。
**没有任何一条用例跑过"重排开着的装配"**——所以这个 bug 能活到今天。

## 要改的文件

| 文件 | 改什么 |
| --- | --- |
| `claw4j-agent/.../rag/RagAutoConfiguration.java` | 去掉 `ObjectMapper json` 形参，在方法里自己造；一行注释写明为什么不是注入（Boot 4 只给 Jackson 3） |
| `claw4j-agent/.../rag/KnowledgeRetrievalTest.java` | 加一条**装配级**用例：`rerank.enabled` 不写（走默认 true）+ 目录 → 上下文不失败、重排器在容器里。该文件已满 20 条，需先合并一条 |
| `docs/reviews/2026-11-16-capabilities-verification.md` | 在结论处标注已修（一行，不重写报告） |

## 不做什么

- **不**给 Jackson 2 的 `ObjectMapper` 注册一个 bean。那等于把框架已经不再提供的东西在我们这边
  复活，下一个读代码的人会以为它是框架给的；而且它和 Jackson 3 的 `JsonMapper` 在容器里并存，
  「注入哪个」变成一个靠类型猜的问题。整个模块的既有写法是各自 `new`，跟着它走。
- **不**把 `RerankDocumentPostProcessor` 迁到 Jackson 3。它单独迁过去就成了本模块第三种 JSON 约定；
  要迁就整模块一起迁，那是另一张票，与本 bug 无关。

## 验收判据（用例清单）

| 判什么行为 | 用例 | 先红的样子 |
| --- | --- | --- |
| 重排开着时装配不失败，且容器里有重排器 | `KnowledgeRetrievalTest#rerankerIsAssembledWhenItIsLeftOn` | 把 `ObjectMapper` 形参加回去，只有这一条红（`hasNotFailed` 失败，报错点名 `ObjectMapper`） |
| 重排关掉时容器里没有重排器（原来那条语义不变） | `KnowledgeRetrievalTest#advisorIsRegisteredAfterMemory`（既有，可顺手加一句断言） | —— |
| 装配失败时点名的是缺目录，不是缺 bean | `KnowledgeRetrievalTest#missingDirectoryFailsAtStartup`（既有，不改） | —— |

## 实现记录

- `RagAutoConfiguration#knowledgeReranker` 去掉 `ObjectMapper` 形参，方法里 `new ObjectMapper()`；
  类 javadoc 写明为什么"不向容器要"（Boot 4 只提供 Jackson 3，本模块另外四处本来就各自 `new`）。
- 用例没有新增文件/方法（该文件已满 20 条），改的是既有那条：`advisorIsRegisteredAfterMemory`
  **删掉** `claw4j.rag.rerank.enabled=false`，让装配走默认（重排开），并加 `hasSingleBean(RerankDocumentPostProcessor)`。
  名字保留（它验的还是 order），DisplayName 改成"重排保持默认（开）也能装配，且 advisor 排在记忆之后"。
- **变异验证**：把形参加回 `ObjectMapper injected` → 只有这一条红，报错与生产环境逐字一致
  （`No qualifying bean of type 'com.fasterxml.jackson.databind.ObjectMapper' available`）。
- 真实例：`--claw4j.rag.enabled=true --claw4j.rag.directory=examples/knowledge`，6 秒 UP，日志
  `知识库重排已启用：…（模型 qwen3-rerank，候选 10 条，超时 PT2S）`、`知识库已启用：… 混合检索 开、重排 开`，
  问「东西买了能退吗」答里带 `[1]` 且内容对得上（华东 7 天 / 华南 5 天 / 从签收日算）。
- **顺带发现一个新 bug**：装配起来之后重排的调用一直失败（真实响应多一个顶层 `object` 字段，
  `UnrecognizedPropertyException` → 退回原顺序）。这是票 04。
- 踩坑记一笔：`cp` 恢复源码后 mtime 比 class 旧，Maven 跳过重编，jar 里跑的还是变异版——
  改了源码又"验过了"之前，先 `touch` 或 `clean`，否则验的是上一次的产物。

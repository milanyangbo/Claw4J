# 票 12：知识库独立成 `claw4j-rag` 模块

状态：已完成（2026-11-20）

## 一、为什么

知识库是**可独立部署的能力**，但它一直住在 `claw4j-agent` 里，带来两个后果：

1. **agent 被迫带着一套存储实现**：`PgVectorStore`、pg 表、`pg_trgm` 索引、向量模型调用全在 agent 的
   依赖里。一个不需要知识库的部署也得把 `spring-ai-pgvector-store` 拉进来。
2. **rag 反过来依赖 agent 的模型层**：`KnowledgeIndexer` 用 `EmbeddingProperties` 算索引身份，
   重排器用 `ModelProperties` 取 qwen 的 key。能力模块依赖上层的模型路由表，方向是反的。

拆开之后依赖方向是单向的：

```
core ← capability ← rag
core ← capability ← agent        （agent 不依赖 rag）
app = agent + rag                  （由部署把两者接起来）
```

`claw4j-agent` 主代码里原本就**没有任何一处**引用 `agent.rag`（它只按 `ObjectProvider<Advisor>`
收 advisor），所以拆出去不需要改 agent 的装配。

## 二、搬了什么

22 个文件（17 个类 + 8 个测试，其中 3 个测试原本就在，见下），包名 `io.github.claw4j.agent.rag`
→ `io.github.claw4j.rag`，用 `git mv` 保留历史：

| 分组 | 类 |
| --- | --- |
| 索引 | `KnowledgeIndexer`、`SemanticTextSplitter`、`MarkdownOutline`、`ContentHash` |
| 检索 | `HybridDocumentRetriever`、`KeywordDocumentRetriever`、`WeightedScoreJoiner`、`KnowledgeRetrievalPipeline`、`RerankDocumentPostProcessor`、`TopKDocumentPostProcessor`、`SectionExpander` |
| 改写 | `GuardedQueryRewriter`、`RewriteGuard`、`PatternGuard` |
| 基础设施 | `RagProperties`、`RagMetadata`、`RagAutoConfiguration` |

新模块自带 `META-INF/spring/...AutoConfiguration.imports`；agent 的那份里删掉了
`io.github.claw4j.agent.rag.RagAutoConfiguration`。

## 三、三处解耦

| 原来 | 现在 | 为什么 |
| --- | --- | --- |
| `KnowledgeIndexer` 持 `EmbeddingProperties` | 持 `String embeddingModel` + `int dimensions` | 两个普通值，由装配处从 `claw4j.model.embedding.*` 读出来传进去。rag 不知道模型层长什么样 |
| 向量库用 `EmbeddingProperties.dimensions()` | `@Value("${claw4j.model.embedding.dimensions:1024}")` | 与建表维度**同源**。换成读 `EmbeddingModel.dimensions()` 会让"建表用配置、身份用探测值"两处可能不一致 |
| 重排器从 `ModelProperties` 找 qwen 的 key | `RagProperties.Rerank.apiKey`（默认 `secret:qwen_api_key`） | rag 不该知道 agent 的 provider 路由表。默认值指向同一把钥匙，不是复制一份新的 |
| `@AutoConfiguration(after = EmbeddingAutoConfiguration.class)` | `afterName = "io.github.claw4j.agent.model.EmbeddingAutoConfiguration"` | 跨模块只能按类名字符串，否则 rag 编译期就得依赖 agent |

## 四、不搬迁移脚本（有意为之）

`V4` 建的是 `claw4j_rag` schema，按"归属"该跟着 rag 走。但它留在 `claw4j-agent` 的迁移序列里：

Flyway 的版本号是**全局一条线**。把 V4 拆到另一个 jar 之后，"先只部署 agent、后来才加 rag"的库里，
V4 会变成 out-of-order，`flyway.validate-on-migrate` 默认直接报错。脚本集中、代码分模块，两边不冲突。

## 五、验收

- [x] `claw4j-rag` 编译通过，`-am` 带上 core / capability
- [x] `claw4j-rag` 70 条单测全绿（含 `KnowledgeRetrievalTest` 的装配用例，验证解耦后仍能启动）
- [x] `claw4j-agent` 不含 `rag` 包，单测全绿（搬走的 8 个测试类已在 `claw4j-rag` 里跑过）
- [x] `claw4j-app` / `claw4j-eval` 显式依赖 `claw4j-rag`，测试里的 import 同步
- [x] checkstyle / spotbugs 全绿
- [ ] **未跑**：整机（`SearchSqlTest` 要 Testcontainers）与一轮真实评测

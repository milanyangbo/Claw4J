# 票 05：向量模型换 text-embedding-v4，索引身份补上模型名

## 现象

现在用的是 `text-embedding-v3`。百炼同一个兼容端点上还有 `text-embedding-v4`
（Qwen3-Embedding 系列）：**1024 维仍可选**、8192 token/条、单请求上限同为 10 条、单价同档
（0.0005 元/千 token）。也就是说换它不需要改表、不需要改分批逻辑、不需要改端点。

## 一个必须先修的静默错误

`KnowledgeIndexer.identityOf(chunkSize)` 只含 `chunkingVersion` + `chunkTokens`：

```java
return "{\"chunkingVersion\":" + CHUNKING_VERSION + ",\"chunkTokens\":" + chunkSize + "}";
```

**不含向量模型名与维度**。换模型既不触发全量重建，也不报错——库里会新旧向量混着存，
余弦分数看着仍然"正常"，检索悄悄变差。这类错误没有任何症状，只有换完模型之后
"分数没变/反而低了"这个模糊感受。

所以换模型之前先把模型名与维度写进身份。

## 要改的

1. `EmbeddingProperties.DEFAULT_MODEL` → `text-embedding-v4`；
   `application.yml` 的 `model: ${QWEN_EMBEDDING_MODEL:text-embedding-v4}`。
2. `KnowledgeIndexer.identityOf(int chunkSize, String embeddingModel, int dimensions)`：
   ```json
   {"chunkingVersion":2,"chunkTokens":800,"embedding":"text-embedding-v4","dimensions":1024}
   ```
   `CHUNKING_VERSION` 同时从 1 提到 2——票 07（块上下文）会改块文本，这里一次到位，
   避免连着两次全量重建。
3. 身份需要的模型信息从哪来：`KnowledgeIndexer` 构造器加一个 `EmbeddingProperties` 参数
   （容器里已有这颗 bean），不要在索引器里反推 `EmbeddingModel` 的类名——装饰器类名与模型名无关。
4. 调用点同步：`RagAutoConfiguration#knowledgeIndexer`、`SearchSqlTest`、`KnowledgeRetrievalTest`。

## 不在范围

- 不改维度（1024 不动，`vector(1024)` 与迁移都不碰）。
- 不改语义缓存的阈值（0.93 是对 v3 量的；换模型后它可能偏松或偏紧，那是独立的一件事，
  且缓存有 `enabled` 开关可关）。

## 验收

- 新单测：模型名变 / 维度变 → identity 变；同参数两次调用结果相同。
- 一次全量重建，日志里 `这次全量重建：{...,"embedding":"text-embedding-v4",...}`，段数与上一轮接近；
- 一次 L1 导出，与票 04 那一轮同口径对照。

## 四、实施（2026-11-20）

- `EmbeddingProperties.DEFAULT_MODEL` → `text-embedding-v4`，`application.yml` 同步。
- `KnowledgeIndexer.identityOf(chunkSize, embeddingModel, dimensions)`；构造器多一个
  `EmbeddingProperties` 参数（`RagAutoConfiguration` 与两处测试同步）。
- 新增 `KnowledgeIndexerIdentityTest` 5 条：模型变 / 维度变 / 块长变 / 同输入稳定 / 串里带模型名。
- 实测：装配日志 `model=text-embedding-v4, dimensions=1024`；索引 440 段
  （上一轮 434，差异来自块前缀与切块边界，不是 v4 本身）。
- **未验收**：与 v3 的同口径 L1 对照（需要把模型改回 v3 跑一轮，没做）。

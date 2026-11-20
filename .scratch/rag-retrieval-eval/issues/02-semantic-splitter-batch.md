# 02: 语义切块的向量请求分批，别再整篇降级

**What to build:** `SemanticTextSplitter` 算句子组向量时按供应商的单请求上限（10 条 input）分批，
任一批失败仍然整篇降级。目标是让"语义跃迁"这条判据真的生效，而不是形同虚设。

## 一、现象与根因（2026-11-20 实测）

导出那一次（111 份 / 230 段）几乎每篇都刷这条：

```
WARN i.g.c.agent.rag.SemanticTextSplitter : 语义切块：156 句的向量算不出来，这一篇只按转折词切：
400: <400> InternalError.Algo.InvalidParameter: Value error, batch size is invalid,
it should not be larger than 10.: input.contents
```

- 触发点是 `SemanticTextSplitter.java:85` 的 `embeddings.embed(groups)`，一条请求带上全部句子组。
- `ChunkPlanner.groups(sentences, BUFFER_SIZE=1)` 每组一窗口，**组数等于句子数**，所以一篇上百句的文档
  一次发上百条 input。
- 向量模型是通义 `text-embedding-v3`（`application.yml` 的 `claw4j.model.embedding`），
  单请求 input 上限 10，直接 400，`OpenAiEmbeddingModel` 抛 `RuntimeException`。
- 结论：句子数 > 10 的文档**必然失败**。语义切块上线以来只有 ≤10 句的短文真的跑了它，
  其余全部退化为"只按转折词切"。票 01 的缺口 4 记的就是这件事。

## 二、做法

`semanticBreaks()` 里把单次调用改成按 10 条切片，顺序追加：

```java
for (int from = 0; from < groups.size(); from += EMBEDDING_BATCH) {
    int to = Math.min(from + EMBEDDING_BATCH, groups.size());
    vectors.addAll(embeddings.embed(groups.subList(from, to)));
}
```

三条必须守住：

1. **`try` 包住整个循环**，不是每批 catch 后 continue。断点靠下标对齐相邻组，缺任何一批都会让后面的配对
   全部错位——而错位不报错，只会把块悄悄切坏。这与既有的"向量条数与句子组数不一致就不用向量"
   （92–97 行）是同一个取舍。
2. **保序**：`subList` + `addAll` 天然保序。不并行：并行要自己维护角标回填，收益只是索引时快几秒。
3. **10 是供应商上限，写成常量并注明依据**，不做成配置键。换上限更大的模型只是多发几批，功能不坏；
   多一个配置键就多一处要维护的默认值。

## 三、不做（写清去向）

- **不加重试**：失败仍整篇降级。索引是启动时的事，重试会把一次抖动拖成启动变慢，且降级本身是正确的。
- **不动断点算法**：`percentile=95`、`buffer_size=1`、转折词表全部保持 ADR-0028 的口径。
- **不处理票 01 的缺口 3**（"万分之五"被切在词中间）：那是按词边界的问题，影响全文检索那一路，
  与分批无关，另开一件。
- **不加 `claw4j.rag.embedding.batch-size`**：见上。

## 四、测试（不进 `eval` 组，`verify.sh` 会跑）

用 Mockito 假的 `EmbeddingModel`（记录每批入参），断言三件事：

1. 每批 input ≤ 10，且总条数等于句子数（25 句 → 3 批：10/10/5）。
2. 拼接顺序与 `groups` 一致：把每批的向量按批序回填后，与"一次给全"的结果逐条相等
   （用可区分的向量值，否则顺序错了也看不出来）。
3. 第二批抛 `RuntimeException` → 仍返回非空块（降级为只按转折词切），不抛给调用方。

## 五、代价

首次索引的请求数从 1 次（失败）变成 ⌈句数/10⌉ 次，111 份全量索引会慢几分钟；
增量索引只处理内容哈希变过的文档，日常无感。这笔时间换的是"语义切块真的在切"。

**无需 ADR：** 不改对外契约、不加配置键、不改断点口径，只修一处内部调用的分批方式。

## 六、上限集中到一层（2026-11-20 追加，用户裁决按 B 改）

第一版把分批写在 `SemanticTextSplitter` 里，当天跑评测就撞上第二个调用点：

```
PgVectorStore.doAdd(PgVectorStore.java:261) → EmbeddingModel.embed(List) 一次交 11 块
  ← KnowledgeIndexer.indexOne(:218) 的 vectorStore.add(chunks)
  ← 一篇文档被语义断点切成了 11 块（修复生效的直接证据）
→ 400 batch size is invalid → 那份文档索引失败 → ApplicationRunner 抛错，上下文起不来
```

结论：单请求条数上限是**模型的性质**，不是某个调用方的性质。两处各写一遍，第三处还会漏。

**第二版把闸放在 `embed(List)` 上，同一次评测又撞了第三次**，这条值得记：

```
PgVectorStore.doAdd → EmbeddingModel.embed(documents, options, batchingStrategy)   ← 三参默认实现
  → 内部直接 call(new EmbeddingRequest(该批全部文本))                              ← 绕过 embed(List)
→ 仍然一条请求 11 条 input → 400
```

`EmbeddingModel` 里三条批量入口最后都汇到 `call(EmbeddingRequest)`：`embed(List)`、
`embedForResponse` 是三参那两条的直接调用者，三参 `embed` 则是自己拼 request 再 `call`。
所以闸落在 `call`。

最终形状（`BatchingEmbeddingModel`）：

- `call(EmbeddingRequest)` 里按 10 切片、逐批 `delegate.call`，结果顺序拼接；
- 跨批合并口径：`index` 重新编成全局序号（沿用每批的 0 起序号，下游按 index 放置时会错位）、
  输入 token 相加（输出恒 0，向量请求没有输出 token）、`nativeUsage` 丢弃（单条请求的原生结构，
  拼起来只会得到一份假的）；每批自己的 observation 仍由真模型上报，不因合并而丢；
- `dimensions()` / `embed(Document)` / `getEmbeddingContent()` 原样透传；
- 切块器改回单次 `embed(groups)`，自己那份循环和常量撤掉。

消费方不用改，它们都只注入 `EmbeddingModel` 一个类型：向量库（`RagAutoConfiguration:147`）、
切块器（`KnowledgeIndexer:112/117`）、语义缓存（`CacheAutoConfiguration:86`）。

测试跟着搬：`BatchingEmbeddingModelTest` 八条——10/10/5 分批、跨批保序、合并后 index 是全局序号、
用量按批相加、正好 10 条只发一次、**三参 `embed` 那条路同样受闸**（就是撞过两次的那个点）、
`dimensions` 与单条透传、null delegate 拒绝；`SemanticTextSplitterTest` 只留降级那一条。

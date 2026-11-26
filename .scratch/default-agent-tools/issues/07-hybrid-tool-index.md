# 票 07：索引 hybrid——关键字 ∪ 向量（RRF）

**Status:** open

## 由来

票 06 修的是"露出多少"，没修"命中不了"。`ChineseToolIndex` 是词面匹配：中文问法切成二元组，
对框架自带英文描述命中数为 0；票 04 靠同义词表给网页那族搭了一座桥（`页/链接/网址/浏览 → fetch,url`），
桥是逐个搭的，不通用。用户 2026-11-26 要求 `index: hybrid` + `hybrid.keyword-weight`。

## 裁决

`docs/adr/0021-explicitly-not-doing.md`（「**领先 2**：工具可见性」）再修订一条：检索索引可插拔，
`hybrid` = 关键字 ∪ 向量按 RRF 融合、权重可配。ADR-0005 已经定了 pgvector，本票复用它，
不引第二个向量库。

## 一个必须先改的现状

`VectorToolIndex` 直接 `vectorStore.add(documents)`，metadata 只有 `sessionId` / `id`，检索也只按
`sessionId` 过滤；而本仓唯一的 `VectorStore` bean 是 `PgVectorStore` 建在 `claw4j_rag.vector_store`
（`RagAutoConfiguration:150`），BM25 全文索引（`ix_rag_vector_store_content_bm25`）也在同一张表上。
工具描述一旦走这个 bean，知识库的向量路与全文路都会把"Glob 是文件匹配工具"召回进业务问答。

所以：

- 工具向量独立落一张表：`claw4j_tool.tool_index`，迁移 `V8__tool_index.sql`。
- **不注册成 `VectorStore` bean**。`RagAutoConfiguration` 那条是 `@ConditionalOnMissingBean(VectorStore.class)`，
  多一个同类型 bean 会因装配顺序让它静默退让，表现是"知识库突然全空"——一个只在重启后才发现、
  日志里没有任何一行提到向量的故障。工具那一路在 `toolIndex` 工厂里现场构造 `PgVectorStore`，
  直接交给 `VectorToolIndex`，不进容器。

这两条是 schema 变更，按 `AGENTS.md`「先问再动」要先拿到裁决。

## 融合怎么算

```yaml
claw4j:
  advisor:
    tool-search:
      index: hybrid          # regex | lucene | vector | hybrid
      hybrid:
        keyword-weight: 0.5  # 0..1；0 = 纯向量，1 = 纯关键字（= 现状，用于对照）
```

- **RRF 而不是加权求和**：两路的分值量纲不同（正则是命中计数，向量是余弦距离），求和要先归一化，
  而归一化系数只能靠调。RRF 只吃名次：`score = w / (k + rank_kw) + (1 - w) / (k + rank_vec)`，
  `k = 60`（Cormack 的常用值）。
- **每路先取 `3 × maxResults`** 再融合取前 `maxResults`：只取 N 的话，融合没有腾挪空间。
- **namespace 过滤在融合之前**：`Hybrid(Namespaced(regex), Namespaced(vector))`，两个内层各自过滤。
  反过来（包在融合外面）会让某个 namespace 的工具先被别的 namespace 顶掉名额，过滤后凑不满一页。
- **不做 rerank**：候选只有 5–20 个；RAG 那套 rerank 要一次模型调用，它的收益来自"几十段文档里的
  长噪声"，工具这边没有那个量级。

## 成本

框架的 `prepareIteration` 已经按 `computeFingerprint(toolReferences)` **只在工具集变化时**重建索引，
不是每轮 embed。沿用这个时机：一次部署 embed 一遍工具集，换了工具再算一次。重启后第一次必重建
（fingerprint 在内存里），接受。

## 改哪

1. `ToolSearchProperties`：`index` 枚举加 `VECTOR` / `HYBRID`；加 `Hybrid(double keywordWeight)`，
   缺省 0.5、越界夹到 `[0,1]`。
2. 新增 `claw4j-agent/toolaccess/HybridToolIndex implements ToolIndex`：两路各查一次（都是本地计算 +
   一次向量查询，不必并行），RRF 融合。
3. 新增 `V8__tool_index.sql`：`create schema if not exists claw4j_tool`、`tool_index`
   （`id uuid` / `content text` / `metadata json` / `embedding vector(1024)`），形状与
   `claw4j_rag.vector_store` 一致；`V4__rag.sql` 的建法与索引照抄。
4. `AgentAutoConfiguration`：`index=vector|hybrid` 时构造工具专用 `PgVectorStore` + `VectorToolIndex`，
   外包 `NamespacedToolIndex`（票 06），两路合成 `HybridToolIndex`。
5. 配置校验：`index=vector|hybrid` 但拿不到 `EmbeddingModel` 或维度对不上 → **启动失败**，不降级
   （与 `claw4j.rag.enabled` 同一口径：开着就必须能用，不给一个"每次都搜不到"的空库）。
6. `application.yml`：加两项与注释；**默认仍是 `regex`**——换默认值要单独量一次，不跟着本票顺手改。

## TDD 用例清单

并入 `ModelAndToolSelectionTest`（票 06 之后 19 条，本票 +5 → 24，仍在 26 以内）。

| 判什么行为 | 用例 | 先红的样子 |
| --- | --- | --- |
| 中文说法与英文描述无字面重叠时也命中 | `ModelAndToolSelectionTest#hybridFindsToolWithNoLiteralOverlap` | 纯 regex 索引返回空，断言含 `WebFetch` 红 |
| `keyword-weight=1` 等价纯关键字 | `ModelAndToolSelectionTest#keywordWeightOneBehavesLikeKeywordOnly` | 权重没生效时结果与 0.5 相同 |
| `keyword-weight=0` 时关键字独占不再压过向量 | `ModelAndToolSelectionTest#keywordWeightZeroIgnoresKeywordPath` | 固定权重时顺序不变，断言红 |
| 融合按名次而非原始分 | `ModelAndToolSelectionTest#fusionUsesRanksNotRawScores` | 直接相加时一路量纲占满，断言顺序红 |
| namespace 过滤发生在融合之前 | `ModelAndToolSelectionTest#namespaceFilterAppliedBeforeFusion` | 包在融合外面时凑不满 `maxResults`，断言条数红 |
| 工具向量不进知识库那张表 | `ApplicationAssemblyTest#toolVectorsStayOutOfTheKnowledgeTable` | 共用 bean 时 `claw4j_rag.vector_store` 行数变多，断言红 |

## 缺口（本票不做）

- **embedding 质量未验证**：`text-embedding-v4` 对"工具名 + 一句英文描述"这种短文本的效果要量。
  效果不好就退回**描述中文化**（装配时把 description 换成"中文一句 + 英文原文"，索引与模型两边
  同时受益）——那是另一种解，不必换索引。
- **没有评测集**：`eval/tools`（票 06 缺口）建起来之前，本票只能判"通了"，判不了"好多少"。
- **工具向量表的清理**：沿用框架的 `clearIndex(sessionId)`；进程重启留下的孤儿行暂时不管，
  等 TTL 那套（`SessionExpirySweep`）有了第二类对象再一起想。

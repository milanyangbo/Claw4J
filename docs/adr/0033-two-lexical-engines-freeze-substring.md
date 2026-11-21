# 全文那一路留两个引擎，进化只留给 BM25

---

status: accepted
date: 2026-11-21

---

形状规则这条路走不通。这篇 ADR 记的不是「要不要上 BM25」，而是**为什么两套并存、哪一套冻结、什么条件下换默认**。

## Context

### 现在这一路在做什么

`content ilike '%词%'`，没有分词、没有 IDF、没有倒排。为了让它在中文上能用，问题被拆成三类字词，每一条规则都替部署方做了一个领域决定：

| 规则 | 为什么加 | 它替谁做了决定 |
| --- | --- | --- |
| 整句 ≤ 12 字才用 | 长句在文档里不会原样出现 | 中文「一句话」有多长 |
| 编号 ≥ 3 字符 | 躲开 `a`、`1` 这类噪声 | 多长的串才算「精确标识」 |
| 加「字母 + 1~2 位数字」 | 产品型号是两位（`A1`、`A2`） | 哪些短串值得当锚点 |
| bigram 权重 1、锚点 3 | 覆盖度 vs 字面精确 | 两类信号的分量比 |

这些值现在全部走配置（`claw4j.rag.keyword.substring.*`）。但**配置化只解决「写死」，没解决「要一直加」**：换一批语料，出现 `AB-12`、中文型号「甲型」、英文提问，还得再加一条。

真实成本不在改代码，在每次都要判断「这条新规则会不会和已有的打架」，而判错的症状是「某类查询一条都不命中」——不报错。这才是要停下来的理由。

### 另一条路上的三个候选

| 方案 | 是 BM25 吗 | 缺什么 |
| --- | --- | --- |
| `pg_bigm` | 不是。只让 2 字模式可索引 | 不产分数，还是要自己拼 SQL |
| `zhparser` + `ts_rank` | 接近但不是：无长度归一、无饱和项 | 词典自己维护，排序口径与 BM25 不同 |
| ParadeDB `pg_search` | 是。Tantivy 引擎 + `pdb.jieba` 中文分词 | 要换镜像，许可证要过一遍 |

### 今天实测的五个事实

在 `paradedb/paradedb:latest`（pg_search 0.27.0 / PG 18.7）上跑过，不是查文档推的：

1. 建索引与查询都能跑通：

   ```sql
   create index ix_rag_vector_store_content_bm25
     on claw4j_rag.vector_store using bm25 (id, (content::pdb.jieba)) with (key_field = 'id');

   select id, content, pdb.score(id) as hits
   from claw4j_rag.vector_store
   where metadata->>'source' = ? and content @@@ pdb.match(?)
   order by hits desc limit ?;
   ```

2. `pdb.match($1)` 可以参数化：既不用把用户问题拼进 SQL，也不用手写 Lucene 语法转义。
3. **没有 BM25 索引时查询直接报错**（`does not contain a USING paradedb index`）。它不是「慢一点」的优化，是硬依赖——所以建索引失败必须启动失败，不能像 trigram 那样只 WARN。
4. 分数无上界（实测 0.18 ~ 1.21），但融合层 `WeightedScoreJoiner` 本来就按「本次最大值」归一，所以融合不用改。
5. 镜像有 `pg16` 变体（`paradedb/paradedb:pg16`），换引擎不必顺带升 PG 大版本。

## Decision

**两个引擎并存，靠 `claw4j.rag.keyword.engine` 切；`substring` 冻结，不再加规则。**

1. 默认 `substring`：现有部署零变化，评测口径不变。
2. 新增 `bm25`：一个类、一条 SQL、一次建索引。它不认「锚点」「bigram」这些概念——分词与 IDF 在引擎里。
3. **`substring` 冻结**：不再加任何形状规则、权重、上限。要新能力，去 `bm25` 那一侧。
4. `bm25` 的失败策略是**启动失败**，不是降级：缺 `pg_search`、建不了索引、连不上 ParadeDB，一律在启动时点名说清「换镜像或把 engine 设回 substring」。理由见事实 3。
5. **默认值不改**。换默认要过两道：68 题 L1 上 `bm25 ≥ substring`，且 L2 重钉基线后不掉（一轮 75 分钟，单独排）。

## Consequences

- 短期多一套代码与一套测试。这是有意付的：**它买的是「以后不再往规则链上加东西」**。
- 两套引擎的分不能横向比。融合权重 0.7 / 0.3 是照 `substring` 的分数量级定的，归一之后 `bm25` 能用，但真要上位时权重该重标一次。
- **许可证要过一遍**：ParadeDB 是 AGPL-3.0 + 商业双许可。这不是技术判断，是产品/法务判断，**在决定切默认之前必须有结论**。
- 业务词可能被切碎：`备机押金`、`万分之五` 这类词 jieba 未必是一个词，可能要自定义词典。这是运维成本，不是代码成本。
- 单测给不了保证：分词、IDF、无索引报错这三件事只能在真容器里验，集成测试默认不跑。

## 不做

- **不给 `substring` 加规则**——这是本 ADR 的核心约束，比「上不上 BM25」更重要。
- 不加 Elasticsearch / Meilisearch 旁路：问题不在「要不要独立检索引擎」，在「这一路够不够用、会不会一直加规则」。
- 不做 `pg_bigm` / `zhparser` 的中间态：换三次不如换一次到位。

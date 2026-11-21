# 票 15：全文那一路加 BM25 引擎（双引擎，配置切换）

状态：设计中（2026-11-21）。**本票只定方案，不写代码。** 关联 ADR-0033。

## 一、目标

把「全文那一路用什么引擎」变成配置，而不是继续往 `KeywordDocumentRetriever` 的形状规则上加东西。

- `claw4j.rag.keyword.engine = substring`（默认，现状）
- `claw4j.rag.keyword.engine = bm25`（ParadeDB `pg_search` + `pdb.jieba` 分词 + IDF）

`substring` 冻结：本票不动它的任何规则，之后也不再动。

## 二、范围（实施时）

| # | 改什么 | 要点 |
| --- | --- | --- |
| 1 | `RagProperties.Keyword` 加 `engine` | 值只认 `substring` / `bm25`；拼错（`bm2`）启动即抛，不许静默回落——静默回落的表现是「改了配置没反应」 |
| 2 | 新增 `Bm25KeywordDocumentRetriever` | 一条 SQL，见下；空查询返回空列表，不抛 |
| 3 | `RagAutoConfiguration` 按 engine 选实现 | `hybrid.enabled=false` 仍然谁都不装 |
| 4 | 建索引 + 失败即启动失败 | `create extension if not exists pg_search` + `create index if not exists ... using bm25`。**与 trigram 不同**：这里失败必须抛，因为没索引时查询直接报错（见三.3） |
| 5 | `application.yml` 注释 | 写明八项规则只对 `substring` 生效 |
| 6 | 集成测试（真容器） | 默认跳过，`-Dclaw4j.test.paradedb=true` 触发；镜像名也走系统属性，避免写死镜像站 |
| 7 | 文档 | `docs/design/knowledge-retrieval.md` 词项一节补「两个引擎」；README-QA 本轮不改（还没有分数） |

查询（已在真容器里跑通，不是草案）：

```sql
select id, content, metadata->>'path' as path, metadata->>'source' as source,
       metadata->>'index' as ordinal, metadata->>'content_hash' as hash,
       metadata->>'section' as section,
       pdb.score(id) as hits
from claw4j_rag.vector_store
where metadata->>'source' = ? and content @@@ pdb.match(?)
order by hits desc
limit ?
```

## 三、已验证的事实（2026-11-21，`paradedb/paradedb:latest` / pg_search 0.27.0 / PG 18.7）

1. 建索引的写法成立：`using bm25 (id, (content::pdb.jieba)) with (key_field = 'id')`，`id` 是 uuid 也没问题。
2. `pdb.match($1)` 能参数化——不用拼 SQL、不用手写 Lucene 语法转义。
3. **没有 BM25 索引时查询报错**：`ERROR: test_chunks does not contain a USING paradedb index`。所以索引是硬依赖，不是性能优化 → 建索引失败要启动失败。
4. 分数无上界（实测 0.184 ~ 1.208）。融合层 `WeightedScoreJoiner` 按本次最大值归一，**融合不用改**。
5. `A2 买一年延保要多少钱` 在三条样例块上，价格表（含「A2 单价 3999」）排第一（1.208），延保政策第二（1.193）。`substring` 引擎为这件事专门加过一条形状规则——**正是本票要避免的模式**：换一种编号写法就得再加一条。
6. 镜像有 `pg16` 变体（`paradedb/paradedb:pg16` / `0.26.6-pg16`），换引擎不必升 PG 大版本。

## 四、验收（实施后）

- [ ] 默认 `substring` 下行为零变化：现有 84 条单测全绿，一条不改也不许改断言
- [ ] `engine=bm25` + ParadeDB 容器：问「A2 买一年延保要多少钱」，价格表那块排第一
- [ ] `engine=bm25` 但库里没有 `pg_search`：启动失败，消息里同时给出「换 ParadeDB 镜像」和「设回 substring」两条出路
- [ ] `engine=bm2`：启动即抛，消息列出允许值
- [ ] 空查询 / 纯标点查询：返回空列表，不抛
- [ ] 一轮 L1 对照（bm25 vs substring，同 68 题）——**这是决定要不要换默认的依据，但不作为本票的验收门**

## 五、不做 / 边界

- 不改默认值，不重钉基线，不跑 L2（要跑另开票）
- 不动 `substring` 的任何规则（形状、上限、权重都算）
- 不做 jieba 自定义词典：等出现真实切错的例子再做
- 不因为「上了 BM25」就顺手改融合权重：权重重标是换默认值时的事
- 许可证结论（AGPL-3.0 + 商业双许可）**不在本票里解决**，但它是换默认的前置条件，记在 ADR-0033 的代价里

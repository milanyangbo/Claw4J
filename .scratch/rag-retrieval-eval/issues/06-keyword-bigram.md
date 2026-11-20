# 票 06：词法那一路改成中文 bigram 词项覆盖

## 现象

`KeywordDocumentRetriever` 现在把**整句**放进 `ilike '%整句%'`，再加几个纯 ASCII 锚点：

```sql
where content ilike '%为什么隔壁那家店能退我不能退%'
```

中文长问题在文档里几乎不可能整句出现，于是这一路对绝大多数中文题**零命中**。
`hybrid.enabled=true` 名字上叫混合检索，实际只有向量一条腿在跑——这正是那 8 条
"三次实验都没进过池"的题里，口语题与多跳题的主要嫌疑。

## 要改的

把查询拆成词项，用"命中了几个不同词项"排序：

1. **中文 bigram**：把查询里连续的中日韩字符串切成 2-gram（`退货政策` → `退货`、`货政`、`政策`）。
   不解词、不需要分词器、不需要额外扩展——这是 pg_bigm 的思路，用现有 `ilike` 就能做。
2. **ASCII 锚点**：保留现状（`SKU-2024`、`A-07`、`v1.2`）。
3. **整句**：保留，但只在整句长度 ≤ 12 字时加入（短查询如「退货政策」本身就是最好的词项；
   长句加进去只是一条必然不命中的 `or`）。
4. 词项总数封顶（12 条），去重保序：SQL 里的 `or` 数量直接等于词项数，不封顶会让一条长提问
   生成几十个 `or`。
5. **打分改成"命中的不同词项数"**，不是"总共出现多少次"：
   ```sql
   (case when content ilike ? then 1 else 0 end) + ...
   ```
   原来是 `(length - length(replace(...))) / length(?)` 的出现次数求和，一个高频词出现 10 次
   会压过命中 5 个不同词项的块——那是拿词频当相关度。

## 诚实边界（写进类注释，不写成"BM25"）

这不是 BM25：没有 IDF（"的"和"押金"权重相同）、没有长度归一、没有倒排索引。它是
"bigram 词项覆盖 + 融合时按本次最大值归一"。真正的 BM25 需要 `pg_bigm` / `zhparser` /
ParadeDB `pg_search`，三者都要换数据库镜像，那是另一件事。

**索引退化**：`pg_trgm` 的 GIN 索引只对 ≥3 字符的模式有效，2 字 bigram 会走全表扫描。
当前 400 多段可以忽略（毫秒级）；段数上万时必须换 `pg_bigm`，这条写进注释与设计文档。

## 不在范围

- 不换镜像、不装扩展（`pg_bigm` / `zhparser` / ParadeDB 都不引入）。
- 不改融合权重（仍 0.7 / 0.3）。
- 不做同义词、不做繁简转换。

## 验收

- `KeywordDocumentRetrieverTest`：`searchTerms("为什么隔壁那家店能退我不能退")` 产出 bigram 词项
  且不含整句；`searchTerms("SKU-2024 还剩多少")` 含整句与锚点；词项数不超上限。
- 打分列改成 0/1 相加，`SearchSqlTest` 里对 SQL 形状的断言同步。
- 一次 L1 导出：关注那 8 条"从没进过池"的题里，口语 2 条与多跳 2 条是否进池。

## 五、实施（2026-11-20）

- `KeywordDocumentRetriever#terms` 返回带权词项：整句 3 / ASCII 锚点 3 / 中文 bigram 1，
  上限 12；`searchTerms` 保留为"只要文本"的视图，给日志与单测用。
- SQL 打分改成"命中词项权重之和"；`select` 与 `where` 两处都用带 `%` 的模式串。
- 踩到两次，都是"改了形状忘了改配套"：
  1. 每词项占位符从 3 个变 2 个，参数仍按 3 个给 → `column index is out of range`（真库测试抓住）；
  2. `select` 那一处给裸词项 → `ilike` 退化成精确相等，`where` 命中而 `score` 恒 0
     （`SearchSqlTest` 的 "命中次数要落进 score" 断言抓住）。
- 单测 `KeywordDocumentRetrieverTest` 7 条 + 真库 `SearchSqlTest` 17 条全绿。
- 真库实测：问 `SKU-2024 还剩多少`，全文那一路第 1 条回到 `warehouse/sku-2024-stock.md`。

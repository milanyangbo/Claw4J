# 票 07：块文本前置「文档 › 章节」上下文

## 现象

语义断点生效后平均 3.9 段/份，块变碎。碎块的直接后果是：**块里不再出现"这是哪份文档的哪一章"**。

一段 350 字的块可能只写"押金在 7 个工作日内原路退回"，向量里没有"备机"、没有"租赁"、
没有"售后政策"。同一种"7 个工作日"的说法散在三份文档里时，三块的向量几乎一样，
谁进池只能靠运气。这正是 Anthropic Contextual Retrieval 的出发点（他们量到的效果是
top-20 失败率 5.7% → 3.7%）。

## 要改的

索引时给每块前置一行轻量上下文，**拼接进 content 本身**（不是只给 embedding 用）：

```
【售后政策（after-sales.md） › 第二章 退货 › 2.3 运费承担】
（原文）
```

这样检索与生成两侧都受益：向量里有"售后/退货"，模型注入了也知道这段的归属。
Anthropic 那套是用 LLM 生成 50–100 token 的具体上下文（约 $1.02/百万 token），
这里先做零成本版（标题 + 章节路径）；LLM 版是下一步，不是本票。

1. 新增 `MarkdownOutline`（`claw4j-agent/.../rag`）：
   - `title(content, path)`：第一个 `# ` 的文本，没有就用文件名去扩展名；
   - `headings(content)`：所有 `#{1,6}` 行的 `(offset, level, text)`；
   - `pathAt(headings, offset)`：该位置所在的章节链，用 ` › ` 连接，超过 3 级只留最后 3 级。
2. `KnowledgeIndexer#chunk`：按切块输出顺序维护原文游标（`content.indexOf(piece, cursor)`），
   用块在原文中的位置查章节；`indexOf` 找不到时沿用上一块的章节（不猜、不抛）。
3. `CHUNKING_VERSION` 已在票 05 提到 2；本票不再动身份，但要保证块文本变了会重建
   （身份已含 version，满足）。
4. 前缀长度上限（比如 120 字符）：章节标题可能是整句，无限长会把块的有效内容挤掉。

## 影响面

- `gold_spans` 的 `contains` 判定不受影响（前缀加在前面，span 仍在块内）。
- 注入格式不变（`[n] 路径 第 N 段`），只是正文多了前缀一行。
- 块的实际 token 数增加约 20–40，`chunk-size: 800` 的硬切仍按 token 算，不需要改。

## 不在范围

- 不用 LLM 生成上下文（那是 Anthropic 那套的完整版，要花钱、要缓存 prompt）。
- 不把前缀单独存成 metadata（除非票 08 需要——它需要 `section`，见票 08）。

## 验收

- `MarkdownOutlineTest`：H1 缺失时回退文件名、多级标题链、代码块里的 `#` 不算标题（围栏内跳过）。
- 一次全量重建，抽样 3 份文档的块文本确认前缀正确（`after-sales.md` 的三段应分别落在不同章节）。
- 一次 L1 导出对照。

## 六、实施（2026-11-20）

- 新增 `MarkdownOutline`：`title` / `sections` / `at` / `sectionOf` / `prefix`；
  跳过围栏代码块；章节链最多 3 级；前缀上限 120 字符。
- `KnowledgeIndexer#chunk` 前置前缀并把章节写进 metadata（`RagMetadata.SECTION`）；
  `RagMetadata.of` 多一个参数；`CHUNKING_VERSION` 1→2（改块文本必须重建）。
- 定位做了两级：先看块里出现了哪个章节标题的文字，再回退到"块起点所属章节"。只按位置不行——
  切块器拼接句子时删掉换行，块文本不是原文的连续子串（短文档被切成一块时必失败，实测抓到）。
- 单测：`MarkdownOutlineTest` 10 条、`KnowledgeRetrievalTest#chunksCarrySectionContext` 全绿。

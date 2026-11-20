# 票 08：章节父块回填（small-to-big）

## 现象

小块召回准、上下文碎：命中一段 350 字的块，模型只看到那一段，前后条件（适用范围、
例外、办理入口）都在兄弟块里。RAGFlow 0.23 的论点与此相同：一个分片同时承担"召回要小"
与"生成要全"，本身是矛盾的。

## 要改的

子块检索、父块生成。父块 = **同一文档同一章节的全部块**，检索后按章节聚合回填：

1. `RagMetadata` 加 `SECTION` 键（章节链的规范化文本），票 07 已经算出它，这里落库。
2. 新增 `SectionExpander implements DocumentPostProcessor`：
   - 输入是重排后的候选（按名次）；
   - 按 `(path, section)` 分组，序按组内最靠前的名次；
   - 每组用一条 SQL 拉出该章节在库里的全部块（`order by index`），拼成父块文本；
   - 父块替代组内所有子块，位置取最靠前的名次；
   - 父块字符数超过 `max-chars`（默认 2400）时**不聚合**，保留原子块——
     宁可不合并，也不把一章几千字塞进提示词。
3. 装配：在 `KnowledgeRetrievalPipeline` 里排在重排之后、截断之前（`reranker → expander → truncation`）。
4. 配置：`claw4j.rag.parent.enabled`（**默认 false**）、`claw4j.rag.parent.max-chars`。
   默认关的理由：它是用 token 换上下文完整度，收益要用对照实验证明，不能凭"业界这么做"就默认开。

## 评测口径（关键）

返回的是父块，`reference_context_ids` 里的子块 id 不在返回列表里，直接算会**假跌**。
所以：

- 父块的 metadata 带 `covered`（"1,2,3" 这样的子块号列表）；
- 导出时把一条父块**展开**成多个 `(id, text)` 对，text 相同、顺序按子块号；
- `idsAlignWithContexts` 断言仍然要求两个列表等长（同时展开即可）。

代价：同一个 id 在一个用例里出现多次（不同父块覆盖到同一子块）时要去重，只留第一次出现的名次。

## 不在范围

- 不做"父块另存一张表"（当前用 SQL 聚合就够，434 段量级）。
- 不做 LLM 摘要式父块。
- 不默认打开。

## 验收

- `SectionExpanderTest`：同章节三条子块聚成一条父块、跨章节不合并、超 `max-chars` 保留子块、
  候选顺序按最靠前名次。
- 一次开/关对照（只跑 L1），报告里同时给"父块平均字数/条数"与 Recall，判断默认值。

## 七、实施（2026-11-20）

- `SectionExpander implements DocumentPostProcessor`：按 `(path, section)` 分组，同章节命中 ≥2 块
  才聚合；拼起来超 `max-chars` 不聚合；父块占组内最靠前的名次。
- 装配：`claw4j.rag.parent.enabled`（默认 false）、`max-chars`（默认 2400）；
  `KnowledgeRetrievalPipeline` 多一个 expander（保留旧 4 参构造器）。
- 导出：父块按 `covered` 展开成子块 id，`idsAlignWithContexts` 仍要求两个列表等长（同时展开）。
- 单测 `SectionExpanderTest` 6 条全绿。
- **未验收**：开/关对照（要 `CLAW4J_RAG_PARENT_ENABLED=true` 跑一轮 L1）。

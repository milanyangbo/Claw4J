# 票 09：查询改写（含数字/型号时跳过）

## 现象

池内从没出现过的 8 条里，口语题 2 条（"没白纸黑字写能退吗"）、多跳题 2 条。这类问题与文档
用词几乎不重叠：文档写"无书面约定时按下列顺序处理"，用户问"没白纸黑字写能退吗"。
向量与 bigram 都救不了——两个文本没有一个共同词。

## 要改的

检索前把问题改写成"文档会怎么写"的一句话：

1. 实现 `QueryTransformer`（框架自带该接口，`RetrievalAugmentationAdvisor.Builder#queryTransformers`
   接受它）。用 `ChatClient` 一次调用：给一段强约束的提示词，要求只输出一句检索用查询，
   保留专有名词与数字，不解释、不加引号。
2. **跳过规则（必须有）**：问题里出现数字、金额、百分比、ASCII 型号（`SKU-2024`、`A-07`、
   `万/元/%`）时不改写。理由是 HyDE 类方法对数值/型号有害——改写会把 `万分之五` 变成
   `千分之几` 这类近似，而这类题考的就是精确值。跳过时打一行 DEBUG。
3. 失败回落：超时（默认 1.5s）或异常一律用原问题，WARN 一行。改写是增强，不是前提。
4. 配置：`claw4j.rag.rewrite.enabled`（**默认 false**）、`claw4j.rag.rewrite.timeout`。
5. 挂载位置：`RagAutoConfiguration#knowledgeAugmentationAdvisor` 的 `queryTransformers`。
   **导出侧也要挂**：`RetrievalExportTest` 现在直接调 `pipeline.candidates()`，绕过了 advisor；
   把同一个 transformer 注进导出、先 transform 再检索，否则线上改了、评测量不到。

## 评测口径

导出加一个 `retrieval_query` 字段（实际送去检索的文本），`run_ragas.py` 不需要改——
Ragas 用的是 `question` 与 `reference`，改写只影响检索。这一列用于排障：
改写把问题改坏了的时候，能一眼看出来。

## 不在范围

- 不做 multi-query（一次改出 3 条再各检索一遍）：那是 3 倍 embedding 与 3 倍重排。
- 不做 HyDE（生成假设答案再检索）：对数值题有害，且更贵。
- 不做多轮指代消解（"那它呢"）：那是 advisor 拿到上文之后的事，与改写是两件。

## 验收

- `QueryRewriterTest`：含数字/型号的问题不改写、正常问题被改写、模型抛异常时回落原文、
  超时回落原文。
- 一次开/关对照（只跑 L1），报告里给"被改写的条数"与 Recall；默认值由此决定。

## 七、实施（2026-11-20）

- `RetrievalQueryRewriter implements QueryTransformer`：含数字/量词跳过；3 秒超时；
  失败一律回落原 query；输出取首行、去「改写：」标签与引号、截 60 字。
- 装配：`claw4j.rag.rewrite.enabled`（默认 false）、`timeout`（默认 3s）。bean 只在开启时创建。
  线上由 advisor 的 `queryTransformers` 调用，评测导出自己再 transform 一次（同一个 bean）。
- 导出新增 `retrieval_query` 字段：实际送去检索的文本，改写把问题改坏时能一眼看出来。
- 单测 `RetrievalQueryRewriterTest` 6 条全绿（含超时回落，断言不早于超时值太多）。
- **未验收**：开/关对照（要 `CLAW4J_RAG_REWRITE_ENABLED=true` 跑一轮 L1）。

# 01: RAG 评测——Ragas 指标，默认不进回归

**What to build:** 给知识库加一层"检索准不准"的评测：Java 侧把每条问题的检索结果（chunk id + 文本 + 分数）
导出成 JSONL，Python 侧用 Ragas 的**非 LLM 指标**算 Recall / Precision。整条链路默认不进 `./scripts/verify.sh`，
只在 `./eval/rag/run.sh` 时跑。

## 一、为什么端到端那套验法不够

`README-capability-tests.md` 的 B6 有十一条判据，验的是"问题 → 检索 → top-k → 重排 → 模型 → 回答"这条链。
它是**可用性**验收：链上任何一环坏了，屏幕上都是"答错了"，分不清是没召回、召回了没被用上、还是模型自己编的。
扩语料到 78 份时，`batch size is invalid` 那个 bug 让十几份文档的语义切块**全部降级**，
而 B6 十一条照样全绿——这就是端到端验法的盲区。

检索层要回答的是三个可量化的东西：

1. 该被找到的段，有没有进 top-k（召回）
2. 进了 top-k 的段，有多少是想要的（精确）
3. 换一组参数（hybrid / rerank / top-k），上面两个数是变好还是变坏

## 二、为什么用 Ragas 而不是 ranx，且只用它的免费指标

先落在 ranx（确定性 IR 指标），后确认 **Ragas 自带非 LLM 指标**，这一条改变了结论：

| 指标 | 吃什么 | 调模型 |
| --- | --- | --- |
| `IDBasedContextRecall` | `retrieved_context_ids` vs `reference_context_ids` | 否 |
| `IDBasedContextPrecision` | 同上（set-based，**不 rank-aware**） | 否 |
| `NonLLMContextRecall` / `NonLLMContextPrecisionWithReference` | 段落文本 + 编辑距离（需 rapidfuzz） | 否 |

用户要的"召回率"Ragas 给得了，且**零成本、可复现**。用 ranx 会多一套依赖去算同样的东西。

**分层，这是本票的核心设计：**

| 层 | 指标 | 要不要模型 | 成本 | 用途 |
| --- | --- | --- | --- | --- |
| **L1** | IDBased* / NonLLM* | 不要（只检索） | 0 | 参数对照，跑一百遍也行 |
| **L2** | faithfulness / LLMContextRecall / LLMContextPrecision / answer_relevancy | 要 judge | 约 0.5 元/30 题 | 配置定版时验一次 |

**L1 与 L2 都做。** L1 的输入是"检索结果"，不需要模型回答，所以能绕开生成层单独跑——这正是区分
"检索错"与"生成错"的前提。L2 要"真回答 + 真 judge"，导出侧因此要产出 `response`（走一遍
`AgentPort` 的完整问答），算分时用 `--skip-judge` 可以只跑 L1、零成本。

## 三、默认不进回归

沿用仓库既有机制，不新造：

- Java 侧导出用例带 `@Tag("eval")`；根 pom 的 surefire 已经 `<excludedGroups>real-cli,real-mcp,eval,docker</excludedGroups>`，
  所以 `./mvnw clean verify`（`verify.sh` 第 3 步）**不会跑它**。
- 手动跑：`./eval/rag/run.sh`，内部 `-Peval -Dtest=RetrievalExportTest`。
- **不用 `@Disabled` 或 `assume`**：`verify.sh` 把 `skipped != 0` 判红，跳过等于在门上开口子。

代价（认下来）：导出用例不进回归，意味着"导出代码坏了"要等手动跑才发现。缓解办法是给它的**纯逻辑部分**
配一条不进 `eval` 组的用例（`RetrievalGoldTest`，7 条，不碰库、不碰模型），那部分坏了回归当场红。

## 四、judge 选型（2026-11-20 定：`deepseek-flash`）

结论改了两次，过程与依据都留在这里，避免下次又从头吵一遍：

| 候选 | 单 token 价（元/百万，输入/输出） | 实测 | 结论 |
| --- | --- | --- | --- |
| qwen-turbo | 0.3 / 0.6 | 这个 key **单条请求就 429** | 不可用，不是慢 |
| qwen3.5-flash | 0.24 / 2.4 | 未测 | 输出是大头，反而不划算 |
| qwen-plus | 0.8 / 2.0 | 200 正常 | 兜底 |
| **deepseek-flash** | ≈1.1 / 4.3（$0.15 / $0.60，高峰翻倍） | 200 正常，并发上限 2500 | **采用** |

- **为什么最终选 deepseek-flash**：主链路 `primary=deepseek` 带来的 self-preference 顾虑，比不过
  "这个 key 能跑、充值与配额都在一个后台"这一条硬约束——Ragas 判的是"回答有没有被上下文支持"，
  与模型家族的相关性远低于它与"判不判得动"的相关性。且 flash 是 DeepSeek 最便宜的一档。
- **两个必须写进代码的坑**（都已落地在 `make_judge`）：
  1. **关思考**：`extra_body={"thinking": {"type": "disabled"}}`。默认 `reasoning_effort=high`，
     judge 只是按格式填判断，开着白花推理 token 的钱。
  2. **`max_tokens` 必须显式给 2048**：默认值只够短回答。长回答要拆成十几条主张逐条判，
     JSON 超限被截断 → instructor 抛 `IncompleteOutputException`（它的 `repr` 是空的，
     不打类型名会看到"算不出来："后面什么都没有）。实测同一条用例给了上限就正常出分。
- **一致性校验降级为"NaN 体检"**：原计划是 turbo 与 plus 跑同一批题比 Spearman，turbo 不可用后
  这条没了对照物。改为每次跑完看两件事：**NaN 是否为 0**、**最低三条是否是真缺陷**。
  换 judge 时必须重建基线（不同 judge 的分数不能横向比，Ragas 明确警告）。

## 五、gold 怎么标

每条题标两样，缺一不可：

```yaml
- id: rag-east-return
  question: 华东区无理由退货是几天？
  reference: 华东区自签收之日起 7 天内可无理由退货
  gold_span:
    doc: after-sales.md
    span: "华东区（上海、江苏、浙江、安徽）自签收之日起 7 天内可无理由退货"
```

`gold_span` 在运行时解析成 chunk id（`path#index`）。**绑句子不绑段号**：改 chunk-size、改切块算法，
gold 都不失效。绑段号的话，切块一变整份评估集就悄悄过期，而过期的评估集比没有更糟——它给出可信的假分数。

解析不是朴素的 `contains`，实测逼出来的三条规则：

1. **归一化**：去掉 Markdown 标记与空白。语料里关键句带 `**` 强调，gold 里不写星号。
2. **按标点分片**：`，。；：、！？` 处切开，每片各落一段。切块会在句中断开。
3. **按重合比例判**：一片与一段的**最长连续重合**达到 30% 就算命中。实测 `settlement.md` 被切成
   `…每日万` + `分之五计息…`，**"万分之五"被劈成两半**，整片 `contains` 在两段上都为假。

`reference` 照 span 写，不另编一段话。两处不一致时 judge 会拿编的那段去判，检索反而背锅。

## 六、实测结果（2026-11-20，78 份 / 93 段 / 12 条 gold）

```
   k   Recall   Precision       全召回
   3    0.958       0.333     11/12
   5    0.958       0.200     11/12
   8    0.958       0.125     11/12
```

唯一失信的一条是 `rag-overdue-interest`（跨块 gold，`settlement.md#1` + `#2`）。查导出的 JSONL：

```
gold 段:  ['settlement.md#1', 'settlement.md#2']
检索到:  ['settlement.md#2', …, 'settlement.md#1'(第 9 位), …]
```

**两块都被检索到了，只是其中一块排在第 9 位**，k=8 差一位。这不是"没召回"，是"名次不够"——
而 Ragas 的 ID 型指标是 set-based，给不了名次，这是本票最该记住的缺口。

顺带暴露的产品缺陷（不属本票，另开票）：切块把"万分之五"劈成"每日万"+"分之五"，
全文检索那一路因此拿不到这个词——**关键词精确匹配在这种边界上会静默失效**。

## TDD 用例清单

纯逻辑 7 条（进回归，`RetrievalGoldTest`）与导出 2 条（`@Tag("eval")`，`RetrievalExportTest`）：

| 判什么行为 | 用例名 | 先红的样子 |
| --- | --- | --- |
| span 与文档原句只差 Markdown 强调也能落段 | `RetrievalGoldTest#goldBindsToChunkId` | 归一化不再去 `*` → 落不到任何段 → 抛错 ✅ |
| 同一句在多份文档出现时按 doc 消歧 | `RetrievalGoldTest#docDisambiguatesSharedSpan` | 去掉 `doc.equals` → 期望 `settlement.md#3`，实得 `[archive/…#2, settlement.md#3]` ✅ |
| span 跨两块时两块都算 gold | `RetrievalGoldTest#crossChunkSpanResolvesToBothChunks` | 只按整句 `contains` → 落不到 → 抛错 ✅ |
| 块边界把词劈开时仍能定位两块 | `RetrievalGoldTest#splitWordAcrossChunksStillResolves` | 去掉重合比例、要求整片连续 → 两块都为假 → 抛错 ✅ |
| span 太短当场拒（区分度不足） | `RetrievalGoldTest#tooShortSpanIsRejected` | 去掉最短长度校验 → 不抛，返回空 id ✅ |
| yaml 多写一个键当场拒 | `RetrievalGoldTest#unknownKeyIsRejected` | 不校验未知键 → 敲错字变成什么都不判 ✅ |
| span 落不到时报出哪条 gold、哪份文档、哪句话与该文档各段边界 | `RetrievalGoldTest#missingSpanNamesTheGold` | 去掉 `nearby` → 只剩"落不到"，无从判断是抄错还是切块变了 ✅ |
| 每条 gold 都导出一行，且检索结果非空 | `RetrievalExportTest#everyCaseExportsRetrievedIds` | 导出循环截成 `subList(0,1)` → 行数 1 ≠ 12；不传 `principal` → 12 行全空 ✅ |
| id 与正文等长且同序 | `RetrievalExportTest#idsAlignWithContexts` | 两个列表各自过滤 → 一方跳过空段后错位 ✅ |

验证方式：改坏实现 → 跑 → 看红 → 还原。前两行的红是实测到的（`goldBindsToChunkId` 抛
"IllegalState: span 没有落到任何一段"、`docDisambiguatesSharedSpan` 报
"some elements were not expected: [archive/settlement-2025.md#2]"）。

**复核：2026-11-20 `./scripts/eval.sh ragas` 跑通**（导出 12 条、93 段、Recall@5 0.958、11/12 全召回）；
`RetrievalGoldTest` 7 条单独跑全绿（`./mvnw -o -pl claw4j-eval -am test -Dtest=RetrievalGoldTest`）。

**L2 实测（judge=`deepseek-flash`，12 条，NaN 全 0，整轮 8 分 43 秒含 L1）**：

| 指标 | 均值 | NaN |
| --- | --- | --- |
| faithfulness | 0.946 | 0 |
| context_recall | 1.000 | 0 |
| context_precision | 0.791 | 0 |

读法：`context_recall=1.0` 配 `context_precision=0.791` 是"该给的一段没漏，但夹带了不少无关段"——
与 L1 的 `Precision@8=0.125` 互相印证，指向同一件事：top-k 里噪声多，重排没把 gold 顶到前面。
最低三条落在 `rag-old-warranty(0.74)`、`rag-shipping-hours(0.75)`、`rag-sku-spec(0.86)`：前两条正是
长回答 + 同形状数字那两类，属于"主张多、判断更难"，不是算错。

**`./scripts/verify.sh` 这一轮没跑完**：环境的安全策略拒绝删除 `$TMPDIR` 下的文件，卡在 `mvn clean`
（`Failed to delete claw4j-core/target/surefire-reports/…`），并让 `PersonaCatalogTest` 的 17 条
`@TempDir` 用例报 "Failed to delete temp directory"。这两处都是原有代码，与本次改动无关；
放开安全规则后需重跑一次确认。

## 七、缺口（本票不做，写清去向）

1. **名次质量（MRR / NDCG）**：`IDBasedContextPrecision` 是 set-based、不 rank-aware，所以"排第几位"拿不到。
   实测已经撞上：唯一失信那条的两块都检索到了，一块在第 9 位。要位次得另接 ranx/pytrec，本票不引第二个库。
2. **L2 的 judge 依赖单一供应商**：现锁 `deepseek-flash`，换供应商要走 `EVAL_JUDGE_API_KEY` /
   `EVAL_JUDGE_BASE_URL` 两个环境变量，并且**必须重建基线**（不同 judge 的分数不能横向比）。
   原计划的"turbo vs plus 一致性校验"因 turbo 对该 key 直接 429 而作废，改为"NaN 必须为 0"这条体检。
3. **切块在词中间断开**：`settlement.md` 的"万分之五"变成"每日万"+"分之五"。影响全文检索那一路，
   属产品缺陷，应单独开票（改切块按词边界，或分批修语义切块）。
4. **语义切块整篇降级**：日志里十几份文档报 `batch size is invalid`（qwen embedding 单次上限 10），
   全部退化成"只按转折词切"。既有缺陷，本票只把它暴露出来。
5. **语料扩容**：用户已明确"等流程对齐再扩"。gold 格式已定，扩语料时同步补 gold。
6. **参数对照表（A/B/C/D）**：`--cutoffs` 已经能一次算多个 k，但 hybrid / rerank 的开关对照还要各跑一次导出。

**无需 ADR：** 不改变运行时行为与对外契约（不新增配置键、不改检索逻辑、不改数据库结构），
只新增一个默认排除（`@Tag("eval")`）的导出用例、一个仓库外的手动脚本路径，以及评测数据文件。

## 八、目录整合与「分数好不好」的判定（2026-11-20 增补）

原来三个目录散着（`eval/ragas` 放脚本、`eval/retrieval` 放 gold、`eval/reports` 放产物），
实际是同一件事的三块，收到一处：

```
eval/rag/{README.md, gold.yaml, run_ragas.py, requirements.txt, reports/, .venv/}
```

**`eval/reports/` 没有并进来**，因为它不是 RAG 的：通用评测（`eval/cases/*.yaml` → `EvalRunTest`）
往那儿写，`scripts/eval.sh` 的主路径也从那儿读最新一份摘要（`latest=$(ls -t eval/reports/*.json | head -1)`）。
把 RAG 的产物塞进去，两边会互相踩。所以 RAG 用自己的 `eval/rag/reports/`。

判定与门槛也落在这一轮：

- 门槛写进 `run_ragas.py` 的 `THRESHOLDS`，依据写进 `eval/rag/README.md` 的表。**两处必须一起改**，
  只改一处就会出现"代码说达标、文档说门槛更高"。
- 每次跑完多两样东西：屏幕上的判定表、`reports/report.md`（人读）。后者回答三件事——
  这些数怎么读、不达标怎么查、局限在哪。
- **不达标时脚本退出码为 1**，这样它能被别的东西判，不是只能靠人看屏幕。
- 原来那一列叫 `NaN`，看着像"还有个指标叫 NaN"。改叫 `judge 失败`，并放进判定表：它是体检项，
  不是分数。失败被剔除而非当 0 分算，否则 judge 的问题会被伪装成质量问题。

这一轮还修了一个会说谎的诊断：原来"没召全"报的是这条题**最好**的名次，于是
`rag-overdue-interest`（两块 gold、一块排第 1、另一块排第 9）被写成"最好名次第 1 位"。
改成逐段报名次，现在是「缺 `settlement.md#1`（完整候选里第 9 位）」——这才是能拿去修的话。

**复核：2026-11-20 `./scripts/eval.sh ragas` 全链路跑通**（新目录下导出 12 条 → L1 + L2 → 判定表 → `report.md`）。

## 附：通用评测下线（2026-11-20）

用户裁决：`./scripts/eval.sh` 与 `claw4j-eval` 里跟检索评测无关的部分全部删除，
其他测评以后按本票这套方式重做（Java 只导出 + Python 算分 + 基线对比）。

删的是 `EvalCase` / `EvalScorer` / `EvalVerdict`（main）、`EvalRunTest` / `EvalCaseSetTest` /
`EvalScorerTest`（test）、`eval/cases/*.yaml`（5 份 441 行）、`eval/reports/*.json`（31 份）、
`scripts/eval.sh`。入口收到 `eval/rag/run.sh`，`ragas` 不再经由 `eval.sh`。

一个连带处理：`RetrievalExportTest` 原来借用 `EvalCaseSetTest.repositoryRoot()`，类一删就编译不过，
抽成了 `TestPaths`，标记载体从已删的 `eval/cases` 换成 `eval/rag/gold.yaml`。

A06 那票（通用评测集）的决策没被推翻，只是不再有代码承载；它的用例留在 git 历史里
（`git log -- eval/cases` 能找到删除前那一版）。

**Status:** resolved

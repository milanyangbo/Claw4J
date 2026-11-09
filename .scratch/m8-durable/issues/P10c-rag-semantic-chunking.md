# P10c · 知识库分块：照 LangChain `SemanticChunker` 的句子级语义边界

**Status:** done（2026-11-09 实现；门禁 675 条、失败 0、跳过 0）
**Blocked by:** P10a（已把切块器换成两级；融合与检索没动）
**裁决来源:** **ADR-0028**（`docs/adr/0028-chunking-semantic-sentence-boundaries.md`）。
本票不重新论证"为什么这么切"，只写"照哪份实现、改哪些文件、哪些用例先红、门禁怎么挤"。

## 一句话

**算法抄 LangChain `SemanticChunker`**（句子 → 句子组 → 向量 → 相邻组余弦距离 → 第 95 百分位
断点 → 小块并入相邻块），只做两处本项目的东西：**中文断句正则换值**、**叠一条句首转折词规则**。
字数只在块超 `chunk-size` 时出现一次。

## 一、逐步照抄，不自由发挥

| 步 | LangChain `SemanticChunker` | 本票落地 |
| --- | --- | --- |
| 切句 | `sentence_split_regex`，默认 `(?<=[.?!])\s+` | 同一个参数换值：`(?<=[。！？；…!?;])\s*`（中文没空格，原正则切不动） |
| 分组 | `buffer_size=1`：每句与前后各 1 句拼成一组再 embed（平滑单句噪声） | 照抄 `1` |
| 向量 | 每组一次 embedding | 照抄，`EmbeddingModel#embed(List)` 批量 |
| 距离 | 相邻两组余弦距离 | 照抄 |
| 阈值 | `breakpoint_threshold_type='percentile'`，`breakpoint_threshold_amount` 缺省 **95** | 照抄 95，类型只留 percentile |
| 断点 | 距离 > 阈值处断开 | 照抄 |
| 最小块 | `min_chunk_size` 缺省 **`None`**（不合并） | **照默认不合并**：实测 corpus 每份 92–176 字，按 350 合并会把每份文档并成一块，语义边界一条都留不下 |
| 最大块 | **它不管上限** | 用已有的 `chunk-size`（token 上限）交回 `TokenTextSplitter` 硬切 |

**加的一条（开源没有）**：句子以转折/转场词**开头**时就地断块。

```
转折：然而  但是  不过  可是  另一方面  反过来说  相比之下  与此相反
转场：此外  另外  同时  与此同时  接下来  最后  总之  综上  因此  所以
```

只认句首；句中引用不触发；词表是常量，不进配置（ADR-0028 第三节、第四节）。

## 二、要改的东西

**落点已定（2026-11-09 选 A）**：算法放 `claw4j-core` 的新包，框架壳放 `claw4j-agent`。

| 文件 | 改什么 |
| --- | --- |
| `claw4j-core` 的 `io.github.claw4j.chunk`（新包） | `ChunkPlanner`：切句 / 分组 / 距离断点 / 最小块合并 / 句首转折词判定。**只碰 JDK** |
| `SemanticTextSplitter`（新，`agent.rag`） | `extends TextSplitter`；`splitText` 串起步骤；超长交 `TokenTextSplitter`（token 上限要在壳里，core 不能依赖 jtokkit） |
| `CoreBoundariesTest` | 把随 `text..` 删掉的那条"只碰 JDK"规则换成 `chunk..`；`@AnalyzeClasses` 的包列表同步改（**两处都改**，只改一处规则会匹配零个类然后报绿） |
| `KnowledgeIndexer` | 切块器换成上面那个；启动先读 `index_meta` 那一行身份：一致才逐文件比 hash，不一致就**全量重建**；建完写回 |
| `RagAutoConfiguration#knowledgeIndexer` | 多注入 `EmbeddingModel`（已是 bean，只是没传进来） |
| `V10__rag_index_meta.sql`（新） | 建 `claw4j_rag.index_meta(key, value)` KV 表（照 OpenClaw 的 `memory_index_meta`），**不动 `claw4j_rag.source`** |
| `RagProperties` | **不加**新键（阈值 / 词表 / 最小块都不做配置，见 ADR-0028 第四节） |
| `docs/design/knowledge-retrieval.md` | 第十一节改成"照 LangChain 的形状" |

纯函数接口草案（实现时按这个形状，签名细节以实现为准）：

```java
// claw4j-core：io.github.claw4j.chunk.ChunkPlanner（只碰 JDK）
static List<String> sentences(String text, String splitRegex);     // 换值：中文句末标点
static List<String> groups(List<String> sentences, int bufferSize); // buffer_size=1 → 每 3 句一组
static List<Integer> breakpoints(List<double[]> vectors, double percentile); // 相邻组余弦距离 > 第 95 百分位
static List<Integer> withTransitions(List<Integer> breakpoints, List<String> sentences); // 叠加句首转折词
static List<int[]> blocks(List<Integer> cutIndexes, List<String> sentences, int minChars); // 最小块合并
```

## 三、TDD 用例清单（落地后的实际情况）

原计划 13 条，落地成 **17 条**，分散三个文件。两处与原计划不同，都记在这儿：

- **原第 7 条（最小块合并）删掉**：`min_chunk_size` 照 LangChain 的缺省是 `None`（不合并）。
  加一个"多短算短"的字符阈值，正是这一层想去掉的东西。
- **原第 6 条里"标题之后起新块"撤掉**：算法照 LangChain 之后，标题不是触发条件；标题不单独
  成块是因为它没有句末标点、粘在下一句上（由第 1 条覆盖）。

| 落点 | 用例 | 判什么 |
| --- | --- | --- |
| `ChunkPlannerTest`（core，12 条） | `chineseSentencesSplitOnFullStop` | 中文句末标点能切句（LangChain 的默认正则要求标点后有空格，中文切不动） |
| | `dotsInsideNumbersDoNotSplit` | `v2.0`、`3.5%` 里的点不断句 |
| | `shortFragmentMergesIntoPrevious` | "然而。" 这种碎片并回前一句 |
| | `groupsAreWindowsOfThree` | 分组是 `buffer_size=1` 的窗口，组数 = 句数 |
| | `breakpointsFollowPercentile` | 断点落在距离 > 第 95 百分位处 |
| | `uniformDistancesProduceNoBreak` | 距离全一样时一个断点都没有（同主题不硬切） |
| | `transitionAtSentenceStartStartsNewBlock` | 句首「然而」起新块 |
| | `transitionInsideSentenceDoesNotCut` | 句中引用「然而」不触发 |
| | `blocksSliceByBreaks` | 区间左闭右开，首块从 0、末块到句数 |
| | `blocksWithoutBreaksAreOne` | 没断点就是一整块 |
| | `emptyTextHasNothing` | 空文本切不出句子也切不出块 |
| | `percentileIsLinearlyInterpolated` | 分位数与 numpy 同口径 |
| `KnowledgeRetrievalTest`（agent，4 条新增） | `oversizedBlockIsSplitByTokenLimit` | 块超 `chunk-size` 才硬切（字数只在超限时出现） |
| | `embeddingFailureKeepsTransitionBreaks` | embedding 挂了只丢向量断点，转折词照切 |
| | `mismatchedEmbeddingCountIgnoresVectors` | 向量条数不符时不错位配对 |
| | `fewSentencesSkipEmbedding` | 句子 < 3 时一次 `embed()` 都不调 |
| `SearchSqlTest`（app，真库） | `indexMetaChangeRebuildsEverything` | `index_meta` 身份对不上时即使 hash 全相同也全量重建；且 `source` 表没有新增列 |

**四条反例，都真的改坏过、真的看过红，各自只红一条**（改回后全绿）：

| 改坏的地方 | 只红的用例 |
| --- | --- |
| `startsWithTransition` 从"句首前缀"改成 `sentence.contains(...)` | `transitionInsideSentenceDoesNotCut` |
| 断点判据 `distances[i] > threshold` 改成 `>=` | `uniformDistancesProduceNoBreak` |
| 跳过条件去掉身份判断（退回只比 hash） | `indexMetaChangeRebuildsEverything`（真库） |
| `semanticBreaks` 里把异常抛出去而不是返回空 | `embeddingFailureKeepsTransitionBreaks` |

## 四、门禁怎么挤（**已定 A**，2026-11-09）

**现状（2026-11-09 实测，`scripts/verify.sh` 的口径：`find src/test -name '*.java'` 计数 ≤10）**：

| 模块 | 测试文件 | 上限 | 最满的文件 |
| --- | --- | --- | --- |
| `claw4j-core` | 9 | 10 | —— |
| `claw4j-agent` | **10** | 10 | `KnowledgeRetrievalTest` 20/20、`ChatPipelineTest` 20/20 |
| `claw4j-app` | **10** | 10 | `ApplicationAssemblyTest` 20/20 |

**定的方案：A —— 纯函数落 `claw4j-core`（9/10 的空位）。**

| 落点 | 文件 | 放哪些用例 | 依据 |
| --- | --- | --- | --- |
| `claw4j-core` | `ChunkPlannerTest`（新，用掉最后一个槽 → 10/10） | 1、2（切句）、3（分组）、4（断点）、5、6（转折词）、7（最小块合并），再加空文本 / 单句 / 全是标点三条边界 | 只碰 JDK：`String` 进、`double[]` 进，边界下标出 |
| `claw4j-agent` | `KnowledgeRetrievalTest`（20/20，要先腾 4 位） | 8（超长硬切，要 `TokenTextSplitter`）、9+10 合成一条（embed 抛异常 / 条数不符都只丢向量断点）、11（句子数 < 3 一次都不调 `embed`）、13（出处序号） | 壳才依赖 Spring AI，超长硬切也在壳里 |
| `claw4j-app` | `SearchSqlTest`（16/20） | 12（`index_meta` 身份变更 → 全量重建，真库） | 已有多余方法位，不用动别的文件 |

**`KnowledgeRetrievalTest` 腾 4 位的做法（同主题合并，不是删覆盖）**：

| 现在 | 合并成 | 省 |
| --- | --- | --- |
| `noPrincipalRetrievesNothing` + `withoutDocPermissionNothingIsRetrieved` | 一条"没有身份或没有 `doc` 权限都不检索"（同一个 `permitted()` 判据的两端） | 1 |
| 重排四条（`rerankReordersAndTruncates` / `invalidIndexesAreDroppedNotFatal` / `endpointFailureReturnsCandidatesAsIs` / `timeoutReturnsCandidatesAsIs`） | 两条：正常重排一条；三种失败姿势（越界 / 500 / 超时）一条 | 2 |
| 索引五条（`unchangedFileIsSkipped` / `reindexDeletesBeforeAdding` / `removedFileLeavesNoChunks` / `emptyDirectoryFailsLoudly` / `missingDirectoryFailsAtStartup`） | 四条：把两个"启动失败"合成一条（都是"开着但库是空的"，只差触发点） | 1 |

合并的判据是 `docs/agents/testing.md` 那条：**同一个 setup、同一个关切**才合并；
合并后每条断言仍要能单独失败（不能变成"一条走完整个流程"的巨型用例）。

**没选 B 的理由**：`agent` 那 10 个文件里最小的两个是 `SemanticCacheTest` 11 与
`ScheduleToolsTest` 14，合起来 25 > 20；要合并就得删别处的覆盖，等于拿缓存层的覆盖换切块层。

**落地结果（2026-11-09）**：与计划一致——core 新增 `ChunkPlannerTest`（12 条）用完最后一个槽，
`KnowledgeRetrievalTest` 用三处同主题合并腾出 4 位、放 4 条新用例，`SearchSqlTest` 加 1 条。
实际合并的三处：两条权限 → 一条；RRF 的两条 → 一条；重排的四条 → 两条（正常重排 + 三种失败姿势）。
"两个启动失败合成一条"**没做**——它们一个在索引器、一个在装配层，setup 不同，按
`docs/agents/testing.md` 不该合并。

**不做的事**：不动上限（10/20 是硬约束）、不把用例写成没有 `@Test` 的辅助方法绕计数、
不为腾位去删一条能独立红的用例。

## 五、迁移与日志

**照 OpenClaw `memory_index_meta` 的形状**（`key TEXT PRIMARY KEY, value TEXT NOT NULL`，
一行 key + JSON），**不给文件行加列**——`memory_index_sources` 那边也没有分块版本列：

```sql
-- V10__rag_index_meta.sql
create table if not exists claw4j_rag.index_meta (
    key   text  primary key,
    value text  not null
);
comment on table claw4j_rag.index_meta is
    '索引身份，照 OpenClaw 的 memory_index_meta：一行 key + JSON。切块实现一变，身份对不上就全量重建';
comment on column claw4j_rag.index_meta.value is
    'index_meta_v1 = {"chunkingVersion": 1, "chunkTokens": 800}。只放我们真有的身份，不抄 model/provider';
```

写入值是 `{"chunkingVersion":1,"chunkTokens":<chunk-size>}`（与 `KnowledgeIndexer.identityOf` 一字不差），
key 是 `index_meta_v1`。`chunkTokens` 也进身份，因为**改 `chunk-size` 同样会改变块边界**。
值用 `text` 而不是 `jsonb`：OpenClaw 也是 TEXT，而且只有我们写、我们读，按字符串比较就够——
`jsonb` 读回来的文本形状（空格、键序）不一定与写进去的一致，那会把比较变成看运气。

日志（实际输出，`KnowledgeIndexer`）：

```
知识库同步完成：40 份文档里重建 40 份、跳过 0 份、删掉 0 份，现有 N 段（切块 {"chunkingVersion":1,"chunkTokens":200}，目录 /…）
```

- 升级后首启：`index_meta` 里没有 `index_meta_v1` 这一行 → 按"身份不符"处理 → **40 份全部重建**。
  验收里要看到，否则分不清"重建了"与"根本没跑"。
- 每份文档多一次批量 `embed`（句子组粒度）。40 份 ≈ 40 次；同步只在启动跑一次。

## 六、验收

- [x] 第三节的用例全部先红后绿：core 12 条、agent 新增 4 条、真库 1 条；**四条反例各只红一条**
      （见第三节末那张表），第五处反例是"往 `ChunkPlanner` 里加一个 slf4j 字段"——
      `chunkPlannerTouchesNothingButTheJdk` 立刻红，证明它不是匹配零个类的空规则
- [x] `CoreBoundariesTest` 里 `chunk..` 只碰 JDK 的规则在位，`@AnalyzeClasses` 的包列表同步改了
- [x] 真库：`V10` 跑过，`claw4j_rag.index_meta` 表在；`information_schema.columns` 里
      `claw4j_rag.source` **没有** `%chunk%` 列（`indexMetaChangeRebuildsEverything` 里断言）
- [x] 身份变了 → `indexed = 40`、`skipped = 0`，重建完写回当前身份（同一条用例）
- [x] 在 `rag-corpus` 上量了一次：40 份、**92–176 字**、每份 **4–6 句**、句首转折词 **0 处**。
      结论写进 Comments：这份语料短到几乎触发不了语义断点，`min_chunk_size` 如果按 350 会
      把每份都并成一块——所以取消合并，语义判据靠 unit 用例证明
- [ ] 转折词密集的文档（每三句一个"不过"）不炸、只变碎：**没单独造这份夹具**，
      规则本身由 `transitionAtSentenceStartStartsNewBlock` 与
      `embeddingFailureKeepsTransitionBreaks` 覆盖；"碎"这一面没有端到端证据
- [x] `./scripts/verify.sh` 全绿、跳过 0、覆盖率 ≥60%、文件数 ≤10、单文件 ≤20

## 实现记录（2026-11-09）

| 文件 | 变化 |
| --- | --- |
| `claw4j-core/.../chunk/ChunkPlanner.java`（新） | 切句、`buffer_size` 分组、距离断点、转折词断点、切片。只碰 JDK |
| `claw4j-core/.../chunk/ChunkPlannerTest.java`（新） | 12 条，用 JUnit 自带断言（core 没有 AssertJ，也不为它加一个） |
| `claw4j-agent/.../rag/SemanticTextSplitter.java`（新） | `extends TextSplitter`：embed 句子组 → 调 `ChunkPlanner` → 超长交 `TokenTextSplitter` |
| `KnowledgeIndexer` | 切块器换成上面那个；`identityOf(chunkSize)` + 读/写 `index_meta`；跳过条件加上"身份一致" |
| `RagAutoConfiguration#knowledgeIndexer` | 多注入 `EmbeddingModel` |
| `V10__rag_index_meta.sql`（新） | `claw4j_rag.index_meta(key text primary key, value text)` |
| `CoreBoundariesTest` | 随 `text..` 删掉的那条"只碰 JDK"规则换成 `chunk..` 加回来；`@AnalyzeClasses` 同步 |
| `KnowledgeRetrievalTest` | 三处同主题合并腾 4 位（权限 2→1、RRF 2→1、重排 4→2），新增 4 条切块用例 |
| `SearchSqlTest` | 新增 `indexMetaChangeRebuildsEverything`；`freshTables` 多 truncate 一张 `index_meta` |
| `BuildContractTest` | 迁移清单 `1..9` → `1..10`（新迁移是事实，断言要跟上） |

门禁（最后一轮，反例全部还原后跑的）：**675 条、失败 0、跳过 0**；
core 87.0% / channel 78.6% / capability 85.8% / agent 70.1% / example-sales 93.8% /
example-mcp-server 75.0% / app 80.8% / eval 82.6%；文件数 core 10、agent 10、app 10；
单文件最大 20。

## 七、刻意不做

| 不做 | 理由 |
| --- | --- |
| overlap | LangChain 的 `SemanticChunker` 没有；重叠会让同一段文字进两个块 |
| `number_of_chunks` / `standard_deviation` / `interquartile` / `gradient` | 只留一种阈值类型，多一种就多一个变量 |
| 自定"绝对距离下限" | 上一版 ADR 里那个 0.35 是拍的，已撤；开源没有这个参数 |
| 词表/阈值做成配置 | 对齐 OpenClaw 把 `memory.search.chunking` 做成死键；改就升 `index_meta` 里的版本 |
| 给 `claw4j_rag.source` 加"切块版本"列 | 那是我上一版自己加的形状，OpenClaw 的源头表没有这一列；身份在 `index_meta` 里 |
| 用 LLM 判"这里要不要断" | 每份文档多 N 次模型调用，且不可复现（同一份文档两次切法不同，出处会漂） |
| 分词/断句库（jieba、HanLP、OpenNLP 中文模型） | 词典/模型要单独下载，且分词边界 ≠ 句子边界；标点切句够用 |

## Comments

- 2026-11-09 拆票。用户两次要求：①"除 token 外也要按语义切"；②"基于句子语义差异，转折词或
  话题跃迁触发"；③"最好看有没有开源实现，不要按自己的想法实现"。ADR-0028 已按第②③条重写
  （照 LangChain `SemanticChunker` 的参数与默认值，撤掉我自创的段落级判据与 0.35 绝对下限），
  本票同步重写。
- 编号沿用 `P10a` / `P10b` 口径：能力 P10 的又一次变更，不给新 P 号（P17–P19 已被 M9 占用）。
- **2026-11-09 定下落点：A**（纯函数落 `claw4j-core` 的 `chunk` 包，框架壳放 `agent.rag`）。
  理由是算法只吃字符串与向量、不该知道 Spring AI 存在；代价是 core 多一个包，要能解释
  "为什么分块算法在领域层"，并把随 `text..` 删掉的那条 ArchUnit 规则换成 `chunk..` 加回去。
  第四节写了具体槽位与 `KnowledgeRetrievalTest` 的四处同主题合并。
- **2026-11-09 落地，三处与原计划不同**：
  ① **`min_chunk_size` 取消（照 LangChain 缺省 `None`）**。原计划取 350（框架
  `TokenTextSplitter` 的缺省），但量了语料：40 份文档 **92–176 字**、每份 4–6 句，
  350 会把每份都并成一块，语义边界一条都留不下。取消合并，碎块留着。
  ② **"标题之后起新块"撤掉**：照 LangChain 之后标题不是触发条件；标题不单独成块是因为它
  没有句末标点、粘在下一句上。
  ③ **"两个启动失败的用例合成一条"没做**：一个在索引器、一个在装配层，setup 不同，
  按 `docs/agents/testing.md` 不该合并；腾位改用"重排四条 → 两条"。
- **一个诚实的缺口**：这份 40 份的语料**证明不了语义切块的效果**——每份 4–6 句、句首转折词
  0 处，向量那一路在假 embedding（按域路由）下同域距离恒 0，断点几乎不发生。所以
  "句首转折词起新块""块超上限硬切""降级不丢断点"都只有 unit 证据，没有端到端证据。
  要让真库也能证明，得多一份"长、带转折"的语料（并改掉所有 `40` 的断言）；要不要加，
  是一次独立决定，没在这张票里顺手做。

# 检索评测（`eval/rag`）：为什么这么设计

给**改这个目录的人**看。用户怎么跑、怎么看结果在 `eval/rag/README.md`，
这里只记"为什么这么定"和"踩过什么"。

## 一、门槛为什么分三层

用户问过一次「全召回率 11/12 是满分，需要这么高吗？这是你定的标准还是行业的？」——
答案是**我拍的，而且拍错了**。那次之后才拆成三层，因为混在一起会得出"这分数到底好不好"这种答不出的问题。

| 层 | 用在谁身上 | 为什么 |
| --- | --- | --- |
| **绝对门**（Recall@k ≥0.90、judge 失败 =0） | 确定性指标 | 同样的输入永远同样的分，才配得上一个固定数字。漏一成正确段，模型只能靠常识补，那叫检索不可用 |
| **相对门**（与 `baseline.json` 比，跌超 `0.05` 判回归） | L2 三项 | 要 judge，分数随模型版本与温度漂，绝对线一设就假 |
| **分档**（差/良/好） | L2 三项 | 只帮人读数字，**永不参与判定**。边界来自实践文章，不是规范 |

关键是：**RAG 评测没有公认的绝对门槛**。qaskills.sh 的 2026 RAG QA 指南把"直接抄文档里的门槛"
列为典型错误——*Example thresholds demonstrate syntax. They are not universal acceptance criteria.
Score distributions depend on task, dataset, model, prompt, language, and implementation.*
所有主张 CI 化的来源（gilzor、dataaspirant、aimec）都是同一句：**与基线比，不与绝对目标比**。
progressiverobot 把反面写得很直白：小样本 + 均值门会 *produce false alarms that erode team trust*。

容差取 0.05 是有意取宽的：dataaspirant 用 `TOLERANCE = 0.05` 吸收 judge 噪声，
callsphere 用 `--max_drop 0.02`——那是 150 条样本的噪声水平。我们只有 56 条，噪声比那个规模大，
宁可漏报也别报假警。**别把它调小**，调小之后每次跑都会红。

## 二、Ragas 不给分档（核实过，别再假设）

用户当时的假设是"按道理 Ragas 应该有相关解释"。**没有。** 三处证据：

- faithfulness 页：*It ranges from 0 to 1, with higher scores indicating better consistency.*
- context_recall 页：*values range between 0 and 1, with higher values indicating better performance.*
- 本地 `ragas 0.4.4` 里这几个 metric 的 docstring 只有定义与公式。

整个官方文档没有一句「above 0.8 is good」。它只负责算分，判定好坏一直是使用者的事。

所以 `BANDS` 的边界是从实践文章交叉出来的，取**多数派下限**：

| 指标 | 差 | 良 | 好 | 好线各家怎么写 |
| --- | --- | --- | --- | --- |
| faithfulness | <0.70 | 0.70–0.85 | ≥0.85 | 0.85（benchmarkagents、ai-tldr）／0.90（sapota 通用）／0.92（callsphere）／0.95（合规） |
| context_recall | <0.70 | 0.70–0.80 | ≥0.80 | 0.80（ai-tldr、sapota）／0.85（callsphere）；sapota 另说 <0.70 先修检索 |
| context_precision | <0.60 | 0.60–0.75 | ≥0.75 | 0.70（ai-tldr）／0.75（callsphere、benchmarkagents） |

「差」与「良」的分界主要取自 labhub 那篇——它是唯一直接给四档的（危险 / 可接受 / 好 / 优秀）。
`Recall@k` 没有分档：IR 侧不给通用线，同样 0.9 在小语料是常态、开放域几乎不可能。

## 三、曾经错在哪：「全召回率 = 满分」

原先它是一行判定，门槛写「=满分」。两个问题：

1. **它等价于 `Recall@5 = 1.000`**，等于把主门槛换个说法又设一遍。只要有一条题的 gold
   落在两个块上就必然够不着 → 永远红 → **常红的红灯没有信息**。
2. **代码根本没读那个门槛**：`verdicts()` 里写的是 `hits == cases` 硬编码，
   `THRESHOLDS["all_recalled"]` 是死配置。改常量不生效。
   同期 README 写「11/12 可用」、代码判「不达标」，文档与代码各说一套。

业界处理"均值掩盖单题失败"用的是另外两件事，不是给均值打补丁：

1. **逐题 diff**（已做）：跟基线比哪几条从召全翻成没召全。gilzor：*An unchanged average can hide
   five new failures and five new passes.*
2. **关键子集硬门**（**没做**）：把"答错不可接受"的题单独硬判。gilzor 点名 pricing / legal /
   safety / permissions。现在等于"平均起来还行"就能过。

## 四、judge 的三个必须（少一个就出问题）

1. **`AsyncOpenAI`，不能同步 client**。Ragas 的 `*_ascore` 是协程，同步 client 一调就把事件循环堵住，
   `asyncio.Semaphore` 形同虚设——并发设 4 实际仍是 1 路排队，12 条跑十几分钟。换异步后 8m43s。
2. **`extra_body={"thinking": {"type": "disabled"}}`**。deepseek-flash 默认开思考，judge 干的是
   "照格式填判断"，不需要推理链；关掉省钱也省时间。
3. **`max_tokens=2048` 显式给**。默认值只够短回答，faithfulness 把回答拆成几十条主张后 JSON 会被截断，
   `InstructorRetryException` 抛 `IncompleteOutputException`——它的 `repr` **是空的**，
   不打类型名会看到"算不出来："后面什么都没有。

**L2 只跑三项，没有票 01 里写的 `answer_relevancy`。** 原因不是漏了：那个指标判"回答切不切题"，
而本仓的提示词明确允许"资料无关就按常识答"，于是它把"正确地忽略了不相关资料"判成不切题。
它测的是对话质量，不是检索质量，放进这层会污染判定。

## 五、其他坑

- **导出必须走线上那条链，不要在评测侧自己拼**（2026-11-20 踩到，代价是一次误判）。产品链路是
  `召回 → 融合 → 重排（只换顺序）→ 截断到 top-k`；导出当初只调 `retriever.retrieve()`，量到的是
  "重排前"的 RRF 融合顺序。实测差 **0.072**：不重排 Recall@5=0.848（判不达标），接上重排 0.920
  （达标）。差点因此去改检索参数，实际问题在评测自己。
  现在这条链是 `claw4j-agent` 的 `KnowledgeRetrievalPipeline`——**advisor 拿它当 `documentRetriever`，
  线上每轮对话都从这里过**，导出只是第二个消费者，调 `pipeline.candidates(query)`（重排后、截断前）。
  取候选池而不是 `retrieve()`：截断是保序切前缀，k=5 上的分数一样，而整池才能回答"缺的那段排第几"。
  自己拼一遍的写法以后不要再出现：少一步不报错，只让分数变低。
- **链外的 joiner 必须直通**（`passThroughJoiner`）。`RetrievalAugmentationAdvisor` 在 retriever 之后
  一定会跑一次它自己的 joiner，默认那个按 `score` 倒序；而重排**不改 score**。接错的话重排结果在最后
  一刻被排回去，不报任何错，只是白排一次。`KnowledgeRetrievalTest#rerankOrderSurvivesPassThroughJoiner`
  就是盯这条的。
- **`check_gold.py` 不等于 Java 侧那套判定**。它按整篇逐字包含判，Java 按块算最长公共子串
  （≥ max(6, 30%)）。所以脚本报错的不一定在导出时会失败（span 错一个字，Java 仍可能落到块上），
  而"这段落在哪一块"它根本验不了——那一步要起容器跑真导出。它的定位是省时间的提示，不是门。
- **warning 抑制要赶在 import 之前**。`IDBasedContextRecall` 在 `ragas.metrics` 老 API 里
  （collections 版没有 ID 型），一 import 就抛两条 `DeprecationWarning`。`simplefilter` 写在
  import 之后，声音已经出来了。
- **`--from-score` 必须跳过 Java 导出**。它的语义是"不调模型、零成本重算"，而导出那步要起
  Testcontainers、真检索、真回答。`run.sh` 里为此单判一次参数。
- **judge 失败记 `NaN`，不记 0**。当 0 分算会把 judge 的问题伪装成质量问题。
  而且失败的那几条往往是最难判的长回答、多段上下文——**剩下的均值反而更好看**
  （实测 0.850 > 完整跑的 0.805）。所以有失败时判「存疑」，当前值后面标 `（5/12）`。
- **一块 gold 落在两个 chunk 上时，名次要逐段报**。取"最好名次"会把"另一块排第 9"说成"第 1 位"，
  那是两件完全不同的事。
- **均值会被单条拉偏**：recall=0.5 与 recall=0 差得远，平均值看起来却差不多。
  这就是 `召回完整性` 存在的理由——但它是**诊断列**，不是门。
- **基线有一道地板**：L2 任一项够不到「好」档就拒绝写入。纯相对门的通病是"基线够烂，
  之后怎么跑都稳定"，这道地板防的就是它。

## 六、还没做的

1. **位次指标（MRR@10 / NDCG@10）**。`IDBasedContextPrecision` 是 set-based、不看名次，
   所以"排第几"答不出来——而唯一失分的那条正是名次问题（排第 9，k=8 差一位）。
   接 ranx 或 pytrec-eval，两者都是 Python。
2. **关键子集硬门**（`gold.yaml` 加 `critical: true`）。
3. **多跳题只能标一处证据**（2026-11-20 code review 发现）。`gold_span` 是单个 `doc` + `span`，
   所以「要拼两份文档」的题只标了第一跳；第二跳漏了，Recall@k 照样是 1.0。
   要改的是 schema：`gold_span` 收成列表。改之前，这类题的可信度靠 L2 的 `context_recall` 兜
   （它判的是"要点覆盖"，不看 id）。
4. **参数对照表**：hybrid 开/关 × rerank 开/关 × top-k 3/5/8/15，全部在 L1 跑（零成本）。
   现在只能说"当前配置能用"，说不出"重排值不值得开"。
5. ~~样本量~~ **已做（2026-11-20）**：gold 12 → 56 条，语料 78 → 108 份（新增 30 份，
   其中 10 份是 2.3–5.6K 的长文档，用来测跨块）。
   一条题的权重从 8.3 个百分点降到 1.8。代价是整轮变贵变慢：实测一轮 34 分钟、L2 约 4–5 元。
   加题之前先算这一轮的账。
6. **judge 一致性校验**：10 题双跑，Spearman ≥0.8 且 NaN <5% 才算这个 judge 能用。
7. **切块劈词**（产品缺陷，**要单独开票**）：`settlement.md` 的"万分之五"被块边界劈成
   "每日万"+"分之五"，关键词检索在那条边界上静默失效。

## 七、改这里的规矩

- **改门槛要改两处**：`run_ragas.py` 的 `BANDS` / `GATE_RECALL5` / `TOLERANCE`，
  和 `README.md` 的两张表。只改一处，代码和文档就各说一套——已经发生过一次。
- **换 judge 必须重建基线**。不同 judge 的分数不能横向比。
- **加题之后也必须重建基线**。`baseline.json` 里记着 `cases`，题集一变逐题 diff 直接跳过——
  否则旧基线里根本没有的题会一律被算成"翻红"，看着像回归，实际是换了卷子（2026-11-20 就是这样：
  12 条基线对 56 条新跑，报出四条假翻红）。
- **加题要同步扩语料**，否则是在同样的几十条上反复过拟合。
- 语料在 `examples/knowledge/`（108 份，其中 10 份是 2.3–5.6K 的长文档），
  `RetrievalGoldTest` 只验 gold 能落到 chunk 上，
  不验检索好坏——那是这个目录的事。

## 八、结构

| 文件 | 职责 |
| --- | --- |
| `run.sh` | 唯一入口。加载 `.env` → 查 key → Java 导出 → Python 算分。`--from-score` 跳过导出 |
| `run_ragas.py` | 算分（Ragas）+ 判定（三层门槛）+ 出报告。**指标一律用 Ragas，不自算** |
| `gold.yaml` | 56 条题。绑句子不绑段号：改 chunk-size 不会让它过期；改了文档原文会当场抛错 |
| `baseline.json` | 上次被接受的版本（入库）。与 `reports/` 的区别：一个是标准，一个是产物 |
| `reports/` | `run.jsonl`（Java 导出）/ `score.json`（机器读）/ `report.md`（人读）。不入库 |

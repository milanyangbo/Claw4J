# P10a · 知识库混合检索：全文那一路 + RRF 融合，编排交给官方 advisor

**Status:** done（2026-11-09 实现；本票是把 P10 文末「第二次方案变更」与「实现记录」拆出来的，
见 Comments 第一条）
**Blocked by:** None（P10 已把存储换成框架的 `PgVectorStore`，`claw4j_rag.vector_store` 与
`claw4j_rag.source` 都在）
**裁决来源:** ADR-0020 第三次修订（P10 一节）+ `docs/design/knowledge-retrieval.md`。
本票不重新论证，只把那份裁决落成"改哪些文件、哪些用例先红"。

## 一句话

上一版撤掉关键词通道，代价是 `SKU-2024` 这类编号查不到。这一票把它加回来，**但不造自有 API**：
两路各实现官方的 `DocumentRetriever`，融合实现官方的 `DocumentJoiner`，编排用官方的
`RetrievalAugmentationAdvisor`，`searchDocs` 工具删除。

## 一、五个槽位各归谁

| 能力 | Spring AI 2.0.2 里有什么 | 我们写什么 |
| --- | --- | --- |
| 编排 | `RetrievalAugmentationAdvisor` | 只装配（显式 `order`，排在记忆之后） |
| 向量那一路 | `VectorStoreDocumentRetriever`（唯一实现） | 只装配 |
| 全文那一路 | `DocumentRetriever`（接口，**无**文本实现） | `KeywordDocumentRetriever` |
| 融合 | `DocumentJoiner` + `ConcatenationDocumentJoiner`（按 score 排，两路分数不可比） | `ReciprocalRankFusionJoiner` |
| 重排 | `DocumentPostProcessor`（零实现） | 不在本票，见 **P10b** |

三个"我们写"的都是**实现官方接口**，没有新增一个自有概念——与上一版删掉的 `RankFusion`
（自有类、自有签名、没有调用点）不是一回事。判据见 `docs/agents/alignment.md`。

## 二、三处必须改的官方默认（都是传 builder 参数，不改它的实现）

| 官方默认 | 后果 | 改法 |
| --- | --- | --- |
| 模板把用户消息整个换掉，并要求"只按上下文回答" | 问"你好"也被要求只按资料回答，人格与工具被压住 | 自定义 `promptTemplate`：相关时引用并标出处，无关就忽略、照常回答 |
| `allowEmptyContext` 默认 false，空检索时用"超出知识库"顶掉问题 | 闲聊一律得到"我无法回答" | `allowEmptyContext(true)` |
| `before()` 与检索链**没有 try/catch**（异常表 0 处） | retriever 抛 = 这一轮失败 | 接受（"库挂了"不该伪装成"文档没写"）；但一路挂必须内部吞成空，见第五节 |

鉴权没有放弃：`before()` 第一条指令是 `new HashMap<>(request.context())`，`Principal` 就在
`ContextKeys.PRINCIPAL`，所以权限判在 `HybridDocumentRetriever` 里（`read` + 伪表 `doc`），
没权限返回空列表、走空上下文那条路。

## 三、全文那一路为什么是 `ILIKE`

`pg_trgm` 在本机实测（`pgvector/pgvector:pg16`，2026-11-09）：

| 表达式 | 结果 |
| --- | --- |
| `similarity('退货政策', '…7 天内可无理由退货，这是本区的退货政策。')` | **0** |
| `word_similarity('退货政策', 同上)` | **0** |
| `同上 % '退货政策'` | **false** |
| `同上 ilike '%退货政策%'` | **true** |
| `similarity('SKU-2024', 'SKU-2024 的剩余库存是多少')` | 0.5 |

`similarity()` 的分母是块长，块越长越接近 0；`word_similarity()` 依赖词边界，中文没有空格。
**能用的只有子串匹配**，这与 OpenClaw 的 FTS5 trigram 同形。所以那一路不产分数，只产顺序——
RRF 正好只要顺序。

两条实现约束，都是实测撞出来的：

1. **整句 `ILIKE` 对自然语言问题无效**：「SKU-2024 还剩多少」在文档里不是连续子串，
   整句一条都命中不了。所以抽**纯 ASCII 锚点**（`SKU-2024`、`A-07`）单独查；
   **中文不切分**——切中文就要自己定分词规则，那是自研检索。
2. **通配符必须转义**：`ILIKE` 的 `%` 与 `_` 是通配符，问「折扣率 5% 怎么算」时不转义
   就变成 `ilike '%5%%'`，把「95 折」「5 个点」全捞回来，且不报错。SQL 尾上 `escape '\'`。

## 四、语料 40 份 / 5 个域

`claw4j-app/src/test/resources/rag-corpus/`，`returns / warehouse / pricing / membership / support`
各 8 份。它必须同时具备三样，否则判据不成立：

1. **编号类**（`SKU-2024`、`A-07`、`华东一区`）——只有全文那一路命中；
2. **近义改写**（"买了能退吗" vs "自签收之日起 7 天内可无理由退货"）——只有向量命中；
3. **排序可分**（同问题下 3 段相关、1 段是答案）——重排才有事可做（P10b 用）。

8 份候选太少，"首条是哪份"靠运气也能过；40 份里同域有 8 份近邻、还有 `SKU-2025` 这种
只差一位的干扰项，命中才说明那一路真在工作。清单见设计文档第九节。

## 五、TDD 用例清单

规矩：**先写用例、看它红、再写实现**。每条功能点一行，判的是行为、不是实现。
「反例」指把实现改坏一次，看**只有这一条**红。

| # | 功能点（判行为） | 用例（先写这个） | 先红的样子 | 反例 |
| --- | --- | --- | --- | --- |
| 1 | 编号类查询只有全文那一路找得到，且不把 `SKU-2025` 一起捞回来 | `SearchSqlTest#keywordLaneFindsTheExactCode`（真库） | 去掉锚点抽取、整句查 → 语料里不是连续子串，返回空 | 待验 |
| 2 | 混合之后第 1 条就是那份文档（两路都命中，RRF 顶到最前） | `SearchSqlTest#hybridRanksTheExactDocumentFirst`（真库） | 关掉 `hybrid.enabled` → 首条不是 `warehouse/sku-2024-stock.md`（向量只认"warehouse 这一类"） | 待验 |
| 3 | 近义改写只有向量那一路接得住（字面 0 重合） | `SearchSqlTest#vectorLaneCatchesParaphrase`（真库） | 只留全文那一路 → 「东西买了能退吗」一条都不回来 | 待验 |
| 4 | 通配符被转义：问「5%」回来的每一段都必须真的含 `5%` | `SearchSqlTest#percentSignIsNotAWildcard`（真库） | `escapeLike` 直接返回原文 → pattern 变 `%5%%`，「95 折」也回来 | **实测** |
| 5 | 转义只在一处、转义规则对 `%` `_` `\` 都成立，且 SQL 带 `escape` 与 `limit` | `KnowledgeRetrievalTest#likeWildcardsAreEscaped`、`#keywordSqlEscapesAndBounds` | 同上 | **实测** |
| 6 | RRF 只看名次：两路都命中的块排第一 | `KnowledgeRetrievalTest#bothRoutesBeatSingleRoute` | 换成 `ConcatenationDocumentJoiner`（按 score 排）→ 两路分数不同源，顺序不再是名次 | 待验 |
| 7 | 融合不是交集：只在一路出现的块必须保留 | `KnowledgeRetrievalTest#singleRouteSurvivorsStay` | 改成取两路交集 → 向量空时结果全没了 | 待验 |
| 8 | 同一路里重复出现只算一次名次（否则等于把这一路的分量偷偷加倍） | `KnowledgeRetrievalTest#duplicateInOneRouteCountsOnce` | 按出现次数累加 → 重复项反超 | 待验 |
| 9 | 两路都被调用，结果按 id 去重 | `KnowledgeRetrievalTest#bothRoutesAreCalledAndMerged` | 只调一路 / 不去重 → 同一块出现两次 | 待验 |
| 10 | 向量那一路抛异常：全文照返，不算整轮失败 | `KnowledgeRetrievalTest#vectorFailureFallsBackToKeyword` | 去掉 `attempt()` 的 catch → 一路抖动就是一次对话失败 | 待验 |
| 11 | 两路都抛才抛出去（"没查"不能伪装成"没写"） | `KnowledgeRetrievalTest#bothRoutesFailingThrows` | 改成两路都吞 → 用户以为文档里没写 | 待验 |
| 12 | 没有身份：不检索、也不抛 | `KnowledgeRetrievalTest#noPrincipalRetrievesNothing` | 没有身份也照查 → 越权 | 待验 |
| 13 | 没有 `doc` 读权限：一条都不检索（不是"查到了不给"） | `KnowledgeRetrievalTest#withoutDocPermissionNothingIsRetrieved` | 去掉 `principal.canUse(...)` → 两个替身被调用 | **实测** |
| 14 | advisor 装配出来且 `order` 排在记忆之后 | `KnowledgeRetrievalTest#advisorIsRegisteredAfterMemory` | 改成负数 order → 检索看到的是没有历史的一轮 | 待验 |
| 15 | 开着却没配目录：启动失败并点名 `claw4j.rag.directory` | `KnowledgeRetrievalTest#missingDirectoryFailsAtStartup` | 改成"没目录就空库" → 每次检索都答"没有相关资料" | 待验 |
| 16 | 目录在但没有可索引的文件：同样启动失败 | `KnowledgeRetrievalTest#emptyDirectoryFailsLoudly` | 同上 | 待验 |
| 17 | 索引幂等：内容没变就跳过，第二次不重算 embedding、不清旧块 | `SearchSqlTest#unchangedFileIsSkipped`、`KnowledgeRetrievalTest#unchangedFileIsSkipped` | 每次启动都重建 → 行数翻倍 | 待验 |
| 18 | 表的形状是框架的：`id` 是 uuid、`metadata` 是 json、维度跟配置走 | `SearchSqlTest#theVectorTableIsTheFrameworkShape` | 迁移里自己建表 → 维度与列名两处要同步 | 待验 |

## 六、这一票没守住的三条（缺口，别当成已验）

设计文档第九节的判据表把它们记成"有人守"，实际**没有用例**。拆票时对着测试文件逐个核过：

| 判据 | 现状 | 该长在哪 |
| --- | --- | --- |
| 关掉 `hybrid.enabled` 后首条不再是那份文档（证明首条是全文那一路带来的） | `SearchSqlTest` 里 `search(..., false)` 一次都没调过 | `SearchSqlTest`，加一条即可（真库，语料现成） |
| 闲聊不被劫持：空上下文时用户消息原样交给模型 | `allowEmptyContext(true)` 只有装配代码，没有断言 | `KnowledgeRetrievalTest`，跑一次 advisor 看增强后的消息 |
| 有资料才注入，且格式是 `[n] 路径 第 N 段` | `formatDocuments` 没有直接用例 | 同上（也可以单独测 `formatDocuments`，但那只验了拼串，不验"有没有注入"） |

**这三条写在这一节而不是勾在验收里**：勾了就是"文档承诺比代码走得远"，而这个仓库的第一大类
缺陷正是这个（`docs/agents/review.md`）。

## 七、实现记录

| 文件 | 变化 |
| --- | --- |
| `KeywordDocumentRetriever` | 全文那一路：整句 + 纯 ASCII 锚点，`ILIKE` + 通配符转义 |
| `ReciprocalRankFusionJoiner` | RRF（k=60），按 `Document#getId` 求和去重；**融合分写回 `Document#getScore()`** |
| `TopKDocumentPostProcessor` | 链路里唯一的截断点，`claw4j.rag.top-k` 在这里生效（重排关掉时也在） |
| `HybridDocumentRetriever` | 两路 + 融合 + 从 `Query.context()` 取 `Principal` 判 `read`/`doc` |
| `RagAutoConfiguration` | 注册 `RetrievalAugmentationAdvisor`（自定义模板 + `allowEmptyContext(true)` + `documentFormatter`）；启动建 trigram 索引 |
| `KnowledgeTools` / `KnowledgeBase` / `VectorStoreKnowledgeBase` | **删除**（advisor 每轮检索，留工具就是双份） |
| `V5__rag.sql` | 加 `pg_trgm`（`vector_store` 仍由框架建） |
| `KnowledgeIndexer` | 切块先用框架的 `TokenTextSplitter`（按 token）；**两级切块见 P10c**（ADR-0028） |
| `claw4j-app/src/test/resources/rag-corpus/` | 40 份 / 5 个域 / 约 40 块 |
| `application.yml` / `README` | `claw4j.rag.hybrid.*` 两组键与注释 |

门禁（2026-11-09 实测）：**661 条、失败 0、跳过 0**；agent 69.7% / app 80.8%；
测试文件 10 个/模块、单文件最多 20 条。

## 七之二、code review 修的两条（2026-11-09 深夜，提交后）

两件事都是**装配层的错**，部件各自的用例全绿也照样存在——所以修的时候先补了一条组合用例。

| # | 症状 | 根因（查过字节码，不是推测） | 修法（照谁） |
| --- | --- | --- | --- |
| 1 | `claw4j.rag.top-k` 没有任何读取点：重排开着时注入 10 段、关掉时 30 段，启动日志却一直说"最终取 5 段" | Spring AI 没有"最终取几条"的部件（`DocumentPostProcessor` 在 2.0.2 里零实现），而 `VectorStoreDocumentRetriever#topK` 是**召回**条数 | 新增 `TopKDocumentPostProcessor`，挂在 post-processor 链最后。照 OpenClaw 把 `slice(0, maxResults)` 放在融合与选择之后 |
| 2 | RRF 排出来的顺序被 advisor 里的官方 joiner 重排掉：只有全文命中的块（score 为 null）按 0.0 甩到末尾，同分之间还取决于 `HashMap` 顺序 | 反编译 `ConcatenationDocumentJoiner.join`：`toMap(...).values().stream().sorted(comparingDouble(Document::getScore).reversed())`，null → 0.0 | 融合分写回 `Document#getScore()`（`mutate().score(fused)`）。OpenClaw 的 `mergeHybridResults` 也是把融合分赋给结果的 `score`；Spring AI 的排序信号只有这一个 |

**截断为什么不并进重排**：重排可关（`rerank.enabled=false`），"注入几段"不可选。绑在一起
就会在关掉重排时把两路候选池整个塞进提示词，而那只在关掉开关时出现。重排因此**只换顺序、
不改条数**，`rerank.candidates` 的含义收敛成"送进重排的条数（输入上限）"。

**融合公式仍是 RRF，不照 OpenClaw 的加权 0.7/0.3**：它的关键词分来自数据库 BM25
（`fullText.rank` + trigram 校准），我们的是 `ILIKE` 命中次数（中文拿不到 trigram 分）。
给"次数"编一个归一化系数去凑加权，那个系数就是拍出来的。要改，前提是先让关键词那一路能给分
（`pg_search`/ParadeDB 或中文分词扩展 + 换镜像），那是另一个决定。分歧记在 ADR-0020 第四次修订段。

反例（各只红一条用例）：去掉 `mutate().score(...)` → `fusedOrderSurvivesOfficialJoinerAndTopKCut`
红在"余弦 0.90 的块压过两路都命中的块"；让 `postProcessors` 在重排关掉时返回空列表 →
同一条用例红在"Expected size: 5 but was: 30"。

## 八、验收

- [x] 编号类查询只有全文那一路命中（`keywordLaneFindsTheExactCode`）
- [x] 混合后首条是那份文档（`hybridRanksTheExactDocumentFirst`）
- [x] 近义改写靠向量（`vectorLaneCatchesParaphrase`）
- [x] 通配符转义，结果不膨胀（`percentSignIsNotAWildcard` + 两条替身用例）
- [x] RRF 按名次融合、不取交集、同路重复只算一次（三条）
- [x] 一路挂不拖另一路；两路都挂才抛（两条）
- [x] 权限下沉：没有身份 / 没有 `doc` 读权限都不检索（两条）
- [x] 装配与开关：advisor 排在记忆之后；缺目录、空目录都启动失败
- [x] 索引幂等（`claw4j_rag.source` 的 hash）
- [x] `searchDocs` 工具与它的两张注册表删除，默认部署的工具清单里没有它
- [x] 融合分写进 `Document#getScore()`，过官方 joiner 之后顺序不变（组合用例）
- [x] `top-k` 生效：30 条候选过完链只剩 5 条，重排关掉时也一样（组合用例）
- [x] `./scripts/verify.sh` 全绿、跳过 0、覆盖率 ≥60%
- [ ] 第六节那三条缺口——**未做**，见那一节

## Comments

- 2026-11-09 拆票。用户裁决"要拆票，票里面要包含 TDD 测试的功能点"，于是把 P10 文末
  「第二次方案变更」与「实现记录」两节拆成本票（P10a）与 P10b。**拆分口径**：P10b 能单独关掉、
  单独回退（`claw4j.rag.rerank.enabled=false` 就是"没有重排"），所以它有自己的票；其余全部在这里。
- **编号为什么不往下续**：ADR-0020 的 M9 已经把 **P17–P19** 分给脚本沙箱 / `doctor` / WebSocket，
  而编号口径是"跨批次唯一、不复用"。这次拆出来的两张属于能力 P10 的第三次变更，所以挂回 P10，
  记作 `P10a` / `P10b`——**不是新能力，不给新号**。
- 这票是**实现后补票**：代码先落地（同一天），票后拆。所以第五节记的是"每条判据由哪条用例守、
  反例怎么红"，而不是追认一个没发生过的 TDD 顺序。标 **实测** 的三条是真的改坏过、真的看过红；
  标"待验"的是按断言应当红，动到那段代码时按它验一遍（`docs/agents/issue-tracker.md`：
  没跑过的写成预计）。
- 两条实现中撞到、值得留着的：① 整句 `ILIKE` 对自然语言问题无效，必须抽纯 ASCII 锚点；
  ② `RagProperties` 不能留辅助构造器——record 的规范构造器 + 一个四参构造器会让 Spring 的
  构造器绑定报 "No default constructor found"，整个上下文起不来。
- 降级矩阵的真相与设计文档原来写的有一处差：`pg_trgm` 扩展缺失**不是运行期降级**，
  而是 `V5` 里 `create extension` 直接让迁移失败（启动失败）。运行期只有"某一路抛异常 →
  那一路返回空 + WARN 一行"，没有"扩展缺失就禁用全文那一路"这条路径。
- **2026-11-09 深夜，提交后的 code review 揪出两条**，都已修（细节见第七之二节）：
  `top-k` 没有读取点（截断缺失）与 RRF 顺序被官方 joiner 重排掉（融合分没写回 `score`）。
  两条的共同点是"部件各自有测试、装配起来就错"，所以补的是一条**组合**用例，而不是再加两个单元用例。
  为腾门禁位（agent 10/10 文件、本文件 20/20 用例），把 `singleRouteSurvivorsStay` 并进了
  `rrfRanksByRankNotByRepeats`（同一被测对象、同一主题），条数不变。
- 融合公式与 OpenClaw 的加权有分歧，**没有**改。理由写在第七之二节与 ADR-0020 第四次修订段：
  我们的关键词那一路只有命中次数，没有可比的分数；照抄加权就得先编一个归一化系数。

# 票 07：索引 hybrid——关键字 ∪ 向量（RRF）

**Status:** resolved

## 由来

票 06 修的是"露出多少"，没修"命中不了"。`ChineseToolIndex` 是词面匹配：中文问法切成二元组，
对框架自带英文描述命中数为 0；票 04 靠同义词表给网页那族搭了一座桥（`页/链接/网址/浏览 → fetch,url`），
桥是逐个搭的，不通用。用户 2026-11-26 要求 `index: hybrid` + `hybrid.keyword-weight`。

## 裁决

`docs/adr/0021-explicitly-not-doing.md`（「**领先 2**：工具可见性」）再修订一条：检索索引可插拔，
`hybrid` = 关键字 ∪ 向量按 RRF 融合、权重可配。ADR-0005 已经定了 pgvector，本票复用它，
不引第二个向量库。

## 一个必须先改的现状

`VectorToolIndex` 直接 `vectorStore.add(documents)`，metadata 只有 `sessionId` / `id`，检索也只按
`sessionId` 过滤；而本仓唯一的 `VectorStore` bean 是 `PgVectorStore` 建在 `claw4j_rag.vector_store`
（`RagAutoConfiguration:150`），BM25 全文索引（`ix_rag_vector_store_content_bm25`）也在同一张表上。
工具描述一旦走这个 bean，知识库的向量路与全文路都会把"Glob 是文件匹配工具"召回进业务问答。

所以：

- 工具向量独立落一张表：`claw4j_tool.tool_index`，迁移 `V8__tool_index.sql`。
- **不注册成 `VectorStore` bean**。`RagAutoConfiguration` 那条是 `@ConditionalOnMissingBean(VectorStore.class)`，
  多一个同类型 bean 会因装配顺序让它静默退让，表现是"知识库突然全空"——一个只在重启后才发现、
  日志里没有任何一行提到向量的故障。工具那一路在 `toolIndex` 工厂里现场构造 `PgVectorStore`，
  直接交给 `VectorToolIndex`，不进容器。

这两条是 schema 变更，按 `AGENTS.md`「先问再动」要先拿到裁决。

## 融合怎么算

```yaml
claw4j:
  advisor:
    tool-search:
      index: hybrid          # regex | lucene | vector | hybrid
      hybrid:
        keyword-weight: 0.5  # 0..1；0 = 纯向量，1 = 纯关键字（= 现状，用于对照）
```

- **RRF 而不是加权求和**：两路的分值量纲不同（正则是命中计数，向量是余弦距离），求和要先归一化，
  而归一化系数只能靠调。RRF 只吃名次：`score = w / (k + rank_kw) + (1 - w) / (k + rank_vec)`，
  `k = 60`（Cormack 的常用值）。
- **每路先取 `3 × maxResults`** 再融合取前 `maxResults`：只取 N 的话，融合没有腾挪空间。
- **namespace 过滤在融合之前**：`Hybrid(Namespaced(regex), Namespaced(vector))`，两个内层各自过滤。
  反过来（包在融合外面）会让某个 namespace 的工具先被别的 namespace 顶掉名额，过滤后凑不满一页。
- **不做 rerank**：候选只有 5–20 个；RAG 那套 rerank 要一次模型调用，它的收益来自"几十段文档里的
  长噪声"，工具这边没有那个量级。

## 成本

框架的 `prepareIteration` 已经按 `computeFingerprint(toolReferences)` **只在工具集变化时**重建索引，
不是每轮 embed。沿用这个时机：一次部署 embed 一遍工具集，换了工具再算一次。重启后第一次必重建
（fingerprint 在内存里），接受。

## 改哪

1. `ToolSearchProperties`：`index` 枚举加 `VECTOR` / `HYBRID`；加 `Hybrid(double keywordWeight)`，
   缺省 0.5、越界夹到 `[0,1]`。
2. 新增 `claw4j-agent/toolaccess/HybridToolIndex implements ToolIndex`：两路各查一次（都是本地计算 +
   一次向量查询，不必并行），RRF 融合。
3. 新增 `V8__tool_index.sql`：`create schema if not exists claw4j_tool`、`tool_index`
   （`id uuid` / `content text` / `metadata json` / `embedding vector(1024)`），形状与
   `claw4j_rag.vector_store` 一致；`V4__rag.sql` 的建法与索引照抄。
4. `AgentAutoConfiguration`：`index=vector|hybrid` 时构造工具专用 `PgVectorStore` + `VectorToolIndex`，
   外包 `NamespacedToolIndex`（票 06），两路合成 `HybridToolIndex`。
5. 配置校验：`index=vector|hybrid` 但拿不到 `EmbeddingModel` 或维度对不上 → **启动失败**，不降级
   （与 `claw4j.rag.enabled` 同一口径：开着就必须能用，不给一个"每次都搜不到"的空库）。
6. `application.yml`：加两项与注释；**默认仍是 `regex`**——换默认值要单独量一次，不跟着本票顺手改。

## TDD 用例清单

`claw4j-agent` 的文件数已经到顶（14），`ModelAndToolSelectionTest` 在票 08 之后是 23 条，本票只能再进 3 条
（→26，正好到顶）；`ApplicationAssemblyTest` 25 → 26。**下一张票进来之前必须先拆或先合并**，别再加。

| 判什么行为 | 用例 | 先红的样子 |
| --- | --- | --- |
| 中文说法与英文描述无字面重叠时也命中 | `ModelAndToolSelectionTest#hybridFindsToolWithNoLiteralOverlap` | 纯 regex 索引返回空；一路空手把另一路也吞掉时红 |
| `keyword-weight` 两端各归一端 | `ModelAndToolSelectionTest#keywordWeightDecidesWhoWins` | 权重 1 时向量那一路的候选以 0 分混进结果，断言名单红 |
| 融合按名次而非原始分 | `ModelAndToolSelectionTest#fusionUsesRanksAndGroupsBeforeFusion`（第一段） | 直接相加时一路量纲占满，`[Y,X,Z]` 变 `[X,Y,Z]` |
| namespace 过滤发生在融合之前 | `ModelAndToolSelectionTest#fusionUsesRanksAndGroupsBeforeFusion`（第二段） | 包在融合外面时融合前排全是 `file_*`，收窄完一条不剩（已实测：把 `of` 里的分组去掉，这条立刻红） |
| 权重缺省与越界 | 并入 `ModelAndToolSelectionTest#maxResultsIsClamped` | 缺省落成 0（= 纯向量）而不是 0.5 时红 |
| 四档各建各的、hybrid 两翼各自带分组 | 并入 `ModelAndToolSelectionTest#indexChoiceIsHonoured` | 分组包在融合外面时 `keywordWing()` 不是 `NamespacedToolIndex` |
| 工具向量不进容器也不进知识库那张表 | `ApplicationAssemblyTest#toolVectorsStayOutOfTheKnowledgeTable` | 注册成 bean 时容器里多一个 `VectorStore`；schema 抄成 `claw4j_rag` 时红 |

`fusionUsesRanksAndGroupsBeforeFusion` 里放了两段：同一个入口（`search`）、同一种断言（回来的顺序与名单），
与 `chineseQueryNeedsNoSpaces` 拿一张表跑多行是同一个写法。合在一条是因为方法数到顶了，不是它们是一件事。

## 落地时与票面不同的两处（据实改）

1. **V8 只建 schema，不建表**。票面原写"`tool_index`（`id uuid` / `content` / `metadata` / `embedding vector(1024)`），
   与 `V4__rag.sql` 的建法照抄"——而 `V4__rag.sql` 的实际建法是"**不**建 `vector_store`，交给
   `PgVectorStore` 自己建"，理由写在那个文件开头：建在迁移里就成了"我们定义官方表"，维度与列名要在两处同步。
   `V8__tool_index.sql` 因此只 `create schema if not exists claw4j_tool`，表由 `ToolVectorStore.create`
   里的 `initializeSchema(true)` 建。
2. **`index=vector` 也改成用工具专用表**。票面只说 hybrid 要独立表，但 `vector` 那一档原来的行为是
   把工具向量写进 `claw4j_rag.vector_store`——同一个串味问题。两档一起改，`ToolSearch.index` 的
   `VectorStore` 参数含义随之从"容器里那个"变成"工具专用的那个"。

## 落地之后看到的一件事

同义词表已经把「网页」这一类问法桥接掉了（`页 → fetch,url`，票 04），所以
`hybridFindsToolWithNoLiteralOverlap` 得用「这个地址」这种表里没有的说法才走得到"零字面重叠"——
hybrid 的真实收益边界因此是"同义词表没覆盖的说法"，不是"中文问英文描述"整个面。
表是手写的、会跟着描述漂移，这件事本身就是上向量那一路的理由。


## 真机验证（2026-11-26，本地实例，`index=hybrid`，其余默认：weight 0.5 / max-results 5 / auto）

落表对不对（这三条是本票的结构性判据）：

- 启动后 `claw4j_tool.tool_index` 建出来，32 个工具全部 embed 进表；
- `claw4j_rag.vector_store` 440 行、`metadata->>'toolName' is not null` 的行数为 **0**——工具描述一条都没进知识库那张表；
- 同一句知识库提问仍带出处回答（`[1]` / `[6]`），说明多出来的那个向量库没有把知识库顶掉。

**踩到的坑（值得记）**：`PgVectorStore.builder().build()` 只 `new` 对象，**不调** `afterPropertiesSet`——
建表在后面那一步。容器里的 bean 由 Spring 调，这一个是我们现场 new 的，没人替我们调。第一次启动时
日志照样打出 `Using the vector table name: tool_index. Is empty: false`，而 `claw4j_tool` schema 里
一张表都没有，`select` 直接撞 SQL 错。修法：工厂里显式调一次并把异常包成"表建不出来"。

检索实测（模型把问法改写成「列出目录下的文件」）：

```
关键字  = [github_issue_list, mail_list_unread, Skill]
向量    = [Read, MemoryRename, list_schedules, github_issue_list, MemoryDelete,
           mail_list_unread, Glob, MemoryView, systemStatus, Grep, ...]
融合后  = [github_issue_list, mail_list_unread, Read, MemoryRename, Skill]     ← Glob 第 7，没进前 5
```

模型仍然答"我没有列目录的工具"。两个结论：

1. **关键字那一路的噪声不只占名额，还会被 RRF 加权**：`github_issue_list` / `mail_list_unread` 是靠
   动词「列出」命中的（`ChineseToolIndex` 早就写了这个代价），而它们在向量那一路也排第 4、第 6，
   于是"两路都出现"把两个噪声顶成了第一名。这是 RRF 的固有行为，不是实现错。
2. **纯向量也救不回这一句**：向量那一路 Glob 本来就在第 7。所以这一句缺的不是融合，是
   "一句话英文描述"的 embedding 质量——正好是本票缺口第 1 条（描述中文化是那条的解法）。

因此本票只判"通了"：三档都真的在跑、数据落对表、指标有值、知识库不受影响。判不了"好多少"，
也不据此调权重——一次样本不够，调了就是把噪声记成结论。

附带加了一行 DEBUG（`工具检索 hybrid：query=… 关键字=… 向量=… 融合后=…`）：指标只记个数，
"回来 5 个"与"回来哪 5 个"是两件事，而排障要看后者。票 08 缺口里写的是"要看 query 原文去 DEBUG
日志"，而那时那里其实什么都没打——这一行补的就是那个空。

## 缺口（本票不做）

- **embedding 质量未验证**：`text-embedding-v4` 对"工具名 + 一句英文描述"这种短文本的效果要量。
  真机那一次里 `Glob` 在向量那一路排第 7（问法「列出目录下的文件」），说明这个量法本身就是下一件事。
  效果不好就退回**描述中文化**（装配时把 description 换成"中文一句 + 英文原文"，索引与模型两边
  同时受益）——那是另一种解，不必换索引。
- **关键字那一路的噪声该不该压**：`列出` 这种动词撞出的候选与真命中同权。压它要 IDF 或"命中长度"
  之类的权重，而那需要评测集来判"压对了没有"。与上一条同一批。
- **没有评测集**：`eval/tools`（票 06 缺口）建起来之前，本票只能判"通了"，判不了"好多少"。
- **工具向量表的清理**：沿用框架的 `clearIndex(sessionId)`；进程重启留下的孤儿行暂时不管，
  等 TTL 那套（`SessionExpirySweep`）有了第二类对象再一起想。

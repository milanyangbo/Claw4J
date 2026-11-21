# 知识库检索：混合检索与重排

一句话：**两路召回（向量 + 全文）各用框架的 `DocumentRetriever`，融合用 `DocumentJoiner`，
重排用 `DocumentPostProcessor`** —— 能力是新的，接口全是框架的。

这篇文档只讲"为什么是这个形状"。表结构、索引器与权限那部分在 `P10a` 票面与
`V5__rag.sql` 的注释里，每条判据由哪条用例守在同票的「TDD 用例清单」一节。

## 一、先摆能力边界：框架给了什么、没给什么

2026-11-09 对本地 `2.0.2` 的 jar 做了反编译，不是查文档：

| 类 / 接口 | 在哪个 jar | 官方提供了什么 | 有没有实现 |
| --- | --- | --- | --- |
| `DocumentRetriever` | `spring-ai-rag` | 接口：`List<Document> retrieve(Query)` | **只有一个实现** `VectorStoreDocumentRetriever`（向量路） |
| `DocumentJoiner` | `spring-ai-rag` | 接口：`join(Map<Query, List<List<Document>>>)` | 一个 `ConcatenationDocumentJoiner`（按 id 去重 + 按 score 倒序） |
| `DocumentPostProcessor` | `spring-ai-rag` | 接口：`process(Query, List<Document>)` | **零实现** |
| `RetrievalAugmentationAdvisor` | `spring-ai-rag` | 编排器：transformers → expander → **单个** retriever → joiner → processors → augmenter | 有 |
| rerank | —— | —— | **整个 2.0.2 的 BOM 里没有任何 rerank 模块**（逐个 jar 扫描，0 命中） |

三条结论，直接决定下面的结构：

1. **`documentRetriever` 是单数字段**。`RetrievalAugmentationAdvisor.Builder` 只收一个 retriever，
   所以"两路"不能交给 advisor 去并行，必须在**一个** `DocumentRetriever` 内部完成，或者不经过 advisor。
2. **`DocumentJoiner` 的签名是 `Map<Query, List<List<Document>>>`** —— 一个 query 对**多路**结果。
   这是框架为"同一问题多路召回"留的口子，我们直接用它，不另造融合接口。
3. **rerank 官方没有**。能落的只有一个位置：`DocumentPostProcessor`。

## 二、与 OpenClaw 对齐的三点

### 1. 它有 hybrid，也有降级

`packages/memory-host-sdk/src/host/types.ts:56`：

```ts
searchMode: "hybrid" | "fts-only";
```

也就是说"混合"是它的一等模式，同时保留"没有 embedding provider 时只走全文"的降级。
我们照这个形状：`claw4j.rag.hybrid.enabled` 关掉就退回单路向量（不是退回单路全文——
我们的降级方向与它相反，见第七节）。

### 2. 它的全文索引是 FTS5 trigram，不是分词

`memory-search-schema.ts` / `memory-schema.ts:152`：

```ts
ftsTokenizer?: "unicode61" | "trigram";   // 默认 unicode61，可切 trigram
```

SQLite FTS5 的 trigram tokenizer 做的是**子串匹配**（等价于 `LIKE '%x%'` 能被索引加速），
不是把中文切成词再打分。这一点很关键——它说明"中文全文检索"在 OpenClaw 里
**不依赖分词器**，靠的是三元组子串。

Postgres 上的对应物就是 `pg_trgm` + `GIN (content gin_trgm_ops)`。**但它只能加速 `LIKE`，
不能打分**——见第四节实测。

### 3. 它**有** rerank，用途不同（2026-11-09 更正）

OpenClaw 源码副本（`/tmp/oc-ref-2`）里查得到：

- `extensions/rerank/` 是**随包插件**（默认启用，没配端点时什么都不做）；
- `docs/providers/rerank.md`：把任何 `/rerank` 端点（vLLM / TEI / Infinity / Cohere / Jina / Voyage）
  接成 **Decision model**；响应可以是 Cohere 的 `results[].relevance_score`、Voyage 的 `data[]`、
  TEI 的裸数组，分数必须是 0~1 的概率（越界判 `invalid-response`，它不替你归一化）；
- `docs/plugins/knowledge/memory-provider.md:175`：推荐用 `gte-reranker-modernbert-base`
  当**召回闸门**的判定模型——那个闸门问的是"这一页能不能帮上忙？"。

差别在**用途**：它是一对一的**判定**（这段资料值不值得进上下文），我们做的是列表**重排**
（10 条候选按相关性排序，只换顺序不改条数；截断是链路最后单独一步）。端点形状一样
（`/rerank` + `relevance_score` 0~1），所以这一层是"对齐到端点、不对齐到用法"。

仍然要自写 HTTP，因为 Spring AI 2.0.2 没有 rerank 类型（第六节）——**这一条没有变**。

> 更正记录：早先这里写的是"全仓搜 `rerank` 0 命中"，**是错的**。成因是用了 Bash 里的 toybox
> grep 加 `\|` 分支——它不支持 BRE 的 `\|`，恒返回 0 且不报错（`docs/design/pitfalls.md`
> 第十八条就是这条坑）。换成 ripgrep 复查，`extensions/rerank/`、`docs/providers/rerank.md` 都在。

## 三、结构：用官方的 `RetrievalAugmentationAdvisor`

```
ChatClient.defaultAdvisors(...)
        │
        ▼
RetrievalAugmentationAdvisor                     ← 官方编排器，装了就不再自己串
        │  before(): 取最后一条用户消息 → 检索 → 增强这条消息
        │
        ├─ documentRetriever: KnowledgeRetrievalPipeline   ← 实现官方 DocumentRetriever，整条链
        │      ├─ HybridDocumentRetriever                   ← 两路召回
        │      │      ├─ VectorStoreDocumentRetriever（官方，只装配）
        │      │      ├─ KeywordDocumentRetriever（实现官方接口）
        │      │      └─ 内部用 WeightedScoreJoiner 融合两路（0.7 / 0.3）
        │      ├─ ConcatenationDocumentJoiner（官方）：去重 + 按融合分排序
        │      ├─ RerankDocumentPostProcessor：换顺序，不改条数
        │      └─ TopKDocumentPostProcessor：唯一的截断点
        │
        ├─ documentJoiner: passThroughJoiner               ← 必须直通，理由见第五节
        └─ queryAugmenter: ContextualQueryAugmenter        ← 官方，但要自定义模板（见下）
```

### 为什么四步收进一个类，而不是摊在 builder 上

摊在 `RagAutoConfiguration` 的 builder 链上时，「链路是什么」只对读装配代码的人可见。第二个
想用这条链的人（评测）只能自己拼一遍——它当初只调了 retriever，量到的是重排前的排序，
Recall@5 比线上低 0.07，而报告上完全看不出这件事，只显得检索很差。

所以链路挪进 `KnowledgeRetrievalPipeline`，它实现官方 `DocumentRetriever`：装配侧只把整条链
交给 advisor，评测侧只调 `candidates(query)`（重排后、截断前的候选池，用来回答"缺的那段排第几"）。
顺序只有一处定义，两边量的就是同一个东西。

### 为什么两路必须在 retriever 内部融合

`RetrievalAugmentationAdvisor.Builder` 的 `documentRetriever` 是**单数字段**；而它交给
`DocumentJoiner` 的每个 query 只带一路结果（字节码里 `List.of(retriever 的结果)` 包了一层）。
所以 joiner 的"多路"入口在 advisor 这条路上用不到——多路只能在 `HybridDocumentRetriever`
内部完成。我们仍然用官方 `DocumentJoiner` 接口做融合（`WeightedScoreJoiner`），
只是调用点是 retriever 内部，不是 advisor 传进去的那个。

### 官方 advisor 的三个默认行为，必须改

反编译 `before()` 与 `ContextualQueryAugmenter` 之后，这三条是硬事实：

| 默认行为 | 后果 | 我们怎么改 |
| --- | --- | --- |
| 默认模板把用户消息整个换掉，并要求"只根据上下文回答，不在上下文里就说不知道" | 问"你好"也会被要求"只按资料回答"——通用助手的人格与工具能力被压住 | 传自定义 `promptTemplate`：资料放在 `{context}`，措辞是"相关时引用并标出处，无关就忽略、照常回答" |
| `allowEmptyContext` 默认 **false**：检索为空时直接用 "The user query is outside your knowledge base" 顶掉问题 | 闲聊、寒暄、与文档无关的问题全部得到"我无法回答" | `allowEmptyContext(true)`：空上下文时原样返回 query |
| `before()` 与检索链路**没有任何 try/catch**（字节码异常表 0 处） | retriever 抛异常 = 这一轮对话失败 | 接受：检索失败就该是一轮失败；但重排必须在实现内部吞掉（第六节），否则一次接口抖动就是一次对话失败 |

### `Query.context` 带得动身份，所以权限可以下沉

`before()` 的第一条指令是 `new HashMap<>(request.context())` —— `ChatClientRequest.context()`
被整体复制进 `Query.context()`。我们的 `Principal` 本来就在那里（`ContextKeys.PRINCIPAL`，
`AgentContextAdvisor` 从同一处读），所以 `HybridDocumentRetriever` 能拿到身份。

**它现在只判一件事：这一轮有没有身份。** 有身份就检索，没有就跳过并留一条 WARN。
原来还问"这个身份能不能看伪表 `doc`"，那是业务数据授权，随权限登记表在 ADR-0030 里整块下线；
知识库的细粒度 ACL 属于第二步，记在 ADR 的缺口表与 `guardrails.md` 里。这是**行为放宽**，
`KnowledgeRetrievalTest` 里显式立了 `anyPrincipalCanRetrieve` 一条，别让它悄悄变化。

这也解决了原来"用 advisor 就绕过鉴权"的担心：**判据不在工具层，在 retriever 里**。
没有身份时返回空列表，走 `allowEmptyContext` 那条路，模型照常回答（只是没有资料）。

### 代价：每轮都检索

advisor 在 `before()` 里无条件跑，所以**每一轮提问都会检索一次**（embedding + trigram + rerank），
哪怕这一轮是"帮我发封邮件"。这是官方 advisor 的形态（它假设你在建一个 RAG 应用），
没有"这轮要不要检索"的钩子。

边界是 `claw4j.rag.enabled`：**开着知识库的部署就是"带知识库的助手"**，每轮检索是预期成本；
默认关，所以普通部署不受影响。

### `KnowledgeTools` 删掉

两条路会重复检索（advisor 每轮一次 + 模型主动调工具一次），而且工具返回的"带出处段落"
可以由 `ContextualQueryAugmenter.documentFormatter` 承担。所以：

| 删掉 | 去处 |
| --- | --- |
| `KnowledgeTools.searchDocs` 工具 | advisor 自动检索 |
| 它的业务权限登记（`read` + 伪表 `doc`） | 无。那一层随 ADR-0030 整块下线，retriever 现在只看有没有身份 |
| 它的 `ToolTierRegistry`（`MESSAGING` 档） | 不再受档位管：知识库是 advisor，不是工具 |
| "库里 0 段" 与 "没匹配" 的区分 | 合并成一句；两种都在日志里分得开（WARN 带 `chunks=0`） |

第三行是要留意的：**换档位不再影响知识库**。原来 `minimal` 档看不到 `searchDocs`，
现在只要 `rag.enabled=true`，任何档位都会被注入资料。这一条写进 `application.yml` 的注释。

### 出处靠 `documentFormatter`，不靠工具返回

`ContextualQueryAugmenter.Builder#documentFormatter` 是官方参数。我们把每段渲染成
`[1] 文档路径 第 N 段` + 正文，并要求模型引用时沿用这个格式——与原来工具返回的格式一致。
这样"回答带出处"这条没有丢，只是从"工具的返回值"变成"注入内容的格式"。

## 四、全文那一路：为什么是 `ILIKE` 而不是 `similarity`

`pg_trgm` 装好之后，在本机 `pgvector/pgvector:pg16` 上实测（2026-11-09）：

```sql
select similarity('退货政策', '华东区自签收之日起 7 天内可无理由退货，这是本区的退货政策。');
-- 0

select word_similarity('退货政策', '华东区自签收之日起 7 天内可无理由退货，这是本区的退货政策。');
-- 0

select '华东区自签收之日起 7 天内可无理由退货，这是本区的退货政策。' % '退货政策';
-- false

select '华东区自签收之日起 7 天内可无理由退货，这是本区的退货政策。' ilike '%退货政策%';
-- true

select similarity('SKU-2024', 'SKU-2024 的剩余库存是多少');
-- 0.5

select similarity('退货', '无理由退货');
-- 0.125
```

原因：`similarity()` 是"交集 trigram 数 ÷ 并集 trigram 数"。文档块越长，分母越大，
一段确实包含查询词的文字反而得 0。`word_similarity` 依赖"词"的边界，中文没有空格，
整段是一个词，于是也退化成 0。

**能用的只有子串匹配**：`content ilike '%' || ? || '%'`，由 GIN trgm 索引加速。
这与 FTS5 trigram 的行为正好一致（它也是子串），所以我们不是"发明了一个打分法"，
是**照着它的检索形态在 PG 上找等价物**。

### 词项：整句、型号锚点、中文 bigram

上一版只拿整句去 `ilike`。中文长问题在文档里几乎不会原样出现（「为什么隔壁那家店能退我不能退」
十七个字），于是这条路对绝大多数中文题零命中——`hybrid.enabled=true` 名义上是混合检索，实际只有
向量在干活。那 8 条"三次实验都没进过候选池"的题里，口语题与多跳题的主要嫌疑就是它。

现在一条查询拆成三类词项（`KeywordDocumentRetriever#terms`）：

| 词项 | 取值 | 权重 | 为什么 |
| --- | --- | --- | --- |
| 整句 | 不超过 12 字时才用 | 3 | 短查询本身就是最好的词项；长句加进去只是一条必然不命中的 `or` |
| ASCII 锚点 | 最多 4 个，≥3 字符 | 3 | `SKU-2024` 只有那份文档有，是这一路最该命中的东西 |
| 短型号 | 一个字母 + 1~2 位数字（`A1`、`A2`、`K4`） | 3 | 与锚点同一类，但只有两位，长锚点规则会把它漏掉。**它是补丁不是解**：只对一种写法有效，所以这一路已冻结（ADR-0033） |
| 中文 bigram | 连续汉字串两两切 | 1 | 覆盖度信号，单个 bigram 命中不足以说明相关 |

总数上限 12：SQL 里每个词项是一个 `or` 加一个 `case`，一条长提问能切出几十个 bigram。

**这三个正则、三个上限、两个权重全部走配置**（`claw4j.rag.keyword.substring.*`），代码里没有词表。
理由是它们每一个都替部署方做了领域假设：`≥3 字符`放过 `a`、`1` 这类噪声，但正好漏掉 `A1`/`A2`
这种两个字符的型号；
`12 个字`对中文是一句话，对英文只有一两个词；`3 : 1` 是"字面精确 vs 覆盖度"的比值，换语言要重标。
正则语法错在启动时抛，否则表现是"全文那一路一条都不命中"，不报错。

### 两个引擎：`substring` 与 `bm25`

全文那一路有两个实现，由 `claw4j.rag.keyword.engine` 选：

| 引擎 | 实现 | 要求 | 上面那套词项规则 |
| --- | --- | --- | --- |
| `substring`（默认） | `KeywordDocumentRetriever`：`ilike` + 自拆词项 | 任何 PG | 生效 |
| `bm25` | `Bm25KeywordDocumentRetriever`：ParadeDB `pg_search` + `pdb.jieba` + IDF | 必须换 `paradedb/paradedb` 镜像 | **不生效** |

配置结构上也照这个分：八项挂在 `keyword.substring` 下，与 `engine` 分开。切到 `bm25` 之后那些键
仍能写进配置文件，但谁都不读——"这些值属于哪个引擎"由结构说出来，不靠注释提醒。将来 `bm25`
若有自己的旋钮（分词词典、`k1`/`b`），挂在 `keyword.bm25` 下，两边对称。

为什么要第二个：`substring` 那一路没有分词，"什么算词"只能靠正则猜。语料里多一种编号写法就要多
一条规则（`A1`/`A2` 就是这么来的），而规则没有尽头——ADR-0033 决定这一路冻结，新能力走 BM25。
BM25 把分词、IDF、长度归一交给引擎，`A2`、`AB-12`、中文型号都不需要各自的形状规则。

代价是**换引擎**，不是加参数：镜像、许可证（AGPL-3.0 + 商业双许可）、融合权重重标都要重新判断。
所以默认仍是 `substring`。L1 对照跑过了（2026-11-21，同 68 题）：`bm25` 0.912 对 `substring` 0.919，
**一条原缺题都没救回来**。

那 0.007 不是引擎强弱：把 `short-model-pattern` 摘掉，`substring` 也正好是 0.912，与 `bm25` **逐段相同**
（73/81）。两边会搜的能力一致，差别全在那一条针对性规则上。所以换默认的指标收益是 0，只有"不必再写
形状规则"这一条维护收益。结论与重开条件写在 ADR-0033 的「验证」一节。

**索引是硬依赖**：没有 bm25 索引时查询直接报错（`does not contain a USING paradedb index`），
不是慢一点。所以建索引失败必须**启动失败**，消息里给两条出路（换镜像 / 设回 `substring`）。
这与 trigram 索引"失败只 WARN"刚好相反：那边没有索引只是慢，这边没有索引是不可用。

语法错（`engine: bm2`）也在启动时抛：静默回落成 `substring` 的表现是"改了配置没反应"，
而两条路的部署前提差一个镜像，这种错不能靠运气被人发现。

真容器测试是 `claw4j-app` 的 `Bm25SearchTest`，**默认跳过**：
`-Dclaw4j.test.paradedb=true` 才跑，镜像名走 `-Dclaw4j.test.paradedb.image`（内网填镜像站地址）。
它验的是单测验不了的那一半——分词真的把 `A2` 当成一个词。

### 分数怎么来

子串匹配是布尔，没有分数。三档可选：

| 方案 | 打分 | 结论 |
| --- | --- | --- |
| `similarity()` | 0~1 | 实测对中文长文本恒 0，不可用 |
| **命中词项权重之和** | 整数，融合时归一 | **选它**：`hits` 落进 `Document#getScore()`，融合按"本次最大命中"归一成 0~1（第五节） |
| 只用排名 | 不定义 | 上一版用 RRF 时的做法，第五次修订撤了 |

**不是命中次数**：早先的式子是"出现次数 ÷ 词长"求和，那样一个高频词（某份文档里反复出现"退货"）
出现十次会等于命中十个不同词项——拿词频当相关度。现在每个词项只贡献 0 或它自己的权重。

归一化系数仍是"这一问的最强命中"，所以**两路之间的可比性是个近似**：计数与余弦不是同一个物理量，
0.7/0.3 只是把两者拉到同一区间。要真正可比，得让这一路也由数据库给分（BM25），见上一节。

### SQL

```sql
select id, content, metadata->>'path' as path, metadata->>'section' as section,
       ((case when content ilike ? escape '\' then 3 else 0 end)
      + (case when content ilike ? escape '\' then 1 else 0 end)) as hits
from claw4j_rag.vector_store
where metadata->>'source' = ? and (content ilike ? escape '\' or content ilike ? escape '\')
order by hits desc, (metadata->>'index')::int
limit ?
```

**两处都是带 `%` 的模式串**。`select` 那一处若给裸词项，`ilike` 会退化成精确相等：`where` 命中了、
`score` 恒为 0，融合时全文那一路等于白召回。这个错误不报错，只在融合后表现为"关键词好像没什么用"。

### 通配符必须转义

`ILIKE` 的 `%` 与 `_` 是通配符。用户问「折扣率 5% 怎么算」时，不转义就变成
`ilike '%5%%'`——**匹配所有含 5 的块**，而且不会有任何报错。所以拼参数前把
`\` `%` `_` 三个字符各加一个反斜杠，SQL 尾上 `escape '\'`：

```sql
where content ilike '%' || ? || '%' escape '\'
```

这是这一路唯一一个"错了也不报错、只是结果变多"的地方，必须有专门一条用例
（问一个带 `%` 的问题，结果不膨胀）。

### 索引

`vector_store` 由 `PgVectorStore` 建（`initializeSchema(true)`），所以**迁移里建不出这个索引**
——迁移跑的时候表还不存在。顺序只能是：

1. `V5__rag.sql`：`create extension if not exists pg_trgm;`
2. `RagAutoConfiguration` 的 `ApplicationRunner` 里、**同步之前**：
   `create index if not exists ix_rag_vector_store_content_trgm on claw4j_rag.vector_store using gin (content gin_trgm_ops);`

第 2 步写在 Java 里是不得已：索引属于 schema，但表的所有权在框架手上。
`if not exists` 让它幂等。**退场条件**：框架哪天开放"额外索引"的配置项，这段删掉。

**2 字 bigram 走不了这个索引**：`pg_trgm` 的 GIN 只对 ≥3 字符的模式有效。当前库是几百段，
全表扫描毫秒级；段数上万时这一条会变成瓶颈，那时要换 `pg_bigm`——它的索引支持 2 字，代价是
换数据库镜像（与第四节"它不是 BM25"是同一笔账）。

不加这个索引也**能正确工作**，只是每次全文查询扫全表。所以它是"性能开关"，建失败不该让启动失败
——降级成一行 WARN，检索照跑（见第七节）。

## 五、融合：加权 0.7 / 0.3，融合分写回 `score`，截断在最后一步

`WeightedScoreJoiner` 实现官方 `DocumentJoiner`：

```
score(d) = 0.7 × 向量分(d) + 0.3 × 全文分(d)
全文分(d) = 命中次数(d) / 本次查询的最大命中次数        （0~1）
```

权重是**常量**（`VECTOR_WEIGHT = 0.7` / `TEXT_WEIGHT = 0.3`），不做配置。这与 OpenClaw 一致：
`src/agents/memory-search.ts:165` 里 `hybrid: { vectorWeight: 0.7, textWeight: 0.3 }` 是硬编码，
而它的配置键 `memory.search.query.hybrid.vectorWeight` 已经在死键清单里（撤了）。

**两路的分怎么变成可比的**：向量那一路给的是余弦（0~1）；全文那一路给的是命中次数，融合前按
"这一问的最强命中"归一成 0~1——这是 OpenClaw `createKeywordScoreScale` 的第一步。
**没有照搬它的 `relevance = score / (1 - score)` 反饱和**：那一步为还原 BM25 的饱和曲线，
而计数本来就是线性的，套上去只会把分布压扁。归一之后两路都在 0~1，权重才真的是权重。
这是唯一一处适配，理由是分本身的形状不同，不是"我们觉得这样更好"。

**代价（写在这里，不藏）**：只有全文命中的块封顶 0.3，排序上会输给余弦高于 0.43 的向量命中
（`0.7 × 0.43 ≈ 0.3`）。0.7/0.3 的用途是"编号类查询别被挤出**召回**"，不是"让字面命中排第一"。
召回不受影响——那些块仍在候选里，只是名次靠后；要让它们靠前，改的是权重常量。

**融合分要写回 `Document#getScore()`**，不留在内部 map 里。原因不是偏好，是链路决定的：

- 链里紧接着跑的是官方的 `ConcatenationDocumentJoiner`（`KnowledgeRetrievalPipeline` 装配的
  就是这个），反编译可见它是 `sorted(comparingDouble(Document::getScore).reversed())`，
  分数为 null 时按 `0.0`。不写回的话，融合算得再对也会被它按原分数重排；
  同分之间还会退化成 `HashMap` 的迭代顺序，每次跑都可能不一样。
- Spring AI 的排序信号本来就只有一个：`Document#getScore()`。喂给它融合分，就是照它的契约办事。

**但链外的那个 joiner 必须直通。** `RetrievalAugmentationAdvisor` 在 retriever 之后一定会跑一次
它自己的 joiner，默认也是 `ConcatenationDocumentJoiner`。而重排只换顺序、不改 score——把默认那个
接在链后，重排的结果会在最后一刻被按 score 排回去，**不报任何错**。所以
`RagAutoConfiguration` 传的是 `KnowledgeRetrievalPipeline.passThroughJoiner()`：单查询只有一路结果，
摊平就够，去重与排序在链内已经做过。

**同分定序**：融合分相同（只有全文命中的块常常同为一个命中数）时，按出处路径、段号兜底——
OpenClaw 也是 `rankingScore` 相同后比 path / startLine / endLine。不定序就是不确定。

**与 OpenClaw 对齐的三点**：

| 关注点 | OpenClaw | 我们 |
| --- | --- | --- |
| 权重 | `vectorWeight 0.7` / `textWeight 0.3`，常量 | 两个常量，不加配置 |
| 融合结果的分 | `mergeHybridResults` 给结果赋 `score: rankingScore` | `mutate().score(fused)` |
| 最终截断 | `slice(0, maxResults)` 放在融合与选择**之后** | `TopKDocumentPostProcessor`，链的**最后**一步 |

去重键是 `Document.getId()`：两路查的是同一张表，同一个块在两路命中时 id 相同。
**每一路内部先去重**：同一块在一路里出现两次就给两次权重，等于把这一路的分量偷偷加倍。

### 截断只做一次，且不跟着重排走

`TopKDocumentPostProcessor` 是链路里唯一截断的地方，`claw4j.rag.top-k` 在那里生效。
它单独存在，而不是并进重排：重排可关（`rerank.enabled=false`），"注入几段"不可选。
绑在一起的话，关掉重排就会把两路候选池（默认 30 段）整个塞进提示词，而这只在关掉开关时出现
（`2026-11-09` 修：`top-k` 原先没有任何读取点，重排开着时注入 10 段、关着时 30 段）。

顺序也是它的语义：重排在前面换顺序但不改条数，截断在后面保序地切。两处都不重排第二遍。

### 与 `MultiQueryExpander` 无关

框架的 `MultiQueryExpander` 是"一个问题展开成多个问题，各自检索再合并"，用来提高召回。
我们不做它：每多一个 query 就多一次向量调用，而这一层要解决的是"编号查不到"，
不是"召回不够"。真要做，它是 `RetrievalAugmentationAdvisor` 的 `queryExpander` 槽位，
插进去即可，不动这里。

## 六、重排：专用 reranker，落在 `DocumentPostProcessor`

### 官方没有 rerank，但通义有，且不需要新凭据

Spring AI 2.0.2 的 BOM 里没有任何 rerank 模块，逐个 jar 扫 `rerank` 也是 0 命中——
**连 `RerankModel` 这个接口都不存在**。所以"实现框架的 rerank 接口"这条路不成立，
只能走供应商自己的接口。

通义（我们已经有 `QWEN_API_KEY`）提供 OpenAI 兼容的 rerank 端点：

```
POST {base-url}/reranks
Authorization: Bearer <同一个 key>
{"model": "qwen3-rerank", "query": "...", "documents": ["...", "..."],
 "top_n": 10, "return_documents": false}
```

实测（2026-11-09，真实请求，不是抄文档）：

| 候选 | `relevance_score` |
| --- | --- |
| 华东区自签收之日起 7 天内可无理由退货 | **0.657** |
| 生鲜商品不支持无理由退货 | 0.588 |
| A-07 号仓的作业时间 | 0.281 |

三段都与"退货"沾边，它把**真正讲政策的那段**排到了前面，且分差足够做截断。
`qwen3-rerank` 单请求最多 500 条文档、query 最多 4000 token，我们的 10 条远在限内。

注意端点是 `/compatible-api/v1/reranks`，而 chat/embedding 用的是
`/compatible-mode/v1` ——**不是同一个前缀**，所以 rerank 要单独一个 base-url，
不能从 qwen 那个派生（拼接字符串迟早拼错）。

### 位置与实现

`RerankDocumentPostProcessor implements DocumentPostProcessor`：
取候选池前 `rerank.candidates` 条（默认 10）、调一次端点、按返回的 `index` 重排。
**它只换顺序，不改条数**——"最终留几段"是 `TopKDocumentPostProcessor` 的事（第五节末）。
`return_documents=false`——只要下标与分数，文本我们手上已经有，不必来回传。

HTTP 用 JDK 自带的 `java.net.http.HttpClient`，不用 `RestClient`：`claw4j-agent` 的 classpath
上没有 `spring-web`（依赖树查过），为一个调用引一整个 web 模块不值得，而 Jackson 本来就在。

**这是本项目唯一一处"官方没有、我们直接调 HTTP"的能力**，按 `alignment.md` 记账：
接口位置是框架的（`DocumentPostProcessor`），调用层是自写的——而这不是"我们选了自己写"，
是**官方在这个版本上根本没有 rerank 类型的接口**。退场条件：Spring AI 出 rerank 模块
（或某个 provider 模块带 `RerankModel`）时，换掉这个 HTTP 客户端。

### 端点挂了怎么办

| 情况 | 结果 |
| --- | --- |
| HTTP 4xx / 5xx | 候选**原样返回**，WARN 一行（含状态码，不含 key） |
| 超时（默认 2 秒） | 同上 |
| 响应缺 `results`，或下标越界 / 重复 | 丢弃非法项，剩下的按原顺序补在后面；一条都不合法就原样返回 |
| 没有 `QWEN_API_KEY` | 这一轮**不重排**，DEBUG 一行（默认开，但凭据在调用时取：`SpringSecretResolver` 不缓存，凭据轮换后不必重启。不在启动时禁用它，否则"补上凭据"也要重启才生效） |
| `claw4j.rag.rerank.enabled=false` | 不调端点 |

重排是排序优化，不是检索。它挂掉时结果必须还在——否则一次接口抖动就会让用户得到
"没有查到相关规定"，而那与"文档确实没写"分不开。

### 这个端点给的是什么、不给什么

它是专用 reranker，给的是 **query 与文档的相关性分**，可解释、可截断、可跨候选比较
（同一请求内）。它不判断"这段能不能回答问题"——段落写得含糊、缺前提条件，它照样可能
给高分。所以检索结果仍然要带出处，让用户能回去看原文。

换掉上一版方案（用同一个 chat 模型做 listwise 排序）的原因也在这里：chat 模型自评有
位置偏见与"读起来像答案"的偏好，而专用 reranker 是对着相关性训练出来的。

## 七、代价与降级

| 环节挂了 | 结果 |
| --- | --- |
| embedding 算不出来 | 这一路返回空，全文那一路照跑（`searchMode=fts-only`，与 OpenClaw 同形） |
| 全文 SQL 报错 | 这一路返回空，向量那一路照跑 |
| 两路都空 | 返回空列表 → advisor 走 `allowEmptyContext` 那条路，原样把问题交给模型（不劫持对话） |
| rerank 失败 | 候选原样返回（第六节） |
| `pg_trgm` 索引建不出来 | WARN 一行，查询扫全表，结果不变 |
| `pg_trgm` 扩展装不上 | **不降级**：`V5` 里 `create extension` 直接让迁移失败，进程起不来（"索引"与"扩展"是两件事，见第四节） |
| 没有 `QWEN_API_KEY` | 这一轮不重排（DEBUG 一行），检索与截断照常；向量那一路本来就依赖它，缺 key 时知识库整体起不来（与本次改动无关） |

"返回空"只针对**某一路不可用**，不针对"库连不上"。后者会让 retriever 抛出，而
`RetrievalAugmentationAdvisor.before()` 没有 try/catch（第三节第三条），于是**这一轮对话失败**。
这与工具时代"如实告诉模型检索失败、模型仍能回答"不同，是换成 advisor 的既定代价：
好处是"库挂了"不会被伪装成"文档里没写"，代价是用户看到一次失败回复。

## 八、配置

```yaml
claw4j:
  rag:
    top-k: 10                         # 最终注入几段（TopKDocumentPostProcessor 在最后截断）
    min-similarity: 0.5               # 只作用于向量那一路
    chunk-size: 800                   # token，交给 SemanticTextSplitter
    hybrid:
      enabled: true                   # 关掉 = 只有向量
      candidates-per-route: 25        # 每路召回；融合按 id 去重，池子约等于这个数
    keyword:                          # 全文那一路：先选引擎，再给 substring 专属的规则
      engine: substring               # substring（默认，任何 PG）/ bm25（ParadeDB，要求换镜像）
      substring:                      # 只对 substring 生效；engine=bm25 时这些一个都不读
        anchor-pattern: '[A-Za-z0-9][A-Za-z0-9._-]{2,}'      # 长锚点：≥3 字符
        short-model-pattern: '(?<![A-Za-z0-9])[A-Za-z]\d{1,2}(?![A-Za-z0-9])'   # A1 / A2
        cjk-pattern: '[\u4e00-\u9fff\u3040-\u30ff]+'         # bigram 的切分范围
        max-anchors: 4                # 长短锚点共用
        max-terms: 12                 # 每个词项在 SQL 里占两个占位符
        whole-max-chars: 12           # 汉字口径；英文提问要调大
        whole-weight: 3               # 整句与锚点共用
        bigram-weight: 1
    rerank:
      enabled: true
      candidates: 20                  # 送进重排的条数；必须 > top-k
      base-url: https://dashscope.aliyuncs.com/compatible-api/v1
      model: qwen3-rerank             # 可换：gte-rerank-v2 / bge-reranker-v2-m3
      timeout: 2s
      # key 不在这里：复用 providers 里 name=qwen 那一条的 api-key（secret:qwen_api_key）
    parent:                           # 章节父块回填，默认关
      enabled: false
      max-chars: 2400
    rewrite:                          # 检索前改写，默认关
      enabled: false
      timeout: 3s
      target: 知识库                   # 拼进提示词，换领域改这个词
      prompt:                         # 空 = 内置中文模板；自定义必须含 {query}
    answer:                           # 回答的范围约束，默认开
      enabled: true                   # 关 = 退回旧三条（"多条信息请整合"），做对照用
      instructions:                   # 空 = 内置六条；自定义不能含花括号（占位符）

```

三级比例是量出来的，不是等比放大：**每路 25 → 融合 24.6 → 重排 20 → 截 10**。

- 重排候选必须 **>** top-k。`rerankCandidates()` 取 `max(top-k, candidates)`：两者相等时，重排
  输入 = 最终输出，它从"筛选 + 排序"退化成"只排序"。而 `max` 只保证不退化，不保证有余量——
  要余量得手动把两者分开。
- 每路召回要 ≥ 重排候选。融合按 id 去重，**池子约等于每路条数**（实测每路 25 → 24.6，
  每路 15 → 11.9）。每路 15 配 top-k=10 时池子只有 12 条，重排候选设 20 也取不满。
- 加到 30 没有收益：重排只收 20 条，第 21 条起永远进不了重排。

`rrf-k` 不做成配置：60 是论文默认值，调它没有可依据的目标，多一个旋钮只会让
"结果为什么变了"多一个可能的答案。

**回答范围为什么不靠题集管。** 检索回来的块是 350 字，答案常常只有一句；块一旦被判相关，
模型就有权把整块搬进回答。这一层在 `ContextualQueryAugmenter` 的模板里，`answer` 那两行就是它。
默认措辞不写"最多三句"（行业常见写法），因为分级账期、例外清单这类枚举型答案会被截断；
约束的是"不多写没被问的东西"，并且显式允许"多处资料合并回答"——否则多跳题会被读成"只能引用一段"。
`enabled=false` 保留旧三条，因为这项改动的收益要跟旧版对照才能说清：现有三项指标都量不到它
（无关的两句来自上下文，faithfulness 照样满分），要读数得用 `eval/rag` 的 `--answer-relevancy`。

## 九、语料与判据

### 语料要够，是因为现在的语料证明不了任何一条

上一版只有两段常量（`RETURNS_TEXT` / `FRESH_TEXT`），只能测出"相似度下限生效"。
混合检索与重排要看出效果，语料必须**同时**具备三样，而且量要够：

1. **编号类**：`SKU-2024`、`A-07`、`华东一区` —— 向量认不出，只有全文那一路能命中；
2. **近义改写**：问"东西买了能退吗"，文档写"自签收之日起 7 天内可无理由退货" ——
   只有向量能命中；
3. **排序可分**：同一问题下至少 3 段相关，但只有 1 段是真正的答案 —— 重排才有事可做。

两段语料的时候，"首条是哪份文档"这种断言几乎恒真（一共就两条）。要让它有意义，
语料得像一个真的知识库：**同域里有一堆近邻、有只差一个数字的干扰项、有只讲流程不讲结论的段落**。

所以扩到 **40 份文档 / 约 120 段**，放 `claw4j-app/src/test/resources/rag-corpus/`，
按业务域分五个目录：

| 目录 | 8 份文档覆盖什么 | 编号锚点 | 干扰项 |
| --- | --- | --- | --- |
| `returns/` | 无理由退货、生鲜例外、跨境、发票、时效、流程、售后、争议 | `RT-7`（7 天无理由） | 生鲜"不支持"与政策段结论相反 |
| `warehouse/` | `A-07` 与 `B-12` 作业时间、`SKU-2024` 与 `SKU-2025` 周转、盘点、温控、拣货、退货入库 | `A-07`、`SKU-2024` | `SKU-2025` 只差一个数字 |
| `pricing/` | 华东一区 / 二区、全国口径、折扣审批、返点、促销、价保、价差 | `华东一区` | 全国口径不含分区名 |
| `membership/` | 等级、积分、权益、退订、企业账户、发票抬头、隐私、账户迁移 | `MB-3`（三级会员） | 权益段与积分段都含"会员" |
| `support/` | 工单分级、SLA、升级、值班、复盘、知识沉淀、话术、投诉 | `P1` 工单 | SLA 段与分级段都含"时限" |

每份 2~4 段，短段落也会被 `TokenTextSplitter` 合并，实际块数约 120。

**为什么这个量级才够**：`SearchSqlTest` 里"问 `SKU-2024` 首条是 `sku-2024-stock.md`"这条断言，
在 8 份语料下靠运气也能过（候选少）；40 份里同域有 8 份近邻、还有 `SKU-2025` 这个只差一位的
干扰项，命中才说明全文那一路真的在工作。同理，重排要在 10 条候选里把"讲政策的"从"讲流程的"
里分出来，候选不够就没有可分的。

### 判据

| 能力 | 判据 | 谁守 |
| --- | --- | --- |
| 全文那一路真的起作用 | 问 `SKU-2024 还剩多少`，首条出处是 `sku-2024-stock.md`；关掉 hybrid 后首条不是它 | 前一半 `SearchSqlTest`（真库）；**后一半是缺口**（`search(..., false)` 从没调过），见 P10a 第六节 |
| 向量那一路真的起作用 | 问"东西买了能退吗"，首条是 `returns/east-china.md`（字面不重合） | `SearchSqlTest` |
| 两路结果会合并去重 | 同一块被两路命中时，结果里只出现一次 | `KnowledgeRetrievalTest`（替身） |
| 加权融合真的在加权 | 两路都命中的块（0.7×0.9 + 0.3×1 = 0.93）压过只有向量的同名分数块（0.63）；只有全文的封顶 0.3；同一路重复不加分 | `KnowledgeRetrievalTest#weightedFusionAddsLanesAndCapsTextOnly` |
| 融合分是**下游**的排序信号 | 过了官方 `ConcatenationDocumentJoiner` 之后顺序不变：余弦 0.90 的块不该压过"两路都命中"的块，也不该把只有全文命中的块甩到最后 | `KnowledgeRetrievalTest#fusedOrderSurvivesOfficialJoinerAndTopKCut` |
| `top-k` 真的生效 | 候选过完 post-processor 链只剩 top-k 条；**重排关掉时也一样** | 同上 |
| 重排真的改变顺序 | 替身 reranker 返回倒序，结果顺序跟着变 | `KnowledgeRetrievalTest` |
| 重排结果非法时不丢结果 | 替身返回越界下标 / 空 `results`，结果条数与候选一致、顺序不变 | `KnowledgeRetrievalTest` |
| 某一路挂了不影响另一路 | 全文 SQL 抛异常，向量结果照返 | `KnowledgeRetrievalTest` |
| 两路都挂才报错 | 两路都抛，`search` 抛出去 | `KnowledgeRetrievalTest` |
| 扩展缺失时降级 | **不成立**：`V5` 里 `create extension`，扩展装不上是迁移失败（启动失败），运行期没有这条路径 | 迁移 |
| 通配符被转义 | 问「折扣率 5% 怎么算」，命中的块里必须真的含 `5%` 这两个字符，不是"含 5 的全都回来" | `SearchSqlTest` |
| 闲聊不被劫持 | 问"你好"，回答不是"资料里没有相关内容"——空上下文要原样交给模型 | **缺口**（只有装配代码，无断言），见 P10a 第六节 |
| 有资料时才注入 | 命中文档时用户消息里出现 `[1] 路径 第 N 段`，未命中时用户消息保持原样 | **缺口**（`formatDocuments` 没有直接用例），见 P10a 第六节 |
| 权限下沉到 retriever | 没有 `doc` 读权限的 `Principal`，检索返回空（而不是"查到了但不给"） | `KnowledgeRetrievalTest` |
| 检索失败 = 这一轮失败 | 库连不上时抛出去，不静默返回空 | `KnowledgeRetrievalTest` |
| advisor 排在记忆之后 | 检索看到的是已加载历史的这一轮（顺序写错时 `Query.text()` 仍是最后一条用户消息，但 history 为空） | `KnowledgeRetrievalTest` |

真库那几条需要 `pg_trgm`，`V5` 里会建。测试容器是 `pgvector/pgvector:pg16`，扩展自带。

## 十、刻意不做

| 不做 | 理由 |
| --- | --- |
| `MultiQueryExpander` | 每多一个 query 多一次 embedding 调用；这一层要解决的是"编号查不到"，不是召回不足。它的槽位（`queryExpander`）已经留着，要开只改装配 |
| `RewriteQueryTransformer` 等改写器 | 要额外一次模型调用，而工具参数已经是用户原话；改写收益在这类短查询上不明显 |
| `policy` / `context` 过滤进检索 | 权限已经在工具入口判过（`read` + 伪表 `doc`）；在检索里再判一遍会多一套口径 |
| 分页 | 工具一次返回 top-5，模型不需要翻页 |
| 本地 cross-encoder（onnx） | 通义的 rerank 端点已经够用，本地模型要引运行时 + 下载权重，收益不抵维护成本 |

## 十一、分块：从"按 token 平切"改成"按句子语义断"（见 ADR-0028）

这篇文档前六节讲的是"检索"，分块原来只有一个数字（`chunk-size: 800`，交给
`TokenTextSplitter`）。用户 2026-11-09 要求"除了按 token，也要按语义方式切割"、"基于句子语义
差异、转折词或话题跃迁触发"、"看有没有开源实现、不要自己造"，所以分块单独有了裁决
（**ADR-0028**）与执行票（**P10c**）。

**算法照 LangChain `SemanticChunker`**（`langchain_experimental.text_splitter`，LlamaIndex 的
`SemanticSplitterNodeParser` 同款），参数也照抄：

| 步 | 照谁 | 我们的取值 |
| --- | --- | --- |
| 切句 | `sentence_split_regex`（默认 `(?<=[.?!])\s+`） | **换值**：`(?<=[。！？；…!?;])\s*`（中文没空格，原正则切不动） |
| 分组 | `buffer_size=1`（每句 + 前后各 1 句成组再 embed） | 1 |
| 距离 / 断点 | 相邻组余弦距离 > 第 95 百分位 | 95（`percentile` 是它的默认） |
| 最小块 | `min_chunk_size`（字符） | 350（框架 `TokenTextSplitter` 的缺省值） |
| 最大块 | 它不管 | `chunk-size`（token）交回 `TokenTextSplitter` 硬切 |
| 转折词 | **开源没有**（LangChain / LlamaIndex 都只有向量距离） | 用户规则：句首转折/转场词就地断块 |

**代码落点**（2026-11-09 定）：算法在 `claw4j-core` 的 `io.github.claw4j.chunk`——纯函数、
只碰 JDK（切句 / 分组 / 距离断点 / 最小块合并 / 转折判定）；框架壳是 `claw4j-rag` 的
`SemanticTextSplitter extends TextSplitter`，只有它碰 `EmbeddingModel` 与 `TokenTextSplitter`。

三条容易记错的：
**① 字数不是判据**，只在块超 `chunk-size` 时出现一次；
**② 语义断点只认句首转折词与向量跃迁**，句中引用不触发；
**③ 切块算法变了必须重建**，靠 `claw4j_rag.index_meta` 那一行身份（照 OpenClaw 的
`memory_index_meta`，不是给 `source` 加列），否则库里会混着两种边界的块且没有迹象。

## 十二、召回侧的三个增强（块上下文 / 父块 / 改写）

三个都在"把正确段弄进候选池"这条线上，各自独立可关，默认值不同——原因写在各自的条目里。

### 块上下文：`【文档标题 › 章节路径】` 前置进块文本

语义断点生效后平均 3.9 段/份，一段 350 字的块可能只写"押金在 7 个工作日内原路退回"，
里面没有"备机"、没有"租赁"。同一种说法散在三份文档里时，几块的向量几乎一样，谁进池靠运气。
这就是 Anthropic Contextual Retrieval 的出发点。

做法是零成本版：`MarkdownOutline` 抽出第一个 `#` 标题与所在章节链，拼在块文本最前面
（**拼进 content 本身**，向量与提示词两侧都要）；章节链同时落进 metadata 的 `section`。
Anthropic 那套用 LLM 生成 50–100 token 的具体上下文（约 $1.02/百万 token），这里没做。

两个实现细节容易踩：块文本经过切块器拼接后换行被删、**不是原文的连续子串**，按位置定位会失败
（短文档被切成一块时必失败），所以定位有两级——先看块里出现了哪个章节标题的文字，再回退到位置。
`CHUNKING_VERSION` 因此从 1 提到 2，升级后第一次启动会全量重建。

### 父块回填：`SectionExpander`，默认关

小块召回准、上下文碎：命中一段，适用范围与例外都在兄弟块里。打开后，重排后的候选按
`(path, section)` 聚合，命中的子块被"同章节全部块"拼成的父块替代（small-to-big）。

三条约束：父块超过 `max-chars`（默认 2400）不聚合；只有同章节命中 ≥2 块才聚合（单块命中时
父块只是变长）；父块在重排**之后**生成（先按子块排序，否则分数会被章节里无关部分拉平）。

评测口径要跟着改：父块的 metadata 带 `covered`（覆盖的子块号），导出时展开成子块 id——
否则 gold 的 reference 是子块、返回列表里只有父块 id，"其实答上了"会被记成没召回。

默认关的理由：它用 token 换上下文完整度，段数变少而每段变长，收益要用开/关对照证明。

### 查询改写：`GuardedQueryRewriter` + 框架的 `RewriteQueryTransformer`，默认关

文档写"无书面约定时按下列顺序处理"，用户问"没白纸黑字写能退吗"——两个文本没有一个共同词，
向量与 bigram 都救不了。改写把问题换成"文档里会怎么写"的一句话，只用于检索，不进提示词。

**改写本身交给框架**：Spring AI 2.0.2 自带 `RewriteQueryTransformer`，提示词
（`promptTemplate`）与目标检索系统（`targetSearchSystem`）都能换，输出为空时已经回落原 query。
自己再写一遍"拼 Prompt、取 content"只是把它重新实现一次，还多一处要跟着框架升级的地方。

本仓只补它没有的四件事（`GuardedQueryRewriter`）：

| 补的 | 为什么框架不够 |
| --- | --- |
| **超时** | `ChatClient.call()` 会一直等；改写挂在检索前，用户等的是首字 |
| **跳过** | 精确值问题改写了必然坏事，知道就别发请求（省一次调用） |
| **校验** | 模型把型号改掉时，检索会跑到另一款产品的文档上，而且从结果看不出来 |
| **清洗** | 模型常顺手加解释，或照着示例回「改写：xxx」，那些字进了检索就是噪声词 |

四者都是"改写失败"的形态，统一行为：**拿原问题继续检索**。改写是增强，不是前提——让
"改写没成"变成"这一轮检索不到"，是把一个可选优化做成了单点故障。

**精确值保护是两条正则，由配置给**（`PatternGuard`，接口是 `RewriteGuard`）：

| 配置键 | 作用 |
| --- | --- |
| `rewrite.skip-pattern` | 命中就跳过改写，一次模型都不调 |
| `rewrite.keep-pattern` | 改写结果必须原样保留原问题里匹配这里的片段，丢一个就回落 |

**代码里没有词表**。中文的金额单位（元/天/个工作日）、型号写法（`SKU-2024`、`A2`）、标准号
（`ISO 9001`）全在 `application.yml` 里——一个知识库是售后合同，另一个是医疗指南或英文手册，
单位表不可能共用一份；把任何一份写进代码，它就只对那一个领域成立，而这个类会被所有领域引用。

两道闸的分工：

- **跳过**只该拦会被 HyDE 近似化的量（金额、比例、时限、数量），因为改写会把「万分之五」改成
  近似值，而这类题考的就是精确值。
- **校验**是事后闸：`ISO 9001` 与 `SKU-2024` 在字面形态上无法区分，但校验能分开——它们都只是
  "必须原样保留的 token"。

第二道更关键：**保护从"猜哪些该跳过"变成"检查改写结果"**。前者靠正则猜，漏一个就放一个精确值
过去；后者是确定性的。

两条都可以为空，空 = 不做那一道检查（英文手册、纯代码库可以两条都不用）。正则由装配处编译：
**语法错在启动时就抛**，不会等到第一次提问才暴露；语义写错（漏了"小时"这个单位）没法自动检测，
只能靠对照实验发现——这是"配置优先"的必然代价，认下来。

实测（68 条）：跳过 17 条、改写 51 条（收窄前是跳过 24、改写 44）。

**但默认值仍是关**，理由很硬：改写开/关的 Recall@10 是 0.919 / 0.926，差 0.007；而同为"改写开"的
两次跑之间也能差 0.008。68 条里一条 = 1.5 个百分点，**改写的影响落在测量噪声里**——当前样本证明
不了它有效。要证明，得先把评测集扩到 200 条以上。在那之前开着它，等于用每条提问 1–3 秒的首字
延迟，去换一个量不出来的收益。

顺带一个反直觉的观察：把 7 条标识型题放开改写之后，其中 6 条在改写**关**时本来就召全了
（型号锚点 + bigram 够用）。也就是说"含数字就跳过"这条规则误伤的题，大多本来就不需要改写——
修它是为了逻辑对，不是为了分数。

**提示词与目标系统走配置**（`claw4j.rag.rewrite.target` / `.prompt`）：内置模板不写「内部知识库」、
也不带领域例子（原来写的是「退货」），领域信息通过 `{target}` 传进来。自定义模板必须含 `{query}`，
否则启动直接失败——缺占位符会让改写把提示词本身当查询发出去，而模型照样回一段话，这种错不会自己暴露。

挂载点有两处且必须是同一个 bean：线上由 `RetrievalAugmentationAdvisor#queryTransformers` 调用，
评测导出绕过 advisor 直接调检索链路，所以自己也要先 transform 一次。只挂一处的话，
线上改了、评测量不到，两条路静默分叉。

## 十三、已知局限（不打算修，写清性质）

### 多跳题：中间证据单看与问题不相似

三条：`multihop-tradein-a1`（价格表块第 14 位）、`discount-rebate`、`multihop-price-protect`
（后两条那块**根本没进池**）。

问题不是链路漏了，而是"以旧换新抵多少钱"与"整机价格表"之间没有字面或语义上的相似性——
中间证据要靠推理连起来，而检索做的是相似性匹配。行业解法是查询分解（拆子问题分别检索再合并），
代价是每条提问多 2–4 次模型调用加一套合并逻辑。

**不做**：收益 3/68 = 4.4%，而 Recall@10 已 0.919（门 0.90）；且其中两条是召回侧就没捞到，
分解后仍要去查那张表，救不了。真要提 Recall 到 0.95+ 再谈。

### 表格块的向量天然弱

`price-list-2026.md#1` 是 markdown 表格，在向量空间里与"价格""多少钱"这类自然语言问题的距离
比正文块远。上面三条多跳题里两条缺的都是它。

**没做**：表格转文本嵌入（索引时把表行转成「A1：单价 3999 元」的键值句再嵌入，返回时仍给原表格）。
只动索引侧、可开关，但块文本一变就要 `CHUNKING_VERSION` +1、全量重建一次，属于下一轮的活。

### `context_precision` 评的是整池，不是模型看到的那几段

Ragas 的 `LLMContextPrecisionWithReference` 逐段判，而导出给它的列表是**完整候选池**（约 20 段），
模型真正读的只有 top-10。所以这一项在评价"20 段里夹带多少噪声"，其中一半模型根本没看。

同口径下（只喂前 10 段）会更高。要改就得让导出只带 top-k 给 L2，但那就不再反映"池子里夹带多少"——
得先想清楚要测哪个问题，所以先记着。

### `pg_trgm` 索引对 2 字模式无效

`pg_trgm` 的 GIN 只对 ≥3 字符的模式有效，而中文 bigram 全是 2 字。当前几百段全表扫描是毫秒级，
段数上万时会变成瓶颈，那时要换 `pg_bigm`（索引支持 2 字）——代价是换数据库镜像。

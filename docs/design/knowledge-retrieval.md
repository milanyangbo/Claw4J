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
（10 条候选按相关性重排、截到 topK）。端点形状一样（`/rerank` + `relevance_score` 0~1），
所以这一层是"对齐到端点、不对齐到用法"。

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
        ├─ documentRetriever: HybridDocumentRetriever      ← 实现官方 DocumentRetriever
        │      ├─ VectorStoreDocumentRetriever（官方，只装配）
        │      └─ KeywordDocumentRetriever（实现官方接口）
        │      └─ 内部用 ReciprocalRankFusionJoiner 融合两路
        │
        ├─ documentJoiner: ConcatenationDocumentJoiner     ← 官方默认，去重 + 排序
        ├─ documentPostProcessors: RerankDocumentPostProcessor  ← 实现官方 DocumentPostProcessor
        └─ queryAugmenter: ContextualQueryAugmenter        ← 官方，但要自定义模板（见下）
```

### 为什么两路必须在 retriever 内部融合

`RetrievalAugmentationAdvisor.Builder` 的 `documentRetriever` 是**单数字段**；而它交给
`DocumentJoiner` 的每个 query 只带一路结果（字节码里 `List.of(retriever 的结果)` 包了一层）。
所以 joiner 的"多路"入口在 advisor 这条路上用不到——多路只能在 `HybridDocumentRetriever`
内部完成。我们仍然用官方 `DocumentJoiner` 接口做融合（`ReciprocalRankFusionJoiner`），
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
`AuthorizationAdvisor` 与 `AgentContextAdvisor` 都从同一处读），所以
`HybridDocumentRetriever` 能拿到身份，按 `read` + 伪表 `doc` 判一次，再决定检不检。

这也解决了原来"用 advisor 就绕过鉴权"的担心：**鉴权不在工具层，在 retriever 里**。
没有权限时返回空列表，走 `allowEmptyContext` 那条路，模型照常回答（只是没有资料）。

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
| 它的 `ToolPermissionRegistry`（`read` + `doc`） | retriever 内部判（同一个 `Principal.canRead`） |
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

### 分数怎么来

子串匹配是布尔，没有分数。三档可选：

| 方案 | 打分 | 问题 |
| --- | --- | --- |
| `similarity()` | 0~1 | 实测对中文长文本恒 0，不可用 |
| 命中次数 | 整数，可归一 | 需要自己定义归一化，等于自造打分法 |
| **只用排名** | 不定义 | RRF 只需要每路的顺序，见第五节 |

选第三个：**关键词那一路按"命中次数降序、同一文档按块号升序"排序，把分数留空**。
RRF 不读分数，所以"余弦 0.93 与命中 2 次谁更高"这个不可比的问题根本不会出现。

### SQL

```sql
select id, content, metadata
from claw4j_rag.vector_store
where metadata->>'source' = ?
  and content ilike '%' || ? || '%'
order by (length(content) - length(replace(lower(content), lower(?), ''))) / length(?) desc,
         (metadata->>'index')::int
limit ?
```

三个 `?` 是同一个查询词：`ilike` 一次、计数两次（`length` 与 `replace`）。
`lower()` 两侧都做：`ilike` 本身不区分大小写，但 `replace` 区分——不统一就会把
`SKU-2024` 与 `sku-2024` 算成两种命中。

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

不加这个索引也**能正确工作**，只是每次全文查询扫全表。所以它是"性能开关"，建失败不该让启动失败
——降级成一行 WARN，检索照跑（见第七节）。

## 五、融合：RRF，只看排名

`ReciprocalRankFusionJoiner` 实现官方 `DocumentJoiner`：

```
score(d) = Σ_route  1 / (k + rank_route(d))     k = 60，论文默认值
```

为什么不是官方的 `ConcatenationDocumentJoiner`：它按 `Document.getScore()` 倒序，
而两路分数不同源（余弦 vs 空）。用它会得到"向量那一路永远排在前面"的稳定错误——
且错得很安静，因为结果看起来总是有条理的。

为什么不是加权求和：要加权就得先把两路分数归一到同一量纲，而"归一到多少"又是一个
没有依据的常数。RRF 只要求每路给得出**顺序**，这是两路都天然有的东西。

去重键是 `Document.getId()`：两路查的是同一张表，同一个块在两路命中时 id 相同。
RRF 的求和本身就能合并，不需要额外的去重步骤。

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
输入候选（默认 10 条）、调一次端点、按返回的 `index` 重排、截到 `topK`。
`return_documents=false`——只要下标与分数，文本我们手上已经有，不必来回传。

HTTP 用 Spring 自带的 `RestClient`，不引 DashScope 的 Java SDK：那会带进一整套阿里云
依赖，而这里只有一个端点、一个请求体。

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
| 没有 `QWEN_API_KEY` | rerank 禁用并 INFO 一行；向量那一路本来就依赖它，缺 key 时知识库整体起不来 |
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
| 没有 `QWEN_API_KEY` | rerank 禁用并 INFO 一行；向量那一路本来就依赖它，缺 key 时知识库整体起不来（与本次改动无关） |

"返回空"只针对**某一路不可用**，不针对"库连不上"。后者会让 retriever 抛出，而
`RetrievalAugmentationAdvisor.before()` 没有 try/catch（第三节第三条），于是**这一轮对话失败**。
这与工具时代"如实告诉模型检索失败、模型仍能回答"不同，是换成 advisor 的既定代价：
好处是"库挂了"不会被伪装成"文档里没写"，代价是用户看到一次失败回复。

## 八、配置

```yaml
claw4j:
  rag:
    top-k: 5                          # 最终返回几段
    min-similarity: 0.5               # 只作用于向量那一路
    chunk-size: 800                   # token，交给 TokenTextSplitter
    hybrid:
      enabled: true                   # 关掉 = 只有向量，等价于上一版行为
      candidates-per-route: 15        # 每路各召回多少，再融合
    rerank:
      enabled: true
      candidates: 10                  # 送进重排的条数（必须 ≥ top-k）
      base-url: https://dashscope.aliyuncs.com/compatible-api/v1
      model: qwen3-rerank
      timeout: 2s
      # key 不在这里：复用 claw4j.model.qwen.api-key（secret:qwen_api_key），不引第二把钥匙
```

`rrf-k` 不做成配置：60 是论文默认值，调它没有可依据的目标，多一个旋钮只会让
"结果为什么变了"多一个可能的答案。

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
| RRF 真的按排名融合 | 构造"向量第 3、全文第 1"的块，融合后应排在"两路都第 2"的块之前 | `KnowledgeRetrievalTest` |
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
只碰 JDK（切句 / 分组 / 距离断点 / 最小块合并 / 转折判定）；框架壳是 `agent.rag` 的
`SemanticTextSplitter extends TextSplitter`，只有它碰 `EmbeddingModel` 与 `TokenTextSplitter`。

三条容易记错的：
**① 字数不是判据**，只在块超 `chunk-size` 时出现一次；
**② 语义断点只认句首转折词与向量跃迁**，句中引用不触发；
**③ 切块算法变了必须重建**，靠 `claw4j_rag.index_meta` 那一行身份（照 OpenClaw 的
`memory_index_meta`，不是给 `source` 加列），否则库里会混着两种边界的块且没有迹象。

# 票 15：全文那一路加 BM25 引擎（双引擎，配置切换）

状态：**已实施（2026-11-21）**，默认仍是 `substring`，没有换默认。关联 ADR-0033。

实施记录与偏差见文末「六、实施结果」。

## 一、目标

把「全文那一路用什么引擎」变成配置，而不是继续往 `KeywordDocumentRetriever` 的形状规则上加东西。

- `claw4j.rag.keyword.engine = substring`（默认，现状）
- `claw4j.rag.keyword.engine = bm25`（ParadeDB `pg_search` + `pdb.jieba` 分词 + IDF）

`substring` 冻结：本票不动它的任何规则，之后也不再动。

## 二、范围（实施时）

| # | 改什么 | 要点 |
| --- | --- | --- |
| 1 | `RagProperties.Keyword` 加 `engine` | 值只认 `substring` / `bm25`；拼错（`bm2`）启动即抛，不许静默回落——静默回落的表现是「改了配置没反应」 |
| 2 | 新增 `Bm25KeywordDocumentRetriever` | 一条 SQL，见下；空查询返回空列表，不抛 |
| 3 | `RagAutoConfiguration` 按 engine 选实现 | `hybrid.enabled=false` 仍然谁都不装 |
| 4 | 建索引 + 失败即启动失败 | `create extension if not exists pg_search` + `create index if not exists ... using bm25`。**与 trigram 不同**：这里失败必须抛，因为没索引时查询直接报错（见三.3） |
| 5 | `application.yml` 注释 | 写明八项规则只对 `substring` 生效 |
| 6 | 集成测试（真容器） | 默认跳过，`-Dclaw4j.test.paradedb=true` 触发；镜像名也走系统属性，避免写死镜像站 |
| 7 | 文档 | `docs/design/knowledge-retrieval.md` 词项一节补「两个引擎」；README-QA 本轮不改（还没有分数） |

查询（已在真容器里跑通，不是草案）：

```sql
select id, content, metadata->>'path' as path, metadata->>'source' as source,
       metadata->>'index' as ordinal, metadata->>'content_hash' as hash,
       metadata->>'section' as section,
       pdb.score(id) as hits
from claw4j_rag.vector_store
where metadata->>'source' = ? and content @@@ pdb.match(?)
order by hits desc
limit ?
```

## 三、已验证的事实（2026-11-21，`paradedb/paradedb:latest` / pg_search 0.27.0 / PG 18.7）

1. 建索引的写法成立：`using bm25 (id, (content::pdb.jieba)) with (key_field = 'id')`，`id` 是 uuid 也没问题。
2. `pdb.match($1)` 能参数化——不用拼 SQL、不用手写 Lucene 语法转义。
3. **没有 BM25 索引时查询报错**：`ERROR: test_chunks does not contain a USING paradedb index`。所以索引是硬依赖，不是性能优化 → 建索引失败要启动失败。
4. 分数无上界（实测 0.184 ~ 1.208）。融合层 `WeightedScoreJoiner` 按本次最大值归一，**融合不用改**。
5. `A2 买一年延保要多少钱` 在三条样例块上，价格表（含「A2 单价 3999」）排第一（1.208），延保政策第二（1.193）。`substring` 引擎为这件事专门加过一条形状规则——**正是本票要避免的模式**：换一种编号写法就得再加一条。
6. 镜像有 `pg16` 变体（`paradedb/paradedb:pg16` / `0.26.6-pg16`），换引擎不必升 PG 大版本。

## 四、验收（实施后）

- [x] 默认 `substring` 下行为零变化：现有 84 条单测全绿，一条不改也不许改断言
      → 实际 96 条全绿（84 + 新增 10 + 绑定 2）；`SearchSqlTest`（真库）也跑过
- [x] `engine=bm25` + ParadeDB 容器：「A2 买一年延保要多少钱」命中价格表，分数远高于无关块
      → **偏差**：没有排第一，见「六」第 1 条，断言改成不依赖顺序的性质
- [x] `engine=bm25` 但库里没有 `pg_search`：启动失败，消息里同时给出「换 ParadeDB 镜像」和「设回 substring」两条出路
      → `Bm25KeywordDocumentRetrieverTest.failsFastWhenPgSearchIsMissing`
- [x] `engine=bm2`：启动即抛，消息列出允许值
      → `RagPropertiesBindingTest.rejectsUnknownEngineAtStartup`（绑定期，不是运行期）
- [x] 空查询 / 纯标点查询：返回空列表，不抛
      → 单测 3 种（空、空白、纯标点）+ 真容器 1 条
- [ ] 一轮 L1 对照（bm25 vs substring，同 68 题）——**这是决定要不要换默认的依据，但不作为本票的验收门**
      → 未跑。前提是把 ParadeDB 容器接进 `eval/rag` 的导出流程（现在导出走 `docker-compose` 里的
      `pgvector` 镜像），那是另一件事，单独排

## 五、不做 / 边界

- 不改默认值，不重钉基线，不跑 L2（要跑另开票）
- 不动 `substring` 的任何规则（形状、上限、权重都算）
- 不做 jieba 自定义词典：等出现真实切错的例子再做
- 不因为「上了 BM25」就顺手改融合权重：权重重标是换默认值时的事
- 许可证结论（AGPL-3.0 + 商业双许可）**不在本票里解决**，但它是换默认的前置条件，记在 ADR-0033 的代价里

## 六、实施结果（2026-11-21）

### 1. 一条偏差：「排第一」不成立，而且不该被写进断言

实测（`paradedb:latest` / pg_search 0.27，三块语料）：

| 块 | 分数 |
| --- | --- |
| `warranty.md`（延保一年 599 元，整机延保两年 999 元） | 1.199 |
| `price-list-2026.md`（A2 单价 3999，A3 单价 4599） | 1.171 |
| `returns.md`（退货 7 天） | 0.192 |

票第三节那条 1.208 / 1.193 是**更短的样例块**上量的。这一版的价格表多一行 A3 价格，块更长，
被 BM25 的长度归一（b=0.75）压下来 0.03，顺序就翻了。**排序由块长与词频决定，不由"A2 是否被认出来"决定。**

所以断言改成三件与顺序无关的事：价格表在结果里、它分数为正、它比无关块高一个量级。
把三块语料上的偶然顺序写成契约，下次改语料就要改测试——那也是「为指标改尺子」的一种。

### 2. 落地

| 改了什么 | 落点 |
| --- | --- |
| 引擎配置 + 校验 | `RagProperties.Keyword#engine`，只认 `substring` / `bm25`，拼错抛 `IllegalArgumentException`（绑定期） |
| 新检索器 | `Bm25KeywordDocumentRetriever`：一条 `pdb.match($1)` 的 SQL；空/纯标点不打库 |
| 建索引 | 同类的 `static ensureIndex(JdbcTemplate)`：先查 `pg_available_extensions`，再建扩展与索引；**失败即启动失败**，消息给两条出路 |
| 装配 | `RagAutoConfiguration#keywordRetriever`（唯一一处判断引擎）+ `knowledgeIndexRunner` 按引擎建对应索引 |
| 配置 | `claw4j.rag.keyword.engine`（`CLAW4J_RAG_KEYWORD_ENGINE`，默认 `substring`）；八项规则移到 `claw4j.rag.keyword.substring.*`，见下 |
| 单测 | `KeywordEngineTest` 5、`Bm25KeywordDocumentRetrieverTest` 5、`RagPropertiesBindingTest` +3 |
| 集成测试 | `claw4j-app/Bm25SearchTest` 3 条，默认跳过 |
| 文档 | `docs/design/knowledge-retrieval.md` 新增「两个引擎」小节 + 配置节 |

### 3. 八项规则挪到 `keyword.substring` 下（**配置键的破坏性变更**）

原本八项与 `engine` 平铺在 `keyword` 下。读配置的人很容易以为"切到 bm25 之后调 `max-anchors`
还有用"——而它一个字都不生效。现在：

```yaml
keyword:
  engine: substring        # 选引擎
  substring:               # 只属于 substring 引擎
    anchor-pattern: ...
```

`RagProperties.Keyword` 因此变成 `(engine, Substring substring)` 两个字段，八项的常量与默认值
都在 `Keyword.Substring` 里；`KeywordDocumentRetriever` 的构造参数也从 `Keyword` 收窄成 `Substring`
——它只用得到那一节，不该拿得到 `engine`。将来 `bm25` 的旋钮挂在 `keyword.bm25`，结构对称。

**没留旧键兼容**：项目还没发布（`0.1.0-SNAPSHOT`，没有外部部署），不存在"别人按旧结构写过的配置"
要迁移。旧键不识别就是不识别——不加别名，也不为它写测试。Spring Boot 对未知属性默认不报错，那是
框架的行为，不是本项目的契约。

**那一层包装最后整个删掉了**：`Rules#compile` 原本把 `PatternSyntaxException` 包成
"`claw4j.rag.keyword.xxx` 不是合法正则"，好处只是多一句"哪个键"。键改名时那句前缀漏改过一次，
说明两份描述同一件事本来就是负担。现在直接 `Pattern.compile(...)`——写错在构造 `Rules` 时就抛，
仍是启动失败，而 JDK 的消息自带正则原文与出错位置。`KeywordDocumentRetrieverTest#brokenPatternFailsFast`
断言的就是 `PatternSyntaxException`。

### 4. 两个实现细节，写下来免得被"顺手改掉"

- **DDL 放在检索器类里**（`ensureIndex`），不是放在 `RagAutoConfiguration`：装配与集成测试共用同一份
  DDL。放两处一定会漂：索引名或分词器改一处，另一处照旧，症状是"测试过了、线上没建对"。
- **先查 `pg_available_extensions` 再建**：把"库不是 ParadeDB"与"建索引语法不对"分成两条消息。
  两者都要启动失败，但修法完全不同（换镜像 vs 版本差异）。合成一句"建索引失败"，运维只有一条
  Postgres 英文报错可看。

### 5. 未做（保持原边界）

- 没换默认值、没跑 L1 对照、没跑 L2、没重钉基线
- `substring` 一行规则没动
- 没做 jieba 自定义词典
- 没动融合权重（0.7 / 0.3 照旧；`WeightedScoreJoiner` 按本次最大值归一，BM25 无上界不影响）


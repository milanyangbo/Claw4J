# P10 · 知识库检索：向量 + 关键词混合，结果带出处

**Status:** ready-for-agent
**Blocked by:** None（但下面第一节的裁决是先决条件，开工前已经定了）

## 背景

ADR-0020 的原句是「**P10 知识库检索**：向量 + 关键词混合，结果带出处；表与阈值与语义缓存分开」。
User Story 3 给了判据：问「华东的退货政策怎么规定的」，回答带着出处（文档名 + 段落），
**而不是把某段文档当成"有人问过"的答案吐出来**。

今天这一层一件都没有：

- 有 pgvector、有 embedding 模型、有一套"向量检索 + 阈值判定"的实现，但它们全在
  `claw4j-agent` 的 `cache` 包里，服务的是**缓存**——语义是"这个问题是不是问过"，
  不是"哪段文档能回答这个问题"。复用它的表就是把文档块当成历史答案（spec 的 Implementation
  Decisions 已经把这条写成禁止项）。
- 中文全文检索在这个库里**目前是失效的**。实测（`pgvector/pgvector:pg16`，2026-11-03）：

  ```sql
  select to_tsvector('simple', '华东区的退货政策怎么规定的');
  -- '华东区的退货政策怎么规定的':1        ← 整句一个 token，不是 12 个
  ```

  所以 `@@ to_tsquery('华东')` 永远为假。任何"先接上 tsvector 再说"的实现都会得到一个
  能编译、能返回空结果、日志干净、永远检索不到东西的通道。
- 同一个坑在工具检索上已经踩过一次，结论写在 `ToolSearch.java:62-66`：`RegexToolIndex`
  只按空白与标点切词，**不做中文分词**，实测整句匹配 0 个工具。当时的对策是**提示词**——
  叫模型写「空格分隔的关键词」。那条对策在这里不成立：这次要被切的是**文档正文**，
  而文档不是我们叫它怎么写就怎么写的。

## 一、卡点：中文分词走哪条路（开工前必须定，这是本票唯一的前置）

四条路的代价，按"要不要动镜像、要不要引依赖"排列。下面的数字都是在本机
`docker.m.daocloud.io/pgvector/pgvector:pg16` 上跑出来的，不是查文档查来的。

### 不选：pg_bigm

原理正确（就是 bigram），但**镜像里没有这个扩展**。实测：

```sql
select name, default_version from pg_available_extensions
 where name in ('pg_bigm','zhparser','vector','pg_trgm','pgroonga','hunspell_zh');
--  vector   | 0.8.7
--  pg_trgm  | 1.6
--  （pg_bigm / zhparser / pgroonga / hunspell_zh 一条都没有）
```

要用它得自己构建镜像（`apt install postgresql-16-pg-bigm` 或源码编译），于是每个开发者、
每台要跑数据库用例的机器都要 `docker build` 一次。而这个项目的镜像选择**已经被网络卡过一次**
（`.env` 里的 `CLAW4J_POSTGRES_IMAGE` 就是为了"国内拉不到官方镜像"才有的）。
让"跑测试"再多一个前置条件，代价与 P11 拒绝引 MCP server starter 的理由是同一个：
为了一个能自己实现的东西，给所有人加一道环境门槛。

### 不选：应用侧接一个中文分词库

本地仓库里确实有 `com.huaban/jieba-analysis:1.0.2` 与 `com.hankcs:hanlp-portable:1.8.4`，
离线构建能过。但除了"2MB 词典 + 首次加载要几百毫秒"这些成本之外，还有一个反向的问题：

**关键词通道的价值在精确匹配**——`SKU-2024`、`华东一区`、`A-07 号仓`、人名、单号。
分词器会把它们切成什么，取决于词典里有没有这个词；词典里没有的（新 SKU、新人名）
会被切成单字或直接丢掉，而那正是用户最需要"字面命中"的时候。
bigram 对这类串反而更稳：它不判断"这是不是一个词"，只保证**任何 2 字以上的连续片段都能被匹配**。

### 不选：pg_trgm

官方镜像自带，看起来最省事。实测它做不了 rank：

```sql
select similarity('退货政策', '华东区的退货政策怎么规定的，七天内可退换');  -- 0.0833
select similarity('退货政策', '华南区的物流时效承诺是次日达');              -- 0.0000
select word_similarity('退货政策', '华东区的退货政策怎么规定的，七天内可退换'); -- 0.4000
```

`similarity` 是整串 trigram 的 Jaccard，**分母随文档变长而变大**，所以同一个查询对一段长文档
只有 0.083。这意味着任何"相似度超过 X 才用"的阈值都无法跨文档长度统一——要么长文档永不命中，
要么短文档全是噪声。`word_similarity` 好一些（0.4），但它回答的是"这段文档像不像包含这个查询"，
没有词频加权，多词查询会退化成"字符集合重叠"。它能做**包含过滤**，不能做**排序**。

### 选：应用侧切 bigram，交给 `simple` 分词器

`simple` 分词器的规则简单到没有歧义：按非词字符切，中文汉字算词字符。所以**应用侧把文本切成
空格分隔的 2-gram**，它就能得到一堆正确的 token。实测：

```sql
select to_tsvector('simple', '华东 东区 区的 的退 退货 货政 政策 策怎 怎么 么规 规定 定的');
-- '东区':2 '么规':10 '区的':3 '华东':1 '定的':12 '怎么':9 '政策':7
-- '的退':4 '策怎':8 '规定':11 '货政':6 '退货':5          ← 12 个 token

-- 多词 AND（plainto_tsquery 用 & 连接）
select to_tsvector('simple','华东 东区 区的 的退 退货 货政 政策 策怎 怎么 么规 规定 定的')
    @@ plainto_tsquery('simple', '退货 货政 政策');        -- true
-- 不相关词
    @@ plainto_tsquery('simple', '物流 流时 时效');         -- false
-- 单字查询（1 个字切不出 bigram，用前缀匹配）
    @@ to_tsquery('simple', '退:*');                        -- true
-- 排序
select ts_rank(tsv, plainto_tsquery('simple','退货 货政 政策'));  -- 0.268330
```

生成列与索引也都实测可用（这一步必须验：生成列要求函数 immutable，不满足就建不出表）：

```sql
create table probe_chunk (
    id int primary key,
    bigrams text not null,
    tsv tsvector generated always as (to_tsvector('simple', bigrams)) stored
);
create index probe_chunk_tsv on probe_chunk using gin (tsv);   -- 成功
```

**为什么这一条能接受它的代价**：bigram 的召回是分词召回的超集——任何 2 字以上的词都由
若干 bigram 组成，所以"分词能命中的，bigram 也能命中"。多出来的是噪声 token（"的退"、"策怎"
这种跨词边界的碎片），而这些碎片的危害被两件事挡住：`plainto_tsquery` 的 AND 语义要求查询里
每个 bigram 都出现，以及向量通道在融合里承担语义。**精度靠融合，不靠切分。**

已知局限，实现时必须正面处理（不许绕过）：

- **单字查询切不出 bigram**。用 `to_tsquery('simple', '退:*')` 前缀匹配（实测命中）。
  这条要单独有用例，否则"搜一个字返回空"会被当成"知识库没这段"。
  （顺带实测：空 tsquery 的 `@@` 是 `false`，所以这条退化**不会**变成"返回全库"那种响亮的失败，
  而是一种安静的"没有"——安静的那一种才是要防的。）
- **token 数约为字符数**，tsvector 每行比"分词后"大一倍左右。这个量级（几千个块）无所谓；
  真要涨到十万块再考虑 `pg_bigm` 或者换检索，那时是一次独立的重估，不是现在的理由。
- 它**不认词边界**。所以"华东"与"华东区"会被当成不同查询（前者命中后者，后者也命中前者，
  因为 bigram 是子串关系）——这是想要的行为，不是缺陷。

## 二、第二个结构问题：embedding 模型现在住在缓存的装配里

`CacheAutoConfiguration#cacheEmbeddingModel` 同时挂着两个条件：`QwenModelCondition`（有 qwen key）
与 `claw4j.cache.enabled`（缺省 true）。也就是说：

- 知识库要算向量，但"关掉语义缓存"这个**省钱开关**会连带把向量模型关掉；
- 两个能力共用一个 bean，谁能拿到它取决于另一个功能的开关——这种耦合迟早会被某个部署踩到：
  「我们不开缓存，只想要知识库」→ 起不来，或者要靠打开一个用不上的缓存来换。

**裁决：把向量模型的装配提到 `model` 包，成为独立的一层。** 配置键从
`claw4j.cache.embedding-model` / `claw4j.cache.embedding-dimensions` 搬到
`claw4j.model.embedding.model` / `.dimensions`；`CacheProperties` 只留缓存自己的三件事
（阈值、TTL、清理间隔），`RagProperties` 只留检索自己的事（topK、下限、目录、块长）。

代价要写在明处：

1. **这是一次配置键改名**，`application.yml` 与 README 要同步，升级的人会看到旧键失效。
   不做兼容读取（两个来源读同一个值，迟早不一致——pitfalls 第十条）。
2. 关掉缓存之后向量模型仍然装配。它是懒的（只有被调用才发请求），所以代价是一个 bean
   与一次启动日志，不是一次网络调用。
3. 维度 `1024` 与 `vector(1024)` 的成对约束没有变，只是它现在同时约束两张表
   （缓存与知识库）。改维度要同时改两处迁移——这条写进迁移文件的注释。

## 三、第三个结构问题：检索是工具还是自动注入

两种形态：

- **工具**（`searchDocs`）：模型自己决定调几次、用什么词。鉴权天然落在
  `ToolPermissionRegistry`（`read` 动作 + 伪表名 `doc`），审计落在 run 级，与其余 21 个工具同一条路。
  代价是模型可能不调用（那就靠提示词说清"问政策、规定、流程时先检索"）。
- **Advisor 自动注入**：每轮把检索结果塞进系统提示词。看起来"更 RAG"，
  但它绕开了三件事：鉴权（哪些块能进上下文由谁判）、审计（模型输入被改了，run 里看不到为什么）、
  分级（一个 `guest` 与一个 `admin` 会拿到同一段上下文）。

**裁决：工具。** 与 P09/P11 同一个立场——能力要么是清单上的一个工具，要么不存在。
自动注入这一版不做，理由不是"没必要"，是它必须有自己的一套鉴权与留痕，那是另一件事。

## 表与阈值：与语义缓存分开（spec 已定，这里落成结构）

```sql
create schema if not exists claw4j_rag;

create table claw4j_rag.document (
    id           bigserial primary key,
    source       text        not null unique,   -- 相对目录的路径，也是出处的第一段
    content_hash varchar(64) not null,          -- 文件内容的 SHA-256，用来跳过没变的文件
    indexed_at   timestamptz not null default now()
);

create table claw4j_rag.chunk (
    id          bigserial primary key,
    document_id bigint       not null references claw4j_rag.document (id) on delete cascade,
    ordinal     int          not null,          -- 文档内第几块，从 1 开始；出处的第二段
    text        text         not null,
    bigrams     text         not null,          -- 应用侧切好的 2-gram，空格分隔
    tsv         tsvector generated always as (to_tsvector('simple', bigrams)) stored,
    embedding   vector(1024) not null,
    unique (document_id, ordinal)
);

create index chunk_tsv on claw4j_rag.chunk using gin (tsv);
```

三条与缓存对照着看才有意义：

| | 语义缓存 | 知识库 |
| --- | --- | --- |
| schema | `claw4j_cache.semantic` | `claw4j_rag.chunk` |
| 一行是什么 | 一次问答 | 一段文档 |
| 命中的意思 | "这个问题问过" | "这段文档相关" |
| 阈值 | 0.93（同一问题） | 不设绝对阈值，用 topK + RRF |
| 过期 | TTL 1 小时 | 不过期，跟着文件哈希走 |

**不做 HNSW/IVFFlat 索引**，理由与缓存那份迁移相同（量级是几百到几千块，全表精确扫描够用），
且知识库更在意召回：漏掉真正相关的那段，比慢 10 毫秒糟得多。

**融合用 RRF（Reciprocal Rank Fusion），不用分数加权。** 向量通道给的是余弦相似度（0~1），
关键词通道给的是 `ts_rank`（0~0.3），两者量纲不同，直接加权要先归一化，而归一化系数会随
文档集变化——那是"调参调到看起来对"。RRF 只用**排名**：`score = Σ 1/(k + rank)`，`k = 60`。
参数只有一个，且它的取值对结果不敏感（论文与实践中 60 是缺省）。

## 留给实现决定的点

1. **文档从哪来、什么时候进库**。这一版选目录（`claw4j.rag.directory`，扫 `*.md` / `*.txt`），
   启动时同步一次，按 `content_hash` 跳过没变的文件。要不要提供"重新索引"的工具
   （模型能主动触发）由实现定，但**写入不能交给模型**：让模型往知识库里写，就是给它一个
   把提示注入落库的口子。新增/删除文件的表现（文件删了，块还在）必须说清怎么处理。
2. **切块怎么切**。这一版按 markdown 空行分段，累加到上限（默认 800 字符）就切。
   不做语义切块、不做重叠窗口。代价是"一段话被切在两块中间"时会各答一半——
   能接受，因为出处会指回原文，模型可以再检索一次。要更好就得引 chunking 策略，
   那是"检索质量"的另一件事。
3. **伪表名怎么定**。`read` 动作 + 表名 `doc`，于是"能不能看知识库"与"能不能查表"
   用的是同一套授权表（`claw4j.http.roles.<角色>.tables` 里加 `doc`）。
   代价：`guest` 这类只给 `region` 的角色默认看不到知识库——这是**想要**的默认，
   但要在 `application.yml` 的注释里写出来，否则会变成"配了知识库但某些人问什么都答没有"。
4. **索引失败怎么办**。开着（`claw4j.rag.enabled=true`）但目录没配、目录不存在、
   或者目录里一个文件都读不出来 → **启动失败并点名**，与 P09「未分档即失败」、P11「开着却清单为空即失败」同构。
   embedding 服务在启动时不可达 → 也是启动失败（知识库是显式配的能力，
   索引没成功等于库是空的，而空库的表现是"我没找到相关资料"，那与"知识库没建起来"在日志上必须分得开）。
   代价要认：通义抖动时进程起不来。想避免就得接受"启动成功但知识库是空的"，那是更坏的一种。
5. **结果的形状**。每个结果至少要有：`source`（相对路径）、`ordinal`（第几块）、片段文本。
   要不要带分数、带相似度，由实现定；**不要带内部 id**（模型读不懂，也没有一个动作能拿它做事）。

## 验收判据

- [ ] **中文关键词真能命中**：问「退货政策」，关键词通道能命中包含该词的块。
      反例必须能红：把 `bigrams` 换成原文、直接 `to_tsvector('simple', text)` 的实现，
      在这条用例上必须返回 0 条（实测整句一个 token，见第一节）。
- [ ] **单字不炸也不空**：查询长度为 1 时走前缀匹配（`退:*`），返回包含该字的块；
      断言的是"有结果"，不是"没抛异常"。反例：把单字也去切 bigram → 切出 0 个 token →
      空 tsquery **一条都不命中**（实测 `to_tsvector(...) @@ plainto_tsquery('simple','')` 为
      `false`，不是"全部命中"），于是"搜一个字"与"知识库没这段"在结果与日志上完全一样。
- [ ] **两路各自都在起作用**：构造一对文档——A 与查询**字面不重合但语义相同**（只有向量通道能查到），
      B 与查询**字面重合但语义无关**（只有关键词通道能查到）。两条都能出现在结果里。
      这一条是把"混合"与"只做向量"分开的唯一办法；只测"能查到"的实现，删掉一路照样绿。
- [ ] **出处可追溯**：每条结果带 `source` + `ordinal`，且用 `source` 与 `ordinal` 能取回那段原文
      （断言取回的文本与结果里的片段一致）。
- [ ] **不是缓存**：同一段文档被两次检索，两次都带出处；并且 `claw4j_cache.semantic` 里
      **一条文档块都没有**（反例：把块写进缓存表 → 这条红）。
- [ ] **表与阈值分开**：`claw4j_rag` 与 `claw4j_cache` 是两个 schema；把
      `claw4j.cache.similarity-threshold` 改成 0.99 之后，知识库的检索结果不变。
- [ ] **权限在工具这一道**：工具登记 `read` + `doc`；表范围不含 `doc` 的身份调用时被拒，
      且**检索一次都没执行**（探针计数为 0）。只有"返回了拒绝文本、检索照跑"不可接受。
- [ ] **开关与空库**：`claw4j.rag.enabled=false` 时容器里没有检索 bean、工具清单里没有 `searchDocs`；
      `enabled=true` 但目录为空 → 启动失败并点名（文案里要有"目录"两个字）。
- [ ] **索引幂等**：同一份文档连续索引两次，`claw4j_rag.chunk` 的行数不变（按 `content_hash` 跳过）。
      反例：每次启动都重建 → 行数翻倍，且第二次的 `ordinal` 与第一次错位。
- [ ] **SQL 真跑**：真容器上跑迁移（生成列 + GIN 索引 + 唯一键），并以两路查询各取一次 topK。
      这一条不能只靠单元测试：生成列要求 `to_tsvector('simple', …)` 是 immutable，
      不满足时建表就失败；`plainto_tsquery` 的 AND 语义也只在真库上成立。
- [ ] `./scripts/verify.sh` 全绿，跳过 0，行覆盖率不低于 60%。
- [ ] **门禁余量**：五个模块都已经是 10 个测试文件（上限 10，脚本按
      `find -name '*.java'` 计数，**辅助类也算**）。本票不能新增测试文件，只能腾槽或塞进未满的文件：
      - `claw4j-core`：合并 `DomainBoundaryTest`(2) 与 `CoreBoundaryTest`(2) → 腾一槽，
        给切块 / bigram / RRF 的纯逻辑用例（这两条都是结构规则，合并是同主题合并，不是凑数）。
      - `claw4j-agent`：`SemanticCacheTest` 18 → 20（装配两条：开关关掉没有 bean / 目录为空启动失败）。
      - `claw4j-app`：合并 `ProcessBoundaryTest`(2) 与 `MigrationCompositionTest`(2) → 腾一槽，
        给 `KnowledgeSqlTest`（真容器的迁移与两路查询）；`ApplicationAssemblyTest` 18 → 20
        （工具在清单里 + 权限登记对）。

## Comments

- 2026-11-03 拆票。**卡点不再是一个待定项**：四条路都在本机镜像上实测过，
  结论是"应用侧切 bigram"，理由与数字都在第一节，可以照着复现。
  这一节是本票唯一需要先读的部分——它决定了表结构（`bigrams` 列 + 生成列 `tsv`）。
- 实测用的是临时容器（`docker run --rm pgvector/pgvector:pg16`），跑完已删；
  上面那些 SQL 与输出是原样抄的，没有整理成"看起来更漂亮"的样子。
- **同一句"仓库里的向量设施能复用"要打个折扣**：能复用 pgvector 实例、复用 embedding 供应商，
  但 `SemanticCache` 那个接口（`nearest` / `store` / `deleteExpired`）语义是缓存的，
  知识库不复用它。共享的只有"怎么把 float[] 拼成 pgvector 字面量"这种实现细节
  （`PgVectorSemanticCache#toVectorLiteral`，`package-private`，要么提成公共工具，要么各写一份——
  各写一份的代价是两份维度检查，提成工具的代价是动一个已经稳了的类，由实现定）。
- 本票同时是一次配置键搬迁（`claw4j.cache.embedding-*` → `claw4j.model.embedding.*`），
  范围写进第二节。如果实现时发现搬迁的影响面超出预期（比如还有别处读旧键），
  **先回来改这份票**，不要一边搬一边加兼容读取。
- 逐条勾验收框，证据落到方法名 / 文件名，不要只写 Comments——M7 的 A01 / A02 就是这样补的一轮。

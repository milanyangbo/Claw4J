-- 知识库（P10）：存储与检索改用框架的实现，我们只留 OpenClaw 有、框架没有的那张表。
--
-- 本文件原来的两张表（document + chunk）是自写的：手拼两条 SQL 做"向量 + bigram 全文"
-- 两路检索，再在 Java 里做 RRF 融合。2026-11-09 改成框架的实现（与语义缓存同一次裁决）：
--
--   · 块与向量 → `claw4j_rag.vector_store`，由 `PgVectorStore` 建、由它读写。
--     表结构是官方的（id uuid / content text / metadata json / embedding vector(N)），
--     所以**这个文件不建它**——建在这边就成了"我们定义官方表"，维度与列名两处要同步。
--   · 切块 → `TokenTextSplitter`（spring-ai-commons）。
--   · 检索 → `VectorStore.similaritySearch(SearchRequest)`。
--
-- **保留的那张表**：`source`。它照 OpenClaw 的 `memory_index_sources`
-- （packages/memory-host-sdk/src/host/memory-schema.ts）：一行一个文件，记 hash，
-- 用来跳过内容没变的文件。框架没有这个概念——`VectorStore` 只认 Document，
-- 没有"这份文件上次是什么样"的账。没有它，每次启动都要把全部文档重算一遍 embedding，
-- 那是每次重启烧一遍 token。
--
-- **关键词那一路在 2026-11-09 晚加回来了**：不再是自建的 bigram + tsvector，而是照 OpenClaw
-- 的 FTS5 trigram 形态——`content ilike '%q%'` 子串匹配，GIN trgm 索引加速。那一路实现的是
-- 框架的 `DocumentRetriever` 接口（`KeywordDocumentRetriever`），融合用框架的 `DocumentJoiner`
-- 接口（`ReciprocalRankFusionJoiner`），都不是自有 API。所以这个文件只管扩展与文件账。
create schema if not exists claw4j_rag;

-- pgvector 在会话/缓存那两个迁移里已经建过，这里重复一次是为了"只跑这个文件"的场景也不会失败。
create extension if not exists vector;

-- 全文那一路的索引类型：`content ilike '%q%'` 由 GIN trgm 加速。
--
-- **它不产分数**——`similarity()` 对中文长文本恒 0（分母是块长），`word_similarity()` 依赖词边界，
-- 中文没有空格。所以那一路只排顺序，融合交给 RRF。实测数据在
-- `docs/design/knowledge-retrieval.md` 第四节。
--
-- 扩展在这里建，**索引不在这里建**：`vector_store` 由 `PgVectorStore` 在应用启动时创建，
-- 迁移跑的时候它还不存在。索引在 `RagAutoConfiguration` 的启动任务里 `create index if not exists`。
create extension if not exists pg_trgm;

-- 一行一份文档。path 是相对目录的路径，也是出处的第一段。
--
-- **source 为什么与 path 一起做主键**：OpenClaw 的来源分 memory / project 等几类，
-- 同一个相对路径可能在不同来源下各有一份。我们今天只有一类文档目录，但把来源写进键里
-- 不花钱，而"以后要多接一个目录"时不用改表——它本来就是 OpenClaw 的主键形状。
create table claw4j_rag.source (
    path         text        not null,
    -- 来源类别。今天只有 docs（claw4j.rag.directory 那一份目录）。
    source       text        not null default 'docs',
    -- 内容的 sha256。只覆盖内容不覆盖路径：文件改名会被当成"删掉旧的 + 新增一份"，
    -- 这是对的——出处里的路径变了。
    content_hash varchar(64) not null,
    -- 文件的修改时刻与字节数，与 hash 一起判断"要不要重建"。hash 相等时它们不参与判定，
    -- 但排障时"hash 没变而 mtime 变了"能直接说明是 touch 而不是改内容。
    mtime        timestamptz,
    size         bigint,
    indexed_at   timestamptz not null default now(),
    constraint pk_rag_source primary key (path, source)
);

-- "这个来源下有哪些文件"是同步时唯一的多行读，索引跟着这个形状走。
create index ix_rag_source_source on claw4j_rag.source (source);

comment on table claw4j_rag.source is
    '知识库的文件账（照 OpenClaw 的 memory_index_sources）：一行一份文档，记 hash 用来跳过没变的文件。块与向量在 vector_store 里，由 PgVectorStore 管';
comment on column claw4j_rag.source.content_hash is
    '内容 sha256。只覆盖内容不覆盖路径：改名等于删旧建新，因为出处里的路径变了';
comment on column claw4j_rag.source.mtime is
    '文件修改时刻。hash 相等时不参与判定，只用于排障（hash 没变而 mtime 变了 = touch）';

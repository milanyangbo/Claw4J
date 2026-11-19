-- 知识库（P10）：存储与检索用框架的实现，我们只留 OpenClaw 有、框架没有的两张表。
--
-- 块的向量表 `claw4j_rag.vector_store` **不在这个文件里建**：它由 `PgVectorStore` 自己建、
-- 自己读写，表结构是官方那套（id uuid / content text / metadata json / embedding vector(N)）。
-- 建在这边就成了"我们定义官方表"，维度与列名两处要同步。
--
-- 保留这两张，是因为框架两个概念都没有：`VectorStore` 只认 Document，没有"这份文件上次是什么样"，
-- 也没有"切块算法换过没有"的账。
--   · 缺 source：每次启动都要把全部文档重算一遍 embedding；
--   · 缺 index_meta：切块实现一变，内容没变的文件永远不重建，库里会混着两套边界的块且没有迹象。
create schema if not exists claw4j_rag;

-- pgvector 与 pg_trgm 都在这里建：扩展必须由用它的文件自己建。换一台机器、换一个库，
-- 缺这一行会让建表直接失败，而失败点在 Flyway，看得见。
create extension if not exists vector;

-- 全文那一路的索引类型：`content ilike '%q%'` 由 GIN trgm 加速。
--
-- **它不产分数**——`similarity()` 对中文长文本恒 0（分母是块长），`word_similarity()` 依赖词边界，
-- 中文没有空格。所以那一路给的是命中次数，融合时按最强命中归一再加权。实测数据在
-- `docs/design/knowledge-retrieval.md` 第四节。
--
-- 扩展在这里建，**索引不在这里建**：`vector_store` 由 `PgVectorStore` 在应用启动时创建，
-- 迁移跑的时候它还不存在。索引在 `RagAutoConfiguration` 的启动任务里 `create index if not exists`。
create extension if not exists pg_trgm;

-- 一行一份文档。path 是相对目录的路径，也是出处的第一段。
--
-- **为什么 source 与 path 一起做主键**：来源分 docs / memory 等几类，同一个相对路径可能在
-- 不同来源下各有一份。今天只有一类文档目录，把来源写进键里不花钱，而"以后要多接一个目录"时
-- 不用改表——它本来就是 OpenClaw 的主键形状。
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

-- 索引身份：跳过一份文件的条件不只是 `content_hash`，还要"切块实现没变"。
--
-- 一张 key/value 表，一行 `index_meta_v1`，值是 JSON。**不是**在文件行上加一列——
-- 那样每次改切块参数都要扫全表。
--
-- 这一行是：
--   key   = 'index_meta_v1'
--   value = {"chunkingVersion":1,"chunkTokens":800}
-- 身份对不上（或没有这一行）→ 下次启动全量重建，重建完写回。
--
-- 值用 text 而不是 jsonb：只有我们写它、只有我们读它，按字符串比较就够；
-- 而 jsonb 读回来的文本形状（空格、键序）与写进去的不一定一致，会把比较变成"要看运气"。
create table claw4j_rag.index_meta (
    key   text primary key,
    value text not null
);

comment on table claw4j_rag.index_meta is
    '索引身份（照 OpenClaw 的 memory_index_meta）：一行 key + 一个 JSON 值。切块实现一变，身份对不上就全量重建';
comment on column claw4j_rag.index_meta.value is
    'index_meta_v1 = {"chunkingVersion":1,"chunkTokens":800}。改切块算法、词表、阈值、块长都要升 chunkingVersion';

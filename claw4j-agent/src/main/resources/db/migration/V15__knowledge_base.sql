-- 知识库的表（P10）。与语义缓存**分开**是这一票的硬要求，不是命名偏好。
--
-- 为什么不能共用一张表：缓存的命中语义是"这个问题问过"，所以它直接返回历史回答；
-- 文档块进同一张表之后，一段文档会被当成"有人问过"的答案吐出来——没有出处、也没有
-- "这段话来自哪里"这回事。共享 embedding 供应商与 pgvector 实例就够了，表与阈值必须分开，
-- 否则两边的参数互相拉扯：缓存把阈值调到 0.93（宁漏不误），知识库需要的是低得多的下限。
--
-- 维度 1024 与 V4 的 vector(1024) 是同一个模型的输出（text-embedding-v3）。改维度要
-- **同时**改这两个迁移，因为插入维度不符会直接报错（vector 不做静默截断）。
create schema if not exists claw4j_rag;

-- pgvector 在 V4 已经建过，这里重复一次是为了"只跑这个迁移"的场景也不会失败（幂等）。
create extension if not exists vector;

-- 一行是一份文档。source 是相对目录的路径，也是出处的第一段。
-- content_hash 的唯一用途是跳过没变的文件：每次启动都重新算一遍 embedding，等于每次重启
-- 都烧一遍 token，而且块的 ordinal 会与上一次错位。
create table if not exists claw4j_rag.document (
    id           bigserial    primary key,
    source       text         not null unique,
    content_hash varchar(64)  not null,
    indexed_at   timestamptz  not null default now()
);

-- 一行是一段文档。检索与出处都以它为最小单位。
create table if not exists claw4j_rag.chunk (
    id          bigserial    primary key,
    document_id bigint       not null references claw4j_rag.document (id) on delete cascade,
    -- 文档内第几块，从 1 开始。出处的第二段就是它，所以人读的时候不用再 +1。
    ordinal     int          not null,
    text        text         not null,
    -- 应用侧切好的 2-gram，空格分隔。**为什么不是分词结果**：Postgres 的默认分词器对中文
    -- 失效（`to_tsvector('simple', '华东区的退货政策怎么规定的')` 得到整句一个 token），
    -- 而镜像里没有 pg_bigm / zhparser。把 bigram 预先切好、用 simple 分词器按空格切，
    -- 就能得到正确的 token——实测这句话切出 12 个。
    bigrams     text         not null,
    -- 生成列而不是触发器：只有一个落点，写的时候不用记得"还要更新 tsv"。
    -- 这要求 to_tsvector(regconfig, text) 是 immutable——它是，所以建表能成功；
    -- 换成依赖配置的版本（两参以外的形式）会直接建不出来。
    tsv         tsvector     generated always as (to_tsvector('simple', bigrams)) stored,
    embedding   vector(1024) not null,
    unique (document_id, ordinal)
);

-- 关键词通道的索引。查询形态是 `tsv @@ to_tsquery('simple', ?)`，GIN 是它唯一合适的索引。
create index if not exists idx_chunk_tsv on claw4j_rag.chunk using gin (tsv);

-- 刻意**不建 HNSW/IVFFlat**：理由与 V4 相同（几百到几千块，全表精确扫描够用），
-- 但这里的方向更明确——知识库更在意召回，漏掉真正相关的那段比慢十毫秒糟得多，
-- 而这两类索引都是近似召回。什么时候建：块数上万、或者抓到时延问题时再单独一个迁移。

-- 语义缓存（M3-05）。ADR-0005：一个 Postgres 三种存储，靠 schema 分开——
-- 业务在 business、会话在 claw4j_session、缓存在这里，谁都不跟谁抢表名。
--
-- 本文件是旧 V4 / V5 两段历史合并后的基线：V5 把分组键从"权限"改成"权限 + 实体"，
-- 现在一次建到位。后续改表结构请加新版本。
--
-- **为什么不是 Redis**：ADR-0005 早先的修订记录里写着"引回 Redis Stack"，后来的实测推翻了它
-- （理由在 ADR-0005 的第二次修订）。一句话：这个缓存要和授权、会话放在同一套数据里做
-- 一致性判断，另起一个存储只是多一个会不一致的来源，而 pgvector 已经在库里了。
create schema if not exists claw4j_cache;

-- pgvector 扩展要显式建：镜像里装了扩展不等于这个库里已经 create extension。
-- 换一台机器、换一个库时，缺这一行会让建表直接失败。
create extension if not exists vector;

create table claw4j_cache.semantic (
    id           bigserial    primary key,
    -- 权限指纹：Principal 的 tables + actions 排序后的哈希。不含 userId，
    -- 同角色的人可以互相命中；也不含角色名，否则角色改一次配置缓存全失效。
    fingerprint  varchar(64)  not null,
    question     text         not null,
    -- 归一化问题的哈希。用途只有一个：同一个问题不重复存（见下面的唯一索引），
    -- 也顺带让"一字不差的重问"不必走向量比较。
    question_key varchar(64)  not null,
    -- 维度 1024 是 text-embedding-v3 在 dimensions=1024 下的输出。它**必须与配置里的
    -- dimensions 一致**：改模型或改维度要新建一列并重新灌数据，因为 vector(1024)
    -- 不接受别的长度（插入直接报错，不会静默截断）。
    embedding    vector(1024) not null,
    answer       text         not null,
    created_at   timestamptz  not null default now(),
    expires_at   timestamptz  not null,
    -- 实体签名（问题里的时间词、数字、地名归一之后的哈希）。fingerprint + entities
    -- 合起来相当于框架接口里那个 contextHash。
    --
    -- **为什么需要它**：实测"上周华东成交额多少"与"上上周华东成交额多少"余弦是 0.9950，
    -- 比真正的同义改写（0.9372）还高。任何单一阈值都分不开这两句，而它们意思完全不同。
    -- 原先是取到最近邻之后再拿时间词/数字做二次否决，那个判断在框架里没有对应物；
    -- 改成把实体算进上下文键——"上下文不同就不是同一个问题"，这是框架自己的语义
    -- （它的 SemanticCacheAdvisor 用 SHA-256(系统提示词) 填这个位置）。
    entities     varchar(64)  not null default ''
);

-- 主查路径是 "fingerprint = ? and entities = ? and expires_at > now()
-- order by embedding <=> ?"。前两列加过期走这个唯一索引，向量排序在过滤之后做；
-- 唯一性同时挡住"同一个问题存两遍"。
create unique index uq_semantic_scope_question
    on claw4j_cache.semantic (fingerprint, entities, question_key);

create index idx_semantic_scope_expires
    on claw4j_cache.semantic (fingerprint, entities, expires_at);

-- 另一条读路径是"同一权限、任意实体"（只为判断 miss 的原因），
-- 它按 (fingerprint, expires_at) 过滤，上面那个复合索引的前缀用不上，单独给一个。
create index idx_semantic_fingerprint_expires
    on claw4j_cache.semantic (fingerprint, expires_at);

-- 刻意**不建 HNSW/IVFFlat 索引**：它们换来的是"十万行时仍然快"，代价是每次写入要维护图结构、
-- 以及召回变成近似的（会漏掉真正最近的那条）。这个缓存的量级是"一个角色一天几十到几百条"，
-- 全表精确扫描在这个量级上是微秒级。
-- 什么时候建：表行数上万、或者抓到时延问题时，再单独一个迁移加上
-- using hnsw (embedding vector_cosine_ops)，那时候要接受近似召回。

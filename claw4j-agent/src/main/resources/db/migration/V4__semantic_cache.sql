-- 语义缓存的表（M3-05）。ADR-0005：一个 Postgres 三种存储，靠 schema 分开。
-- 业务在 business、会话在 claw4j_session、缓存在这里，谁都不跟谁抢表名。
--
-- **为什么不是 Redis**：ADR-0005 早先的修订记录里写着"引回 Redis Stack"，
-- 这一票推翻了它（理由写在 ADR-0005 的第二次修订里）。一句话：这个缓存要和授权、
-- 会话放在同一套数据里做一致性判断，另起一个存储只是多一个会不一致的来源，
-- 而 pgvector 已经在库里了。
--
-- 维度 1024 是 text-embedding-v3 在 dimensions=1024 下的输出。它**必须与
-- 配置里的 dimensions 一致**：改模型或改维度要新建一列并重新灌数据，
-- 因为 vector(1024) 不接受别的长度（插入直接报错，不会静默截断）。
create schema if not exists claw4j_cache;

-- pgvector 扩展要显式建：镜像里装了扩展不等于这个库里已经 create extension。
-- 换一台机器、换一个库时，缺这一行会让建表直接失败。
create extension if not exists vector;

create table if not exists claw4j_cache.semantic (
    id           bigserial    primary key,
    -- 权限指纹：Principal 的 tables + actions 排序后的哈希。不含 userId，
    -- 同角色的人可以互相命中；也不含角色名，否则角色改一次配置缓存全失效。
    fingerprint  varchar(64)  not null,
    question     text         not null,
    -- 归一化问题的哈希。用途只有一个：同一个问题不重复存（唯一索引），
    -- 也顺带让"一字不差的重问"不必走向量比较。
    question_key varchar(64)  not null,
    embedding    vector(1024) not null,
    answer       text         not null,
    created_at   timestamptz  not null default now(),
    expires_at   timestamptz  not null
);

create unique index if not exists uq_semantic_fingerprint_question
    on claw4j_cache.semantic (fingerprint, question_key);

-- 查询形态是 "fingerprint = ? and expires_at > now() order by embedding <=> ?"。
-- 前两列走这个索引，向量排序在过滤之后做。
create index if not exists idx_semantic_fingerprint_expires
    on claw4j_cache.semantic (fingerprint, expires_at);

-- 刻意**不建 HNSW/IVFFlat 索引**：它们换来的是"十万行时仍然快"，代价是每次写入要
-- 维护图结构、以及召回变成近似的（会漏掉真正最近的那条）。这个缓存的量级是
-- "一个角色一天几十到几百条"，全表精确扫描在这个量级上是微秒级。
-- 什么时候建：表行数上万、或者抓到时延问题时，再单独一个迁移加上
-- using hnsw (embedding vector_cosine_ops)，那时候要接受近似召回。

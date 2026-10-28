-- 语义缓存的分组键从"权限"变成"权限 + 实体"。
--
-- 起因：M3 的实测里，"上周华东成交额多少" 与 "上上周华东成交额多少" 的余弦是 0.9950，
-- 比真正的同义改写（0.9372）还高。任何单一阈值都分不开这两句，而它们的意思完全不同。
-- 原先是取到最近邻之后再拿时间词/数字做二次否决；那个判断在框架里没有对应物，
-- 所以改成把实体算进上下文键——"上下文不同就不是同一个问题"，这是框架自己的语义
-- （它的 SemanticCacheAdvisor 用 SHA-256(系统提示词) 填这个位置）。
--
-- fingerprint + entities 两列合起来，相当于框架接口里那个 contextHash。

alter table claw4j_cache.semantic
    add column if not exists entities varchar(64) not null default '';

-- 旧行没有实体签名，无法判断该归到哪一组；缓存是可丢的，清空比猜安全。
delete from claw4j_cache.semantic;

drop index if exists claw4j_cache.uq_semantic_fingerprint_question;
create unique index if not exists uq_semantic_scope_question
    on claw4j_cache.semantic (fingerprint, entities, question_key);

drop index if exists claw4j_cache.idx_semantic_fingerprint_expires;
create index if not exists idx_semantic_scope_expires
    on claw4j_cache.semantic (fingerprint, entities, expires_at);

-- 另一条读路径是"同一权限、任意实体"（只为判断 miss 的原因），
-- 它按 (fingerprint, expires_at) 过滤，上面那个复合索引的前缀用不上，单独给一个。
create index if not exists idx_semantic_fingerprint_expires
    on claw4j_cache.semantic (fingerprint, expires_at);

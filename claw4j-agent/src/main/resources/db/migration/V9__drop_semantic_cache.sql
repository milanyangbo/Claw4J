-- 语义缓存改落 Redis Stack（框架的 DefaultSemanticCache，M3-05 第二次方案变更）。
--
-- 这张表连同它所在的 schema 一起删：`claw4j_cache` 里只有这一张表。
-- 不带 cascade——万一后来有人往里加了别的东西，这里应当报错，而不是把它们一起删掉。
--
-- 迁移文件本身不能改（Flyway 校验和），所以用新建一版来撤掉 V4 建的东西。
drop table if exists claw4j_cache.semantic;
drop schema if exists claw4j_cache;

-- 工具描述的中文译文（票 16）：同一个工具只译一次，跨进程复用。
--
-- 为什么落表：票 13 的第三版量出中文化对向量那一路有收益（zh-en 的 R@5 0.600 → 0.75~0.80），
-- 但同时发现**开的那一侧不可复现**——译文只缓存在进程内，每跑一次评测就重译一遍，措辞不同、
-- embedding 不同、名次跟着变（三次跑出三个 R@1）。一个自己会抖的指标不能当回归门。
--
-- 表形状的三点取舍：
--   · 按 `tool_name` 做主键，不按 hash：一个工具只该有"当前这一份"译文，留历史会让
--     "这段描述当时译成什么"有两种答案。
--   · 但带 `source_hash`：查表要"名字 + 原文 hash"都命中才算数，原文改了就必须重译。
--   · 留 `source_text`：排障时要看"当时译的是哪一版"，否则只能靠 hash 猜。
--
-- 与 V8 的关系：schema `claw4j_tool` 已由 V8 建（工具向量那张表也在这个 schema 下），
-- 本文件只加表，不重复建 schema。
create table if not exists claw4j_tool.tool_description (
    tool_name   text        primary key,
    source_hash char(64)    not null,
    source_text text        not null,
    zh_text     text        not null,
    updated_at  timestamptz not null default now()
);

comment on table claw4j_tool.tool_description is
    '工具描述的中文译文（票 16）：只喂向量索引；按「工具名 + 原文 hash」命中，原文变了自动重译';

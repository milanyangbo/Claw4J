-- 知识库的索引身份（P10c / ADR-0028）：照 OpenClaw 的 `memory_index_meta`。
--
-- 为什么需要它：跳过一份文件的条件原来只有 `content_hash`。切块实现一变（算法、词表、阈值、
-- 块长），内容没变的文件永远不会重建，库里就会混着两套边界的块——而且没有任何迹象：
-- 检索照常返回，出处照样拼得出来，只是"第 N 段"指向的已经不是当初那段。
--
-- 形状照 OpenClaw（packages/memory-host-sdk/src/host/memory-schema-base.ts:16）：
-- 一张 key/value 表，一行 `memory_index_meta_v1`，值是 JSON。**不是**在文件行上加一列——
-- 它的 `memory_index_sources` 只有 path / source / hash / mtime / size。
--
-- 我们这一行是：
--   key   = 'index_meta_v1'
--   value = {"chunkingVersion":1,"chunkTokens":800}
-- 身份对不上（或没有这一行）→ 下次启动全量重建，重建完写回。
--
-- 值用 text 而不是 jsonb：只有我们写它、只有我们读它，按字符串比较就够；
-- 而 jsonb 读回来的文本形状（空格、键序）与写进去的不一定一致，会把比较变成"要看运气"。
create table if not exists claw4j_rag.index_meta (
    key   text primary key,
    value text not null
);

comment on table claw4j_rag.index_meta is
    '索引身份（照 OpenClaw 的 memory_index_meta）：一行 key + 一个 JSON 值。切块实现一变，身份对不上就全量重建';
comment on column claw4j_rag.index_meta.value is
    'index_meta_v1 = {"chunkingVersion":1,"chunkTokens":800}。改切块算法、词表、阈值、块长都要升 chunkingVersion';

-- 工具向量（票 07）：hybrid 索引的向量那一路落在自己的一张表上。
--
-- 表 **不在这里建**：与 `claw4j_rag.vector_store` 同一条口径——它由 `PgVectorStore` 自己建、
-- 自己读写（id uuid / content text / metadata json / embedding vector(1024)）。建在这边就成了
-- "我们定义官方表"，维度与列名要在两处同步，而改维度那一次必然会漏掉一处。
--
-- 这个文件只保证 schema 在。两件事靠它：
--   · 迁移在应用启动时先跑，`PgVectorStore` 建表之前 schema 已经在了；
--   · `claw4j_tool` 这个名字有且只有一处定义（`ToolVectorStore.SCHEMA`），
--     "工具向量不进知识库那张表"因此是能看见的，而不是靠代码里两处字符串碰巧不同。
--
-- 为什么不是一个 schema 下的两张表：知识库的 BM25 与向量两路都按表扫，共用一张表就会串味——
-- 症状是答退货政策时冒出一句"Glob 是文件匹配工具"，而没人会想到是工具索引写进去的。
create schema if not exists claw4j_tool;

comment on schema claw4j_tool is
    '工具检索的向量（票 07）：与知识库的 claw4j_rag 分开，工具描述不参与业务问答的召回。表 tool_index 由 PgVectorStore 建';

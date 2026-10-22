-- 容器首次启动时执行。pgvector 扩展只需装一次，之后业务数据、会话表与向量共用同一实例（ADR-0005）。
CREATE EXTENSION IF NOT EXISTS vector;

-- 业务数据放 business schema，会话与向量后续按 schema 分开，避免命名撞车。
CREATE SCHEMA IF NOT EXISTS business;

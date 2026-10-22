# 一个 Postgres 承担三种存储：业务数据、会话、向量

---
status: accepted
date: 2026-10-22
---

业务数据（销售域 schema）、`spring-ai-session` 的会话表、pgvector 向量库，三者共用同一个 Postgres 实例，靠 schema 与命名前缀分开。Redis 只保留语义缓存这一项用途。这样本地开发只需要一个数据库容器，不会出现"会话存 A 库、向量存 B 库、业务存 C 库"的配置拼装。

## Considered Options

**会话单独用一个库，与业务库隔离**
否决。多一个库就多一套连接配置、健康检查与容器，而会话表本身没有独立的隔离需求。隔离靠 schema 就够了。

**向量库继续用文件（1.x 的 `vector_store.json` 做法）**
否决。文件落盘在多实例下必然数据分裂，且检索能力无法演示 pgvector 的相似度阈值与混合过滤。

**Redis 兼做会话存储**
否决。`spring-ai-session` 的 JDBC starter 直接可用，而 Redis 做会话持久化缺少事务与查询能力；Redis 留给语义缓存更合适。

## Consequences

- `spring-ai-session` 走 `spring-ai-starter-session-jdbc`，会话表与业务表同库不同 schema。生产禁止用 `InMemorySessionRepository`（多实例失忆）。
- 向量检索用 pgvector，走 Spring AI 的 PgVectorStore；业务库用同一实例，`vector` 扩展只需装一次。
- Redis 只留 Lettuce 一个客户端；1.x 的 Jedis、Redisson 依赖不再引入，扫描类命令（`KEYS`）一律替换为带游标的迭代。
- docker compose 里只有三个服务：Postgres（含 pgvector）、Redis、应用本身。

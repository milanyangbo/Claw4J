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

- `spring-ai-session` 走 **`org.springaicommunity:spring-ai-starter-session-jdbc`**（版本见根 pom 的 `spring-ai-session.version`，当前 0.10.0），会话表与业务表同库不同 schema。生产禁止用 `InMemorySessionRepository`（多实例失忆）。
- 向量检索用 pgvector，走 Spring AI 的 PgVectorStore；业务库用同一实例，`vector` 扩展只需装一次。
- Redis 只留 Lettuce 一个客户端；1.x 的 Jedis、Redisson 依赖不再引入，扫描类命令（`KEYS`）一律替换为带游标的迭代。
- docker compose 里只有三个服务：Postgres（含 pgvector）、Redis、应用本身。

## 修订记录

**2026-10-23：补全坐标的 groupId。** 原文只写了 `spring-ai-starter-session-jdbc`，没写 groupId，
于是实现时（M1-06）按 Spring AI 官方的 `org.springframework.ai` 去 2.0.2 的 BOM 里找，查无此物，
一度得出"坐标不存在、要退到内置 ChatMemory"的结论。

错在 groupId：`spring-ai-session` 是 **spring-ai-community 的独立项目**（`org.springaicommunity`），
根 pom 里 import 的 `spring-ai-session-bom:0.10.0` 一直在管着它，
`spring-ai-starter-session-jdbc`、`spring-ai-session-jdbc`、`spring-ai-session` 三个 artifact 都在。

核实过的两点：**0.10.0 依赖 `spring-ai-model` / `spring-ai-client-chat` 的 2.0.1**，
被 2.0.2 的 BOM 提上来即可（项目就是这么跑的）；它自带 Postgres 方言与建表脚本，
会话表落哪个 schema 由连接的 `currentSchema` 决定——这一点不能靠它的自动配置，
它默认拿容器里那个主 `DataSource`，表会落到 public。

**同一次落地还定了另外两条**，都属于"会话存在库里"这件事的直接后果：

- **记忆不可用就降级，不拖垮整条链**。会话是增强能力不是安全边界：库挂了的时候，
  "没权限"这句话仍然要说出来。所以会话服务外面包了一层容错
  （`FaultTolerantSessionService`），存储异常时退化成"这一轮没有历史"并打 WARN。
  对比之下，鉴权与脱敏失败只能拒绝——放行就是越权发生、原始号码出去。
  三个 Advisor 的失败姿势不一样，是刻意区分的。
- **`InMemorySessionRepository` 由架构测试禁止**，不只是"现在没用它"。
  它能编译、能跑、单实例下表现完美，只在多实例下失忆，
  而本地演示永远只有一个实例——这种缺陷只会暴露在生产。

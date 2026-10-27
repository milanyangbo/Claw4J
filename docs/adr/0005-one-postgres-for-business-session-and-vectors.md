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
  （2026-10-26 修正：引回 Redis 做语义缓存时客户端要 **Jedis** 而不是 Lettuce，见修订记录。）
- docker compose 里只有两个服务：Postgres（含 pgvector）与应用本身。Redis 在 M3 语义缓存落地前不引入。

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

**2026-10-23：M1-05 / M1-07 定下的两条"库怎么用"的规则**，原先只在票与代码注释里，
这里补上——它们不是实现的副作用，是决策。

**schema 怎么演进：Flyway 版本化，只有一个入口。**
迁移脚本放在 `claw4j-capability` 的 `db/migration`（`V1` 业务表、`V2` 种子、`V3` 会话表），
改字段是加一个新版本文件，**不改已提交的版本**——本地常驻库已经记过账，改历史版本会让校验和不匹配。
三条随之而来的约束值得记：

- **建表由 Flyway 做，官方 initializer 关掉**（`spring.ai.session.repository.jdbc.initialize-schema=never`）。
  两套初始化并存时谁先谁后是笔糊涂账，而官方那份默认只在嵌入式库上跑，在 Postgres 上它什么都不做，
  靠它就等于"以为建了其实没建"；更要命的是它在 bean 创建时就要连库探测驱动类型，
  于是"库挂了"会变成"应用起不来"。
- **Boot 4 要显式引 `spring-boot-flyway`**。它把 Flyway 的自动配置拆成了独立 artifact，
  只有 `flyway-core` 在 classpath 上时，`spring.flyway.enabled=true` 是一句没有东西在执行的配置：
  不报错、不警告、日志里不出现 Flyway 三个字，只是表一直不建，而业务侧报的是"连不上库"
  （M1-07 第一次真实联调就是这么挂的）。这一条要有测试守着：classpath 上没有 `flyway-core` 时启动必须失败，而不是安静地不建表。
- **Flyway 建的语句写全限定名，运行时的查询不带前缀**。Flyway 连的是默认 `search_path`，
  不写前缀表就落到 public；而运行时那条连接带着 `currentSchema=claw4j_session`。
  建的时候限定、查的时候不限定，两边落点一致且都不依赖"当前 schema 是什么"。

**业务查询只能只读，而且要有两层，因为一层不够。**
第一层是语句校验（`SalesSqlGuard`）：去注释与字符串字面量后单语句、首关键字是 select/with、
不含写操作关键字、外面包一层子查询加行上限。第二层是只读事务
（`TransactionTemplate(readOnly=true)`），写操作由**数据库**拒绝。

只做第一层不够的理由很具体：**PostgreSQL 有可写 CTE**，
`with x as (insert into ... returning *) select * from x` 是一条语句且以 `with` 开头，
任何"首关键字是 select/with 就放行"的规则都会放过它。所以校验漏了，数据也不会没。
反过来只做第二层也不行：脏 SQL 会在数据库里报错再回滚，模型拿不到能改的信息，
自纠错链路就断了——自纠错的前提是工具把数据库的原话返回给模型（ADR-0008）。

**同一次落地还定了另外两条**，都属于"会话存在库里"这件事的直接后果：

- **记忆不可用就降级，不拖垮整条链**。会话是增强能力不是安全边界：库挂了的时候，
  "没权限"这句话仍然要说出来。所以会话服务外面包了一层容错
  （`FaultTolerantSessionService`），存储异常时退化成"这一轮没有历史"并打 WARN。
  对比之下，鉴权与脱敏失败只能拒绝——放行就是越权发生、原始号码出去。
  三个 Advisor 的失败姿势不一样，是刻意区分的。
- **`InMemorySessionRepository` 由架构测试禁止**，不只是"现在没用它"。
  它能编译、能跑、单实例下表现完美，只在多实例下失忆，
  而本地演示永远只有一个实例——这种缺陷只会暴露在生产。

**2026-10-26：Redis 先摘除，M3 落地语义缓存时再按 Redis Stack 引回。**

依赖（`spring-boot-starter-data-redis`）、`spring.data.redis` 配置、compose 的 `redis` 服务一起删掉。
理由不是"暂时用不上"这么轻：它当时的状态是"引了、配了、代码零引用"，而会话记忆走的是 Postgres，
于是每个读代码的人都要先问一句"记忆是不是存在 Redis 里"——这个误解在本项目已经发生过两次
（一次 review，一次对话）。留个占位省下的那点事，抵不上它带来的反复误读。

引回时有两个坑必须一起改，否则会白跑一遍：

- **镜像换 Redis Stack**（`redis/redis-stack-server`，或 Redis 8 及以上）。普通 `redis:7-alpine`
  没有 RediSearch 与 RedisJSON，`RedisVectorStore` 建索引时直接报
  `ERR unknown command 'FT._LIST'`——那是"装了但用不了"，比没装更难查。
- **客户端要 Jedis，不是 Lettuce**。`RedisVectorStore` 的构建器收的是 `JedisPooled`，
  而 `spring-boot-starter-data-redis` 默认装配的是 Lettuce 连接工厂。要么用官方的
  `spring-ai-starter-vector-store-redis`（自带 Jedis），要么把 Lettuce 显式排除。

上面 Consequences 里"只留 Lettuce 一个客户端"那条随之作废，已在原处标注。

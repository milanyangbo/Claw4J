# 一个 Postgres 承担三种存储：业务数据、会话、向量

---
status: accepted
date: 2026-10-22
---

业务数据（销售域 schema）、`spring-ai-session` 的会话表、pgvector 向量库（知识库），三者共用同一个 Postgres 实例，靠 schema 与命名前缀分开。本地开发只需要一个数据库容器，不会出现"会话存 A 库、向量存 B 库、业务存 C 库"的配置拼装。

**语义缓存不在这三者里**（2026-11-09 第三次修订）：它落在 Redis Stack，因为框架的 `DefaultSemanticCache` 只提供 Redis 实现。所以本地现在要两个容器（Postgres + Redis Stack），这是知道代价后接受的，理由见文末。

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
  （2026-10-26 修正：引回 Redis 做语义缓存时客户端要 **Jedis** 而不是 Lettuce，见修订记录。
  ~~2026-10-27 再修正：不引 Redis 了，语义缓存走库里的 pgvector，上面这条随之作废——见第二次修订。~~）
- docker compose 里只有两个服务：Postgres（含 pgvector）与应用本身。Redis 不引入
  （2026-10-27：M3-05 的语义缓存落在 `claw4j_cache` schema 的 pgvector 上，不是 Redis。
  ~~2026-11-09 再修正：改落 Redis Stack，`claw4j_cache` 已删——见第三次修订。~~）

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
迁移脚本按归属分散在各模块自己的 `db/migration`（示例插件 `V1` 业务表 / `V2` 种子，
`claw4j-agent` 的 `V3` 会话表 / `V4` 语义缓存表），改字段是加一个新版本文件，
**不改已提交的版本**——本地常驻库已经记过账，改历史版本会让校验和不匹配。
"只有一个入口"指的是**一个执行者**（应用启动时那一次迁移），不是"一个目录"：Flyway 会扫遍
classpath 上所有 `db/migration` 再按版本号排序，所以文件在哪个 jar 里不影响执行顺序，
而版本号必须全局唯一。
（2026-10-28，M6-04 修订：本条原文写的是"迁移脚本放在 `claw4j-capability` 的 `db/migration`"。
那三张表只有 `agent.session` / `agent.cache` 在读，让一个不碰 JDBC 的模块替它们背着
Flyway 与驱动是归属错误，DDL 与依赖已搬回 `claw4j-agent`。）
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

**2026-10-27：上面那条"引回 Redis Stack"作废——语义缓存直接用库里的 pgvector（M3-05）。**

上一条的判断前提是"Redis 有向量检索，所以语义缓存该放 Redis"。真到实现时这个前提不成立：

1. **这个缓存要和授权、会话在同一套数据里做一致性判断**。命中与否取决于权限指纹，
   而指纹来自角色配置；缓存的有效期要与会话里"上次说过的口径"对齐；清理任务与过期判定
   跟会话那边是同一套心思。放到另一个存储只是多一个**会不一致的来源**，
   而每多一个来源就多一种"两边对不上时信谁"的问题。
2. **pgvector 已经在库里了**。镜像自带、扩展已 `create extension`，
   一个迁移文件就能建表——相比之下引 Redis Stack 要换镜像、换客户端、加一个 compose 服务。
3. 代价是向量检索的性能上限：没有 HNSW/IVFFlat 索引时是全表精确扫描。
   这个缓存的量级是"一个角色一天几十到几百条"，全表扫描是微秒级；
   真到需要近似召回的那天，加一条迁移建 HNSW 即可——**那时候要接受近似召回会漏掉真正最近的一条**，
   所以这件事不该提前做。

于是上面那两条"引回时的坑"（换 Redis Stack 镜像、客户端要 Jedis）一并作废，
`spring.data.redis` 与 compose 里的 redis 服务继续不引入。**照这份 ADR 去装 Redis 的人会白装一遍，
这一条就是为拦住他写的。**

同一次落地还定下两条与"能不能命中"有关的规则，都不是调参能替代的：

- **缓存 key 含权限指纹（`tables + actions` 排序后哈希），不含 `userId`、不含角色名。**
  前者是安全边界（guest 读不到 `sales_order`，同一个问题两个角色答案不同），
  后两者是可用性（按人分等于完全不共享；按角色名分则角色改一次配置缓存全失效）。
- **相似度阈值挡不住"只差一个时间词"的问题。** 实测 `text-embedding-v3`（1024 维）：
  「上周华东成交额多少」与「上上周华东成交额多少」余弦 **0.9950**，
  而真正的同义改写「华东上周卖了多少」只有 **0.9372**。也就是说任何单一阈值都无法
  同时命中后者、挡住前者。默认阈值定在 0.93 而不是当初拍的 0.95（0.95 会让同义改写全部落空）。
  这一段挡的是"时间窗不同"，挡不住同义词层面的差别（"成交额"与"销售额"）——
  语义缓存对"数值/时间实体只差一点"的问答本质上不可靠，
  它只该用来省"同一个人十分钟内换个说法再问一遍"的成本，不该被当成正确答案的来源。
  （2026-10-27 当时还加了一维"问题里的时间词与数字"来分开这两句，那一维在第三次修订里删掉了。）

上面 Consequences 里"只留 Lettuce 一个客户端"那条随之作废，已在原处标注。

**2026-11-09：语义缓存移出这个 Postgres，改落 Redis Stack（第三次修订）。**

理由不是容量或性能，是"不要自己实现官方没有的东西"：改用框架的 `SemanticCacheAdvisor` 之后，
它的存储接口只有一个官方实现（`DefaultSemanticCache`，Redis + RediSearch）。继续用 pgvector
就得自己维护表结构、TTL、唯一键、时间窗维度——那四样官方都没有。判据见
`docs/agents/alignment.md`（那一份就是这次踩坑写下来的）。

后果，逐条记清楚：

- `claw4j_cache` schema 由 `V9__drop_semantic_cache.sql` 删掉；本文上面讲的"三种存储"
  仍然成立（业务数据、会话、知识库向量），**语义缓存不再算其中一种**。
- 语义缓存从"可选优化"变成**强依赖**：Redis 不可用时启动失败，运行中挂掉这一轮报错。
  原来那句"缓存不参与可用性"跟着旧实现一起作废，不是被放弃了，是它本来就只对旧实现成立。
- 「上周」与「上上周」重新落进同一个上下文（阈值挡不住 0.9950），会互相命中。
  这是照官方实现走的既定结果，记在 `docs/design/semantic-cache.md` 的"刻意不做的东西"。
- Redis 的镜像必须是 `redis/redis-stack-server`（带 RediSearch + RedisJSON），
  普通 Redis 建不出向量索引。

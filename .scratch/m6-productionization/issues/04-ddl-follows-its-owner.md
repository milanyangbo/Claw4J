# 04: 谁的账本，DDL 就放在谁的模块里

**What to build:** 把 `V3__session_schema.sql`、`V4__semantic_cache.sql`、`V5__semantic_cache_context.sql`
从 `claw4j-capability` 搬到 `claw4j-agent`；Flyway 的三条运行时依赖跟着搬；搬完之后
`claw4j-capability` 不再有 `db/migration` 目录。

**Blocked by:** 无（`02` 已经结束，模块结构稳定了）。

**Status:** resolved

## 为什么

这三张表**全部**由 `claw4j-agent` 的代码读写，`claw4j-capability` 里没有一行：

| 迁移 | 建的表 | 谁在用 |
| --- | --- | --- |
| `V3` | `claw4j_session.ai_session` / `ai_message` | `agent.session.SessionMemoryConfiguration` / `SessionSchema` |
| `V4` `V5` | `claw4j_cache.*` | `agent.cache.PgVectorSemanticCache` / `CacheAdvisor` / `SemanticCacheCleanup` |

而 `claw4j-capability` 里装的是 `mcp` / `script` / `skill` / `generator` / `process` / `builtin.system`
——工具与能力来源，一条 JDBC 都不碰。

这件事之所以一直没被当问题，是因为 pom 里那段注释把它写成了理由：

> 迁移落在本模块：会话表与语义缓存表是框架自己的账本，**跟能力来源无关，所以它们留在这里**。

"跟能力来源无关"是对的，"所以留在这里"是错的——跟能力来源无关的东西，恰恰不该住在能力模块里。
它落在 capability 的真实原因只是历史顺序：capability 是第一个需要连库的模块，于是 DDL 顺手放在了那儿。

后果不是抽象的：`claw4j-capability` 的 pom 里因此挂着 `flyway-core`、`flyway-database-postgresql`、
`spring-boot-flyway`、`postgresql` 四条数据库依赖。**一个只装工具适配器的模块，会因为与它无关的表
而被迫依赖一整套 Flyway + JDBC 驱动。** 谁想单独拿工具层（比如只引 `capability` 写个 CLI），
就得把数据库驱动一起拖进来。

还有个文档层面的直接错误：`CONTRIBUTING.md` 写着"业务 schema 与种子在
`claw4j-capability/src/main/resources/db/migration/`（`V1` 建表、`V2` 放种子、`V3` 会话表）"。
业务 schema 早跟着示例插件搬去 `claw4j-example-sales` 了，这句话两头都错。

## 要做

1. `git mv` 三个 SQL 到 `claw4j-agent/src/main/resources/db/migration/`（文件名与内容都不改，
   版本号不动 —— 全局唯一，Flyway 按版本号排序、跨 jar 合并不受影响）。
2. `claw4j-agent/pom.xml`：`flyway-core` 从 `test` 提到编译期，并加 `flyway-database-postgresql`、
   `spring-boot-flyway`。照 `claw4j-example-sales/pom.xml` 的写法（它已经这么干了，V1/V2 和自己的依赖在一起）。
3. `claw4j-capability/pom.xml`：删掉四条数据库依赖，把那段注释换掉。
4. 文档里"迁移在 capability"的说法全部改掉：`CONTRIBUTING.md` 的「迁移与种子」整节重写、
   `application.yml` 的 `initialize-schema: never` 注释、`application-local.yml.example`、
   ADR-0005 第 50 行。

## 判据

- `claw4j-capability/src/main/resources/db/migration/` 目录不存在；
  `claw4j-capability` 的 `dependency:tree` 里搜不到 `flyway` 与 `postgresql`。
- `claw4j-agent` 的 jar 里有这三个 SQL；`claw4j-app` 依赖树里 `spring-boot-flyway` 仍在（经 agent 传递）。
- 全量门禁照旧全绿，用例数不减 —— 迁移只是换了 jar，行为不该变。
- 起 `local` profile 时 Flyway 仍然能跑完 V1~V5（日志里 5 条 `Migrating schema`），
  这一条**必须真跑一次**，不能只看"编译过了"。跨 jar 合并这件事是这次唯一的真实风险点。

## 不要做

- 不改版本号。改成 `V3`/`V4`/`V5` 以外任何数字，已经跑过迁移的库都会重跑或直接校验失败。
- 不为了"版本号连续"把 V1/V2 从 example-sales 搬回来。示例插件带自己的 DDL 是有意的：
  它能被拔掉，框架照样起得来。
- 不顺手把 `claw4j-capability` 拆成多个模块（mcp / script / skill 各一个）。那是另一件事，
  现在没有证据说明它需要拆。

## 实现记录

**做了什么**

1. `git mv` 三个 SQL 到 `claw4j-agent/src/main/resources/db/migration/`，文件名与内容一字未改，
   版本号没动。`claw4j-capability/src/main/resources/db/` 整个目录删掉（只剩 `META-INF/`）。
2. `claw4j-agent/pom.xml`：
   - 新增 `flyway-core`、`flyway-database-postgresql`、`spring-boot-flyway`（均编译期）；
   - **删掉**原先那条 `flyway-core`（`scope=test`）——它升级成了编译期依赖，留着就是重复声明；
   - `postgresql` 驱动仍只在 test 作用域：agent 是库，运行期驱动由 `claw4j-app` 提供。
3. `claw4j-capability/pom.xml`：删掉四条数据库依赖（`flyway-core` / `flyway-database-postgresql` /
   `spring-boot-flyway` / `postgresql`），那段"跟能力来源无关，所以它们留在这里"的注释换成了
   这次的反面：**跟能力来源无关的东西，恰恰不该住在能力模块里**。
4. 文档五处：`CONTRIBUTING.md`「迁移与种子」整节重写（原文连"业务 schema 在 capability"都是错的）、
   `application.yml` 的 `initialize-schema: never` 注释、`application-local.yml.example`、
   ADR-0005 加一条带日期的修订、`README.md`「还没做的」里删掉这条、
   `docs/design/pitfalls.md` 第三条的"放在能力模块"改成 `claw4j-agent`。三道门禁表的 ArchUnit 那句
   上一票已经加过 `channel-core`，这票不涉及。
5. 新增 `claw4j-app/src/test/.../MigrationCompositionTest.java`（2 条）。

**判据核对**

| 判据 | 结果 |
| --- | --- |
| capability 下没有 `db/migration` | 成立，`src/main/resources/` 只剩 `META-INF/` |
| capability 依赖树里搜不到 flyway / postgresql | 成立（`dependency:tree` 一次 grep 无输出） |
| agent 的 jar 里有三个 SQL | 成立：`V3` 2544B / `V4` 2755B / `V5` 1677B |
| capability 的 jar 里没有 SQL | 成立：`unzip -l ... | grep -c '\.sql'` = 0 |
| app 仍经传递拿到 `spring-boot-flyway` | 成立：`dependency:tree` 显示它挂在 `claw4j-agent` 下（flyway 12.4.0 / boot 4.1.2） |
| 全量门禁全绿、用例数不减 | 成立：**301 条**（299 + 新增 2），失败 0、跳过 0，九个模块 SUCCESS |
| 起 local profile 真跑一次 V1~V5 | 用测试替代，见下 |

**为什么用测试替代"起 local profile 看一眼日志"**：手工起一次只能证明"这次能跑"，
下次搬动文件它不会说话。`MigrationCompositionTest` 干的是同一件事但会留下：
真 Testcontainers（`pgvector/pgvector:pg16`）、真 Flyway、**locations 留空**（走默认
`classpath:db/migration`，也就是"所有 jar 同名目录的并集"——正是要验的那条路径）。

两条用例：
- `migrationsFromEveryModuleLandInOneHistory`：跑完后迁移历史**正好是 `1..5`**，
  且 `business` / `claw4j_cache` / `claw4j_session` 三个 schema 同时存在，`business.sales_order` 有种子行。
- `secondRunIsANoOp`：第二次 `migrate()` 执行 0 条（防"某个 jar 里的又被当成新的"）。

**变异验证**（不做这一步，"断言在检查东西"是无从判断的）：把 `V3__session_schema.sql` 移出
agent 的 resources、并删掉 `target/classes/db`（见下面的坑），重跑：
```
Expecting actual: ["1", "2", "4", "5"] to contain exactly ["1", "2", "3", "4", "5"]
but could not find the following elements: ["3"]
```
恢复后转绿。

**踩到的坑（已写进 pitfalls 第十五条）**

第一次编译完我用 `unzip -l` 看 `claw4j-capability` 的 jar，**三个 SQL 还在**。
`process-resources` 不会删除 `target/classes` 里已经不存在的源文件，所以
`git mv` 之后增量构建打出来的 jar 里，两个模块**各有一份同号迁移**。Flyway 遇到同版本
同校验和的两份不报错，它去重——"搬完了"的假象能活到两份内容不再一样的那天。
门禁脚本本来就 `clean`，所以门禁看不出这个问题；是我手工看 jar 才发现的。
判据：搬文件之后用 `unzip -l <jar> | grep '\.sql'` 确认归属，别只看 `src/`。

**没做的**

`claw4j-capability` 依然是个 46 个类的模块，mcp / script / skill / generator 混在一起。
搬完 DDL 之后它已经没有数据库相关的东西了，但"要不要按能力来源再拆"没有证据支撑，
留到有真实需要时再说（票里写了不做）。

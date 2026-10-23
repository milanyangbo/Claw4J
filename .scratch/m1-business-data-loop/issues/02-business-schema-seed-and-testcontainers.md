# 02: 业务 schema 与种子 + Testcontainers 干净实例

**What to build:** 销售域的业务 schema 与可复现的种子数据，以及一个每次测试都销毁重建的 Postgres 实例。这一张票定下"数从哪来"，后面 Text-to-SQL 那张票的判据要跟这里的种子答案对得上。

**Blocked by:** None (can start immediately)

**Status:** resolved

**Demo:** `mvn verify` 里有一条连着一次性 Postgres 的测试，用固定 schema 算出"上周华东区成交额"的确定答案——这个答案写死在断言里，不是口头约定。

- [x] 销售域四张表：区域 / 客户 / 订单 / 订单项
- [x] **迁移方式定在下面 Answer 里**（Flyway），判据也写在那里
- [x] 种子数据让"上周华东区成交额"有唯一确定答案，该数值写进测试断言
- [x] Testcontainers 起干净 Postgres，用完销毁（每**类**一个容器，见 Answer 的偏差）
- [x] 数据源接缝的第一条断言：给定时间窗与区域，查出的金额等于预期值。开工时是红的——库和类都不存在
- [x] compose 常驻（开发用）与 Testcontainers（测试用）的分工写进 README
- [x] 这条慢测试不进提交门禁：pre-commit 仍然只跑 `*ArchitectureTest`

**注意**：pgvector 扩展已经在 compose 里，但 M1 不接向量检索，这张票不要顺手把 PgVectorStore 拉进来。
（做到了：V1 里没有 `create extension vector`。测试镜像仍用 pgvector 镜像，跟 compose 保持一致，
免得 M3 才发现"测试的库和生产不是一种库"，但扩展本身留到 M3 那张票。）

**判据要对齐**：本票算出的那个金额，就是 05 号票"问一句话拿到数"要拿到的同一个数。两张票的断言值必须一致，否则会出现"库里算的是 A、Agent 答的是 B"这种谁都没错的假绿。

---

## Answer

### 迁移方式：Flyway，逐条对判据

| 判据 | 怎么满足的 |
| --- | --- |
| 能在 Testcontainers 上重复执行 | 每个新库从 V1 跑到最新。有一条测试专门跑第二次 `migrate()`：第二次是 no-op，所以它断言的是 `flyway_schema_history` 里只有 2 条成功记录——把 V2 写成 repeatable、或改了已提交版本，这条会红 |
| CI 可重现 | CI 只要能起容器就必然得到同一个库，不依赖 runner 上留过什么 |
| 改字段不需要手工清库 | 加 `V3__xxx.sql` 即可。**不要回头改已提交的版本**：本地常驻库已经记过账，改历史版本的 checksum 会让它的校验失败，而删库重建正是这套迁移要替你免掉的事 |

没选 `schema.sql` + `data.sql`：那种"幂等靠重建"的路子只在每次都空库的场合成立，
而本地那个 compose 库是**常驻**的，改字段就得手工清库——正好是判据里要避免的那件事。

### 数字契约（05 / 06 直接引用）

| 问法 | 答案 |
| --- | --- |
| 上周华东区成交额 | **128,400.00**（54000 + 74400） |
| 上周华南区成交额 | **26,000.00**（06 号票多轮追问"那华南呢"用这个） |
| 上周华北区成交额 | 31,500.00 |
| 本周华东区成交额 | 99,999.00 |

**成交额只算未取消的订单**：上周华东还有一单 50,000 是 `CANCELLED`，它不该出现在任何答案里。
漏掉 status 过滤会算出 178,400（而不是 128,400），变异检验时确实红了 4 条。

05 号票的断言值必须是 128,400.00，不能"也写一个差不多的数"。

### 时间窗基准从数据库取，不从 JVM 取

种子的日期是 SQL 里的相对表达式（按 `date_trunc('week', current_date)` 推算），
测试若按 JVM 时区另算一遍上周一，跨日或跨时区时两边会差一天，而差一天就把订单推到另一个周里去。
所以测试里 `thisMonday` 是**查出来的**，Java 只在它上面做加减。

### 一个必须知道的事实：相对日期只在迁移那一刻求值

种子写进库之后就是绝对日期了。所以：
- 测试库每次重建 → 每次迁移 → "上周"永远正确；
- 本地常驻库建好之后，它里面的"上周"是**建库那天**的上周，放久了照样漂空。
  演示时发现数字对不上，重建一次库就好。README 里写明了这条。

### 三个环境坑（都不改代码、只换环境就会撞上）

1. Boot 4.1.2 的 BOM 指向 testcontainers **2.0.5**，镜像源里这个 artifactId 只同步到 **1.21.4**。
   而且 BOM 管得住 postgresql / junit-jupiter，**管不住** junit-jupiter 传递引的 core——
   得在根 POM 里单独再钉一次 core，否则 1.21.4 的扩展配上 2.0.5 的 core，跑起来是混着来的。
2. 镜像站的名字（`docker.m.daocloud.io/pgvector/pgvector:pg16`）长得不像 postgres，
   Testcontainers 在**启动前**就拒绝它，必须 `asCompatibleSubstituteFor("postgres")`。
   用官方镜像的人永远撞不到这个坑。
3. Testcontainers 还要拉 ryuk（1.21.4 要 **0.12.0**）。Docker Hub 不可达时它不报错，
   表现为整类静默跳过。

### 假绿风险与 `-Dclaw4j.dbTests=required`

拉不到镜像时整类跳过，而**跳过的绿和通过的绿在构建结果里长得一模一样**。
所以：本机默认宽容（不该逼人为了提交去拉镜像），CI 上跑
`./mvnw verify -Dclaw4j.dbTests=required`——那边跑不起来就直接失败。
两种模式都实测过：镜像缺失时 required 是 `BUILD FAILURE`，默认是 8 skipped 加一行 stderr 提示。

### 两处与票面文字的偏差

1. **"每次销毁重建"是每个测试类一个容器，不是每个方法一个**。每方法一个会让这套已经最慢的
   测试再慢十倍，而类内共享不会引入串扰（这些测试全是只读的）。
2. `SalesDataRepository` **没有注册成 Spring bean**（评审判定这属于 05 号票接工具时才需要的装配，
   现在没有任何东西用到它）。05 号票要先补一个自动配置，或直接把它塞进 `ToolsAutoConfiguration`。

### 留给后面的

- Flyway 历史表落在 public（不是 business schema）。业务表都在 business 下，跟会话、向量分开，
  这一条 ADR-0005 的要求是满足的；历史表归 public 属于工具自己的账本，暂不动。
- M3 接向量时要在某个版本里 `create extension vector`，测试镜像已经是 pgvector 镜像了。
- 只读约束（05 号票定）落在这一层最合适：现在 `SalesDataRepository` 只有一条聚合查询，
  没有暴露 `JdbcClient`，正是为了不给"随便写 SQL"留口子。

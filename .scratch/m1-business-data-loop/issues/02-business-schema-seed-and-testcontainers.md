# 02: 业务 schema 与种子 + Testcontainers 干净实例

**What to build:** 销售域的业务 schema 与可复现的种子数据，以及一个每次测试都销毁重建的 Postgres 实例。这一张票定下"数从哪来"，后面 Text-to-SQL 那张票的判据要跟这里的种子答案对得上。

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

**Demo:** `mvn verify` 里有一条连着一次性 Postgres 的测试，用固定 schema 算出"上周华东区成交额"的确定答案——这个答案写死在断言里，不是口头约定。

- [ ] 销售域四张表：区域 / 客户 / 订单 / 订单项
- [ ] **迁移方式在这张票里定**（Flyway 还是 `schema.sql` + `data.sql`），并把判据写进票的 Answer：能在 Testcontainers 上重复执行、CI 可重现、改字段不需要手工清库
- [ ] 种子数据让"上周华东区成交额"有唯一确定答案，该数值写进测试断言
- [ ] Testcontainers 起干净 Postgres，每次销毁重建（ADR-0008：compose 常驻会给测试带来脏数据串扰）
- [ ] 数据源接缝的第一条断言：给定时间窗与区域，查出的金额等于预期值。**开工前必然红**——库根本还不存在
- [ ] compose 常驻（开发用）与 Testcontainers（测试用）的分工写进 README
- [ ] 这条慢测试不进提交门禁：pre-commit hook 仍然只跑 `*ArchitectureTest`

**注意**：pgvector 扩展已经在 compose 里，但 M1 不接向量检索，这张票不要顺手把 PgVectorStore 拉进来。

**判据要对齐**：本票算出的那个金额，就是 05 号票"问一句话拿到数"要拿到的同一个数。两张票的断言值必须一致，否则会出现"库里算的是 A、Agent 答的是 B"这种谁都没错的假绿。

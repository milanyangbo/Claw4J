# 07: 真实模型联调与文档收尾

**What to build:** 前面六张票都是在脚本化假模型上验的。这一张用真实供应商把整条闭环跑一遍，并把本轮新定的决策与新的演示方式写进文档。两个真实 key（DeepSeek 与通义）都已在本机 `.env` 里，随手可用。

**Blocked by:** 03, 04, 05, 06

**Status:** resolved

**Demo:** 用真实模型跑一遍完整闭环，curl 的真实输出（同步与流式）录进 README，换一个角色头演示没权限时拿不到。

- [x] 真实模型跑通同步与流式，两家各一次（`CLAW4J_MODEL_PRIMARY` 切换）
- [x] 主备降级在真实链路上复现一次（把主的 base-url 指到没人听的端口，看请求落到备）
- [x] README：curl 示例换成真实闭环（问"上周华东区成交额"拿到数）、模型切换说明、测试计数更新
- [x] GLOSSARY：补 M1 新增的术语（`@Tool`、会话 / Chat ID 的区别、脱敏）
- [x] ADR 留痕：本轮在票内定下的三个决策——schema 迁移方式、只读约束的落法、自纠错重试上限——写进对应 ADR，不要留在 `.scratch/` 里
- [x] 真实调用的集成测试用 profile 隔离，默认不进 CI（ADR-0006、ADR-0008）

**注意**：真实调用不进 CI，它是一次性的闭环证明，不是回归基线。回归基线是前六张票留下的那些测试。

**顺手对一遍**：M0 结束时是 64 个测试。这一张票收尾时要确认新增的测试数被写进 README，且 `mvn verify` 全绿。

## Answer

**闭环（真实 key + 真库，同一句话"上周华东区成交额多少"，库里就是 128,400.00）**

| 场景 | 结果 |
|---|---|
| DeepSeek 主 · 同步 | `￥128,400.00`，并自报口径（排除 CANCELLED、上周一到本周一左闭右开） |
| DeepSeek 主 · 流式（华北） | 逐块吐出 `￥31,500.00`，末尾 `done` |
| DeepSeek 主 · 追问"那华南呢" | `￥26,000.00`，并说明华东那个数来自上一轮 —— 会话记忆在真模型上接得上 |
| `guest` 角色 | `没有权限：角色 guest 不允许访问表 customer、order_item、sales_order（工具 querySales）` |
| 通义为主 | `上周华东区成交额为 128,400.00 元。` |
| 主挂掉（base-url 指到没人听的端口） | 6.4 秒后由通义答出 128,400.00，日志 `模型 deepseek 调用失败，降级到下一个（若有）` |

**真实联调抓到的三个缺陷（都只在真实链路上出现）**

1. **启动即迁移根本没跑。** Boot 4 把 Flyway 的自动配置拆成了独立 artifact
   （`spring-boot-flyway`），只有 `flyway-core` 时 `spring.flyway.enabled=true`
   是一句没有东西在执行的配置：不报错、不警告，业务侧报的是"连不上库"。
   补依赖（跟着迁移脚本放在 tools 模块），并由 `SchemaMigrationOnStartupTest` 盯着
   （变异：去掉依赖 → 两条 error、BUILD FAILURE）。
2. **降级时工具会丢。** 跨厂商换参数只搬了标量，工具回调没搬，于是备模型看不到 `querySales`，
   回答变成"我无法直接访问数据库"——没崩、没超时，只是查不到数。补 `copyTools` 并钉断言。
3. **模型内部重试把降级拖成分钟级。** Spring AI 默认的重试模板是 10 次、2s 起、×5、上限 180s，
   与上层"换一家"相乘，实测 4 分 40 秒还没轮到备。改成 `SHORT_RETRY_TEMPLATE`（约 1 秒）后 6.4 秒完成。

三个都有变异检验：去掉实现 → 对应的测试红（分别是编译/加载失败、1 条红、1 条红）。

**文档落点**

- README：进度改为 M1 完成；curl 示例换成上面这批真实输出（同步/流式/没权限/追问/换主模型/降级）；
  迁移一节补"Boot 4 要显式引 `spring-boot-flyway`"；新增"降级这条路径上踩到的两个坑"；
  `-Preal-model` 的用法与"真实调用不进 CI"的理由；测试计数 171 → 175。
- GLOSSARY：新增 Advisor、`@Tool`、Text-to-SQL、只读事务、自纠错、调用预算、脱敏；
  Chat ID 那条补上"本项目的会话 id 只由用户标识派生，不掺 Chat ID"。
- ADR-0005：补 schema 演进方式（Flyway 版本化、一个入口、建表写全限定名而查询不带前缀）
  与只读两层（可写 CTE 是只做第一层不够的具体理由）。
- ADR-0006：补两个降级缺陷与"备模型的回答质量不保证与主模型一致"。
- ADR-0008 里已有自纠错重试上限（3/6），无需再补。

**真实调用怎么隔离**：`RealModelIT`（app 模块）以 IT 结尾，surefire 默认不收；
`-Preal-model` 把 includes 打开；主模型走 `CLAW4J_MODEL_PRIMARY`，与运行时同一个开关。
两家各跑一遍 4 条断言全绿（同步 / 流式 / 越权 / 追问继承）。它不进 CI：
烧 token、依赖外网、还依赖本机起着的库，三条里断一条就红，而那不是回归。

**测试数**：175（domain 13 / tools 33 / agent 97 / channel 20 / app 12），全绿。

## Code review（2026-10-23，固定点 `8c87eda`，覆盖 M1-03～07）

两条轴各跑一个子代理：Standards（对照 ADR/GLOSSARY + Fowler 坏味道基线）、
Spec（对照 spec.md 与 03～07 的验收清单）。**Standards 无硬违规**，
Spec 抓到一条 P0，另有几条断言失真。

### P0：26 条数据库测试从未执行，却被计进"175 全绿"

守卫 `imageAvailable` 判的是**本地有没有这个镜像的缓存**（`inspectImageCmd`）：
机器没缓存过 `pgvector/pgvector:pg16` 时，全部数据库测试整类静默跳过，
而跳过的绿和通过的绿在构建结果里一模一样。实测（本机没缓存）：

```
claw4j-tools: 33，跳过 16   SchemaMigrationOnStartup 2 / ReadOnlySqlExecutor 6 / SalesDataRepository 8
claw4j-agent: 97，跳过 10   SalesQueryEndToEnd 3 / SessionMemoryEndToEnd 7
总计 175，其中从未执行 26，实际执行 149
```

被跳过的正好是 M1 最硬的三条判据：只读约束（DDL/DML 不可能通过提问发生）、
会话记忆继承（第二轮追问接得上第一轮）、启动即迁移。
CI 上更糟：`-Dclaw4j.dbTests=required` 会让守卫抛 `IllegalStateException`，
整类 error，而不是让 Testcontainers 去把镜像拉下来——守卫把"没拉过"和"拉不到"当成了同一件事。

**修法**：判据改成"Docker 守护进程在不在"（`DockerClientFactory.instance().isDockerAvailable()`），
拉镜像交回 Testcontainers。拉不动就红——那是环境问题，该红；静默跳过才是要防的。
`required` 的语义收窄成"连 Docker 都没有就别装绿"。
Docker 在时往 stderr 打一行"正在用哪个镜像起库"，卡住的人知道该换哪个源。

修完实测：`CLAW4J_POSTGRES_IMAGE=... ./mvnw verify` → **177 全绿、跳过 0**。

### 其余三条

- **`ToolCallBudget.MAX_TOTAL_CALLS`（6）没有任何断言，而且在当前工具集下不可达**：
  单工具上限（3）比它小，M1 只注册了一个工具，6 永远撞不到。
  按"不留测不出作用的代码"这条原则它该删，但它守的"多工具叠加"是真实风险，
  所以新增 `ToolCallBudgetTest`：造三个假工具各调 2 次，第 7 次落在 gamma 上
  （它才第 3 次，没撞单工具上限），于是只有总数上限能拦住它。
  变异：去掉 `maxTotalToolCalls` → 2 条全红。
- **HTTP 层鉴权退回成了"猜"**：票 03 要的是"用调用计数证明工具一次都没被调用"，
  而 `AuthorizationOverHttpTest` 的替身执行器是无计数 lambda，三条断言只比对文本。
  工具跑了但查出来是空的，文本里同样没有数字——那种情况它抓不到。
  换成计数替身，并按"请求前后差值"断言（三条测试共用一个 Spring 容器）。
  变异：拒绝时只换文本、保留 toolCalls → 文本断言仍绿、**计数断言红 2 条**。
- 文档两处失真：`SalesSqlGuard` 的 `{@link JdbcSalesQueryExecutor}` 指向不存在的类；
  README 把调用预算写成框架自带的 `ToolCallLimits`（实际是 `ToolCallingManager`
  的 `maxCallsPerTool`/`maxTotalToolCalls`），并声称"有单测钉着"（只钉了一半）。

### 未消除的两条（记录在案）

- **测试夹具 `PostgresTestSupport`（tools）与 `PostgresSupport`（agent）仍是两份**：
  合并要靠 tools 的 test-jar，而那会让 `./mvnw test`（不到 package 阶段）在 agent 上解析失败——
  为了消一份重复去破坏一条更常用的命令，不划算。两份都加了互相指名的注释。
- Standards 轴提的 `AuthorizationAdvisor#denialText` 重复判定、`Principal#canUse` 是 Middle Man：
  前者是**归因**（已判定越权之后，为了说清"是动作还是表"再分项查一次），不是第二套判定；
  后者是给 Advisor 留的单一入口。都不改。

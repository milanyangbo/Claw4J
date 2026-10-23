# 05: Text-to-SQL 闭环 —— 问一句拿到库里的数

**What to build:** 真实的业务 `@Tool` 替换 01 号票的桩工具：模型把自然语言问题变成 SQL，只读执行，拿回行再组织成回答。这是 M1 判据的正面——"能在 IM 里问上周华东区成交额并拿到数"。

**Blocked by:** 01, 02

**Status:** resolved

**Demo:** curl 问"上周华东区成交额多少"，拿到的是业务库算出来的数，不是模型编的。

- [x] 真实 `@Tool` 落在 `claw4j-tools`，替换 01 的桩工具
- [x] **只读约束的落法在这张票定**（只读库用户 / 语句校验 / 两者都要），并把理由写进票的 Answer
- [x] 一条"DDL 与 DML 不可能通过提问发生"的断言——只读约束没有断言就是口头承诺
- [x] 结果行数上限
- [x] 自纠错链路用固定 schema 跑通（ADR-0008 列为必测项），重试上限在这张票定
- [x] `@Tool` 方法接缝：给定参数断言返回的行（脱离模型，单独定位 SQL 执行）
- [x] AgentPort 端到端：拿到库里算出来的数，且**与 02 号票种子数据的确定答案一致**
- [x] 流式路径同样拿得到

**注意**：这条闭环是面试演示的主路径，所以要能在两种模式下都跑——脚本化假模型（CI 与无网络）与真实模型（07 号票）。别把判据写成"只有在真实模型下才成立"。

**不要做**：澄清追问（口径不对时先问一句再算）——spec 里明确不在 M1。向量检索与 RAG 也不在这张票。

## Answer

提交 `cb39bb9`。142 个测试全绿（`CLAW4J_POSTGRES_IMAGE=docker.m.daocloud.io/pgvector/pgvector:pg16 ./mvnw clean verify`）。

### 一、只读约束的落法：两层都要，理由是可写 CTE

票面让在"只读库用户 / 语句校验 / 两者都要"之间选。**选两者都要，但第二层不是只读库用户，是只读事务。**

决定性的理由很具体：PostgreSQL 有可写 CTE，

```sql
with doomed as (delete from business.sales_order returning *) select * from doomed
```

这是**一条**语句，而且以 `with` 开头。任何"首关键字是 select/with 就放行"的字符串规则都会放过它。
所以字符串校验能拦下绝大多数写操作，但"绝大多数"不是保证，必须有第二层。

第二层为什么不选只读库用户：配只读用户要引入第二套连接凭据（迁移用 owner、运行用 reader），
而这套凭据在 Testcontainers 里还得现场造一遍角色与授权。
复杂度换来的保护和"事务本身是只读的"完全等价——
`TransactionTemplate.setReadOnly(true)` 由 Spring 翻译成 `Connection.setReadOnly(true)`，
PostgreSQL 驱动再翻译成事务级只读标记。真要上生产，只读用户仍然该配，那是第三层，与这一层不冲突。

**顺带删掉了一段自己加的冗余代码**：我一开始额外发了一条 `set transaction read only`，
变异检验（去掉它）显示 5 条数据库测试照样全绿——驱动已经做了这件事。
留着就是一段"测不出作用"的代码，这种代码比没有更糟：它会让人以为读只读是靠它生效的。

### 二、只读断言的写法被现实纠正了两次

第一版断言"delete/drop 抛异常且消息含 read-only"，**红得很意外**：

| 语句 | 实际结果 |
| --- | --- |
| `delete from ...` | `bad SQL grammar [select * from (delete ...) as claw4j_limited limit 51]` |
| `drop table ...` | 同上，连解析都过不去 |
| 可写 CTE | `WITH clause containing a data-modifying statement must be at the top level` |

原因全是同一个：行上限那层**包一层子查询**的写法顺手把它们挡在了解析阶段——
PG 的子查询里不能是 DML，带数据修改的 WITH 又必须在最外层。
这是包装带来的额外一道拦截，不是设计时就打算依赖的，但它把"字符串校验漏掉"的后果
从"数据没了"降级成了"语法错误"。

于是断言改成：**抛异常 + 数据一条没变**，不断言具体消息。
而"只读事务真的生效"另找一条能穿过包装的写操作来证明：`select ... for update`
是合法的 select（能穿过包装），撞到的就是数据库那条规则。
去掉 `template.setReadOnly(true)` 的变异检验里，只有这一条变红——它才是真正的证据。

### 三、自纠错：失败要返回原因，不能抛异常

工具不抛异常，而是把数据库的原话（取 root cause，因为 Spring 会把 PG 的原话包进
`BadSqlGrammarException`，最外层那句只有"prepared statement 执行失败"）拼上改写提示返回。
抛异常会被框架包成模型看不懂的话，模型只能原样再写一遍同样的 SQL。

固定 schema 写在工具描述里，连区域代码的取值（`east`/`south`/`north`）都给了。
这不是啰嗦：模型写 `where region_code = '华东'` **不报错、只返回 0 行**，
而"返回 0 行"是自纠错唯一无法自己发现的错误——没有报错就没有改写的依据。
零行时工具会明确提示"检查 region_code 是否用了代码"。

### 四、重试上限：用框架自带的，但不能走配置

`DefaultToolCallingManager.Builder` 自带 per-tool 与 total 上限，默认 **40 / 150**，
`THROW` 行为下由 `ToolCallingAdvisor` 接住 `ToolCallLimitExceededException`、
直接变成一代回答给客户端，不再回头问模型——这正是想要的（另一种行为
`RETURN_ERROR_RESPONSE` 会把超限当成工具结果喂回模型，等于没设上限）。

40 是为分页取数设计的，放在自纠错上就是一次提问烧上百次往返。收紧到 **3 / 6**。

**关键坑**：`spring.ai.tools.limits.*` 配置到不了这条链。`ChatClient` 是我们自己建客户端的
（`ChatClient.builder(chatModel)`），它内部用的是框架内建 manager 与默认值；
只有五参重载 `ChatClient.builder(chatModel, observations, null, null, toolCallingAdvisor)`
里传进去的 manager 才生效，而 Boot 自动配置那个 manager 只在使用注入的
`ChatClient.Builder` bean 时才会被用上。变异检验里把装配退回单参 builder，
"一直写错时在上限处截断"立刻红（执行器被调了 4 次而不是 3 次）。

### 五、装配上的一个静默失效坑：`afterName`

`ToolsAutoConfiguration` 加了 `@ConditionalOnBean(JdbcClient.class)` 之后，
app 的四个测试全红了：真实执行器没注册，工具静默消失。

原因是自动配置**按类名排序**，`io.github.claw4j.*` 排在 `org.springframework.*` 前面，
于是本类的条件在 Boot 注册 `JdbcClient` bean **之前**求值，`@ConditionalOnBean` 永远为假。
失效姿势是"不报错、只是模型手上没有工具，于是它开始编答案"——在聊天界面上完全看不出来。

修法是 `@AutoConfiguration(afterName = "...JdbcClientAutoConfiguration")`。
配套加了 `ToolsWiringTest`（app 模块）钉住：去掉 `afterName`，三条断言立刻变
`NoSuchBeanDefinitionException`。

### 六、行上限与数据源接缝

行上限 50，取 51 行来判断是否截断——只取 50 行的话"刚好 50 行"和"被截断了"返回的行数一样，
模型会拿着前 50 行报出一个没有前提的合计。

新增 `ReadOnlySqlExecutor` 接缝（第三条接缝），两个收益：
① Agent 侧的循环测试（自纠错、上限）不需要 Docker；
② 02 号票的 `SalesDataRepository.sumAmount` 不再是"没人用"的代码——它是数字契约的锚点，
05 的端到端断言的是种子里那个写死的 `128400.00`。

**一个代价要记账**：Testcontainers 的镜像守卫逻辑现在有两份
（tools 的 `PostgresTestSupport` 与 agent 的 `SalesQueryEndToEndTest`）。
试过用 test-jar 共享，但 test-jar 绑定在 package 阶段，
`mvn test`（以及 pre-commit 钩子）会解析不到，所以维持复制并在两处都写了同一套注释。

### 七、断言与变异检验

新增 31 条测试（11 校验 + 5 工具接缝 + 6 数据库 + 3 循环 + 3 端到端 + 3 装配）。

| 变异 | 结果 |
| --- | --- |
| 装配退回 `ChatClient.builder(chatModel)` | 循环 3 红 1（上限那条） |
| 去掉只读事务 | 数据库 6 红 1（写锁那条） |
| 去掉 `SET statement_timeout` | 数据库 6 红 1（超时那条） |
| 工具不再做只读校验 | 工具接缝 5 红 1 |
| 去掉 `afterName` | 装配 3 红 3 |

"换个条件数字就跟着变"这条是防假绿的关键：它把 SQL 换成必然为空的条件，
断言第二轮提示词里**不再出现** `128400.00`——如果那个数是脚本写死的，这条会红。

### 留给后面

- 07（真实模型）接进来之后，工具描述里的 schema 提示要按真实模型的表现再调一轮。
  脚本化假模型不会写错 SQL，真实模型会，届时"固定 schema + 改写提示"够不够用才知道。
- 写操作关键字清单里有 `comment` / `set` 这类词，业务表若出现同名列会被误拒。
  误拒的方向是拒绝，但会让模型莫名其妙，到时候按需收窄清单。
- 现在只认"上周"这一种相对时间窗写法。要支持"环比""同比"，schema 描述里得给更多锚点。

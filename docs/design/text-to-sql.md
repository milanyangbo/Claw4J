# Text-to-SQL：模型写 SQL，数据库说了算

> 设计说明：为什么这么做、否决了什么。要看结论去 [README](../../README.md)，要看决策去 [ADR](../adr/)。

放行之后才是正题。模型把问句**翻译成一条 SQL** 交给 `querySales`，工具只读执行、把行还回去，
由模型自己组织成回答。工具参数是整条 SQL 而不是"区域 + 时间窗"两个字段——
收成两个字段，模型就退化成填空机，"按产品""按客户""环比"这些问法全都答不了。

固定 schema（表名、列名、区域代码取值、成交额口径、时间窗写法）写在工具描述里，
随每次请求一起发出去。这不是啰嗦：模型不知道 `region_code` 的取值是 `east` 而不是 `华东`，
写出来的 SQL 不报错、只返回 0 行，而**"返回 0 行"是自纠错唯一无法自己发现的错误**。

## 只读约束落在两层，因为一层不够

| | 拦什么 | 谁来保证 |
| --- | --- | --- |
| 语句校验（`SalesSqlGuard`） | 多语句、非查询首关键字、写操作关键字 | 我们写的正则 |
| 只读事务（`JdbcReadOnlySqlExecutor`） | 任何写操作 | 数据库自己 |

只做第一层不够，理由很具体：**PostgreSQL 有可写 CTE**，
`with x as (insert into ... returning *) select * from x` 是一条语句、而且以 `with` 开头，
任何"首关键字是 select/with 就放行"的规则都会放过它。
所以第二层必须存在：查询跑在 `TransactionTemplate(readOnly=true)` 里，
Spring 把它翻译成 `Connection.setReadOnly(true)`，PostgreSQL 驱动再翻译成事务级只读标记，
写操作由数据库拒绝。校验漏了，数据也不会没。

校验本身也踩过两个坑，都留了断言：注释里夹分号（`select 1 -- ; drop table t`）
在字符串层面看是多语句、在数据库层面是一条，所以校验前要先去掉注释与字符串字面量；
而行上限是**包一层子查询**而不是在末尾拼 `limit`——模型自带的 `order by ... limit 10`
后面再拼一个 limit 就是语法错误。包一层还顺手把 DML 挡在了解析阶段
（PG 的子查询里不能是 `insert` / `update` / `delete`）。

## 自纠错与重试上限

写错了怎么办：**工具返回数据库的原话，而不是抛异常**。抛异常会被框架包成模型看不懂的话，
返回原因（含 `column "amout" does not exist` 这种具体信息）模型才有东西可改。
这是自纠错链路能成立的前提。

重试上限由 `ToolCallBudget` 收紧到**每工具 3 次、整轮 6 次**——落在 `ToolCallingManager`
的 `maxCallsPerTool` / `maxTotalToolCalls` 上，不是 yml 里那个 `spring.ai.tools.limits.*`
（默认 40/150 是为分页取数设计的，放在自纠错上就是一次提问烧几百次往返）。
超限的行为是 `THROW`：`ToolCallingAdvisor` 接住后直接变成一句回答给客户端，
不再回头问模型——否则"超限"会被当成工具结果喂回模型，它还会再试一次。

这里有个**静默失效**的坑值得单独记：`spring.ai.tools.limits.*` 配置到不了我们这条链。
`ChatClient` 是我们自己建客户端的，五参重载里传进去的 `ToolCallingManager` 才是生效的那个，
自动配置那个只有在使用 Boot 注入的 `ChatClient.Builder` bean 时才会被用上。
哪天改成注入 bean，这两个数字会静默退回 40/150，所以**两个数字各有一条测试钉着**：
`TextToSqlLoopTest` 钉单工具上限，`ToolCallBudgetTest` 钉总数上限。

总数那条值得一说：单工具上限（3）比总数上限（6）小，而 M1 只注册了一个工具，
所以"整轮 6 次"在单工具场景下**永远撞不到**——它守的是"多工具叠加"这个还没发生的风险。
与其留一个没人能证明它在生效的数字，测试里干脆造了三个假工具把它逼出来
（三个各调 2 次，第 7 次落在还没调满的那个上）。

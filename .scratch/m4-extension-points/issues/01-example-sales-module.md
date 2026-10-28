# 01: 把示例销售工具搬成独立模块 `claw4j-example-sales`

**What to build:** 把 `claw4j-capability` 里的 `builtin/sales` 整包与 `V1`/`V2` 两个迁移搬到新模块
`claw4j-example-sales`，让它成为一个**只依赖 `claw4j-domain` + Spring AI** 的 jar，
被 `claw4j-app` 依赖。它同时是"第三方工具插件长什么样"的参照实现。

**Blocked by:** 无

**Status:** claimed

## 为什么

三条，按严重程度：

1. **框架往别人的库里建业务表**。`V1__business_schema.sql` / `V2__business_seed.sql` 在
   `claw4j-capability` 的 classpath 上，任何引入这个库的应用启动时都会被 Flyway 建出
   `business.region/customer/sales_order/order_item` 四张表并塞进假订单。
   这不是示例，这是副作用。
2. **定位冲突**。README 已经改成"核心不含业务"，而示例业务与 `mcp`/`script`/`skill` 这些接缝
   并排放在同一个包里，读代码的人会以为销售是这个框架的一部分。
3. **"插件能外置"这句话没有任何证据**。今天所有工具都在核心模块里，说"第三方 jar 能接进来"
   只是推断。搬出去之后它是事实——而且能反过来验证：删掉这条依赖，核心照常构建。

## 要做

- [ ] 新模块 `claw4j-example-sales`，包名 `io.github.claw4j.example.sales`
- [ ] 搬 `SalesTools` / `SalesSqlGuard` / `ReadOnlySqlExecutor` / `JdbcReadOnlySqlExecutor` / `QueryResult`
- [ ] 搬 `V1__business_schema.sql`、`V2__business_seed.sql`（**内容一字不改**，Flyway 按 version + checksum 认）
- [ ] 自己一个 `@AutoConfiguration`，注册 `ToolCallbackProvider` + `ToolPermissionRegistry`
      **并加一条 `claw4j.example.sales.enabled`（默认 true）**——示例要给得出"怎么关掉我"的答案
- [ ] `claw4j-app` 加依赖；父 POM 加 module；`scripts/verify.sh` 的 `MODULES` 加一项
- [ ] `ToolsAutoConfiguration` 瘦身成"只剩 system 层"，并改掉类名与 javadoc 里对 sales 的指涉
- [ ] 把 `SalesQueryTest` 一起搬过去（capability 的测试文件数正好少一份）

## 判据

- **删依赖实验**：从 `claw4j-app/pom.xml` 注释掉 `claw4j-example-sales`，`./mvnw -o clean verify`
  除"依赖 sales 的那两条用例"外全绿；`querySales` 不在工具清单里；`business` schema 不再被建。
- 装回来之后，`ApplicationAssemblyTest` 那条真模型用例仍然答得出 128400（**真跑一次，不是推断**）。
- 变异：把 `V1` 从新模块里删掉 → `SalesQueryTest` 必须红（证明迁移真的跟着走了，
  而不是还在 capability 里被 classpath 顺带扫到）。

## 注意

- **别把 `ToolPermissionRegistry` 写成 `@ConditionalOnMissingBean`**。它的语义是"容器里已经有一个就让位"，
  而容器里本来就有 system 层与 MCP 层各一张表——让位的结果是某一方的工具从此一律按"未登记权限"被拒。
  这个坑在 `ToolsAutoConfiguration` 的 javadoc 里记了两遍，搬的时候别丢。
- `ToolsAutoConfiguration` 里那条"同一个自动配置类内按 @Bean 方法声明顺序求值"的依赖，
  搬走 sales 之后就不存在了（sales 成了另一个类），**相关的注释要跟着删**，不能留一段解释一个已不存在的问题的话。
- capability 的 `@ConditionalOnBean(JdbcClient.class)` 也随 sales 一起走；capability 不再需要 `spring-boot-starter-jdbc`。
  但 `script` / `generator` 那边如果用了 `JdbcClient`，要先确认——**读代码，别按印象删依赖**。

## 不要做

- 不动 `builtin/system`（它跟业务无关，是"这个进程自己"的能力）。
- 不改 SALES 的 SQL 与口径（那是 `docs/design/text-to-sql.md` 的事）。
- 不做"插件市场"、不做插件清单接口。

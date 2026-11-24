# 票 18：`/api/audit` 从来没被注册（读接口整条不在）

**Status:** resolved

## 由来

2026-11-23 的 A–I 全量复跑（`docs/acceptance/2026-11-23/README.md`）里，I 节整节跑不了：
`GET /api/audit` 与 `?executionId=…` 一律 404。查下来不是配置、不是库、不是开关，
是**没有任何代码把 `AuditController` 变成 bean**：

- `claw4j.persistence.enabled=true`，`--debug` 的条件报告里 `AgentPersistenceConfiguration#auditQuery` 是
  `matched`，库里 303 条审计正常在写；
- `HttpChannelAutoConfiguration` 只注册了 `httpChatController` 与 `approvalController`，
  **没有 `auditController`**；
- `@SpringBootApplication` 只扫 `io.github.claw4j.app`，扫不到 `io.github.claw4j.channel.http`。

单测没红是因为 `HttpEndpointsTest` 直接 `new AuditController(...)`。**"controller 写对了"与
"controller 被注册了"是两件事**，现有用例只钉了前一件——这与票 16 遗留的
"`@RequestParam` 名字没有用例覆盖"是同一个根：容器这一层从来没被验过。

## 裁决

**无需 ADR**：补一个遗漏的 `@Bean` 注册与两条容器级用例。不改公开契约（线格式仍是
`executionId`，ADR-0038 决定二），不改 schema，不改权限语义。

## 改哪

1. `HttpChannelAutoConfiguration` 加一个 `auditController`，形状照同文件的 `approvalController`
   （`ObjectProvider<AuditQuery>` 进来，`getIfAvailable()` 为空时 controller 自己回 404——那个行为不变）；
2. `JdbcAuditQuery` 的 `where` 前缀（**补注册之后露出来的第二层**）：原来
   `new StringJoiner(" and ", " where ", "")`，一个条件都没有时它的 `toString()` 仍是
   `emptyValue`（默认 prefix + suffix），拼出 `… audit_event where  order by seq desc` → 语法错、500。
   而"不带任何参数打开这一页"恰恰是最常用的一种。改成先收集条件、最后决定要不要加 `where`；
3. `ApplicationAssemblyTest` 加两条走真 HTTP 的用例（容器这一层，不是 `new`）；
4. 批次 README 的"阻断"一节改成"已修"，并补上修复后的实测。

## TDD 用例清单

| 判什么行为 | 用例名 | 先红的样子 |
| --- | --- | --- |
| 有查询能力时 `/api/audit` 在（200 + `{events, nextCursor}`） | `ApplicationAssemblyTest#theAuditEndpointIsMountedAndServesAPage` | 改前 `GET /api/audit` 得 404，断言 `200` 红 |
| **空过滤**（不带任何参数）也要能查：SQL 不能拼出 `where  order by` | 同上 | 补了注册之后仍红：`expected: 200 but was: 500`，服务端 `BadSqlGrammarException … syntax error at or near "order"` |
| 参数名是 `executionId` 且线格式按 ADR-0038 翻译（`eventType` / 毫秒 `occurredAt` / `actor` 对象 / 无 `runId`） | `ApplicationAssemblyTest#theAuditEndpointFiltersByExecutionIdAndUsesTheWireShape` | 改前 404；把 `@RequestParam("executionId")` 改回 `runId` 后，按 `executionId` 过滤不生效，断言"只回一行"红 |

三条都走 `@LocalServerPort` + `HttpClient`（与类里其余用例同一形状），数据用 `JdbcTemplate` 直接插，
不依赖模型这一轮愿不愿意调工具。

## 缺口

- 没有查询能力时（`persistence.enabled=false`）回 404 的那条分支，本次没加用例：它由
  `ObjectProvider#getIfAvailable` 的空值决定，改注册不会碰到；真要钉，得再起一个关掉持久化的上下文，
  收益不抵成本。记在这里，不勾。
- `/api/audit` 的 `operator.read` 授权仍缺（ADR-0037 的已记账缺口），与本票无关。

## 复核

2026-11-24 `./scripts/verify.sh` 全绿（失败 0 / 错误 0 / 跳过 0；app 14 文件 / 26 方法、
channel 11 文件 / 20 方法，配额没抬）。两次反例都验过：
补注册前后 404 → 补注册后 500（空 `where`）→ 改完 200；把 `@RequestParam` 改回 `runId`，
`theAuditEndpointFiltersByExecutionIdAndUsesTheWireShape` 红在"两条都回来了"。

**I 节十条复跑**（同一份文档，实例 `i-section.log` / `i6-messages.log`）：

| 条 | 结果 |
| --- | --- |
| I1 | 一轮 4 行、`sequence` 倒序（314→311）、`occurredAt` 13 位毫秒、`actor` 是 `{type,id}`、整页无 `runId` / `argsDigest` / `approvalId` |
| I2 | 四变体逐格符合：`agent_run` 无 `toolName`/`toolCallId`/`direction`/`outcome`；`tool_action` 有那三格；`inbound` 有 `accountRef`+`resultCount=1`、无 `deliveryKind`；`outbound` 有 `deliveryKind=text`、无 `resultCount`；消息行没有 `sessionKey` |
| I3 | 三条冲突全 400，文案与文档一致；坏游标 400 |
| I4 | `limit=2` 两页不重叠、游标严格更小；`limit=0` 钳成 1 条。**`limit=1000` 钳到 500 这一条没验到**：库里只有 308 行，够不着上限 |
| I5 | `INSERT 0 1` / `INSERT 0 0`；`having count(*)>1` 空 |
| I6 | 默认关时 `kind=message` 空页、库里 0 行；`audit.messages=true` 后进出各一条，字段与上面 I2 一致 |
| I7 | `tool_action` 102/102 有 `sha256:` 71 位；`agent_run` 112/112 空 |
| I8 | 两个子 Agent 各 4 行、各 1 个 executionId；无 `parent*` 列 |
| I9 | 五个计数全 404（= 0） |
| I10 | 404 只剩「没装查询」这一种解释（controller 已注册） |

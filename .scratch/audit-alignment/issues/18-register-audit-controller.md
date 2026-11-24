# 票 18：`/api/audit` 从来没被注册（读接口整条不在）

**Status:** in progress

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
2. `ApplicationAssemblyTest` 加两条走真 HTTP 的用例（容器这一层，不是 `new`）；
3. 批次 README 的"阻断"一节改成"已修"，并补上修复后的实测。

## TDD 用例清单

| 判什么行为 | 用例名 | 先红的样子 |
| --- | --- | --- |
| 有查询能力时 `/api/audit` 在（200 + `{events, nextCursor}`） | `ApplicationAssemblyTest#theAuditEndpointIsMountedAndServesAPage` | 改前 `GET /api/audit` 得 404，断言 `200` 红 |
| 参数名是 `executionId` 且线格式按 ADR-0038 翻译（`eventType` / 毫秒 `occurredAt` / `actor` 对象 / 无 `runId`） | `ApplicationAssemblyTest#theAuditEndpointFiltersByExecutionIdAndUsesTheWireShape` | 改前 404；只补注册而不改参数名时，按 `executionId` 过滤不生效，断言"只回一行"红 |

两条都走 `@LocalServerPort` + `HttpClient`（与类里其余用例同一形状），数据用 `JdbcTemplate` 直接插，
不依赖模型这一轮愿不愿意调工具。

## 缺口

- 没有查询能力时（`persistence.enabled=false`）回 404 的那条分支，本次没加用例：它由
  `ObjectProvider#getIfAvailable` 的空值决定，改注册不会碰到；真要钉，得再起一个关掉持久化的上下文，
  收益不抵成本。记在这里，不勾。
- `/api/audit` 的 `operator.read` 授权仍缺（ADR-0037 的已记账缺口），与本票无关。

## 复核

（收票时填）

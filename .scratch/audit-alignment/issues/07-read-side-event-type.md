# 票 07 读侧的 eventType：四个变体的判别字段

**Status:** resolved
Blocked by: —
裁决来源：`docs/adr/0037-audit-alignment-with-openclaw.md`（决定 2、3 的读侧部分）。
补的是票 03 留在「缺口」里的那条："`eventType` 判别等票 04 落地后再说"。

## 对照

| OpenClaw（出处） | 现在 | 改成 |
| --- | --- | --- |
| 读侧是四个变体，判别字段是 `eventType`：`agent_run` / `tool_action` / `inbound_message` / `outbound_message`（`packages_gateway-protocol_src_schema_audit-activity.ts:183,215,249,323`） | `Row` 只有 `kind`（三值）；消息要再叠 `direction` 才推得出是进还是出 | `Row` 带 `eventType`，由 `kind + direction` 推出来 |
| `kind` 与消息 `direction` 仍留在响应里，供过滤与展示（`docs_gateway_protocol_ledgers.md:31-33`） | 已有 | 不动 |
| `audit.activity.list` 的参数表里**只有 `kind`**，没有 `eventType`（`docs_gateway_protocol_ledgers.md:21-27`） | 过滤只有 `kind` | 不加过滤参数 |
| 持久层只存 `kind` / `direction`，`eventType` 是**读的时候算出来的**（`src_audit_audit-event-store.ts:94-114`） | 同 | 同：不加列、不加迁移 |

## 做什么

1. `AuditQuery.eventTypeOf(kind, direction)`：唯一一处推导。`message` 按方向分 `inbound_message` /
   `outbound_message`；`agent_run` / `tool_action` 与 `kind` 同值。
2. `AuditQuery.Row` 加一个组件 `eventType`。**它不是表里的一列**——字面意义上不是：
   `audit_event` 只有 `kind` 与 `direction`，OpenClaw 的持久投影也一样。这条写进 `Row` 的
   javadoc，免得下一个人照着"字段与表一一对应"去库里找这一列。
3. `Row` 的 compact constructor 校验它等于推导值：构造错了的 `Row` 立刻炸，而不是落成一行
   "看起来正常、其实判别字段写反了"的数据。
4. `JdbcAuditQuery` 在映射时算一次；`/api/audit` 原样带出去（序列化 `Row`，不用改端点）。

## TDD 用例清单

| 判什么 | 用例名 | 先红的样子 | 结果 |
| --- | --- | --- | --- |
| 四种组合各出一个变体：`agent_run` / `tool_action` / `message+inbound` / `message+outbound` | `HttpEndpointsTest#anAuditPageCarriesTheVariantDiscriminator` | `Row` 没有这个组件时序列化不出 `eventType`（字段缺席）；推错时四条里对应那条红 | ✅ |
| 判别字段与 `kind + direction` 对不上时造不出 `Row` | `HttpEndpointsTest#aRowCannotClaimTheWrongVariant` | 没有校验时构造成功，脏行能一路读出去 | ✅ |

两条都并进 `HttpEndpointsTest`（9 → 11）：读侧唯一暴露口就是那个端点，验在别处验的是同一个函数
隔着三层被调用的样子。配额还有余量，不新建文件。

## 复核

复核：2026-11-23 `./scripts/verify.sh` 全绿（失败 0 / 错误 0 / 跳过 0；channel 11 文件 / 20 方法、
覆盖 79.3%；其余模块不变）。**跑过反例**：把 `eventTypeOf` 的消息分支临时改成返回 `"message"`
（等于不判别），`anAuditPageCarriesTheVariantDiscriminator` 红在
`IllegalArgumentException: eventType 由 kind + 方向推出，不是一列：message / inbound 应当是 message，
实际是 inbound_message`；改回来绿。

一处必须记的取舍：`eventType` 做成了 `Row` 的组件而不是一个派生方法。派生方法不可能对不上，
但 Jackson 只认记录的组件（core 没有 Jackson 依赖，加注解就得为它引一个），字段会从报文里消失——
而"在报文里"正是这个字段存在的理由。于是换成组件 + compact constructor 校验，两头都要。

## 缺口

- **`sequence` / `schemaVersion` / `occurredAt` 仍未对齐**：OpenClaw 的 activity 变体里
  `sequence`、`schemaVersion: 1`、`occurredAt` 是必填，我们这边对应的分别是 `seq`、没有版本号、
  `at`。名字与"必填版本号"是两件事，本票只做 `eventType` 这一格判别，其余另行裁决。
- **`actor` 的形状不同**：它分 inbound / outbound 两套 actor 对象（`AuditActivityInboundActorV1Schema`），
  我们是一行字符串。消息审计那批（票 04）已经定了用假名引用，不改。

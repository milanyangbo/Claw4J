# 票 09 读侧报文：补齐信封五格，改成四个闭对象的样子

**Status:** resolved
Blocked by: 11（要 `session_id`、`actor_type` 两列先有值）
裁决来源：`docs/adr/0037-audit-alignment-with-openclaw.md`（2026-11-23 第三批修订）。

## 对照（都是逐字核过的）

| OpenClaw | 改之前 | 改成 |
| --- | --- | --- |
| 每个变体必填 `schemaVersion` / `sequence` / `sourceSequence` / `occurredAt` / `redaction`（`audit-activity.ts:105-112`） | 只有 `seq` 与 `at`（ISO 字符串），另外三格压根没有 | `sequence`（改名）、`schemaVersion:1`、`sourceSequence`、`occurredAt`（**Unix 毫秒整数**）、`redaction:"metadata_only"` |
| `actor` 是对象：`{type:"agent"\|"system", id}`（`:84-87`）；入站消息 `{type:"channel_sender", id:"hmac…"}`（`:89-98`） | 一行字符串（人的 id） | 对象，两格来自 `actor_type` + `actor` |
| 四个变体都是 `additionalProperties: false`（`:135-142`） | 报文里带 `argsDigest` / `approvalId` / 嵌套 `message`，非消息行还带 `channel` | 只留它 schema 里的字段；空字段整格省掉（`@JsonInclude(NON_NULL)`） |
| 消息变体的字段是**扁平的**（`direction` / `conversationKind` / `outcome` / `reasonCode` / … 都在顶层） | 嵌套在 `message:{…}` 里 | 摊平到顶层 |
| `nextCursor` 是不透明字符串（`ledgers.md:29`） | 数字（seq） | 字符串 |

## 做什么

1. `AuditQuery.Row` 补 `schemaVersion` / `sourceSequence` / `redaction` / `sessionId` / `actorType`
   —— 全部来自已有的列（V14 之后 `session_id` 也在），读侧不做迁移。
2. `AuditQuery.MessageRow` 去掉 `actorType`：它已经升到行级，留着就是两个来源。
3. 新建 `channel/http/AuditEventView`：**报文的唯一一份**。改名（`seq→sequence`、`at→occurredAt`）、
   转类型（Instant → 毫秒）、拼 `actor` 对象、摊平消息字段、省掉空值，全在这一处。
4. `AuditController` 返回 `AuditEventView`；`nextCursor` 用字符串。
5. **扩展列不外带**：`args_digest` / `approval_id` 留在库里（审批绑定与"这次执行是哪次批准的"
   还要用），但不进报文——它们不在它那份 schema 里，多带一格就不是它的实例了。

## TDD 用例清单

| 判什么 | 用例名 | 先红的样子 | 结果 |
| --- | --- | --- | --- |
| 四个变体的必填格都在：`schemaVersion` / `sequence` / `sourceSequence` / `occurredAt`（毫秒）/ `redaction` | `HttpEndpointsTest#anAuditPageCarriesTheVariantDiscriminator` | 少一格就是少一行断言；`occurredAt` 还是 ISO 字符串时毫秒那条红 | ✅ |
| `actor` 是对象：运行行 `{agent, ops}`、入站消息 `{channel_sender, 假名}` | 同上 | 还是字符串时两条都红 | ✅ |
| 空字段整格省掉、扩展列不出现 | 同上 | `@JsonInclude(NON_NULL)` 漏了或没摊平消息字段时 `":null"` 那条红 | ✅ |
| 库里的新列读得回来（`schemaVersion` / `sourceSequence` / `sessionId` / `actorType` / `redaction`） | `BuildContractTest#auditQueryReturnsNewestFirst` | 选列没加时拿到 0 或抛 SQL 错 | ✅ |
| 消息行的 `actor` 等于发送者假名、`channel` 有值 | 同上 | 归属没落到消息行时两条都红 | ✅ |

## 复核

复核：2026-11-23 `./scripts/verify.sh` 全绿（失败 0 / 错误 0 / 跳过 0；channel 11 文件 / 20 方法、
覆盖 79.9%；其余模块不变）。**跑过反例**：把 `AuditEventView` 上的 `@JsonInclude(NON_NULL)` 去掉，
`anAuditPageCarriesTheVariantDiscriminator` 红在「空字段必须整格省掉」，红出来的报文里每个变体都拖着
十几个 `null`（`"channel":null, "direction":null, …`）——那正是闭对象不允许的形状。改回来绿。
`occurredAt` 用毫秒断言（`1795348800000`），ISO 字符串那条会当场红。

## 缺口

- **`sessionId` 的历史行是空的**：V14 加列时不回填（当时也不知道段号），只在库里留空。
  读侧照实输出，不编一个"看起来像"的段。
- **`occurredAt` 的语义略有差**：它的时间是事件产生时刻（`event.ts`），我们的是写库那一刻
  （同步写，两者通常同毫秒）；孤儿补写那一行的时间是补写时刻，不是原本的结束时刻。
  要精确得让事件自己带时间进来，那是另一件事。

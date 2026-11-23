# 票 13 消息行的必填与互斥（照它的四个变体收紧）

**Status:** resolved
Blocked by: —
裁决来源：`docs/adr/0037-audit-alignment-with-openclaw.md`（2026-11-23 第四批修订）。

## 对照（原文）

```ts
// packages/gateway-protocol/src/schema/audit-activity.ts:122-133 —— 消息变体的公共部分
const messageProperties = {
  channel: NonEmptyString,            // 必填
  conversationKind: …ConversationKindV1Schema,  // 必填
  …
};
// :355-395 —— 出站四个终态，各自禁掉不该出现的格子
outcome=sent        → 不带 reasonCode / failureStage
outcome=suppressed  → 必带 reasonCode，**不带 deliveryKind**（withoutDeliveryKind）
outcome=failed      → 必带 failureStage
outcome=unknown     → 必带 failureStage，**不带 deliveryKind**
```

| OpenClaw | 我们现在 | 差在哪 |
| --- | --- | --- |
| 消息行 `channel` 必填 | 可为空（约束只要求 `direction` + `message_outcome`） | 一个没带通道的消息行，读侧必填格空着 |
| 消息行 `conversationKind` 必填 | 生产者总有值，约束不管 | 约束与 schema 不一致，靠"生产者记得" |
| `suppressed` / `unknown` 不带 `deliveryKind` | 生产者**总是**给 `"text"`（`HttpChatChannel`） | 被抑制的投递也写着"文本"，而那批字节根本没出去 |
| `failed` / `unknown` 必带 `failureStage` | 可为空 | 读的人分不清卡在队列还是平台 |
| `suppressed` 必带 `reasonCode`，`sent` 不带 | 没有约束 | 靠生产者自觉 |

## 做什么

1. **V15 迁移**：先把历史行按同一规则归一（`suppressed`/`unknown` 的 `delivery_kind` 清空、
   `failed`/`unknown` 的 `failure_stage` 补 `unknown`、消息行缺 `channel`/`conversation_kind`
   的按 `unknown` 补齐），再加四条 CHECK。
2. 生产者：`HttpChatChannel` 只给 `sent` 带 `deliveryKind`；`failed`/`unknown` 带
   `failureStage`（没给具体阶段时写 `unknown`——那也是它的闭集里的一个值）。
3. `JdbcMessageAudit` 不再替出站行填默认 `"text"`：那一格由生产者决定，补默认值等于替它下结论。

## TDD 用例清单

| 判什么 | 用例名 | 先红的样子 | 结果 |
| --- | --- | --- | --- |
| 被抑制的出站行不带 `deliveryKind` | `MessageAuditTest#suppressedRowHasNoDeliveryKind` | 生产者总给 `"text"` 时是 `text` | ✅ |
| 失败的出站行带 `failureStage` | `MessageAuditTest#failedRowSaysWhereItStopped` | 不传时是 null，CHECK 挡下整行 | ✅ |
| 库里：被抑制带 `deliveryKind`、失败不带 `failureStage`、消息行缺 `channel` 都写不进去 | `BuildContractTest#auditTerminalRowsAreConsistent` | 没有这几条 CHECK 时三条插入都成功 | ✅ |

第三条并进已有的库规则用例（`BuildContractTest` 已 26 个方法，不新增方法）。

## 复核

复核：2026-11-23 `./scripts/verify.sh` 全绿（失败 0 / 错误 0 / 跳过 0；`MessageAuditTest` 6 → 9、
`PersistenceSchemaTest` 25 条全绿）。**跑过反例**：把 `JdbcMessageAudit#deliveryKindOf` 换回
"没有就给 `text`"，`suppressedRowHasNoDeliveryKind` 红在「被抑制的投递被当成一次文本发送」。
库那四条是**负例断言**：约束不在时插入会成功，`assertThatThrownBy` 当场红——所以它们绿着就说明
V15 真的在拦。

顺带改了 `PersistenceSchemaTest` 的一条夹具（消息失败行原来只写方向与结果，现在补
`channel` / `conversation_kind` / `failure_stage`）与 `HttpChatChannel` 的生产者（不再替被抑制的
投递写 `text`）。

## 缺口

- **缺 `channel` 的消息行会被整行拒掉**：账本把异常吞成 `write_errors` 计数，所以现象是
  "这条消息没有审计行"而不是崩溃。这是有意的——读侧必填格空着等于这一行读不出去。
- **`conversationKind` 的 `channel` 取值**（Discord 服务器频道一类）我们仍然没有生产者，
  闭集里留着它（票 04 的决定，不变）。

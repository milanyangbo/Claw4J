# 票 04 消息审计：`kind=message`，收发各有终态

**Status:** resolved
Blocked by: 01, 02
裁决来源：`docs/adr/0037-audit-alignment-with-openclaw.md`（决定 3、7）

## 对照

| OpenClaw（出处） | 现在 | 改成 |
| --- | --- | --- |
| `kind` 三类，`message` 带 `direction`（`audit-event-types.ts:6,120-252`） | 两类，没有消息行 | 加 `message` |
| inbound 终态：`completed / skipped / failed` + 闭集 `reasonCode`（`audit-event-types.ts:22-46`） | 只有 `claw4j_channel.inbound_event` 的业务去重记录 | 审计里每个入站一条终态行 |
| outbound 终态：`sent / suppressed / failed / unknown` + `failureStage` / `deliveryKind`（同上 `:48-57,168-222`） | 投递结果只在日志 | 同样落一行 |
| 身份引用是 HMAC 假名 `hmac-sha256:v1:<keyId>:<digest>`（`ledgers.md:41-48`） | 无 | 假名化 account / conversation / message / target / channel-sender |
| 开关 `logging.audit.messages` 默认 off（`ledgers.md:117-118`） | 无 | `claw4j.audit.messages` 默认 false |

## 做什么

1. V9 加列：`direction`、`outcome`、`reason_code`、`delivery_kind`、`failure_stage`、
   `duration_ms`、`result_count`、`actor_type`、`account_ref`、`conversation_ref`、
   `message_ref`、`target_ref`；`kind` 的 CHECK 放行 `message`。
2. 假名密钥：一张只有自己的表（`claw4j_run.audit_identity_key`，单行，随机 32 字节），
   首次使用时生成；假名 = `hmac-sha256:v1:<keyId 前 8 字节 hex>:<digest hex>`，
   **读接口不返回密钥**。
3. 入站生产点：`InboundMessage` 进 dispatch 时写一条终态（`completed` / `skipped` /
   `failed`），reasonCode 取现有去重与策略分支。
4. 出站生产点：`ConversationDelivery` / 通道发送处写 `queued → finished`（`sent` /
   `suppressed` / `failed` / `unknown`），`failureStage` 区分队列与平台发送。
5. `claw4j.audit.messages=false` 时不写消息行；打开后 30 天 / 10 万行照旧。

## TDD 用例清单

| 判什么 | 用例名 | 先红的样子 | 结果 |
| --- | --- | --- | --- |
| 入站一条写一行 `message/inbound/completed` | `MessageAuditTest#inboundMessageIsAudited` | 无该类 | ✅ 带 runId 与时长 |
| 去重跳过的入站记 `blocked/skipped/duplicate` | `MessageAuditTest#duplicateInboundIsSkipped`（映射）+ `InboundDispatchTest#duplicateInboundIsAuditedAsSkipped`（真去重路径） | 无 | ✅ 跳过不是 failed |
| 出站成功记 `sent` | `MessageAuditTest#outboundCarriesStageAndKind` | 无 | ✅ 带 `delivery_kind` |
| 出站失败记 `failed` + `failureStage` | 同上（第二条断言） | 无 | ✅ `platform_send` |
| 身份引用是假名、且不含原文 | `MessageAuditTest#identityRefsArePseudonymized` | 无 | ✅ 同一原文算同一个假名 |
| 开关关掉不写消息行 | `MessageAuditTest#messagesSwitchOffSkipsWrites` | 无 | ✅ 连密钥都不去取 |
| 入站失败自带码 | `MessageAuditTest#failedInboundCarriesItsOwnCode` | 无 | ✅ `message_processing_failed` |

## 门禁余量（实际怎么落的）

只新增了一个测试文件 `MessageAuditTest`（6 条），槽位来自把 `ApprovalRequiredToolCallbackTest`（6）
并进 `ToolGovernanceTest`（19 → 26）——合并只搬家不改断言，另一条 blocked 用例也在那里。
去重那条落在既有的 `InboundDispatchTest`（core，20 → 21）。

原计划"两个新文件 + 并 SlackFlowTest / ToolFilterBindingTest"没有采纳：一个文件就够，
没必要动那两个。

## 缺口

- queued / platform-started 的"进度行"在 OpenClaw 里落在一个 lazy companion store，
  不进 `audit.activity.list`（`ledgers.md:132-136`）；本票只做终态行，进度行归缺口。
- **HTTP 就地返回的那一侧不写消息行**：它的答案随那次请求回去了，没有"投递"这件事。
  写 `sent` 是假的、写 `suppressed` 是撒谎，所以只留指标 `claw4j.run.delivery.polled`。
  要补得先有一个"响应即投递"的接缝（`ConversationDelivery` 现在只管主动推）。
- **`conversation_kind` 恒为 `unknown`**：这个仓库还没有"这段会话是私聊还是群"的判定，
  闭集里本来就有 unknown，所以不编。
- 读接口能按 `kind=message` / `status` / `channel` 查，但**还不能按 `direction` 过滤**：
  `AuditQuery.Filter` 加一个字段要动三个调用点，本票没做，记在票 03 的缺口里。
- "被 hook 吞掉"这类 suppressed 原因，我们目前没有 hook 接缝（ADR-0021 不做清单），
  先只写 `no_visible_payload`。

## 复核

复核：2026-11-23 `./scripts/verify.sh` 全绿（失败 0 / 错误 0 / 跳过 0；V12 在 testcontainers 的真库上跑过）。

实现要点（落地时定的）：

- 词表扩到 12：消息那三个码来自 OpenClaw 的内部生产者契约（`audit-event-types.ts:157-215`），
  对外 schema 还没有它们，所以票 02 的九值说法只覆盖运行与工具。
- 假名 `hmac-sha256:v1:<keyId16hex>:<digest32hex>`：密钥单行落库，首启生成，
  `on conflict do nothing` + 再读一次，避免两个实例各用一把。
- 生产点三处：入口（`ChatClientAgentPort`，完成/失败）、派发器（重复跳过）、投递
  （`ConversationDelivery`，送达/抑制/失败）。同一条消息只会有一行终态。

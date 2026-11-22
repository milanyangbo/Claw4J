# 票 04 消息审计：`kind=message`，收发各有终态

Status: claimed
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

| 判什么 | 用例名 | 先红的样子 |
| --- | --- | --- |
| 入站一条写一行 `message/inbound/completed` | `InboundAuditTest#inboundMessageIsAudited` | 无该类 |
| 去重跳过的入站记 `blocked/skipped/duplicate` | `InboundAuditTest#duplicateInboundIsSkipped` | 无 |
| 出站成功记 `sent` | `OutboundAuditTest#sentMessageIsAudited` | 无 |
| 出站失败记 `failed` + `failureStage` | `OutboundAuditTest#failedDeliveryCarriesStage` | 无 |
| 身份引用是假名、且不含原文 | `InboundAuditTest#identityRefsArePseudonymized` | 无 |
| 开关关掉不写消息行 | `OutboundAuditTest#messagesSwitchOffSkipsWrites` | 无 |

## 门禁余量

新增两个测试文件会顶穿 `claw4j-agent` / `claw4j-channel` 的文件上限。**先并**：
`AskUserQuestionTest`（6）并进 `SubagentDelegationTest`（22 → 28，超 26，不行）→ 改并
`SlackFlowTest`（2）进 `InboundSecurityTest`（10 → 12）；`ToolFilterBindingTest`（2）进
`ModelAndToolSelectionTest`（12 → 14）。合并后空出的两个槽给本票。**合并前先在票里记一笔**，
合并不改断言，只搬家。

## 缺口

- queued / platform-started 的"进度行"在 OpenClaw 里落在一个 lazy companion store，
  不进 `audit.activity.list`（`ledgers.md:132-136`）；本票只做终态行，进度行归缺口。
- "被 hook 吞掉"这类 suppressed 原因，我们目前没有 hook 接缝（ADR-0021 不做清单），
  先只写 `no_visible_payload`。

## 复核

待实现后补：`复核：<日期> ./scripts/verify.sh 全绿`

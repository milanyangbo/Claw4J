# 审计对齐 OpenClaw（spec）

裁决：`docs/adr/0037-audit-alignment-with-openclaw.md`。
对照物：OpenClaw `main` 的 `packages/gateway-protocol/src/schema/audit.ts`、
`src/audit/audit-event-types.ts`、`src/audit/audit-event-store.ts`、
`docs/gateway/protocol/ledgers.md`（2026-11-22 现读，副本在 `/tmp/oc-audit/`，不入库）。

## 为什么做

审计现在是一张只进不出的流水：能写、不能查；七个终态里四个没有生产者；`agentId` 恒空；
没有幂等键；消息收发一条审计都没有。出事后要回答的四个问题——**谁干的、成没成、
为什么被拦、东西送到没有**——有一半答不出来。

## 做哪几件（一票一件，按依赖排序）

| 票 | 做什么 | 依赖 | 状态 |
| --- | --- | --- | --- |
| 01 | 终态与归属：四个终态生产者、`agentId` 必填、删 `actor_role`、终态配对 CHECK | — | resolved |
| 02 | 幂等写入：`source_id + source_sequence` 唯一，重放只留一行 | 01（同一份 V9） | resolved |
| 03 | 读侧：`AuditQuery` 端口 + HTTP 端点（seq 游标、limit 1–500、六个过滤） | 01 | resolved |
| 04 | 消息审计：`kind=message`，inbound/outbound 终态 + 假名身份引用 | 01、02 | resolved |
| 05 | 去掉自研：内存审批交接 → 持久事实；`tool_call_id` 留到票 06 | 01 | resolved |
| 06 | 工具调用编号：从观测上下文取到，进 `ToolContext`，落 `audit_event.tool_call_id`（V13） | 01 | resolved |
| 07 | 读侧 `eventType`：四个变体的判别字段（`inbound_message` / `outbound_message` 由方向分出） | 03、04 | resolved |
| 08 | 子 Agent 的审计：每次委派开自己的 run，工具行落在这一轮上 | 01、06 | resolved |
| 09 | 读侧报文：信封五格（`schemaVersion`/`sequence`/`sourceSequence`/`occurredAt`/`redaction`）、`actor` 对象、声明闭对象 | 11 | resolved |
| 10 | 读接口参数：`after`/`before` 时间窗、冲突组合 400、`cursor` 改字符串 | 03 | resolved |
| 11 | 写侧归属：`actor` 记执行者、两把会话键、`channel` 只属于消息行（V14） | 01 | resolved |
| 12 | 事件时刻：`occurredAt` 记事件产生那一刻，不是落库那一刻 | 01 | resolved |
| 13 | 消息行必填与互斥：`channel`/`conversationKind` 必填、`suppressed`/`unknown` 不带 `deliveryKind`、`failed`/`unknown` 带 `failureStage`（V15） | 04、11 | resolved |
| 14 | `audit.run.inspect`：建了又撤（我们没有 run 这一层，ADR-0038） | 03、05、11 | wontfix |
| 15 | 改名：`run_id` → `execution_id`（V16），边界照它的 `executionId`（每轮外层请求）；线格式保持 `runId` | 03、14 | resolved |

**状态列与票面同源**：票面开头那行 `**Status:**` 是事实，这张表只是总览；收票时两处一起改。
`scripts/check-ticket-cases.sh` 会比对（表里漏列一张票、或两处写的不是一个词，都会红）。
取值用票面那套词（`resolved` / `in progress` / `open`），不另造图标。

## 不做

- `audit.run.inspect` / decision receipts / `--explain`：依赖 OpenClaw 的
  `execution_decision_facts` 那一套（`ledgers.md:123-131`），本批只到"行上带 approval_id"。
- 导出到 SIEM、CSV/JSON 下载：`ledgers.md:108-110` 明说这不是无损合规档案，我们也不冒充。
- 抬测试配额：`scripts/verify.sh` 的 14 / 26 是"到顶"，不许再抬；本批的新用例**并进现有文件**，
  放不下的先写进票的「缺口」。

## 验收口径

`./scripts/verify.sh` 全绿；每张票的 TDD 表 `❌` 清零；读侧手动验一遍（curl 出
newest-first 与 `nextCursor`），消息审计手动验一遍（发一条、看 `kind=message` 两行）。

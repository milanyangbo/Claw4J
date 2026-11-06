# run 只是一轮的句柄：拆掉持久 run

**Status:** ready-for-agent
**依据:** `docs/adr/0027-run-is-a-handle-not-an-entity.md`

`run` 是本项目自己设计的实体，OpenClaw 里没有对应物（只有 `{ runId }` 这个句柄）。
这条线把它拆掉，把它承载的六类职责按 OpenClaw 的形态重建。

## 一句话

**先建替代，再删旧的。** 顺序反了，中间态就是"审计断了、结果丢了"。

## 现状：run 承载七类职责，OpenClaw 只有第一类

| # | 职责 | OpenClaw | 本线怎么处理 |
| --- | --- | --- | --- |
| 1 | 一轮运行的句柄 | `agent` RPC / `chat.send` 返回 `{ runId }`；`agent.wait` 等它；`openclaw audit --run` 按它过滤 | 保留，语义收窄 |
| 2 | 状态机五态（含 `SUSPENDED`） | 无，只有 lifecycle `start` / `end` / `error` | 票 03 删 |
| 3 | 跨请求续跑 | 无，连续性靠 `sessionKey` | 票 03 删 |
| 4 | 审计（`agent_step` + 视图 + 七列） | **事件流水**：四类 action、元数据 only、30 天 / 10 万行、工具调用只存指纹 | 票 01 重建 |
| 5 | 计划（`plan_ref` / `planOf` / `setPlan`） | **goal**：挂会话、六态、模型工具、每回合注入、`/new` 清 | 票 02 重建 |
| 6 | 投递（`agent_run_delivery` / `result` / `notify_policy`） | 投递是会话与任务上的配置 | 票 03 删，改用会话地址 |
| 7 | 预算（三项累计） | 无 run 级预算；goal 带 token budget，`budget_limited` 是 goal 状态 | 票 04 并进 goal |

## 票

| # | 票 | 产出 | 依赖 |
| --- | --- | --- | --- |
| 01 | [审计改成事件流水](issues/01-audit-event-ledger.md) | `audit_event` 表 + `AuditLedger` + 四类事件 | 无 |
| 02 | [goal 挂会话](issues/02-goal-on-session.md) | goal 六态 + 模型工具 + `/goal` 命令族 + 每回合注入 | 无 |
| 03 | [审批与投递按对话地址](issues/03-approval-and-delivery-by-conversation.md) | 审批表带会话地址；删续跑、`SUSPENDED`、两个 `runId`、投递表 | 01、02 |
| 04 | [预算并进 goal](issues/04-budget-into-goal.md) | `budget_limited` 是 goal 状态；删 `agent_session_budget` | 02、03 |
| 05 | [删掉 run](issues/05-delete-run.md) | 删 `agent_run` / `agent_step` / `GET /api/runs` / 相关代码 | 01–04 |

## 全局约束

- **已发布的迁移一律不改**（V3–V18 的校验和已经落到库里）。所有删改都走新迁移，从 **V19** 起。
- 每张票独立跑 `./scripts/verify.sh`，全绿、跳过 0、覆盖率 ≥60%，测试文件数与方法数不破线。
- **每张票至少一个反例验红**，票面里写了"改坏什么会红"。
- 票面里"不做什么"那节是边界，实现时不要顺手扩。
- 改完代码同步改文档（`GLOSSARY.md`、`docs/design/`、`docs/agents/guardrails.md`）。

## 一条贯穿全线的判据

每张票做完，都用这一句检查一遍：

> **删掉这个东西之后，还有哪段代码问"这件事做到第几步了"？**

问得出来，说明还有没拆干净的 run 语义；问不出来，才轮到下一张票。

# 票 02 幂等写入：重放只留一行

**Status:** resolved
裁决来源：`docs/adr/0037-audit-alignment-with-openclaw.md`（决定 6）；幂等口径同 ADR-0017

## 对照

| OpenClaw（出处） | 现在 | 改成 |
| --- | --- | --- |
| 每条事件带 `source_id` + `source_sequence`，注释写明 "Persist one projected event **idempotently**"（`audit-event-store.ts:81-116,244`） | `event_id` 随机 UUID，无来源序号（`AuditLedger:149-152` 注释自认"将来再说"） | V9 加两列 + `(source_id, source_sequence)` 唯一；插入 `on conflict do nothing` |
| 重复投递不产生第二行（同左） | 重试/多实例即重复 | 同一对来源键只留第一行，重复计入 `claw4j.audit.duplicates` |

## 做什么

1. V9：`source_id varchar(64) not null`、`source_sequence bigint not null`、
   `unique (source_id, source_sequence)`。
2. 运行事件：`source_id = 'run:' + runId`，`source_sequence = 1 | 2`（started / finished）；
   工具事件：`source_id = 'tool:' + runId + ':' + 工具名 + ':' + 指纹`，序号按该键自增。
3. 插入语句改 `insert … on conflict (source_id, source_sequence) do nothing`；
   插入行数 0 时不当作写失败，计 `claw4j.audit.duplicates`。

## TDD 用例清单

| 判什么 | 用例名 | 先红的样子 | 结果 |
| --- | --- | --- | --- |
| 同一事件写两遍只留一行 | `BuildContractTest#auditReplayIsWrittenOnce` | 去掉 `on conflict` 后第二次多出一行，断言 2≠1 | ✅ |
| 重复不算写失败、单独计数 | `BuildContractTest#auditReplayIsWrittenOnce` | 不判 `written==0` 时 `claw4j.audit.duplicates` 不存在，取计数 NPE | ✅ |
| 唯一键在库里成立 | 同上（第二次插入不报错、行数不涨，说明走的是 `on conflict` 而不是异常） | 没有 `uq_audit_event_source` 时第二次插入会抛重复键 | ✅ |

（用例落在 `BuildContractTest` 而不是原计划的 `ToolGovernanceTest`：这一层要真的打到库里，
agent 模块的 `ToolGovernanceTest` 用的是记录型 sink，验不到唯一键。）

## 缺口

- 跨进程真的重放（两个实例同时写同一 run）没测——单实例是既定形态（ADR-0009）。
- `source_sequence` 在工具侧固定 1/2，没有"该键第几次"的递增语义：同参数连调两次会得到
  同一个键，第二对会被挡掉。这是有意的（宁可少记，不让一件事数成两次），但要等票 05 的
  `tool_call_id` 接缝才能把"同一参数的不同调用"分开。

## 复核

复核：2026-11-22 `./scripts/verify.sh` 全绿（V9 迁移在 testcontainers 的真库上跑过）。


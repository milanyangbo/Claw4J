# 票 05 去掉自研：内存交接换持久事实，拿不到的标 blocked

Status: resolved
Blocked by: 01
裁决来源：`docs/adr/0037-audit-alignment-with-openclaw.md`（「自己实现的要去除」三条 + 决定 3）

## 对照

| OpenClaw（出处） | 现在 | 改成 |
| --- | --- | --- |
| 审批与执行的关联是持久事实（`execution_decision_facts` / receipts，`ledgers.md:123-131`） | `ToolCallAudit.granted` 内存 Map：256 条上限、10 分钟 TTL、超限**整份 clear**（`ToolCallAudit:63,169-179`） | 落一张 `claw4j_run.execution_decision_fact`（`run_id / tool_name / args_digest / approval_id / created_at`），唯一键 `(run_id, tool_name, args_digest)`；执行那一下按同一键取走 |
| `toolCallId` 由框架给（`audit.ts:57`） | 拿不到，用 `args_digest` 顶（`V5:344-347`） | **标 blocked**：Spring AI 的 `ToolContext` 是自由 Map，没有调用 id。等上游给接缝 |
| 无 `actor_role` 概念（身份走假名引用） | 死列（`V5:334-337`） | 票 01 删列，本票不重复 |

## 做什么

1. V9 加表 `claw4j_run.execution_decision_fact`（与票 01/02 同一份迁移），
   `ToolCallAudit.granted` 改写它、`take` 删行并返回 `approval_id`；内存 Map 整体删除。
2. 过期清理：超过审批有效期（默认 30 分钟）的行由已有每小时任务顺手删（同 `prune`）。
3. `tool_call_id` 列先建、恒为 null，注释写明"等 Spring AI 给接缝"，**不编调用 id**。

## TDD 用例清单

| 判什么 | 用例名 | 先红的样子 | 结果 |
| --- | --- | --- | --- |
| 换一份实例还读得到（重启不丢） | `BuildContractTest#decisionFactsSurviveAndAreOneShot` | 内存 Map 换了实例就丢 | ✅ |
| 取一次就没了，不为第二次执行放行 | 同上 | 不删行时第二次仍取到编号 | ✅ |
| 同一 `(runId, 工具, 指纹)` 只留一条，后写的覆盖 | 同上 | 去掉 upsert 时主键冲突、或留两条 | ✅ |
| 过期行被清掉 | 同上（`prune(Duration.ZERO)` 后取不到） | 不按 `created_at` 删时仍能取到 | ✅ |

（用例落在 `BuildContractTest` 而不是原计划的 `ToolGovernanceTest`：这一层要真打到库里，
agent 模块那份 `ToolGovernanceTest` 用的是记录型 sink，验不到表。先落进 `PersistenceSchemaTest`
时把该文件顶到 27 个方法，按"先合并不抬上限"挪到了 `BuildContractTest`。）

## 缺口

- **`tool_call_id`：blocked（上游没有接缝）**。缺的是 Spring AI 在 `ToolContext` 里给出一次
  调用的稳定 id（哪怕是一个 `toolCallId` 键）。等它给，回来时改这三处：
  `AuditEvent.Scope` 加字段、`V9` 的列开始写、`ToolCallAudit#fingerprint` 退为辅助。
  在此之前不自己生成 UUID 冒充——那会让"同参数两次调用"看起来可分，实际不可分。
- `audit.run.inspect` 的 decision receipts 依赖同一张事实表的扩展（执行身份快照、覆盖率），
  本票只做最小键，不冒充它能"解释为什么放行"。

## 复核

复核：2026-11-22 `./scripts/verify.sh` 全绿（V10 在 testcontainers 的真库上跑过）。

# 票 17 观测层的名字也只有一个：span 与指标改叫 execution

**Status:** resolved
Blocked by: —
裁决来源：`docs/adr/0038-rename-run-to-execution.md`（代价一节新增"观测层同样只有这一个词"）。

## 为什么

前一版改名扫的是 `runId` / `run_id` 这两个拼法，于是漏掉了三类**不叫 runId 但仍然是 run 的名字**：

| 现在 | 值 | 为什么是残留 |
| --- | --- | --- |
| 根 span 名 | `run` | trace 里那一层的名字，与审计里的"一次执行"是同一段 |
| span 属性 | `claw4j.run.id` | 常量已经叫 `ATTR_EXECUTION_ID`，值还是 run——同一个东西两个名字 |
| 投递指标 | `claw4j.run.delivery` | 外部看板按它过滤"这一轮被策略跳过" |

同一个编号在 trace、审计、工具上下文三处出现，却有两个拼法，读的人会以为它们指两件事。这是
"命名统一"这件事没做完，不是三件小事。

## 做什么

1. `TurnRecorder`：`RUN_SPAN = "run"` → `EXECUTION_SPAN = "execution"`；
   `ATTR_EXECUTION_ID = "claw4j.run.id"` → `"claw4j.execution.id"`。
2. `ConversationDelivery.METRIC` → `"claw4j.execution.delivery"`。
3. 引用它们的注释与用例（`ObservabilityTest`、`TraceExportTest`）、`docs/agents/guardrails.md`
   两条排查手段跟着改。
4. 不动：历史 ADR 与带日期的复盘里那些 `claw4j.run.budget.*`——那是已删配置键的记录。

## TDD 用例清单

| 判什么 | 用例名 | 先红的样子 | 结果 |
| --- | --- | --- | --- |
| 根 span 叫 `execution`，属性 `claw4j.execution.id` 等于这一轮的编号 | `ObservabilityTest#oneTurnIsOneTraceWithActionsNestedUnderTheirTurn` | 只改名不配合：`spanOf("execution")` 取不到 → 空指针；只改常量不改值：属性取到 null | ✅ |
| 失败那一轮的根 span 状态仍正确 | `ObservabilityTest#failuresEndTheirSpanAsErrors` | 同上（span 名对不上时取不到那个 span） | ✅ |
| trace 导出里只有这一套名字 | `TraceExportTest` 整类 | 属性名写错时导出的 span 上没有那一格 | ✅ |

## 复核

复核：2026-11-23 `./scripts/verify.sh` 全绿（失败 0 / 错误 0 / 跳过 0）。反例：把
`ATTR_EXECUTION_ID` 的值改回旧名，`oneTurnIsOneTraceWithActionsNestedUnderTheirTurn` 红在
"属性取到 null"。

## 缺口

- **外部看板与告警要一起改**：`claw4j.execution.delivery` / `claw4j.execution.id` 是新的查询名，
  按旧名写的看板不会报错，只是静默查空。
- **span 名进的是 trace 后端**：已经导出的历史 span 仍叫 `run`，跨改名时间的查询要两段并查。

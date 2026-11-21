# 票 02：自定义 ToolCallback 把解析失败包成 ToolExecutionException

状态：**完成**（2026-11-22）。裁决：`docs/adr/0035-model-gateway-module.md` 第三节。

## 一、目标

工具参数坏 JSON 现在会以原生 Jackson 异常冒到路由层，我们把「整轮重试」当成补丁（票 14 的做法）。
Spring AI 本来就有一条更好的路：`ToolExecutionException` → `DefaultToolExecutionExceptionProcessor`
（默认 `alwaysThrow=false`）→ 把错误文本当**工具结果**回灌给模型，模型在同一轮自己重发工具调用。

## 二、范围

| # | 改什么 | 要点 |
| --- | --- | --- |
| 1 | 自定义 `ToolCallback.call(toolInput)` 的解析失败包成 `ToolExecutionException` | `ScheduleTools`、`GoalTools`、`WebFetchGuard`、`SubagentDelegation`、`RecallSessionTool`、`ApprovalRequiredToolCallback` |
| 2 | 包装层不吞不重抛（`MeteredToolCallback`、audit 回调） | 观测记账后原样透传，不改变异常类型 |
| 3 | 删 `ModelFailures.worthRetryingTheSameModel` 与路由层的同家重试 | 与 OpenClaw 一致：`format` 不重试 |
| 4 | 确认 `ToolCallBudget.toolCallingManager` 仍用框架默认 processor | 它现在只设 observation 与调用上限，不换 processor，保持 |
| 5 | 失败指标里 `ToolExecutionException` 不算模型失败 | 它不消耗模型调用预算；单独计一处工具错误计数 |

## 三、对照（OpenClaw）

| 它的形态 | 位置 |
| --- | --- |
| `format` 归「非瞬时、保留探测预算」；`status` 缺失或 5xx 时**不重试** | `src/agents/failover/retry-evidence.ts:190-197`、`failover-policy.ts:37-45` |
| 工具调用格式问题的主力是修复包（纯文本形态），不是整轮重试 | `packages/tool-call-repair/src/index.ts` |
| 工具执行失败作为结果回给模型，由模型继续 | 与我们用框架 processor 的做法同形 |

## TDD 用例清单

| 判什么行为 | 用例名 | 先红的样子 |
| --- | --- | --- |
| 坏 JSON 入参不再抛原生解析异常，而是 `ToolExecutionException` | `ScheduleToolsTest#badJsonBecomesToolExecutionException` | 仍抛原生异常（改前抛的是 Jackson 异常包出来的 `IllegalArgumentException`） |
| 模型能看到工具错误文本 | 同上（断言框架 `DefaultToolExecutionExceptionProcessor` 输出的文本带上了原因） | 文本为空或没有原因 |
| 坏参数**不触发**同一模型整轮重试 | `ModelAndToolSelectionTest#unparseableOutputDoesNotRetryTheSameModel` | 模型被调用两次 |
| 连续坏参数最终撞工具调用上限 | `ChatPipelineTest#repeatedBadArgumentsHitTheToolCallCap` | 无限重发 |
| 包装层记账后异常类型不变 | `ObservabilityTest#wrapperPreservesToolExecutionException` | 变成 `RuntimeException` 或吞掉 |

## 实施记录

- 三个自己解析入参的类（`ScheduleTools`、`GoalTools`、`RecallSessionTool`）把解析失败改成
  `new ToolExecutionException(definition, failure)`；**语义类参数错误仍回一句话**
  （缺 `goal`、cron 写错、`query` 没词），那些模型换个参数就能自救，不必走异常通道。
  `RecallSessionTool` 的解析原来在 try 之外，坏 JSON 会直接冒出 `call`——这是本票真正修掉的那条。
- `MCP` 那一路（`McpToolCallback`）本来就已经包成 `ToolExecutionException`，没动。
- 删 `ModelFailures.worthRetryingTheSameModel` 与 `RoutingChatModel` 里的同家两连击；
  现在坏内容只在"换一家"的意义上可重试（票 06 会把 `format` 归成不重试）。
- 包装层（`MeteredToolCallback` / `ApprovalRequiredToolCallback`）本来就原样重抛，
  本票只补断言，不改代码。
- 用例没有新开文件：`claw4j-agent` 的测试文件数正好卡在上限 14，五条用例分别并进
  `ScheduleToolsTest` / `ModelAndToolSelectionTest` / `ChatPipelineTest` / `ObservabilityTest`。

## 缺口

- 纯文本工具调用（模型把 tool call 写进 content）不做修复，记缺口：需要时按 `tool-call-repair` 的形状另开票。
- 票 14 的实测样本（`{"text":"…"专}`）要在本票里重跑一次，确认它走的是新路径而不是被重试掩盖。

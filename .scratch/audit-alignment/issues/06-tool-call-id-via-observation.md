# 票 06 工具调用编号：从观测上下文取到，放进工具上下文

**Status:** resolved
Blocked by: —
裁决来源：`docs/adr/0037-audit-alignment-with-openclaw.md`（决定 3）；本票重开票 05「缺口」里的
`tool_call_id` 一条。**不改 schema**：加列属于持久化改动，见「缺口」。

## 对照

| OpenClaw（出处） | 现在 | 改成 |
| --- | --- | --- |
| `toolCallId` 由框架给，audit 行按它绑「这一次调用」（`audit.ts:57`、`V5:60` 注释） | 回调层拿不到，只能用 `args_digest` 顶着（`V5:344-347`）；票 05 标 blocked | 包装层从 `ToolCallingObservationContext#getToolCallId()` 取，写进 `ToolContext`（键 `claw4j.toolCallId`），再往内传 |
| 同上 | 票 05 的「做什么」第 3 条写「V9 加 `tool_call_id` 列、恒为 null」，**实际 V9 没有这一列**（2026-11-23 核过，`grep tool_call_id *.sql` 只命中 V5 的注释） | 本票不动 schema；那一列与它的消费者一起另行裁决（见「缺口」） |

## 为什么之前判 blocked，现在不 blocked

票 05 的判据是「`ToolCallback.call(input, toolContext)` 的入参里没有调用 id」——这句仍然成立，
但只看了一半。Spring AI 2.0.2 的 `DefaultToolCallingManager` 把 id 放在**另一条通道**：

- `buildToolContext`（`:207-216`）确实只拷 `ToolCallingChatOptions#getToolContext()`，不往里加任何东西；
- 但 `executeToolCall` 先建 `ToolCallingObservationContext.toolCallId(toolCall.id())`（`:313-316`），
  再在它的 `observe(...)` 里调 `toolCallback.call(...)`（`:321-328`）。

也就是说回调执行时，**当前线程的 observation 就是那个带 id 的上下文**，而
`ToolCallingObservationContext#getToolCallId()` 是 public 方法（sources:79-81）。取它、塞进
`ToolContext` 就是「在框架基础上补一个键」，没有复制框架的循环，也没有自己生成 UUID 冒充。

## 做什么

1. `ContextKeys.TOOL_CALL_ID`（`claw4j.toolCallId`），与 `RUN_ID` / `AGENT_ID` 同一处定义。
2. `MeteredToolCallback` 注入 `ObservationRegistry`：`call(input, ctx)` 在 `openSpan()` **之前**
   取 id，`new ToolContext(merged)` 再往内传；取不到（NOOP / 非 tool 观测 / 空白）时原样透传同一个实例。
3. 装配：`AgentAutoConfiguration#toolCatalog` 把容器里的 `ObservationRegistry` 传下去
   （没有则 `ObservationRegistry.NOOP`）；4 个重载的旧调用点显式传 NOOP，行为不变。
   子链（`SubagentAutoConfiguration`）也传 NOOP：id 目前只进 `ToolContext`、还没有落点，
   等「缺口」里那一列定了再一起接。

## TDD 用例清单

| 判什么 | 用例名 | 先红的样子 | 结果 |
| --- | --- | --- | --- |
| 在 tool 观测里执行时，被包装的工具能从 `ToolContext` 读到 `call_abc` | `ObservabilityTest#toolCallIdReachesToolContext` | 不注入时工具读到 null（实测 `["<none>"]` vs `["call_abc"]`） | ✅ |
| 没有观测（NOOP）时 `ToolContext` 里**没有这个键**，同一个实例原样透传 | `ObservabilityTest#noObservationMeansNoToolCallId` | 无条件 put 时 map 里出现值为 null 的键，`containsKey` 为真 | ✅ |
| 真实装配里观测注册表**不是** noop（noop 时编号恒空且不报错） | `ApplicationAssemblyTest#observationRegistryIsNotNoop` | 装配退化成 noop 时 `isNoop()` 为真；这条的红法是"代码没坏、环境变了" | ✅ |

## 踩到的坑（2026-11-23，写下来省下一次）

Micrometer 的 `ObservationRegistry.create()` 在**一个 handler 都没有**时整体退化为 noop：不但
`getCurrentObservation()` 在 scope 里返回 null，连 `Observation#getContext()` 都给的是
`NoopContext`——我们那份 `ToolCallingObservationContext` 根本没被装进去。第一版测试就红在这里，
表现是"实现看着对、就是读不到"。

所以测试装置挂了一个什么都不做的 `ObservationHandler` 才等价于真实部署；而真实部署是不是那种
状态，由 `ApplicationAssemblyTest#observationRegistryIsNotNoop` 钉住（Boot + actuator 会挂
`MeterObservationHandler`，实测通过）。**没有这条断言的话，这个能力可以在生产上一直是空的，而所有
单测都是绿的。**

（前两条并进 `ObservabilityTest`（24 → 26，**这个文件已经到顶**）；第三条进
`ApplicationAssemblyTest`（17 → 18）。两个文件都在配额内，不抬上限、不新增文件。下一个用例
进来之前必须先合并 `ObservabilityTest` 里的两条。）

## 缺口

- **落库：blocked，等裁决。** 现在 id 只到 `ToolContext`，审计表里没有它的位置：
  - `audit_event` 没有 `tool_call_id` 列（`args_digest` 是它的替身，`V5:344`）；
  - `approval_request` 绑的是 `args_digest`，OpenClaw 绑的是 `toolCallId`（`V5:60`）；
  - `execution_decision_fact` 的主键是 `(run_id, tool_name, args_digest)`（`V10:19`）。
  三处都要加列或改主键，属于 schema / 持久化改动，按 `AGENTS.md` 先问再动。改的时候位置固定：
  `AuditEvent.Scope` 加字段 → 一条新迁移加列 → `ToolCallAudit` 三个入口多传一个参数。
- **`ApprovalRequiredToolCallback` 不在本票**：它拦下调用时也要组作用域，但把 id 绑进审批要等上面那条。
- 流式路径下 ThreadLocal 可能丢（靠 `ObservationThreadLocalAccessor` 传播）。当前链路是阻塞的，
  取不到就透传，行为与今天一致；不为了它加一条旁路。

## 复核

复核：2026-11-23 `./scripts/verify.sh` 全绿（失败 0 / 错误 0 / 跳过 0；agent 14 文件 / 26 方法、
覆盖 61.1%；app 14 / 26、覆盖 88.4%）。三条用例逐条跑过：`toolCallIdReachesToolContext` 在没注入时
实测红（`["<none>"]` vs `["call_abc"]`），`noObservationMeansNoToolCallId` 与
`observationRegistryIsNotNoop` 绿。

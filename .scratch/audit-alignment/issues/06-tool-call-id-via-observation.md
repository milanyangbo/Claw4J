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
4. **落库（V13）**：照 OpenClaw 加 `audit_event.tool_call_id`——列名、可空、**只有 `tool_action`
   行有值**都照原文（`audit-event-store.ts:100`），并加一条 CHECK 把"运行行/消息行带它"挡掉
   （读侧 schema 里消息变体是 `toolCallId?: never`，`audit-activity.ts:463`）。不建索引：
   OpenClaw 的查询维度里没有它。
5. `AuditEvent` 加组件、`AuditLedger` 的 INSERT 与孤儿补写带上、`ToolCallAudit` 三个入口多一个参数。
   孤儿补写那一条 `select s.tool_call_id`：补出来的终态说的是"同一次调用没了下文"，换一个编号
   就成了另一次调用。
6. 读侧：`AuditQuery.Row` 加 `toolCallId`、`JdbcAuditQuery` 选出来、`/api/audit` 原样带出去
   （OpenClaw 的 activity 里它就是可选字段）。**不加过滤参数**——OpenClaw 的 `audit.activity.list`
   参数表里没有它。

## TDD 用例清单

| 判什么 | 用例名 | 先红的样子 | 结果 |
| --- | --- | --- | --- |
| 在 tool 观测里执行时，被包装的工具能从 `ToolContext` 读到 `call_abc` | `ObservabilityTest#toolCallIdReachesToolContext` | 不注入时工具读到 null（实测 `["<none>"]` vs `["call_abc"]`） | ✅ |
| 没有观测（NOOP）时 `ToolContext` 里**没有这个键**，同一个实例原样透传 | `ObservabilityTest#noObservationMeansNoToolCallId` | 无条件 put 时 map 里出现值为 null 的键，`containsKey` 为真 | ✅ |
| 真实装配里观测注册表**不是** noop（noop 时编号恒空且不报错） | `ApplicationAssemblyTest#observationRegistryIsNotNoop` | 装配退化成 noop 时 `isNoop()` 为真；这条的红法是"代码没坏、环境变了" | ✅ |
| 同一次调用的 started / finished 两条事件带同一个编号 | `ToolGovernanceTest#anExecutedCallLandsAsTwoEvents` | 不往 `AuditEvent` 传编号时两条都是 null | ✅ |
| 库里读得回来（工具行） | `BuildContractTest#auditQueryReturnsNewestFirst` | 读侧不加这一列时拿到 null | ✅ |
| 只有工具行能有：运行行带它被 CHECK 挡下 | `BuildContractTest#auditTerminalRowsAreConsistent` | 去掉 CHECK 时那条 insert 会成功 | ✅ |
| 孤儿补出来的终态跟着 started 行的编号走 | 同上 | 补写不 select 它就得到 null | ✅ |
| `/api/audit` 把编号带得出去 | `HttpEndpointsTest#anAuditPageIsReadable` | `Row` 里没有这个组件时序列化不出该字段 | ✅ |

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

- **`approval_request` 与 `execution_decision_fact` 不改，这是决定不是遗漏。** 两处都还绑
  `args_digest`（`V5:60`、`V10:19`），看起来"应该换成 toolCallId"，但**没有原文依据**：
  OpenClaw 的这两张表只有文字描述（`docs_cli_audit.md:156-188` 讲的是审批绑定是
  "immutable owner-local binding"，以及三种 mismatch 码），没有 DDL 可对。照着想象换主键，
  是把"对齐"变成"我猜它大概长这样"；要改先拿到它的表结构。
  `args_digest` 在审批链上也不是替身而是真键：同一组参数的批准要能被下一次执行复用
  （`V10` 的注释），这一点 `tool_call_id` 表达不了。
- **子链（子 Agent）的工具审计本来就为空**：`SubagentAutoConfiguration` 调 `collectToolCallbacks`
  时审计传 null（"它的调用记在父那一轮里"），没有账本可写，编号也就没有落点。这是早于本票的
  缺口（OpenClaw 的子 Agent 有自己的 run 与自己的审计行），要补是另一张票。
- 流式路径下 ThreadLocal 可能丢（靠 `ObservationThreadLocalAccessor` 传播）。当前链路是阻塞的，
  取不到就透传，行为与今天一致；不为了它加一条旁路。

## 复核

复核：2026-11-23 `./scripts/verify.sh` 全绿（失败 0 / 错误 0 / 跳过 0；agent 14 文件 / 26 方法、
覆盖 61.1%；core 22 方法 / 83.5%；channel 20 方法 / 79.3%；app 14 / 26、覆盖 88.4%）。
`toolCallIdReachesToolContext` 在没注入时实测红过（`["<none>"]` vs `["call_abc"]`）。

第一次跑 `BuildContractTest` 时 `auditQueryReturnsNewestFirst` 与 `auditReplayIsWrittenOnce`
红在"一行都没落"：`INSERT` 的列从 28 加到 29 时占位符只写了 28 个，全表写入失败被账本吞成
`write_errors`。补上 29 个后全绿——这两个既有用例正好替新列证明了"写不进去"会被发现。

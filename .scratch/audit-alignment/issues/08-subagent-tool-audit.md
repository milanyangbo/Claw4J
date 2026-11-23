# 票 08 子 Agent 的工具调用落自己那一轮

**Status:** resolved
Blocked by: 01、06（要 `agentId` 必填、`tool_call_id` 与来源键的形状）
裁决来源：`docs/adr/0037-audit-alignment-with-openclaw.md`（决定 2、3）。
补的是票 06「缺口」里那条：子链的工具调用**既没有自己的行，也没有父的行**。

## 对照

| OpenClaw（出处） | 现在 | 改成 |
| --- | --- | --- |
| "For a child started through `sessions_spawn`, the child owns a new context; it never reuses or mutates the parent context. The spawning agent remains the invoker."（`docs/gateway/audit.md`） | `subagentToolCallbacks` 调 `collectToolCallbacks` 时审计传 `null`；子链的工具上下文里没有 `runId` | 每次委派开自己的 `runId`，子链的工具事件落在这一轮上 |
| 子运行与父运行**不共享** run，只有 lineage 投影把它们连起来，且**只在拿到精确父令牌时**才连："The lineage projection for `sessions_spawn` links the parent context, execution, run, and agent when the exact private parent token was available." / "If the private parent token was unavailable, the child remains inspectable but the missing parent context, execution, and run evidence is explicit." | 没有父 run 那一列 | **不造 lineage**：不从父 `runId` 拼一个"看起来像"的关联，缺就缺着（与它"不推断"同一条） |

## 做什么

1. `SubagentRunner.delegate` 每次委派生成一个 `runId`（本地 UUID，与父那一轮同一个做法）。
2. 写两条运行事件：`agent.run.started` / `agent.run.finished`。作用域：
   - `sessionKey = subagent:<定义名>`（与子链的会话 id 同一个字面，`SESSION_PREFIX` 一处拼）
   - `agentId = <定义名>`：子 Agent 有自己的提示词与工具集，它**就是**这一轮干活的 agent；
     父的部署级 agent 名不适用
   - `actor` = 父这一轮的身份（委派不提权，人还是那个人）
   - `channel` 留空：子链拿不到通道，不为了填这一格去编
3. 失败的归一照父链同一套判据 `RunOutcome.of`（超时/取消/失败分三档），不另写一份。
4. `subagentToolContext` 塞 `RUN_ID` 与 `AGENT_ID`：工具事件因此落在这个子 run 上，而不是
   "没有 runId 所以账本直接跳过"。
5. 装配：`subagentToolCallbacks` 多一个审计参数与一个观测注册表参数；`subagentToolCallback`
   从容器取 `ToolCallAudit`（没有就是 `null`，裁剪部署行为不变），并把**真实的**
   `ObservationRegistry` 传下去——票 06 这里传的是 `NOOP`，那时子链没有账本、编号没有落点；
   现在有了。
6. 审批闸也接审计：被拒的子调用同样是一条 `blocked`（与父链同一个口径：被拒也是"发生过的调用"）。

## TDD 用例清单

| 判什么 | 用例名 | 先红的样子 | 结果 |
| --- | --- | --- | --- |
| 一次委派落两条 run 行 + 子链的工具行，且工具行的 `runId` 就是子那一轮的 | `SubagentDelegationTest#subagentToolCallsAreAuditedInTheirOwnRun` | 审计传 `null` 时一行都没有；只传审计不给 `runId` 时工具行落不下去（账本跳过无 run 的行） | ✅ |
| 子链的 `sessionKey` / `agentId` 指向子 Agent，而不是父那一轮 | 同上 | 不塞 `AGENT_ID` 时落 `unknown`，`sessionKey` 退成父的会话 | ✅ |
| 子 Agent 失败也留一条 `failed` 的结束行 | `SubagentDelegationTest#subagentRunFailureIsRecorded` | 没有 finally 时只剩 started 那条，30 分钟后被孤儿扫成 `unknown` | ✅ |
| 没有审计（裁剪部署 / 没装持久化）时委派照常工作 | 由同一个文件其余 22 条覆盖（它们的 `runner(...)` 传的就是 `null`） | 审计为空时 NPE | ✅ |

两条并进 `SubagentDelegationTest`（22 → 24）：委派唯一的真实装配路径就在那儿
（`delegation(...)` 走库的 `TaskTool`），另搭一条只会验出一个和线上不一样的形状。

## 复核

复核：2026-11-23 `./scripts/verify.sh` 全绿（失败 0 / 错误 0 / 跳过 0；agent 14 文件 / 26 方法、
覆盖 61.3%；其余模块不变）。**跑过两次反例**：

- 把 `subagentToolContext` 里塞 `RUN_ID` / `AGENT_ID` 的两行临时删掉（等于改之前的形状）→
  `subagentToolCallsAreAuditedInTheirOwnRun` 红在工具行的 `scope.runId()` 是 `null`；
- 把 `delegate` 的 `finally` 临时去掉 → `subagentRunFailureIsRecorded` 红在
  「开始、结束各一条」`Expected size: 2 but was: 1`。

两处都改回来绿。

一个顺带的行为变化要记：子链的工具上下文现在有 `runId` 了，于是子链上的审批（`approvals.ask`）
会把 `run_id` 写成子那一轮，而不是原来的空。这是更正（原来那一格空着，按 `runId` 查发布出去的那条
审批永远查不到），但它是可观察的变化，写在这里而不是埋在代码里。

## 缺口

- **父子关联只有"时间 + 会话前缀"**：没有 `parent_run_id` 这一列，也不打算编一个。OpenClaw 那条
  lineage 是它自己的表（parent context / execution / run / agent 四样），我们只有 `run_id`
  一列，加列属于 schema 改动，且没有它的 DDL 可对——按票 05 的规矩，**没有原文就先不加**。
  现在要串起来只能按 `sessionKey` 前缀 + 时间窗，这一点写在这里而不是假装已经连上。
- **`invoker` 与 `actor` 是两个概念**：它那边"发起这次派活的 agent"记在 invoker 上，人记在
  representedSubject 上；我们只有 `actor` 一个格子，装的是人。子链的 actor 因此与父相同，
  看不出"这一步是 agent 自己决定的"。要补得先有那一列。
- **子 Agent 的模型调用本身没有 span**：`SubagentRunner` 把 `ObservationRegistry` 传给了子
  `ChatClient`，但子 run 没有自己的根 span（父的 `RUN_SPAN` 不覆盖另一个线程/另一段上下文）。
  这是 P14 那条的延伸，不属于审计，留在这里备查。

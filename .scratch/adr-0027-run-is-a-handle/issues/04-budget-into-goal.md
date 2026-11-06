# 04 预算并进 goal

**Status:** resolved（2026-11-06）
**依赖:** 02、03
**依据:** ADR-0027 决定五

## 背景

现在的额度是 `agent_session_budget`（V16）：按 `session_id` 累计**三项**——工具调用次数、token、耗时。
超额时把 run 收成 `RunStatus.BUDGET_EXHAUSTED`。

OpenClaw 没有这种"会话累计额度表"。它有：

- **goal 的 token budget**：`create_goal` 可以带 `token_budget`，用完进 `budget_limited` 状态，
  `/goal resume` 从同一目标重新开始（"starts a fresh budget window"）；
- **运行超时**：`agents.defaults.timeoutSeconds`，默认 48 小时，由 `runEmbeddedAgent` 的中止计时器执行；
- **并发上限**：`agents.defaults.maxConcurrent`。

也就是说：**"额度"是目标的属性，不是会话的属性，更不是 run 的属性。**

## 落点

### 1. 记账在 goal 行上

用票 02 已经建好的 `conversation_goal.token_budget` / `tokens_used`：

```text
每轮收尾：tokens_used += 这一轮 usage
当 token_budget 不为 null 且 tokens_used >= token_budget：
    status = budget_limited          -- 不是 FAILED，也不是"这一轮失败"
```

`budget_limited` 的目标**不注入**（票 02 的规则），于是模型不会再被要求推进它——
"额度用完了"这件事对模型是隐形的，对人是可见的（`/goal` 能看到）。

### 2. `V22__drop_session_budget.sql`

- `drop table claw4j_run.agent_session_budget`；
- 删 `RunBudget` 里三项累计的实现，只留"这一轮有没有超 goal 预算"这一问。

窗口：**只有 `resume` 会开新窗口，而且只对 `budget_limited` 开**——那时 `tokens_used` 归零
（OpenClaw：fresh budget window）；其余状态 resume 时用量继续累计（OpenClaw："Token accounting
continues unless the budget was reached"）。`complete` 不关窗口（它能被 resume 回来接着推），
`clear` 才把这一份账整个删掉。

### 3. 工具调用次数上限：改名成熔断

`claw4j.run.budget.max-tool-calls` → `claw4j.agent.tool-call-limit`，默认值不变。

它是**止损**，不是额度：
- 触发时这一轮直接结束，回一句"这一步做了太多次，我先停下"；
- **不改 goal 状态**，也不进 `budget_limited`；
- 配置注释里必须写清楚为什么它不是额度（见下）。

理由（这条要留在注释里，否则下一个人会把它当额度调大）：OpenClaw 有
`agents.defaults.timeoutSeconds`（48 小时）兜住跑飞，我们没有 per-turn 超时，
所以需要一个更早的止损点。**成本控制**由 goal 的 token budget 管，**失控保护**由这个数管，
两者混成一个数，就会出现"因为跑飞被停"和"因为花钱太多被停"看起来是同一件事。

### 4. 耗时

不再记。`Duration` 那一路只进指标（`claw4j.turn.duration`），不进库——
"这件事花了多久"是一次观测，不是一条要留存的状态。

## 判据

- [x] **到额度进 `budget_limited`**：goal 带 `token_budget=100`，每轮 46，第三轮之后 status 变
      `budget_limited`，且下一轮的提示词里**没有**"活跃目标"。
      反例：把 `budget_limited` 也注入（`injected()` 改成 `status != COMPLETE`） →
      `RunLifecycleTest#tokensOfATurnAreChargedToTheGoal` 红（"受限的目标不再注入"那条断言）。
      证据：同一条用例（`tokens_used` 92 → 138、`budget_limited`、提示词不含"活跃目标："）。
- [x] **`/goal resume` 开新窗口**：从 `budget_limited` resume → status 变 `active`，`tokens_used` 归零，
      `objective` 与 `token_budget` 不变。
      反例：resume 里那句 `case when status = 'budget_limited' then 0 else tokens_used end` 改成无条件清零 →
      同一条用例的**另一半**红（"暂停一下不该白拿一份额度"）。
      证据：`RunLifecycleTest#resumingResetsTheWindowOnlyWhenTheBudgetWasReached`。
- [x] **其他状态 resume 不清零**：`paused` → resume，`tokens_used` 保持 40。
      证据：同一条用例的后半段。
- [x] **熔断不改 goal 状态**：一直失败的工具轮次停在单工具上限，回的是我们自己的话，
      goal 仍是 `active`。
      反例：这一条靠结构成立——熔断那条路（Spring AI 的工具循环）根本拿不到 `SessionGoalStore`，
      要写 `budget_limited` 得先把它接进来。用例把结果钉住，接了就会红。
      证据：`ChatPipelineTest#theToolCallCapIsACircuitBreakerNotAQuota`。
- [x] **表真的没了**：`V22` 之后 `agent_session_budget` 不存在；`RunBudget` / `RunBudgetProperties` /
      `RunStore#spent|charge` 全部删除。
      证据：`RunSchemaSqlTest#theSessionBudgetTableIsGone` 与 `migrationCreatesRunTablesInTheirOwnSchema`
      的表清单（少了它）。
- [x] `./scripts/verify.sh` 全绿，跳过 0，覆盖率 ≥60%。
      证据：665 条、失败 0、跳过 0，agent 70.2% / app 80.8%，10 文件 / 20 方法。

## 落地时注意

- `V16` 已发布，**不改它**；`V22` 只做 drop。
- `application.yml` 的 `claw4j.run.budget` 整段要重写（保留 `max-tool-calls` 改名，
  删 `max-tokens` / `idle-minutes`——token 额度现在属于 goal，空闲由会话分段管）。
- `ADR-0025` 的"预算挂会话"在本票之后**再进一步**：额度挂目标。ADR-0027 已经记了这条关系。

## 不做什么

- 不做"按人 / 按部门"的成本分摊。
- 不做 `usage_limited` 的触发（OpenClaw 自己也是"为未来保留"）。
- 不保留 `agent_session_budget` 的读路径"以防有人要查历史用量"——历史用量在 `audit_event` 的
  `agent.run.finished` 上（票 01 已经带上 token 数）。

## 实现记录

### 落在哪儿

| 东西 | 位置 |
| --- | --- |
| 表 | `V22__drop_session_budget.sql`：`drop table claw4j_run.agent_session_budget` |
| 记账 | `SessionGoalStore#addTokens` 改成返回那一行，并在**同一条 SQL** 里撞线进 `budget_limited`：`JdbcSessionGoalStore.ADD_TOKENS` 的 `case when status not in ('complete','budget_limited') and token_budget is not null and tokens_used + ? >= token_budget` |
| 收尾记账 | `ChatClientAgentPort#closeTurn` → `chargeGoal(scope, tokens)`；撞线时打一行 INFO（`used/budget`） |
| 恢复 | `SessionGoalStore#resume`（新方法）：`set tokens_used = case when status = 'budget_limited' then 0 else tokens_used end, status = 'active'`（`where status <> 'active'`，票 02 复核时从 `<> 'complete'` 改正——完成的也能恢复）；`GoalCommand` 的 resume 支改调它 |
| 熔断 | `ToolCallBudget.toolCallingManager(registry, totalCalls)`；`claw4j.agent.tool-call-limit` 落在 `AgentProperties`（同一个 `claw4j.agent` 前缀）；父与子两条链都传它 |
| 熔断话术 | `ChatClientAgentPort#cappedText`：看 `ToolCallLimitExceededException.FINISH_REASON`（公开常量，不做文本匹配），命中就换成 `TOOL_CALL_CAPPED` |
| 删掉 | `RunBudget`、`RunBudgetProperties`、`RunStore#spent|charge` 与 JDBC 实现、`MeteredToolCallback` 的扣额分支、`ChatClientAgentPort` 的进门看额度与 `stopForBudget`、`elapsedMillis` |

### 四处必须说明的

1. **"撞线"只在第一次改状态**。第一版没有排掉 `budget_limited` 自己，于是超支之后**每一轮**
   都会重新"进一次"受限状态、把 `revision` 加一——而 `revision` 记的是"谁改过它"。
   用量仍然继续累加（OpenClaw 的量是"会话自目标建立以来的总量"，不是"这一段还剩多少"）。
2. **流式那一轮的 token 记 0**。汇总 usage 只在最后一帧上，我们这一层拿不到（与"不拿估算值
   冒充真实值"同一个口径）。代价写进了 `docs/agents/guardrails.md` 的已知偏差：
   额度会少算流式那部分；要查真实花费看 `claw4j.model.tokens`。
3. **`RunStatus.BUDGET_EXHAUSTED` 现在没有生产者**。额度不在 run 上了，这个状态只剩历史行；
   票 05 随 `agent_run` 一起删，所以这里不单独改枚举。
4. **`cappedText` 用 `finishReason` 而不是认话术**。Spring AI 把超限变成一代回答
   （`ex.buildGeneration()`），文本是框架的英文原句；拿字符串去认"这是不是超限"会在框架
   改一次措辞之后静默失效，而 `ToolCallLimitExceededException.FINISH_REASON` 是公开常量。

### 对应 OpenClaw 的哪几句

- 额度是目标的：`create_goal` 的 `token_budget`，"When usage reaches the budget, the goal moves
  to `budget_limited`"，且 `budget_limited` **不注入**（"Paused, blocked, budget-limited,
  usage-limited, and complete goals are not injected"）。
- 恢复：`/goal resume` "restarts pursuit from the same objective with a fresh budget window"；
  其余状态 "Token accounting continues unless the budget was reached"。
- 熔断：我们没有 `agents.defaults.timeoutSeconds`（48 小时）那种 per-turn 超时，
  所以用"一轮调几次"做更早的止损点——这条是我们自己的，理由写在 `ToolCallBudget` 的类注释里。

### 没做

- **流式那一轮的用量**（见上，已记进 guardrails）。
- **`usage_limited`**：OpenClaw 自己标着"为未来的用量限制状态保留"，我们也不触发。
- **`/goal start` 收预算**：OpenClaw 原文是"only the model-facing `create_goal` tool can set a
  budget"，票 02 已经照做，这里不动。

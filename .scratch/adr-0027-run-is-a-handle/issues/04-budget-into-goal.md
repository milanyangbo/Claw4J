# 04 预算并进 goal

**Status:** ready-for-agent
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

窗口：`goal start` 与 `goal resume` 打开窗口；`complete` / `clear` 关闭。
`resume` 时如果当前是 `budget_limited`，**`tokens_used` 归零**（OpenClaw：fresh budget window），
其余状态 resume 时不清零（OpenClaw："Token accounting continues unless the budget was reached"）。

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

- [ ] **到额度进 `budget_limited`**：goal 带 `token_budget=1000`，累计到 1000 之后 status 变
      `budget_limited`，且下一轮的提示词里**没有**"进行中的目标"。
      反例：把 `budget_limited` 也注入 → 红（那等于额度用完还逼模型继续）。
- [ ] **`/goal resume` 开新窗口**：从 `budget_limited` resume → status 变 `active`，`tokens_used` 归零，
      `objective` 与 `token_budget` 不变。
      反例：resume 不重置用量 → 下一轮立刻又 budget_limited，红。
- [ ] **其他状态 resume 不清零**：`paused` → resume，`tokens_used` 保持原值。
      反例：无条件归零 → 红（"暂停一下就能白拿一份额度"）。
- [ ] **熔断不改 goal 状态**：把 `tool-call-limit` 设成 1，触发之后 goal 仍是 `active`。
      反例：熔断时写 `budget_limited` → 红（两件事被合成一个）。
- [ ] **表真的没了**：`V22` 之后 `agent_session_budget` 不存在；`RunBudget` 里没有按会话累计的 SQL。
- [ ] `./scripts/verify.sh` 全绿，跳过 0，覆盖率 ≥60%。

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

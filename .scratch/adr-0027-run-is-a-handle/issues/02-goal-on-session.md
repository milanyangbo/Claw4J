# 02 goal 挂会话

**Status:** resolved（2026-11-06）
**依赖:** 无（与 01 可并行）
**依据:** ADR-0027 决定三

## 背景

我们的"计划"是 `agent_run.plan_ref` 指一版 `agent_step(kind='plan')`，模型用 `update_plan` 整体重写。
OpenClaw 不是这个：

> A goal is one durable objective attached to the current OpenClaw session.
> **Goals are session state: they move with the session key, survive process restarts.**
> 六态：`active` / `paused` / `blocked` / `budget_limited` / `usage_limited` / `complete`。
> **只有 `active`** 的目标在每回合注入一行"进行中的目标：…"；其余状态不注入。
> `/new` 与 `/reset` **清掉当前会话的目标**。一个会话同时只能有一个。

差别在三处，每一处都是我们现在做不到的：

- 我们能写计划，但**没有"暂停 / 阻塞 / 达成"这三个人能按的按钮**；
- 我们的计划只在 run 上，run 换一轮就没了，所以"跨轮盯着一个目标"根本表达不出来；
- 暂停中的目标仍然会进提示词（因为注入的是"当前计划"），于是"叫停"不生效。

## 落点

### 1. `V20__goal.sql`

```text
claw4j_session.conversation_goal
  session_key   varchar(255) pk       -- 与 SessionKeys.sessionIdOf 同形状（含段）
  status        varchar(24) not null  -- 六态，check 约束
  objective     text        not null
  status_note   varchar(1024)         -- /goal pause waiting for CI 里那句话
  token_budget  bigint                -- null 表示不限额
  tokens_used   bigint not null default 0
  revision      integer not null default 1   -- 每次 edit 加一
  created_at    timestamptz not null
  updated_at    timestamptz not null
```

`session_key` 做主键：一个会话一行，这就是"同时只能有一个目标"。

### 2. `SessionGoal` + `SessionGoalStore`

```text
SessionGoal(status, objective, note, tokenBudget, tokensUsed, revision, createdAt, updatedAt)
SessionGoalStore.of(sessionKey) / start / edit / setStatus / addTokens / clear
```

`start` 在已有行时**必须失败**并返回 `goal already exists`（OpenClaw 原话：
`Goal error: goal already exists`）。不做"静默覆盖"——那会让用户以为新建了，其实改旧的了。

### 3. 模型侧三个工具

| 工具 | 参数 | 行为 |
| --- | --- | --- |
| `create_goal` | `objective`, `token_budget?` | 新建；已有则报错回模型 |
| `get_goal` | 无 | 回当前目标全文、状态、用量 |
| `update_goal` | `status?`, `note?`, `objective?` | 改状态 / 追加备注 / 改写目标（改写保留原状态与用量） |

三个都要从 `ToolContext` 拿 `ChatMemory.CONVERSATION_ID`（会话键），不从 runId 拿——
这一条与 04 票里 `/new` 那个教训同源：**任何"当前是什么"都要从会话键取，不从 run 取**。

### 4. `/goal` 命令族

落在 `SessionCommand` 旁边（`GoalCommand`），与 `/new` 同层：**命令消息不进模型**。

| 输入 | 效果 |
| --- | --- |
| `/goal` 或 `/goal status` | 回当前目标摘要（状态、目标、用量、可用命令） |
| `/goal start <obj>` / `/goal set` / `/goal create` | 新建 |
| `/goal <obj>`（其余未知动作词） | 新建 |
| `/goal edit <obj>` | 改写，状态与用量不变 |
| `/goal pause [note]` / `/goal resume [note]` | 暂停 / 恢复 |
| `/goal complete [note]` / `/goal done` | 达成 |
| `/goal block [note]` / `/goal blocked` | 阻塞 |
| `/goal clear` | 删掉这一行 |

`start` / `edit` 保留目标里的换行、缩进与连续空格（OpenClaw 同），只裁首尾空白——
一句话的目标与七行的目标都该原样进去。

### 5. 每回合注入

在 `promptText()` 里加一行 **user-role** 上下文，形如：

```text
进行中的目标：<objective>。推进目标或更新其状态（get_goal / update_goal）。
```

- **只有 `active` 才注入**；paused / blocked / budget_limited / usage_limited / complete 一律不注入。
  这一条是"操作员叫停能持续生效"的唯一保证。
- 目标超长时截断（OpenClaw 会截短以保持这一行简洁）；截断只影响注入，不影响库里原文。

### 6. `/new` 与 `/reset`

换段之后 `session_key` 变了，新段**天然没有 goal**——不需要额外 delete。
但要在 `SessionCommand` 的注释里写清楚这一点：**看起来是"清"，机制是"换了个键"**。
旧段那一行留着（等会话 TTL 一起清），这样用户 `/session` 切回旧段时目标还在——
OpenClaw 的 goal 是会话状态，不是"删除"。

### 7. 计划那条老路径

- 删 `update_plan` 工具与 `UpdatePlanTool`；
- 删 `RunStore#planOf` / `setPlan` / `markPlan` 与 `agent_run.plan_ref`
  （票 05 随表一起删，本票先让它们没有调用方）；
- `agent_step` 里 `kind='plan'` 的读取路径一并去掉。

## 判据

- [x] **一个会话只能有一个目标**：`start` 两次，第二次失败、库里仍是一行、`objective` 还是第一个。
      反例：把 `insert … on conflict do nothing` 改成 `do update` → 静默覆盖，红。
      证据：`ToolGovernanceTest#oneGoalPerSessionAndEditKeepsStatusAndUsage`（内存替身）、
      `RunSchemaSqlTest#goalsAreSessionStateWithAClosedVocabulary`（主键那一半，真库）。
- [x] **只有 active 注入**：建目标 → 提示词里有 `活跃目标：`；`/goal pause` 之后没有；`/goal resume` 又有。
      反例：去掉 `filter(SessionGoal::injected)` → `ChatPipelineTest#onlyAnActiveGoalIsInjectedIntoEveryTurn`
      在"暂停之后不再注入"那一句红。
      证据：同一条用例。
- [x] **换段即无目标，旧段那一行还在**：同一地址的 `seg-1` 有目标，`seg-2` 读到空；`seg-1` 仍然在。
      反例：`SessionKeys.sessionIdOf(…, segment)` 丢掉段 → `seg-2` 读出 `seg-1` 的那个目标，红。
      证据：`RunSchemaSqlTest#aFreshSegmentHasNoGoalAndEditKeepsStatusAndUsage`。
- [x] **六态在闭合词表里**：写 `running` 被库拒绝。
      反例：迁移里删掉 `conversation_goal_status_allowed` → 写进去了，红。
      证据：`RunSchemaSqlTest#goalsAreSessionStateWithAClosedVocabulary`。
- [x] **edit 不动状态与用量**：`active` + `tokens_used=1200` + 50k 预算 → `/goal edit` → 状态仍 active、
      用量仍 1200、预算仍 50k、`revision` 2。
      反例：`edit` 改成"先 clear 再 start" → 用量 1200→0，红。
      证据：同上两条用例各断一次（内存 + 真库）。
- [x] **命令不进模型**：发 `/goal 让 CI 变绿` 与 `/goal pause` 时模型调用次数不增。
      证据：`ChatPipelineTest#onlyAnActiveGoalIsInjectedIntoEveryTurn` 里那两条 `model.prompts()`
      断言（第一次 `isEmpty()`、第二次 `hasSize(1)`）。
- [x] **多行目标原样保留**：`/goal start a\n  b\n    c` 解析出的目标逐字含那两个换行与缩进。
      反例：按空白切词重组 → 红。
      证据：`SessionMemoryTest#sessionIdFollowsIdentityAndPermissions` 末段。
- [x] `./scripts/verify.sh` 全绿，跳过 0，覆盖率 ≥60%。
      证据：673 条、失败 0、跳过 0，agent 66.5% / app 80.8%，10 文件 / 20 方法。

## 落地时注意

- 目标挂在 `claw4j_session` schema，不挂 `claw4j_run`——它是会话状态，不是某件事的状态。
- `token_budget` / `tokens_used` 本票只建列不做账；记账在票 04（`budget_limited` 由那里进）。
- 现有 `ToolGovernanceTest` 里数工具个数的断言会因为"少一个 `update_plan`、多三个 goal 工具"变红，
  按净变化改（+2），不要改成"不断言"。

## 不做什么

- 不做 Control UI 的目标胶囊（那是前端）。
- 不做 Local Codex 那种 "Resume" 断点续传（它保留 goal identity 与对话历史，我们没有那个运行时）。
- 不做目标模板、目标历史、多目标队列——OpenClaw 明确"A goal is not a task queue"。
- 不做 `usage_limited` 的触发逻辑：词表留着，但它由未来的用量限制进入，本线不进这个态。


## 实现记录

### 落在哪儿

| 东西 | 位置 |
| --- | --- |
| 表 | `V20__conversation_goal.sql`：`claw4j_session.conversation_goal`，主键 `session_key`（含段） |
| 六态 | `GoalStatus`：`active/paused/blocked/budget_limited/usage_limited/complete` + `resumable()` / `modelSettable()` |
| 记录 | `SessionGoal`（record，目标原文只裁首尾） |
| 存储 | `SessionGoalStore`（接口）+ `JdbcSessionGoalStore`：四条 `returning` 语句 + 一条 delete |
| 工具 | `GoalTools`：`get_goal` / `create_goal` / `update_goal`，会话键从 `ChatMemory.CONVERSATION_ID` 取 |
| 命令 | `GoalCommand`（sealed）+ `parse` / `execute`：十二个动作词，其余文本当新目标 |
| 注入 | `ChatClientAgentPort#activeGoalLine`：只认 `active`，折成一行、截 200 字 |
| 装配 | `SessionMemoryConfiguration`：store + 三个工具 + 权限 + 档位（`MINIMAL`） |

### 四处与票面不一致（前两处是票面写错了）

1. **`update_goal` 没有 `objective` 参数**。票面写"改状态 / 追加备注 / 改写目标"，但 OpenClaw 原文是
   *"模型不能在不作说明的情况下暂停、恢复、清除或替换目标。这些操作仍然是操作员/会话通过
   `/goal` 和重置命令执行的控制"*。所以工具只有 `status`（`complete` / `blocked`）+ `note`；
   改写是人的按钮（`/goal edit`）。这条差别是实质的：给了模型 `objective` 就等于给了它
   "把目标换成另一件事"的能力，而这正是它不该有的。
2. **`SessionGoalStore` 是接口 + JDBC 实现**，票面只写了一个名字。理由：注入路径挂在
   `ChatClientAgentPort` 上，而那个类的用例全是假模型、没有库；不开接口的话，每条
   "提示词里有没有那一行"都要拉一个 Testcontainers。`ScheduleStore` 没开接口是因为它只在
   工具里用、用例本来就走真库。
3. **`StepKind.PLAN` 保留**。票面说"`kind='plan'` 的读取路径一起去掉"——读取路径确实去了
   （`planOf` / `setPlan` / `markPlan` / `recap` 的 plan 段全删），但枚举值与 V7 的约束留着：
   库里还有历史的 plan 行，`fromColumn` 认不出它们会让 `stepsOf` 直接抛。
4. **`goalTools` 不跟着 `claw4j.run.enabled` 进退**。原来的 `update_plan` 跟着它，因为计划写在
   `agent_step` 上；目标挂在会话里，关掉记账之后"这一段对话在往哪儿走"照样成立。

### 顺带删掉的东西

`UpdatePlanTool`（整个文件）、`RunStore#planOf/setPlan/markPlan` 与 `JdbcRunStore` 的实现、
`RunPersistenceConfiguration` 里那三个 bean（工具 / 权限 / 档位）、`recap` 里的 `planBlock`。
`RunSchemaSqlTest` 里三条计划用例随之删掉，但 V7 那条"已发生的一步不能是待做"的约束守门
改写成了 `modelTurnsCannotBePending` 留着——它守的是表结构，与计划那条功能路无关。

### 五个反例，各红一条

| # | 改坏什么 | 红了哪条 |
| --- | --- | --- |
| 1 | 注入去掉 `filter(SessionGoal::injected)` | `onlyAnActiveGoalIsInjectedIntoEveryTurn`（暂停后仍注入） |
| 2 | `SessionKeys#sessionIdOf` 丢掉段 | `aFreshSegmentHasNoGoalAndEditKeepsStatusAndUsage`（新段看得见旧目标） |
| 3 | 迁移删掉 `conversation_goal_status_allowed` | `goalsAreSessionStateWithAClosedVocabulary`（`running` 写进去了） |
| 4 | `edit` 改成先 clear 再 start | 同 #2（`expected: 1200L but was: 0L`） |
| 5 | `/goal` 用前缀匹配 | `sessionIdFollowsIdentityAndPermissions`（`/goals 是什么` → `Start[objective=s 是什么]`） |

### 没做

- **`token_budget` / `tokens_used` 只建列不做账**：记账与 `budget_limited` 的迁移在票 04。
  `/goal start` 也不收预算参数——OpenClaw 的原话是"`/goal start` 不接受 Token 预算标志；
  预算只能通过面向模型的 `create_goal` 工具设置"。
- **不做 Control UI 的目标胶囊、TUI 页脚**：那是前端。
- **不做 `usage_limited` 的触发**：词表留着，等真有用量限制的那一天。

## 收票时要补

| 文件 | 补什么 |
| --- | --- |
| `docs/design/session-memory.md` | 一节"会话目标"：六态、只有 active 注入、`/new` 换段即无目标 |
| `docs/adr/0027` | 落地顺序第 2 步标"已落地（02）" |
| `docs/agents/guardrails.md` | 记一条：`/goal start` 不收预算参数（照 OpenClaw），要设预算只能让模型调 `create_goal` |

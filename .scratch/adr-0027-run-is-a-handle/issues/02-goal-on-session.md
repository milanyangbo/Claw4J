# 02 goal 挂会话

**Status:** ready-for-agent
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

- [ ] **一个会话只能有一个目标**：`start` 两次，第二次抛 `goal already exists`，库里仍是一行，
      `objective` 还是第一个。
- [ ] **只有 active 注入**：建目标 → 提示词里有 `进行中的目标：`；`/goal pause` 之后 → 提示词里没有；
      `/goal resume` → 又出现。
      反例：注入时不过滤状态（只判"有没有行"）→ 暂停后仍出现，红。
- [ ] **换段即无目标**：`/new` 之后同一用户在提示词里看不到旧目标；旧段那一行仍在库里。
      反例：goal 按 `owner`（userId@权限指纹）存而不是 `session_key` → 换段后还能看见，红。
- [ ] **六态都在闭合词表里**：把 status 写成 `running` → 库直接拒绝（check 约束）。
- [ ] **edit 不动状态与用量**：`active` + `tokens_used=1200` → `/goal edit ...` → 状态仍 active，
      用量仍 1200，`revision` 加一。
      反例：edit 走"先 clear 再 start"→ 用量归零，红。
- [ ] **命令不进模型**：发 `/goal pause` 时模型调用次数为 0（用计数的假模型断）。
- [ ] **多行目标原样保留**：`/goal start a\n  b\n  b` → 库里的 `objective` 保留换行与缩进。
      反例：按空格切词重组 → 红。
- [ ] `./scripts/verify.sh` 全绿，跳过 0，覆盖率 ≥60%。

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

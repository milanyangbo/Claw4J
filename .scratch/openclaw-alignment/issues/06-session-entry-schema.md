# 06: 会话状态是一张 entry，不是两张表——goal 与指针按 `SessionEntry` 重做

**What to build:** 把 `conversation_pointer` 与 `conversation_goal` 合成一张
`claw4j_session.session_entry`，字段逐条对齐 OpenClaw 的 `SessionEntry` 与 `SessionGoal`；
`/new` 换段时在同一行里清掉目标（不再靠"读不到"）；token 用量改成"快照差"而不是增量累加。

**Blocked by:** 无（`01`–`05` 都已 resolved，且不与它们碰同一处）。

**Type:** task

**Status:** open（2026-11-09）

**Demo:** `/goal 让 CI 变绿` 之后 `/new`，`/goal` 答"还没有目标"**且库里那一行没有旧目标**
（不是"读不到"）；`/goal` 的完成时间在重复 `complete` 时保持第一次那个值；`at-hour` 从 4 改成 9
之后，今天 05:00 开始的段按新边界判过期。

## 背景：两张表都没对齐，而且拆开本身就是偏离

它没有"指针表"和"目标表"。它只有一条 **session entry**（`src/config/sessions/types.ts:360-450`）：

```ts
sessionId: string;              // 当前段
sessionStartedAt?: number;      // 当前段开始那一刻
lastInteractionAt?: number;     // 最后一次用户交互（延长 idle 寿命的那个）
createdAt?: number;             // 节点创建时间，跨段不变
previousSessionId?: string;     // 上一段（同一 sessionKey 下）
totalTokens?: number;           // 会话累计用量
goal?: SessionGoal;             // 目标就挂在这一条上
```

goal 是这一条里的**一个对象**，字段在 `packages/gateway-protocol/src/schema/sessions-goal.ts:7-32`：
`schemaVersion` / `id` / `objective` / `status` / `createdAt` / `updatedAt` / `tokenStart` /
`tokenStartFresh` / `tokensUsed` / `tokenBudget` / `continuationTurns` / `lastStatusNote` /
`pausedAt` / `blockedAt` / `completedAt` / `usageLimitedAt` / `budgetLimitedAt`。共 17 个。

我们拆成了两张表，于是有了三类偏离，第三类是这次要一并解决的根因：

**一、goal 少了 9 个字段，多了 1 个自造的。**

| 它 | 我们 | 后果 |
| --- | --- | --- |
| `id`（UUID） | 无 | 变更请求没有目标身份，旧按钮会改到同一会话的新目标 |
| `tokenStart` / `tokenStartFresh` | 无 | 用量只能增量累加，漏一轮补不回来；"建目标时还没有新鲜快照"表达不了 |
| `pausedAt` / `blockedAt` / `completedAt` / `budgetLimitedAt` / `usageLimitedAt` | 无 | "重复 complete 保留首次完成时间"（`goals-transitions.ts:129`）做不到 |
| `continuationTurns` | 无 | 自动续跑的轮数没有落点（我们还没做续跑，但字段是 goal 状态的一部分） |
| `schemaVersion` | 无 | 加字段后旧行无法按版本解释 |
| —— | `revision` | 它没有；并发用 `expectedGoalUpdatedAt`（我们也没用 revision 做 CAS，它是装饰） |

**二、指针把"结论"当成了"事实"。**
它存 `sessionStartedAt`，边界在读时算：
`sessionStartedAt < resolveDailyResetAtMs(now, atHour)`（`src/config/sessions/reset-policy.ts:94-104`）。
我们存 `day_started`（按当时的 at_hour 算好的**逻辑日**），判定是 `day_started <> today`。
默认配置下等价；改 `at-hour` 或改时区就分叉——今天 05:00 开始的段，at_hour 从 4 改成 9 之后，
它判过期，我们判新鲜。同类缺失：`createdAt`（跨段不变）、`previousSessionId`、`totalTokens`。

**三、拆成两张表的代价已经发生了。**
ADR-0027 第三节写的是"`/new` 与 `/reset` 清掉当前会话的目标"，而实现只做了
`pointers.rotate(...)`（`ChatClientAgentPort:260`），一次 `clear` 都没调。于是：
旧段那行留在 `conversation_goal` 里；`SessionMaintenance` 只清 `ai_session` 与
`conversation_pointer`，**不碰 goal**；`conversation_goal_active_idx`（`where status='active'`）
把这些孤儿算进"现在有几个目标在推进"，只涨不降。

它不会有这个问题，因为 goal 就在 entry 上——换 `sessionId` 与清 goal 是同一条写。
**这就是"合表"的理由：不是我们想少一张表，是它的不变式本来就在一条记录里。**

## 决定

### 一、一张表：`claw4j_session.session_entry`

主键是 **sessionKey（不含段）**，与它的 entry key 同义。段是这一行的一列，不是主键的一部分。

```sql
create table claw4j_session.session_entry (
    session_key            text        primary key,
    owner                  text        not null,   -- userId@权限指纹（ADR-0025）
    channel                text        not null,
    chat_id                text        not null,

    segment                text        not null,   -- = 它的 sessionId
    session_started_at     timestamptz not null,   -- = sessionStartedAt
    last_interaction_at    timestamptz not null,   -- = lastInteractionAt
    created_at             timestamptz not null,   -- 跨段不变
    previous_segment       text,                   -- = previousSessionId
    total_tokens           bigint      not null default 0,  -- = totalTokens（段级）

    -- goal 平铺；goal_id is null 表示没有目标
    goal_schema_version    smallint,
    goal_id                uuid,
    goal_objective         text,
    goal_status            varchar(24),
    goal_token_start       bigint,
    goal_token_start_fresh boolean,
    goal_tokens_used       bigint,
    goal_token_budget      bigint,
    goal_continuation_turns integer,
    goal_last_status_note  text,
    goal_paused_at         timestamptz,
    goal_blocked_at        timestamptz,
    goal_completed_at      timestamptz,
    goal_usage_limited_at  timestamptz,
    goal_budget_limited_at timestamptz,
    goal_created_at        timestamptz,
    goal_updated_at        timestamptz
);
```

约束（逐条有依据）：

- `conversation_goal_all_or_nothing`：`goal_id` / `goal_objective` / `goal_status` 要么全空要么全非空，
  且 `objective` 非空白——"目标存在但内容是空格"这种行进不来。
- `conversation_goal_status_allowed`：六态，逐字照它的 `SessionGoalStatus`。
- `conversation_goal_budget_positive`：`goal_token_budget is null or > 0`。
- `conversation_goal_window_non_negative`：`goal_token_start >= 0 and goal_tokens_used >= 0`。
- 部分索引 `... (goal_status) where goal_status = 'active'`，保留"现在有几个目标在推进"这个查询。

### 二、换段 = 同一行里换 `segment` 并清空 goal 列

一条 `insert ... on conflict do update` 里完成：`segment`、`session_started_at`、
`last_interaction_at`、`previous_segment`、`total_tokens`（换段清零），
以及 `goal_*` 全部置 null。**不再调 `clear`，也不再靠"新段读不到"**——它的 `/new`
就是"新 sessionId + 没有 goal"这个状态，我们写同一个状态。

`/reset` 与 `/new` 走同一入口（`SessionCommand` 已经合并），所以一处生效。

### 三、用量改成快照差

它的算法（`goals-transitions.ts:26-65`）：

```
tokenStart   = 目标创建时会话的新鲜快照（还没有就等下一次，tokenStartFresh 记这件事）
tokensUsed   = max(旧 tokensUsed, totalTokens - tokenStart)
达到预算时 → status = budget_limited, budgetLimitedAt = now
```

我们的对应物：

- `session_entry.total_tokens` 扮演 `entry.totalTokens`（段级累计，换段清零）；
- `SessionGoalStore#account(sessionKey, Long delta)` 的 `delta` 是**这一轮的用量**；
  `null` 表示这一轮拿不到（现在 `tokensOf` 返回 0，把"没数"与"花了 0"混成一件）；
- 一条语句里先加 `total_tokens`，再算 `tokens_used = max(goal_tokens_used, total_tokens - goal_token_start)`，
  并判预算。`token_start_fresh = false` 时，这一轮把 `token_start` 采纳成当前 `total_tokens`
  （对应 `shouldAdoptFreshStart`），`tokens_used` 保持旧值。

`addTokens` 这个名字随之消失：它描述的是"累加"，而它的语义是"对账"。

### 四、goalId 要真的用起来

它的变更请求全部带 `goalId`（`GoalOperationIdentity`），文档原话：

> Controls target the displayed Goal ID, so a stale button cannot change a replacement Goal.

我们的 `SessionGoalStore` 写方法加 `UUID goalId` 参数，SQL 里 `and goal_id = ?`。
`/goal` 命令与三个模型工具都先读一次拿 id（它们本来就要读来做错误分类），
读到的与写入的之间被换过目标 → 0 行 → 回"目标已经换了，重看一下"。

`revision` 删掉：它没有这个字段，而我们也没有任何一处用它做条件。

### 五、接口与表名

- `ConversationPointerStore` → `SessionEntryStore`；`ConversationPointers` → `SessionEntries`
  （它不再只回答"段"，还写 `last_interaction_at` / `total_tokens`）。
- `JdbcConversationPointerStore` → `JdbcSessionEntryStore`，落 `session_entry`。
- `JdbcSessionGoalStore` 保留类名，改为对同一张表做 `update`（不再 `insert`：行由 entry 侧保证存在）。
- 新增 `SessionKeys#conversationKeyOf(owner, channel, chatId)`（不含段）。
  工具上下文里加一个键 `ContextKeys.CONVERSATION`，值是这个 key——
  **不从 `CONVERSATION_ID` 解析**：`chatId` 里可以有冒号（`cron:` 就是），解析会错。

## 迁移与回填

`V8__session_entry.sql`：

1. 建表与索引；
2. 从 `conversation_pointer` 回填 entry 列（`session_started_at` 由 `day_started` 与该段的
   `ai_session.created_at` 取较晚者——`day_started` 是 date，单独用会丢掉时刻）；
3. 从 `conversation_goal` 回填 goal 列，**只回填"当前段"的那些行**：
   `session_key` 必须等于
   `'claw4j:user:' || owner || ':' || channel || ':' || chat_id || ':' || segment`；
   其余是孤儿，直接丢弃（这正是这次要消灭的东西）；
4. `goal_schema_version = 1`、`goal_token_start = 0`、`goal_token_start_fresh = false`、
   `goal_continuation_turns = 0`、`goal_tokens_used` 沿用旧值；
5. drop `conversation_goal` 与 `conversation_pointer`。

**已有库要重建还是迁移？** 这次是真迁移（保数据），不是基线重来：`V8` 的中间态
（新旧都有）可以跑，回填完再 drop。

## 不照抄的地方（三段式）

1. **它的 entry 是 JSON 文件 / SQLite 的一行，我们是 Postgres 一张表**——形态差异，不是语义。
2. **goal 平铺成 `goal_*` 列，不是嵌套对象**：关系库没有嵌套；前缀是为了不与 entry 自己的
   `status` / `created_at` 撞名。
3. **`usage_limited` 我们进不去**：它自己也是"reserved for a future usage-limit stop state"
   （`docs/tools/goal.md:136`），词表保留、列保留、恒 null。

## 验收判据（反例都要能红）

- [ ] **换段即无目标，且库里没有旧目标**：`/goal x` → `/new` → `session_entry` 那一行的
      `goal_id is null`（不是"另起一行"、也不是"旧行还在"）。反例：只换 segment 不清 goal → 红。
- [ ] **没有孤儿**：`select count(*) from session_entry where goal_status = 'active'` 在
      一轮 `/new` 之后不增。反例：goal 另立一张表、`/new` 只换段 → 红。
- [ ] **goalId 挡旧按钮**：读出 goal A，清掉后建 goal B，再用 A 的 id 改状态 → 0 行、"目标已经换了"。
      反例：写方法忽略 `goalId` → 只有这条红。
- [ ] **重复 complete 保留首次完成时间**：complete → resume → complete，`goal_completed_at`
      第一次那个值不变。反例：无条件 `completed_at = now()` → 红。
- [ ] **paused / blocked / budget_limited 的时刻落库**：各自的 `*_at` 非空，且 resume 之后
      不清（`pausedAt` 是历史，不是"当前是否暂停"）。
- [ ] **token 是快照差**：`total_tokens` 从 0 累到 100，`token_start=40` → `tokens_used=60`。
      反例：增量累加（忽略 baseline）→ 红。
- [ ] **`token_start_fresh=false` 会采纳**：建目标时那一轮没有 usage（`delta = null`），
      下一轮有 `total_tokens=100` → `token_start` 变 100、`tokens_used` 保持旧值。
      反例：直接 `token_start = 0` → `tokens_used` 变成 100 → 红。
- [ ] **`null` 与 0 分开**：拿不到 usage 时不动 `tokens_used`。反例：`tokensOf` 返回 0 → 红。
- [ ] **daily 边界在读时算**：`at_hour` 4→9，段开始于今天 05:00 → 判过期。
      反例：存 `day_started` 比日期 → 红。
- [ ] **`previous_segment` 与 `created_at`**：换段后前者是旧段、后者不变。
- [ ] `./scripts/verify.sh` 全绿、跳过 0、行覆盖率 ≥60%；**没有新增测试文件**。

## 门禁余量

`claw4j-agent` 10 个文件已满；`SessionMemoryTest` / `ToolGovernanceTest` / `ChatPipelineTest`
都在 19–20 条。这次不新增文件，改现有用例：
- `SessionMemoryTest`：加 entry 的跨段/边界用例，合并两条已经重复的维护用例腾位；
- `ToolGovernanceTest`：goal 的用例改成按 goalId，合并"已达成不能改"与"旧 id 被拒"两条；
- `SearchSqlTest`：表名换 `session_entry`，真库用例补"换段清 goal"与"快照差"两条。

## 风险

- 表改名会牵动 `SessionMaintenance`、`BuildContractTest` 的表断言、`SearchSqlTest`、
  `PersistenceSchemaTest`、`TurnFlowTest`。一次改完，不留中间态。
- `total_tokens` 是**段级**的：换段清零。这与"目标预算"的口径一致（新段本来就没有目标）。
- 回填时 `session_started_at` 只能取近似值：`day_started` 是 date，`ai_session.created_at`
  是该段第一次写入的时刻，取较晚者是当前可得的最好值。这条要写进迁移注释。

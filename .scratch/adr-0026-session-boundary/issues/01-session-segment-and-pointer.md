# 01 · 会话段与指针：一段对话有开始也有结束

**Status:** resolved（2026-11-05）
**Blocked by:** 无（ADR-0026 已裁）

## 为什么

一段对话现在的 id 是 `claw4j:user:<owner>:<通道>:<chatId>`，**永远不会变**。它没有"结束"这个动作，
于是三件事各自猜了一套：

| 谁 | 以为会话什么时候结束 | 依据 |
| --- | --- | --- |
| 记忆 / 压缩 / 召回 | 永不结束 | `SessionKeys:98` 的拼法里没有任何时间成分 |
| 预算 | 120 分钟没动静 | `claw4j.run.budget.idle-minutes`（`application.yml:253`） |
| 存储 | 建行 30 天后 | `spring.ai.session.time-to-live: 30d` + `SessionCleanup` |

最刺眼的一条：**web 端没有"开一段新对话"的手段**。`HttpPrincipalResolver:54-55` 里
`chatId = (explicitChatId == null || isBlank) ? userId : explicitChatId`，也就是说浏览器不带
`X-Claw4j-Chat-Id` 时，一个人一辈子只有一段会话——它不会因空闲结束、不会因跨天结束，
只会在第 30 天被后台任务**删掉**（删行，不是换对话）。

本票做 ADR-0026 的决定一：**每日或空闲，谁先到算谁；结束 = 换一个新段。**

## 现状（照源码核过）

| 事实 | 在哪儿 |
| --- | --- |
| 会话 id 的拼法 | `SessionKeys:98-102`（`PREFIX + owner + ":" + 通道 + ":" + chatId`），`NO_CHAT = "-"` 兜底 |
| 生产调用点只有两处 | `ChatClientAgentPort:240`（reply）、`:287`（stream） |
| 审批续跑不重新算 | `ChatClientAgentPort:372-374` 先 `runs.sessionOf(runId)`，读不到才退到 `sessionIdOf(owner, null, null)` |
| 召回读的是工具上下文里的那份 | `RecallSessionTool:164`、`:351` |
| 召回的身份校验是前缀 | `RecallSessionTool:346-348`：`sessionId.startsWith(PREFIX + owner + ":")`——**末尾加段不影响** |
| 缺省 chatId | `HttpPrincipalResolver:54-55`，等于 userId |
| 预算的空闲判定 | `application.yml:253` + `JdbcRunStore#spentOfSession` |
| 会话表 | `V3__session_schema.sql`，schema 是 `claw4j_session`，`ai_session_event` 有 `on delete cascade` |

## 落点

### 1. 新表（`V17__conversation_pointer.sql`）

```sql
create table if not exists claw4j_session.conversation_pointer (
    owner       text        not null,   -- SessionKeys.ownerOf 的结果
    channel     text        not null,   -- 缺省落 '-'
    chat_id     text        not null,   -- 缺省落 '-'
    segment     text        not null,   -- 当前段，拼进 sessionId
    touched_at  timestamptz not null,   -- 空闲判定的依据，每次都刷新
    day_started date        not null,   -- 跨天判定的依据，按 claw4j.agent.zone
    primary key (owner, channel, chat_id)
);
```

`segment = <epochMilli>-<4 位随机>`。带时间前缀是为了两件事：清理时能按段排序，
排查时能一眼看出"这段对话是什么时候开始的"。

### 2. 轮换是一条语句，不是"读-判-写"

```sql
insert into claw4j_session.conversation_pointer
       (owner, channel, chat_id, segment, touched_at, day_started)
values (?, ?, ?, ?, ?, ?)
on conflict (owner, channel, chat_id) do update set
    segment     = case when <过期> then excluded.segment     else conversation_pointer.segment     end,
    day_started = case when <过期> then excluded.day_started else conversation_pointer.day_started end,
    touched_at  = excluded.touched_at
returning segment;
```

`<过期>` 展开成两个条件（`day_started <> 今天` 或 `touched_at < now - idle`），参数在 Java 侧算好传进去。

**为什么用 `case` 而不是 `do update … where <过期>`**：`where` 版本在"不轮换"这条**最常见**的路径上
返回 0 行，调用方还得再查一次才能拿到旧段——每条消息两次往返。更糟的是 `where` 不成立时
`touched_at` 也不会更新，于是空闲判定永远是"从这一段的第一次说话算起"，
120 分钟后每句话都会换段。`case` 版本一条语句、永远返回一行、每次都刷新 `touched_at`。

### 3. `SessionKeys` 多一个参数

```text
sessionIdOf(owner, channel, chatId, segment)   // segment 为空 → 不加冒号后缀（老形状）
```

保留"空段 = 老形状"这一支是给两个地方用的：审批续跑读不到 `session_id` 时的回落
（`ChatClientAgentPort:374`），以及落地之前已经在库里的老会话。它们**只读不写**。

### 4. 配置只有一个来源

```yaml
claw4j:
  session:
    reset:
      mode: daily-and-idle   # daily-and-idle | idle | none
      at-hour: 4             # 时区取 claw4j.agent.zone，不取容器时区
      idle-minutes: 120
```

**`claw4j.run.budget.idle-minutes` 必须删掉。** 它现在是"会话结束"的第二个定义；
留着它，下一次改 idle 的人只会改一处，另一处看起来还在生效——正是这份 spec 要消灭的形状。
`RunBudgetProperties` 里那个字段与它的 `DEFAULT_*` 一起删，`spentOfSession` 的空闲兜底
（见 04）改成读同一个值。

### 5. 指针读不到时的降级

指针是"这段对话叫什么"，不是"能不能回答"。所以：库连不上、表没迁、语句报错 —— 落到
**无段的老形状 id** + 一行 WARN + 一个计数（`claw4j.session.pointer.failures`），
**不拦这一轮**。这正是 `FaultTolerantSessionService` 的口径：记忆是附加信息，不是可用性依赖。

## 一个已知窗口，02 才关掉

两条消息同时跨过 4:00（或同时超过空闲阈值）时，两条都会判定"过期"，各自 `case` 出一个新段，
后写的那条覆盖先写的——**同一次轮换被做了两次，两条消息落在两段会话里**。
窗口很窄（毫秒级），但不是零。

修它需要"轮换与这一轮在同一个临界区里"，那就是 02 的 lane。所以**01 与 02 要连着发版**，
不要只发 01。票面把这条写出来，是为了让 02 的验收能直接对着它：

> 02 的反例 = 同一对话并发两条 → 段必须只有一个。

## 怎么测（门禁顶格，只能是置换）

`claw4j-agent` 是 10 个测试文件（上限），`SessionMemoryTest` / `ChatPipelineTest` /
`ObservabilityTest` / `ModelAndToolSelectionTest` / `ToolGovernanceTest` 都已经 20 个方法（上限）。
所以本票**不新增测试文件、不新增方法**：

| 要验什么 | 落在哪儿 |
| --- | --- |
| 段进 id、同日稳定、空闲/跨天换段、空段回落到老形状 | 置换 `SessionMemoryTest#sessionIdFollowsIdentityAndPermissions`——它现在断的正是"两把键"的形状，改这里的成本最低 |
| `on conflict … case … returning` 在 Postgres 上真的只写一行、每次都刷新 `touched_at` | 置换 `claw4j-app` 的 `RunSchemaSqlTest` 里一条 SQL 用例 |
| 指针 store 的判定逻辑（不连库） | 用内存实现，跟上面那条 `SessionMemoryTest` 一起断言 |

三段放进一条方法不是"一条测三件事"：同一个入口（`sessionIdOf`）、同一种断言形状
（两个 id 相不相等），差的只是时间输入——与 `chineseQueryNeedsNoSpaces` 那个写法同源。

**如果发现置换不下来，停下来问**，不要把方法挪进另一个文件：文件数与方法数都由
`verify.sh` 数着，挪一次就顶破一格。

## 不做什么

- **不做 `identityLinks`**（跨通道共享同一段对话）：需要"这两个 id 是同一个人"的事实来源。
- **不改群聊粒度**：仍按"人 + 权限"分段。
- **不改 TTL 数值**：留存口径不在这张票里（见 04）。
- **不做 `/new`**：它是 04 的入口，本票只负责"段会因时间而换"。
- **不迁移老会话**：老的行保持原样，只读。用户会觉得"以前聊的想不起来了"——这是
  ADR-0026 Consequences 里认过的代价，不在本票里偷偷补一个迁移。

## 验收判据

- [x] **同一天连续两条，会话 id 相等**：同一 owner / 通道 / chatId，`now` 相差一小时，
      两次 `sessionIdOf` 的段相同。反例：把段写成 `now` 的毫秒 → 每条消息都是新会话。
      证据：`SessionMemoryTest#sessionIdFollowsIdentityAndPermissions`（`isEqualTo(today)`）。
- [x] **空闲超过阈值后不相等**：`touched_at` 距今超过 `idle-minutes`，段必须换。
      反例：只在 `day_started` 变化时换段 → 这条红。
      证据：同一条用例的 `isNotEqualTo(today)`；真库上另一条在
      `RunSchemaSqlTest#budgetColumnsAccumulateAndThePointerKeepsOneRow`。
- [x] **跨过 `at-hour` 不相等**：`day_started` 不是今天（按 `claw4j.agent.zone`）时必须换。
      反例：用 UTC 的日期 → 东八区凌晨 0~8 点的行为错，而测试在 CI（UTC）上是绿的。
      证据：同一条用例用东八区 03:59 与 04:00（相差一分钟，idle 不可能触发）断言不相等。
- [x] **`touched_at` 每次都刷新**：不换段的那次调用之后，`touched_at` 必须前进。
      反例：轮换用 `do update … where 过期` → 这条红（而且是"120 分钟后每句话都换段"那种红）。
      证据：`RunSchemaSqlTest#budgetColumnsAccumulateAndThePointerKeepsOneRow` 的 `isAfter(tenAm)`。
- [x] **轮换是一条语句**：用一个计数型 `JdbcTemplate` 替身断言一次调用只发一条 SQL。
      反例：写成"先 select 再 insert/update" → 计数 2 或 3，红。
      证据：`SessionMemoryTest#sessionIdFollowsIdentityAndPermissions` 里
      `verify(jdbc, times(1)).queryForObject(...)` + `verifyNoMoreInteractions(jdbc)`。
- [x] **指针挂了不拦话**：store 抛异常时 `sessionIdOf` 仍返回一个可用的老形状 id，
      并计 `claw4j.session.pointer.failures`。反例：直接把异常抛出去 → 整轮 500。
      证据：同一条用例用 `SimpleMeterRegistry` 断言 `isEmpty()` 与计数为 1.0。
- [x] **召回的身份校验没被段破坏**：带段的 id 仍以 `PREFIX + owner + ":"` 开头，
      `belongsTo` 通过；换成别人的 owner 仍拒绝。反例：段拼在 owner 前面 → 校验失效。
      证据：`ToolGovernanceTest` 的 `CURRENT_SESSION` 改成带段，整个召回那一组 20 条仍全绿；
      `anotherUserSeesNothing` / `downgradedUserLosesItsOwnHistory` 继续挡越权。
- [x] **审批续跑不重新起名**：`runs.sessionOf(runId)` 有值时，续跑用的就是那个带段的 id；
      只有读不到才落到无段的老形状。反例：续跑重新走一遍指针 → 审批结果落进另一段对话。
      证据：`ChatClientAgentPort#resume` 未改（`sessionOf(runId)` 优先、`sessionIdOf(owner, null, null)`
      兜底）；**这一条是读代码确认的，没有反例**——续跑那条路没有可注入的入站消息，
      要造它得先给 `RunResumer` 一条测试入口，不在本票范围。记在这里，不当成已验证。
- [x] **`claw4j.run.budget.idle-minutes` 不存在了**：配置里搜不到这个键，
      `RunBudgetProperties` 也没有对应字段。反例：留着它 → 两处定义，下次改一处。
      证据：`grep -rn "run\.budget\.idle\|idle-minutes"` 在 `src/main` 里零命中
      （只剩 `SessionResetProperties` 与注释里的"曾经存在"）；`RunBudgetProperties` 三个组件。
      与上一条同样，**这是搜出来的，不是测出来的**。
- [x] `./scripts/verify.sh` 全绿，跳过 0；`claw4j-agent` 仍 10 文件 / 每文件 ≤20 方法，
      行覆盖率不低于 60%。证据：672 条、失败 0、跳过 0，agent 66.3%（10 文件 / 20 方法）。

## 实现记录

### 落在哪儿

| 东西 | 位置 |
| --- | --- |
| 表 | `V17__conversation_pointer.sql`（`claw4j_session.conversation_pointer`） |
| 一条语句 | `JdbcConversationPointerStore`：两条 SQL（带日历 / 只看空闲），`on conflict … case … returning` |
| 判定与降级 | `ConversationPointers`：算 `freshSegment` / `idleBefore` / 逻辑日，吞异常 + 计数 + 一次 WARN |
| 配置 | `SessionResetProperties`（`claw4j.session.reset.*`） |
| 拼 id | `SessionKeys#sessionIdOf(owner, channel, chatId, segment)`，空段 = 老形状 |
| 调用点 | `ChatClientAgentPort#sessionIdOf(message, owner)`，`reply` 与 `stream` 共用 |

### 与票面的四处偏差

1. **指针用主连接池 + 全限定表名**，不是会话那个池。会话池不注册成 bean（`SessionMemoryConfiguration`
   的注释写了原因），而容器里出现第二个 `DataSource` 会安静地打掉一整套 JDBC 自动配置。
   这里的 SQL 全在我们手里，不需要靠 `search_path`。
2. **`atHour` 是 `Integer` 而不是 `int`**。`int` 在配置缺失时拿到 0，而 0 是合法值（每天零点换段），
   于是"没配"与"配了 0 点"无法区分，默认值 4 永远用不上。
3. **`RunBudget` 构造多一个 `idleMillis`**（三个调用点跟着改）。它没有直接注入
   `SessionResetProperties`：`agent.run` 不该为了一个数去依赖 `agent.session`。
4. **判据 7/8/9 的反例只做了 7 的一半**：7 用"把 `CURRENT_SESSION` 改成带段"验了正向，
   8 与 9 是读代码 + 搜索确认。三条都写在上面，没有当成"跑过反例"。

### 六条反例，跑完复原

| # | 改坏什么 | 红了哪条 |
| --- | --- | --- |
| 1 | `segmentOf` 直接返回新段（不查库） | `SessionMemoryTest` …`[同一天连着说两句就换段…]` |
| 2 | SQL 的日历分支去掉 `touched_at < ?` | `RunSchemaSqlTest` …`[空过阈值必须换段]` |
| 3 | `logicalDayOf` 用 `ZoneOffset.UTC` | `SessionMemoryTest` …`[04:00 是逻辑日的分界点…]` |
| 4 | SQL 的 `touched_at = excluded.touched_at` 改成读旧值 | `RunSchemaSqlTest` …`[不换段的那一次也要刷新 touched_at…]` |
| 5 | `segmentOf` 里先 `queryForList` 再写 | `SessionMemoryTest` 的 `verifyNoMoreInteractions` |
| 6 | 降级分支改成 `throw unreadable` | `SessionMemoryTest` 直接报 `IllegalStateException: 表还没迁` |

反例 2 第一次就失败了一次：断言写的是"从最初那个时刻起 121 分钟"，而第二次访问已经把
`touched_at` 推到了第 61 秒，于是 `touched_at < idleBefore` 落在等号上、不换段。
判定是**严格小于**，"超过阈值"本来就该这样——错的是断言，不是实现。改成"从上次说话再空 121 分钟"。

### 一个没关掉的东西

票面第 107 行那个窗口还在：两条消息同时跨过 4:00 时，各自 `case` 出新段，后写的覆盖先写的。
01 只做到"语句级原子"，做不到"整次访问原子"——那要 02 的 lane。**01 与 02 连着发版**。


## 收票时要补的三件事

1. `docs/design/session-memory.md` 的会话键一节：形状加一段，并写明"换段 = 换对话"。
2. `SessionKeys` 的类注释：现在写的是"两把键，管两件事"，要加上第三件事——段。
3. `RecallSessionTool:342` 的注释里那句形状描述（`<通道>:<chatId>`）要补段。

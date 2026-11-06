# 04 · 会话清理照 `session.maintenance`；`/new` 手动结束

**Status:** resolved（2026-11-06）
**Blocked by:** 01、02（已落地）；会改掉 01 的一处实现，见落点 5

## 这一版改了什么

上一版的结论是"TTL 一行代码都不改，只把 `mode: none` 的冲突解释一下"。那是在给一个错误的判据打补丁。
这一版按 OpenClaw 的 `session.maintenance` 重做，五个决定：

| # | 决定 | 依据 |
| --- | --- | --- |
| 1 | 删除判据从"建了多久"改成**"多久没动"** | `pruneAfter` 判 `lastInteractionAt` |
| 2 | 默认 `mode: warn`（只算不删），`enforce` 才真删 | 它的默认档位 |
| 3 | `maxEntries: 500`，超了驱逐最久没动的 | 同上 |
| 4 | cron 的段单独按 24h 留 | `cron.sessionRetention` |
| 5 | 不做 `sessions cleanup` 那种手动入口，只有每小时一次 | 已定 |
| 6 | `/new` 是**消息命令**（`/new` / `/reset`），换段 + 剩余内容透传 | 它的 reset 就是一条消息 |

第 1 条是这个票的全部重量。判据错的时候，段只是**盖住**了它（每段活不过一天），没有修它；
而 `idle-minutes: 43200` 或 `mode: none` 这种合法配置一上，误删立刻回来。

## 照 OpenClaw 的对照

| OpenClaw | 它是什么 | Claw4J 对应物 |
| --- | --- | --- |
| `session.maintenance.mode` | `warn`（默认）/ `enforce` | 同名同义 |
| `session.maintenance.pruneAfter: 30d` | 陈旧条目的年龄上限，判 `lastInteractionAt` | `conversation_pointer.touched_at`（01 已经在每次访问时刷新） |
| `session.maintenance.maxEntries: 500` | `sessions.json` 条目上限，超了驱逐最旧的 | 指针表行数上限 |
| `session.maintenance.resetArchiveRetention` | `*.reset.` 归档 transcript 的留存，默认同 `pruneAfter` | 旧段（不再是当前段的那一些）的留存 |
| `cron.sessionRetention: 24h` | 隔离 cron 会话的留存 | cron 的段（03 之后它跑在自己的会话里） |
| `sessions cleanup --dry-run/--enforce` | 手动入口 | **不做**（已定，只有每小时自动跑一次） |
| `maxDiskBytes` / `highWaterBytes` | 按磁盘占用裁剪 | **不做**：我们是 Postgres，按行数与时间就够；磁盘归 DBA |

## 落点

### 1. 配置形状：两个新键，一处删键

```yaml
claw4j:
  session:
    maintenance:
      # warn：只算不删，把"将要删多少"打进日志与指标（默认，与 OpenClaw 一致）
      # enforce：真删
      mode: warn
      # 多久没动算陈旧（OpenClaw 的 pruneAfter，默认 30d）
      prune-after: 30d
      # 指针行数上限（OpenClaw 的 maxEntries，默认 500）
      max-entries: 500
  schedule:
    # cron 那一路的留存（OpenClaw 的 cron.sessionRetention，默认 24h）
    session-retention: 24h
```

新建 `SessionMaintenanceProperties`（`@ConfigurationProperties("claw4j.session.maintenance")`），
**不塞进 `SessionMemoryProperties`**：那个 record 已经 6 个字段，再加三个会让它的 `defaults()`
与七处测试构造点全部连锁；两者也不是一件事（一个是"会话怎么存"，一个是"旧数据怎么清"）。

`spring.ai.session.time-to-live: 30d`（`application.yml:43`）**删掉**，理由在落点 4。

### 2. 四条清理语句

段级的判据是"这一段的最后一次动静"，不是"建行时刻"。桶级仍然看 `touched_at`。

```text
A 段级  删 ai_session 里 last_activity < now - prune-after 的段
        last_activity = coalesce(max(ai_session_event."timestamp"), created_at)
B 桶级  删 conversation_pointer 里 touched_at < now - prune-after 的行
C 上限  指针行超过 max-entries 时，按 touched_at 升序驱逐最旧的
D cron  删 ai_session 里 id like '%:cron:%' 且 last_activity < now - session-retention 的段
```

四条各自对应上表的一行，没有第五条。三个要说清的地方：

- **A 不需要额外的"保护当前段"子查询**：活跃桶的当前段要么已经有事件（`max` 是刚刚），
  要么刚建、事件还没落库（`created_at` 是刚刚）——两条都让它脱离候选。这个"要么"就是
  `coalesce` 的全部作用。
- **A 不能用相关子查询**：`(select max(...) where session_id = s.id)` 是每行跑一次，
  每小时全表扫差一个数量级。用 `left join` 到按 `session_id` 分组的子查询，或者 `lateral`。
  另外给 `ai_session(created_at)` 补一个索引（V19，只加这一条），让"先按建行时间粗筛"能走索引。
- **C 只删指针行，不删段**：被驱逐的桶段往往还很新，删它们要能定位"这个桶的所有段"，
  而那需要给 `ai_session` 加一列记对话地址——为一句 SQL 加一列不划算。它们的段留给 A
  在 `prune-after` 之后删。**多留几十天不违反留存**（留存是上限，不是下限），代价只是空间。

### 3. `warn` 是默认档位，所以"报告"必须真的看得见

`warn` 下不删，那它唯一的产出就是"本来会删什么"。OpenClaw 把这份清单打在 CLI 上；
我们是常驻服务，没有 CLI，所以它只能落两个地方：

- **日志**：一条 INFO，四个数（段 / 桶 / 超限驱逐 / cron），一行说完，每小时一条。
- **指标**：`claw4j.session.maintenance.prunable{kind=segments|conversations|over-limit|cron}`
  与 `…pruned{kind=…}` 两组计数。

这不是装饰：`SessionCleanup` 的类注释已经写过"清理任务最典型的失败姿势是静默空转——
任务在跑、没报错、一条没删"。默认档位改成 `warn` 之后，**这个姿势成了默认行为**，
没有这两个数就没人分得清"没有过期的""该删但没删""根本没执行"。

### 4. `time-to-live` 删掉：一个判据，一个来源

框架的 `expires_at` 是"建行 + TTL"，不随活动刷新，与 `prune-after` 是同一件事的两种算法。
两个都留着，下一个改配置的人只会改一处。

删键之后新建的行 `expires_at` 为 null，框架的 `deleteExpiredSessions` 也就删不到任何东西——
它不再被调用（`SessionCleanup` 换依赖，见落点 6），但接口实现保留，那是框架契约。

**历史行怎么办**：`expires_at` 非空的就是改造前建的行，它们的 `created_at + 30d` 与
`expires_at` 是同一个时刻，所以语句 A 会照常把它们清掉，不需要单独一条。

### 5. `mode: none` 不用改（上一版想错了）

上一版说"`none` 不查库 → 没有指针行 → 会被当成旧段删掉"，所以要给它加一条只刷新
`touched_at` 的语句。**推一遍就知道不需要**：段级判据用的是
`coalesce(max(事件时间), created_at)`，不是指针。`none` 下每轮对话照样写两条事件，
所以它一直是"活着"的；指针只服务"要不要换段"，`none` 不换段，也就不需要指针。
`maxEntries` 数不到它（没有指针行），但它照样按 30 天清——两边口径不冲突。

这一条恰好说明判据选对了：OpenClaw 的 `lastInteractionAt` 也是落在 transcript 上，
所以"没有 store 条目"从来不等于"没法判活"。**少一个 `TOUCH_ONLY` 语句，少一处要维护的语义。**

### 6. `SessionCleanup` 换依赖，降级语义要自己接

现在它拿 `SessionService`，降级由 `FaultTolerantSessionService` 兜（库挂了 WARN 一次、返回 0）。
改成直接写 SQL 之后那层保护没有了，**必须自己在 `sweep` 里接住 `DataAccessException`**：
WARN 一次、返回空报告、不把后台任务变成异常日志刷屏，也不让 `@Scheduled` 的线程死掉。

`SessionCleanup` 只留调度与计数；四条语句、`warn`/`enforce` 分支、日志汇总放进
`SessionMaintenance`（同包）。**`mode` 的判断只放一处**，不要散在四条语句旁边。

### 7. `/new`：一条消息命令，不是请求头

OpenClaw 的原文（`concepts/session`）：`/new` 与 `/reset` **都是一条消息**，
"start a fresh session id and **pass the remainder of the message through**"；
单独发 `/new` 时它跑一个短 hello 轮次确认重置；`/new <model>` 还能顺手指定这一段用哪个模型。

照它做，形态从我上一版写的"HTTP 头"改成"消息前缀"。这一改**反而更省**：

- `InboundMessage` 不加字段、`HttpChatController` 不加参数，那 20 处测试调用点一处不用动；
- 命令解析在 **agent 侧**，与它把 reset 处理放在 auto-reply 那一层同位置。于是**所有通道自动都有**：
  飞书、企微、Slack 里发一条 `/new` 就生效，不用给每个通道注册斜杠命令。

| 用户发的 | 效果 |
| --- | --- |
| `/new` | 换段，回一句固定确认语；不开 run、不调模型、不写事件 |
| `/new 帮我查华南回款` | 换段，"帮我查华南回款"就是新段的第一句 |
| `/reset` 或 `/reset ...` | 同上（别名） |

三个与它的差异，按 AGENTS.md 记账：

1. **确认语是固定文案，不是一次 hello 轮次。** 它跑一个模型调用来确认重置；那一轮除了
   "好，新的开始了"没有别的信息，不值一次调用。重开条件：有人要求确认语要接上一段的结尾。
2. **不做 `/new <model>`。** 我们的模型是部署配置（`claw4j.model.*`）加主备切换，
   按会话换模型会绕开成本口径与主备语义。重开条件：真有人要"这一段用便宜模型"。
3. **没有 `resetTriggers` 自定义。** 两个命令够用，多一个配置项就多一处要解释。

**群里也能用，而且没有歧义**：段是按人分的（`owner` 在键的前面，ADR-0025），
所以群里 A 发 `/new` 只换 A 自己那一段，B 的上下文不动。上一版写"群里发 `/new` 到底给谁换"
是错的——那个歧义在 OpenClaw 按房间分段时才有，我们按人。（顺带：IM 侧不再需要单独做，
命令解析在 agent 侧，通道无感。）

```text
ChatClientAgentPort   新增 SessionCommand.parse(text) → Parsed(reset, rest)
                      answer / streamInLane：reset ? pointers.rotate(...) : segmentOf(...)
                      送模型的文本用 parsed.rest()，不是 message.text()
ConversationPointers  加 rotate(owner, channel, chatId)；segmentOf 签名不变
ConversationPointerStore.Rotation  加 boolean forced；两条 SQL 各加一个 when ? or …
```

三个选择沿用上一版（理由不再重复）：`segmentOf` 不变另开 `rotate`（测试里六处调用点不动）；
`forced` 是字段而不是把 `idleBefore` 拨到 `now+1ms`（后者让参数名在撒谎）；
`Rotation` 现在有两个相邻 boolean，所以 `RunSchemaSqlTest` 两个方向都要断。

**解析是纯函数，换段在锁里**：`parse` 不碰库，可以在 `inLane` 之前做；`rotate` 在
`answer` / `streamInLane` 里调，天然在门内。`/new` 与 `runId` 同时出现（`/new 继续`）时
段照换、run 照接，`openRun` 会把新段写回 run，预算从 0 开始。

## 怎么测

三个模块的测试文件都顶格 10 个，`SessionMemoryTest` 与 `RunSchemaSqlTest` 各 20 个方法也顶格。

| 文件 | 动作 | 验什么 |
| --- | --- | --- |
| `RunLifecycleTest`（13 → 15） | +2 方法 | 四条语句在真 Postgres 上的行为；`warn` 一行不删而计数不为 0 |
| `SessionMemoryTest#expiredSessionsAreDeleted` | **重写** | 它现在断的是"`SessionCleanup` 调了 `deleteExpiredSessions`"，实现换掉后必须改成断新语义 |
| `SessionMemoryTest#sessionIdFollowsIdentityAndPermissions` | 追加 | `SessionCommand.parse` 的三态（含 `/reset` 别名、`/new 后面带内容`） |
| `SessionMemoryTest#memoryFollowsTheConversationNotJustThePrincipal` | 追加 | `/new` 那一轮整轮落在新段：新段读不到旧段，且这一轮的话写进新段 |
| `RunSchemaSqlTest#budgetColumnsAccumulateAndThePointerKeepsOneRow` | 追加 + helper 补参数 | `forced` 两个方向；`dailyGate` 与 `forced` 不互串 |

`RunLifecycleTest` 有真容器、真 Flyway、只清 `agent_*` 三张表；清理要管的另外三张
（`ai_session`、`ai_session_event`、`conversation_pointer`）要加进它的 `freshTables`。
（`theRetentionWindowDeletesWholeRunsAndStaleApprovals` 在 `RunSchemaSqlTest` 里，不在这个文件——
上一版写错了位置。）

**为什么不用"跑 30 天"来验**：`ai_session.created_at` 由框架用系统时钟写，指针的 Clock 可注入，
两者不同源。所以四条语句都用"把 cutoff 推到未来"来验（`sweep(now + 31d)`），
不推进真实时间、不 `sleep`。

## 不做什么

- **不做手动入口**（`sessions cleanup --dry-run/--enforce`）：已定，只有每小时一次。
- **不做 `maxDiskBytes` / `highWaterBytes`**：那是文件形态的磁盘预算，我们是 Postgres；
  按行数与时间已经够，磁盘归 DBA。这是与 OpenClaw 的一处形态差异，已记账。
- **不归档到另一张表**：`04-session-ttl-policy` 的"不要做"仍然成立，留存期不能没有终点。
- **不给 `ai_session` 加"对话地址"列**：为一句驱逐 SQL 加一列不划算，见落点 2。
- **不做 IM 侧的专属命令注册**（斜杠命令要各通道登记）：命令在 agent 侧解析，
  所以 IM 里发 `/new` 本来就生效，不需要额外做。
- **不做会话列表 / 切回旧段**、**不做跨段召回**、**不做 `/stop` / `/compact`**。
- **不动 `V16__session_budget.sql:29` 的注释**（里面还写着 `claw4j.run.budget.idle-minutes`）：
  那是已发布迁移，改注释会让 Flyway 校验和变化、已迁移的库启动即失败。下一个人"顺手改过期键名"
  之前得知道这件事——所以这条要写进代码注释。

## 验收判据

- [x] **桶过期，段一起清**：一个对话地址、最后动作在 31 天前 → `sweep(now)` 之后指针行没了、
      该段的 `ai_session` 行没了、事件随级联没了。
      反例：判据改成按 `created_at`（不看事件）→ `B-now` 被误删，红（`prunedSegments` 3≠2）。
      证据：`RunLifecycleTest#staleSessionsAreJudgedByLastActivity`。
- [x] **活跃桶的旧段照删，当前段留着**：同一地址，旧段最后事件在 40 天前、当前段今天刚写 →
      `sweep(now)` 之后旧段没了、当前段在、指针行在。
      证据：同一条用例（`B-old` 走、`B-now` 留）。
- [x] **`mode: none` 的形状也判得活**：id 没有段、`created_at` 在 40 天前、刚说过话 → 留着。
      它连指针行都没有，靠事件时间活下来。
      反例同上（按 `created_at` 判 → 红）。
      证据：同一条用例里的 `c-legacy`。
- [x] **`warn` 一行不删，但数得出来**：同样的数据，`mode: warn` × 一次 `sweep` →
      表里的行一行不减，`prunable{kind=segments}` = 1、`pruned` 这个 counter 根本没被注册。
      反例：把 `if (!properties.enforces())` 改成 `if (false)` → `enforced` 变 true，红。
      证据：`RunLifecycleTest#warnModeDeletesNothingButCounts`。
- [x] **`maxEntries` 驱逐最久没动的**：三个桶留两个，走的是 `touched_at` 最旧那个。
      反例：把 `order by touched_at desc` 改成 `order by segment desc` → 驱逐了最新那个，红。
      证据：`staleSessionsAreJudgedByLastActivity` 第二段（segment 与时间故意反着排）。
- [x] **cron 按 24h 单独留**：25 小时前的 cron 段被删，普通对话 40 天前的段也走——两条口径互不吃。
      证据：同一条用例（`prunedCron` = 1，段级只数人的对话）。
- [x] **`/new` 之后段立刻换，下一句不换**：同一 key、同一秒，`forced=true` 的段 ≠ 上一段；
      紧接着 `forced=false` 的段 == 上一步那个。
      反例一：`forced` 那个 `?` 绑成 `false` → 真库那条红。
      反例二：把 `forced` 写进指针行 → 第二条红。
      证据：`RunSchemaSqlTest#budgetColumnsAccumulateAndThePointerKeepsOneRow` 末段。
- [x] **`forced` 与 `dailyGate` 不互串**：`dailyGate=false, forced=false` 沿用；
      `dailyGate=false, forced=true` 必换（走的是另一条语句 `IDLE_ONLY`）。
      反例：构造 `Rotation` 时两个 boolean 写反 → 两个方向各红一条。
      证据：同上。
- [x] **命令三态**：`/new`、`/NEW`、`/reset 帮我查华南回款`、`/newer 怎么说`、`/etc 的权限`。
      反例：把整词匹配改成 `startsWith("/new")` → `/newer 怎么说` 被当成清空上下文，红。
      证据：`SessionMemoryTest#sessionIdFollowsIdentityAndPermissions` 末段。
- [x] **`/new` 整轮落在新段**：同一 `chatId` 先说话 → `/new` → 再说话，
      `createdIds` 从 1 变 3（两段对话 + 新段），且新段的提示词里没有上一段那句。
      反例：把 `sessionIdOf` 里的 `reset ? rotate : segmentOf` 三元去掉 → `createdIds` 2≠3，红。
      证据：`SessionMemoryTest#memoryFollowsTheConversationNotJustThePrincipal` 末段。
- [ ] **`/new` + `runId` 同时出现**：seq 接着涨、run 上的 `session_id` 是新段、预算从 0 开始。
      **没做**：要走通它得在 `RunLifecycleTest` 里装一套真 `ChatClient` + 真 run store +
      带指针的 port（现在那个文件里的 port 都是手搓的假模型），成本比它守的风险高一个量级。
      这条路径的代码是一行 `runs.attachSession(opened.run().id(), sessionId)`，已经存在，
      本票没有改它；本票只改了 `sessionId` 的来源。**留给 A12 之前补，或者第一次有人报
      "续跑之后额度没重置"时补。**
- [x] **库挂了不炸后台任务**：`sweep` 撞上连不上的库 → `Report.NONE`、只 WARN 第一次、计数 2。
      反例：去掉 `catch (DataAccessException)` → 红。
      证据：`SessionMemoryTest#cleanupSurvivesAnUnavailableDatabase`。
- [x] `./scripts/verify.sh` 全绿，跳过 0，覆盖率不低于 60%。
      证据：675 条、失败 0、跳过 0，agent 65.6% / app 80.8%，10 文件 / 20 方法。

## 实现记录

### 落在哪儿

| 东西 | 位置 |
| --- | --- |
| 配置 | `SessionMaintenanceProperties`（`claw4j.session.maintenance.*`）+ `ScheduleProperties#sessionRetention` |
| 四条语句 | `SessionMaintenance`：段级 / 桶级 / 上限 / cron，各一对「候选 SQL + delete」 |
| `warn` 的可见性 | 4 个 `prunable{kind}` gauge + 1 个 `pruned{kind}` counter + 每小时一行 INFO |
| 调度 | `SessionCleanup.sweep()` 只留 `@Scheduled` 与一次委托 |
| 命令 | `SessionCommand.parse`（整词、大小写不敏感、剩余内容透传） |
| 换段 | `ConversationPointers#rotate` → 私有 `segment(..., forced)`；`Rotation` 加 `forced` |
| 入口 | `ChatClientAgentPort` 的 `answer` / `streamInLane` 各解析一次；单独 `/new` 走 `startOver` |

### 五处要说明的

1. **`mode: none` 没有改**（票面落点 5 已改成"不用改"）：段级判据用的是事件时间，
   `none` 下每轮都有事件，所以它一直是活的。**少了一条 `TOUCH_ONLY` 语句。**
   这一点由 `c-legacy` 那条断言守——它连指针行都没有。
2. **`/new` 的换段只有一处实现**。第一版在 `startOver` 里直接调 `pointers.rotate`，
   与 `sessionIdOf(..., reset)` 各写了一遍；跑反例时发现"把 reset 传 false，`/new` 照样换段"——
   测试绿着，因为那条路走的是 `startOver`。改成 `startOver` 也只调 `sessionIdOf(..., true)`
   之后反例才红。**"看着像同一个行为"与"走的是同一段代码"是两件事。**
3. **`cast(? as boolean)` 不能省**。`case when ? or ...` 里 PG 推不出参数类型，
   报 `could not determine data type of parameter`；`cast` 比 `::boolean` 好在不依赖驱动拼串。
4. **段级与 cron 两条语句互斥**：段级加了 `where a.id not like '%:cron:%'`。
   不加的话同一个段会被两条语句各数一遍，报告里"这次删了几条"就答不清楚。
5. **`/new` 与 `runId` 那条判据没做**（见上）。

### 五个反例，各红一条

| # | 改坏什么 | 红了哪条 |
| --- | --- | --- |
| 1 | 段级判据去掉 `coalesce(事件时间, created_at)`，只按 `created_at` | `staleSessionsAreJudgedByLastActivity`（3≠2） |
| 2 | `maxEntries` 的 `order by touched_at` 换成 `order by segment` | 同一条（驱逐了最新那个） |
| 3 | `warn` 分支改成 `if (false)`（等于谁都删） | `warnModeDeletesNothingButCounts` |
| 4 | `SessionCommand` 用 `startsWith("/new")` 代替整词 | `sessionIdFollowsIdentityAndPermissions`（`/newer` 变成 reset） |
| 5 | `sessionIdOf` 里的 `reset ? rotate : segmentOf` 去掉三元 | `memoryFollowsTheConversationNotJustThePrincipal`（2≠3） |

## 收票时要补

| 文件 | 补什么 |
| --- | --- |
| `docs/design/session-memory.md` | 换段三条途径（跨天 / 空闲 / `/new`）；**过期判据是"多久没动"，不是"建了多久"**；默认 `warn` 不删 |
| `application.yml` 的 `reset:` 段 | 一句"第三条途径是消息里的 `/new` / `/reset`，不走配置" |
| `README.md` 的部署说明 | **默认 `mode: warn` 不删任何东西**，要落实留存承诺必须显式设 `enforce`；`max-entries: 500` 是单用户形态的数，多租户按"人数 × 通道数"调 |
| `.scratch/m3-observability-and-memory/issues/04-session-ttl-policy.md` | "修正记录"：判据从"建行 + TTL"换成"多久没动"；默认档位变 warn |
| `docs/adr/0026` | 落地顺序第 5 步标"已落地（04）"；第 123 行"阈值可以从 30 天降到 7 天"补一句"判据也已换" |
| `docs/agents/guardrails.md` | 记一条已知偏差：默认 `warn` 下"承诺了删除但不删"，靠部署侧设 `enforce` |

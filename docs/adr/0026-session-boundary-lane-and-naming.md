# 会话的边界、并发与命名

---
status: accepted
date: 2026-11-05
---

这份 ADR 不新增能力。它把**一个已经被四个组件各自定义过的词**钉死：会话。

## Context

`SessionKeys` 里那条注释写着"两把键，管两件事"。现在的事实是：**"会话"这件事有四个定义，四个使用者**。

| 定义 | 出处 | 谁在用 | 什么时候结束 |
| --- | --- | --- | --- |
| 一段对话的地址 | `SessionKeys.sessionIdOf`（owner + 通道 + chatId） | 记忆、压缩、召回、预算 | **永不结束** |
| 一份额度的窗口 | `claw4j.run.budget.idle-minutes: 120`（`application.yml:253`）**——ADR-0027 已删**：额度移到目标上，会话不再有窗口 | `JdbcRunStore#spentOfSession`（已删） | ~~120 分钟没动静就清零~~ |
| 一行存储记录 | `spring.ai.session.time-to-live: 30d`（`application.yml:43`） | `SessionCleanup` 每小时删 | 到期行被删（到期时间写在行上，**不随活动刷新**：仓储层没有任何 UPDATE 触及 `expires_at`） |
| 定时任务的上下文 | `ScheduleScanner:176` 拿 `task.channelRef()` 当 chatId | 定时任务 | 跟着创建者的那段走 |

四条没有一条错，但它们**回答的不是同一个问题**。表现出来就是：

- 两小时后再问，预算说"这是新的"，记忆说"这还是旧的"；
- 每天早上打开同一个聊天，机器人记得上周的事，却在第 30 天突然全忘（删行，不是换对话）；
- 用户没有任何"开一段新对话"的手段：web 端的 `chatId` 缺省等于 userId（`HttpPrincipalResolver`），所以一个人一辈子只有一段会话；
- 定时任务顺手借了创建者的那一段（见第五节），于是 cron 的问答出现在用户的聊天记录里。

这与 ADR-0024 处理过的形状是同一个：run 也曾有四个说法（一句话、一轮、一件事、一个任务），钉成"一轮"之后才收敛。**会话还没有这一钉。**

依据是 OpenClaw 的会话模型（本章三处决定都能在它的文档里找到原句）：

- `dmScope` 的推荐档 `per-channel-peer`（已采纳，见 `SessionKeys`）；
- Lifecycle：**每日 4:00 重置**（默认，`reset.atHour`）与 **`idleMinutes`**（可选，示例 120），"whichever expires first"，且"expiry is evaluated on the next inbound message"；
- 重置的形态是**换一个新 sessionId**，不是删除历史；
- Cron："Isolated cron jobs always mint a fresh sessionId per run"；
- Queue："only one active run per session"、"Default: 1"。

## Decision

### 一、一段对话的结束：每日 或 空闲，谁先到算谁；结束 = 换新段

判定发生在**这一条入站消息到达时**，不靠定时器（与 OpenClaw 同）。两种结束：

- **每日**：过了 `claw4j.session.reset.at-hour`（默认 4，时区取 `claw4j.agent.zone`）就算上一段结束；
- **空闲**：距上一次活动超过 `claw4j.session.reset.idle-minutes`（默认 120）就算上一段结束。

结束不等于删除。「结束」是**给这段对话换一个段 id**，旧段留在库里只读。这样"结束"与"忘记"才分得开：结束是开始新的一段，忘记是把数据删了。

`claw4j.session.reset.mode: daily-and-idle | idle | none`，默认 `daily-and-idle`。第三档是给"一段对话跟一个项目走"的部署留的，不是默认。

### 二、一段对话同时只有一个 run

入站消息要拿一把**按对话地址**（owner + 通道 + chatId）的 lane，拿不到就排队，不拒。

**本 ADR 不做全局并发上限。** 这一处刻意与 OpenClaw 不同：它的 `maxConcurrent` 默认 1，前提是"个人助手、单用户"；我们是多租户，默认 1 等于第二个用户排队等第一个人的模型调用跑完。容量已经由各通道的有界池（HTTP 流式 4~32、IM 各一个有界池）与预算管着，缺的那个上限属于 A12（系统并发 + per-principal 配额 + 排队可见性），不属于"一段对话一个 run"这条正确性保证。

两个细节必须写死，否则实现时一定写错：

- **lane 的键是"对话地址"，不是 sessionId。** 段会换；拿 sessionId 当锁键，跨过切换点就是两把不同的锁，串行化在最需要的那一刻失效。审批续跑那条路没有入站消息，所以 run 上要多记一列对话地址——否则它会和人正在打的那句话抢同一段会话。
- **排队而不是拒绝。** 用户只是打字快，拒绝会让他以为机器人坏了。队列上限与等待上限可配，超了回一句话（沿用 `InboundDispatcher.FAILURE_REPLY_PREFIX` 的文案口径）。

### 三、只有"正在跟人说话"的那条路能起名

一段对话的名字（段）由**入站消息**在 lane 里决定，且只有一处：

```text
人 → 通道 → chatId   →  pointer(owner, channel, chatId) → segment
cron:<taskId>        →  每次运行一个新段（不落指针）
subagent:<name>      →  不进指针（子代理本来就没有多轮）
审批续跑              →  沿用 run 上记的 session_id（V16 那一列），不重新起名
```

定时任务改用 `cron:<taskId>` 并且每次运行新段，是因为它"没有人正在说话"：它既不该继承用户的上下文，也不该把自己写进用户的聊天记录。这与 OpenClaw 的 isolated cron 是同一条规则。

## 形状

一张指针表，一个段 id 的拼法。指针表就是 OpenClaw `sessions.json` 里那张 `sessionKey -> {sessionId, updatedAt}` 的等价物：

```sql
create table claw4j_session.conversation_pointer (
    owner       text        not null,
    channel     text        not null,
    chat_id     text        not null,
    segment     text        not null,   -- 当前段，拼进 sessionId
    touched_at  timestamptz not null,   -- 空闲判定的依据
    day_started date        not null,   -- 每日判定的依据（按 claw4j.agent.zone）
    primary key (owner, channel, chat_id)
);
```

```text
sessionId = claw4j:user:<owner>:<channel>:<chatId>:<segment>
segment   = <epochMilli>-<4 位随机>      // 可排序，便于清理与排查
```

轮换本身就是一条 SQL，不用"先读再判再写"（并发两条消息同时撞上 4:00 时，只有一条能轮换成功）：

```sql
insert into claw4j_session.conversation_pointer (...) values (...)
on conflict (owner, channel, chat_id) do update
   set segment = excluded.segment, touched_at = excluded.touched_at, day_started = excluded.day_started
 where  conversation_pointer.day_started <> excluded.day_started          -- 跨天
    or  conversation_pointer.touched_at < excluded.touched_at - interval '<idle>'  -- 空闲
returning segment;
```

返回空 = 这一段还有效，沿用旧段；返回一行 = 刚刚换了新段。

## Considered Options

| 方案 | 为什么否决 |
| --- | --- |
| 只做每日重置（段 id 用日期算，不加表） | 空闲结束就没了。而"两小时没说话算结束"是预算已经承诺过的语义（ADR-0025），两条规则必须同一个来源 |
| 只把 TTL 从 30 天调短 | 调短只会让活跃对话更快被删。问题不是时长，是"到期"被实现成了"删除" |
| 不做段，只做 `/new` 手动结束 | 大多数用户不会敲命令。一段对话的结束不该依赖用户记得关 |
| 用数据库行锁做串行化 | 锁要跨越整个模型调用（几十秒），连接被占住；而"单实例"是本项目的既定形态（ADR-0009），进程内 lane 够用 |
| 忙时直接拒绝（返 503） | 用户只是连发两条。拒绝的代价比排队大得多；池满时现有的 503 已经覆盖了真正的过载 |
| 把定时任务的 `channelRef` 直接当段用 | 那正是现在的做法：它同时是"送到哪"和"哪一段话"。一个字段两个用途，就是这份 ADR 要消灭的东西 |

## Consequences

- **老会话不再被写入。** 落地之后，没有段后缀的那些 `ai_session` 行只读；用户会觉得"以前聊的它想不起来了"。这与 OpenClaw 换会话后不带旧上下文一致，但要写进发布说明，不能让它成为一次"怎么突然失忆"的排查。
- **`SessionCleanup` 的含义变了**：从"删活跃会话"变成"删旧段"。阈值可以从 30 天降到 7 天——活跃对话永远在新段里，旧段过了就没有人再写。

  注意"旧段只读"**不是**归档：它仍在同一张 `ai_session` 里，到期照删，只是不再被写入。`04-session-ttl-policy` 写的"不要做：归档到另一张表"依然成立，理由是数据留存期不能没有终点——这一点本 ADR 没有放宽。
- **ADR-0025 里"空闲重置额度"那句话被本 ADR 取代**：预算表本来就按 `session_id` 计，换段即归零，不需要再判一次空闲。`spentOfSession` 里那段 idle 判定降级为兜底（指针写失败时不让额度无限累计），正常路径不再经过它。
- **召回范围不变**：仍按当前段查。换段之后"上一段说过的话"召不回来——这与"换段 = 新对话"是一致的，但它是可见的功能损失，要认。
- **语义缓存不受影响**：它的键是权限指纹 + 实体，与段无关，所以新段仍可能命中旧答案。数据上是对的（同权限、同问题、同时间窗），成本上也是想要的；但它意味着"新开一段对话"并不保证回答是重新算的。
- **多一张表的代价**：每个活跃对话一行，跟着对话数增长。清理与 `ai_session` 同一条定时任务，不另起一个。

## 与既有 ADR 的关系

- **ADR-0024（run 的寿命是一轮）不变。** run 仍然是一轮一件事；本 ADR 管的是 run 外面的那层——一段对话活多久。
- **ADR-0025（预算挂会话）的结论不变，口径收紧。** 它说"会话什么时候结束"的答案是空闲超时；本 ADR 把"会话"从"一个 chatId 型字符串"改成"一个会轮换的段"，于是空闲不再是预算独有的判断，而是段的生命周期。
- **ADR-0019 第 97 行仍写着"预算按 run 累计"**，与本 ADR 及 0025 都冲突。那一句不在本 ADR 的修改范围内，但必须单独改掉——两份 ADR 对同一个数给出两种说法，后来的人只会信先读到的那份。

## 落地顺序与反例

按"错得最安静的先修"排。每一步都先写反例跑红，再改代码。

1. **指针表 + 段。** 反例：同一 chat 连续两条消息的 sessionId 相等；`idle-minutes` 之后的一条不相等；跨过 `at-hour` 的一条不相等。
2. **lane（键 = 对话地址，不是 sessionId）。** 反例：同一 chat 并发两条，第二条的提示词里必须含第一条的回答；跑在旧代码上必红。
3. **定时任务用 `cron:<taskId>`，每次运行新段。** 反例：同一 chat 建任务并触发，`count(distinct session_id)` 必须是 2，且定时那条不含用户 chatId。
4. **V16 的 idle 判定降级为兜底。** 反例：把指针写失败（存储替身抛异常），额度仍然按旧规则清零。
5. **`/new`。** 最小形态是 HTTP 一个头（`X-Claw4j-New-Session: true`）推进段；IM 侧的 `/new` 等真的有人要再做。反例：带头之后 sessionId 立刻换了一个。

前两步是同一批：没有 lane 就没有"段切换只发生一次"的保证。

## 未决

- **`identityLinks`（同一个人在多个通道共享一段对话）**：需要"这两个 id 是同一个人"的事实来源，不能猜。OpenClaw 把它做成显式配置，我们连配置项都没有。
- **群聊按"房间"还是按"人"分段**：现在按人（`owner` 在键的前面），代价是群里两个人各自失忆。OpenClaw 按房间分。这是安全取舍，不是缺陷，但两份文档要说法一致。
- **旧段能不能被工具召回**：OpenClaw 有 `sessions_history` / `sessions_search` 跨会话找。我们只有"当前这一段"。换段之后用户问"我们上次说的口径"，答案是"想不起来"。
- **`at-hour` 的时区**：取 `claw4j.agent.zone`（默认 `Asia/Shanghai`），不取容器时区。这一条与 `AgentContextAdvisor` 拼"现在几点"用的是同一个来源，不能两处各取一次。

## 修订记录

**2026-11-09（票 06）：指针并进 `session_entry`，跨天判定改成存时刻。**

上面那段 `conversation_pointer` 的 DDL 与 SQL 是当时的形状，保留作历史。现在：

- 表叫 `claw4j_session.session_entry`，主键是**不含段的会话键**；`segment` 是它的一列。
  这一行同时装着目标（`goal_*`），换段时同一条 `on conflict do update` 把目标列一起清空——
  "换段"与"清目标"不再可能只做一半。
- `day_started date` 换成 `session_started_at timestamptz`。旧版存的是"按当时的 at-hour 算好的
  逻辑日"，改 `at-hour` 或改时区之后旧行不会重算；现在边界在读时算
  （`SessionResetProperties#dailyResetAt`），改配置立刻生效。
- 新增 `created_at`（跨段不变）、`previous_segment`（= 它的 `previousSessionId`）、
  `total_tokens`（段级累计，换段清零；目标预算按 `total_tokens - goal_token_start` 算）。
- `touched_at` 改名 `last_interaction_at`，与它的 `lastInteractionAt` 同名同义。

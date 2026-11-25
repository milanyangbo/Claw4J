# 票 02：侧边栏读侧——列会话与逐轮历史

**Status:** resolved

## 由来

用户第 4 条：`SessionService` 拿得到历史记忆信息，所以侧边栏可以列会话、也能看每一轮。
这是票 01 的下游——身份简化之后，会话就是一个 `ai_session` 行，按 `user_id` 一查就是列表。

对照 OpenClaw：`sessions_list` / `sessions.list` / `openclaw sessions` 三个入口共用一份判定
（按 `updatedAt` 排序、`activeMinutes` 过滤、默认 100 条 + `hasMore`、列表不带正文）。本票只做
**最小的一版**：按人列、按 `createdAt` 倒序、翻页带 `nextCursor`；正文另开一个端点。

## 两个端点各管一层，不能合成一个

| 端点 | 用哪个框架方法 | 回答 |
| --- | --- | --- |
| `GET /api/sessions` | `findByUserId(userId)` | 这个用户有**哪几条会话**（一个会话一行） |
| `GET /api/sessions/{id}/messages` | `getEvents(sessionId, filter)` | **某一条会话**里每一轮说了什么（一条消息一行） |

两条不能互相替代：`findByUserId` 不知道"哪一条"（它只给有哪些），`getEvents` 需要一个
sessionId（列表的意义就是先把它拿到）。第三个数得清的 `findEventsByUserId` 是"跨这个人的
所有会话捞消息"，给召回用——拿它做列表要先分组、拿它做单会话历史是先捞全量再筛。
写清楚这一点的原因见 `docs/design/session-memory.md` 的「读侧」一节。

`getMessages(sessionId)` 只是 `getEvents(sessionId, EventFilter.all())` 的语法糖，会丢事件 id
与时间戳；本票的端点是列表，所以走 `getEvents`（要翻页与时间线）。

## 裁决

ADR-0029 修订（裁决三：会话身份交回框架那两张表）已经定下"会话从哪里读"。

无需 ADR：这是读侧，不改 schema、不改写路径，也不新增存储；形状照现有的 `/api/audit`
（`AuditQuery` 那条线），端点只是把框架已有的 `findByUserId` / `getEvents` 暴露出去。

## 改哪

1. `claw4j-core`：新增端口 `SessionQuery`——`list(String owner, int limit, String cursor)` 与
   `conversation(String owner, String sessionId, int limit)`；返回的记录带 id / createdAt /
   lastActivityAt / title / turns。
2. `claw4j-agent`：`JdbcSessionQuery` 实现。列表走 `SessionService.findByUserId`；每条会话再取
   一次 `lastN(1)` 拿最后一条事件的时间、用 `page(0)` + `pageSize(1)`（`messageTypes=USER`）拿标题，
   按最后活动倒序、上限 100 条；逐轮走 `getEvents(sessionId, lastN)`，先确认这个 session
   属于调用者，不属于就当作不存在。
3. `claw4j-channel`：`SessionController` → `GET /api/sessions`、`GET /api/sessions/{id}/messages`；
   身份走 `HttpPrincipalResolver`（`X-Claw4j-User`），与 `/api/chat` 同一条。
4. `HttpChannelAutoConfiguration` 注册这个 controller（票 18 的教训：**不注册就是 404**，
   而直接 `new` 控制器的用例会照旧全绿）。

## TDD 用例清单

用例写在 `ApplicationAssemblyTest` 里（app 模块，真端口 + 真 HTTP + 真库），形状同票 18 的
`/api/audit` 那两条。**不新开文件**：app 模块已经贴着 14 个测试文件的上限，而这四条要证的
恰好就是"端点挂在真端口上、读的是真库"，与那个文件里已有的审计端点用例同一件事——
新开一个文件只为装四条同形状的用例，挤掉的是别处一个能写用例的位置。

| 判什么行为 | 用例名 | 先红的样子 |
| --- | --- | --- |
| 列表中只有调用者自己的会话，且最近聊过的在前 | `ApplicationAssemblyTest#listsOnlyTheCallersSessions` | 端点不存在时 404；只补注册不过滤时别人的那条也回来 |
| 标题取自首条用户消息（截断 40 字） | `ApplicationAssemblyTest#theTitleComesFromTheFirstUserMessage` | 标题为空或整段正文，断言尾巴那半句不在时红 |
| 逐轮按时间正序，且不混入别的会话 | `ApplicationAssemblyTest#returnsTurnsOfOneSessionInOrder` | 返回顺序反了、或按 id 查漏了 sessionId 条件时红 |
| 拿别人的会话 id 查不到 | `ApplicationAssemblyTest#rejectsAnotherUsersSession` | 不过滤 owner 时 200 + 正文，断言 404 红 |

## 缺口

- **"最后活动"要额外查**：`Session` 只有 `createdAt`，按它排会让聊了三天的会话沉底。本票的做法是
  每条会话再取一次 `lastN(1)`，用最后一条事件的时间排序；标题同理（`page(0)` + `pageSize(1)`）。
  代价是列一次 = **2 × 这个人全部会话数**（要排序就得先把每条的最后活动取出来，不是 2 × 一页条数），
  用**返回上限 100 条**兜住（与 OpenClaw 的 `sessions.list` 默认一致），它限的是这一页多大。
  框架给 `updatedAt` 或聚合读接口之前，不自己写 `max(ai_session_event.timestamp)`
  ——那是碰框架的表。
- **没有搜索与 `activeMinutes`**：OpenClaw 有，这一版不做。
- **标题不可改**：`metadata` 没有 UPDATE 路径（见票 01 缺口）。
- **没有"往前翻"的参数**：逐轮窗口是最近的 N 轮，`hasMore` 只说更早的还有；要翻得再加一个
  按 `seq` 往前的游标，本票不做。
- **`cron:` 与子 Agent 的会话不进列表**：判据是 id 前缀（`claw4j:user:`）。它们是会话，但不是
  "这个人跟谁说话"，混进侧边栏会多出几条他没打开过的对话。

## 复核

复核：2026-11-25 `./scripts/verify.sh`（全绿：失败 0 / 错误 0 / 跳过 0；agent 14 文件 26 方法 /
62.0%，app 14 文件 26 方法 / 88.4%，配额未动）。

四条端到端用例与三条读侧单元用例：

| 用例 | 结果 |
| --- | --- |
| `ApplicationAssemblyTest#listsOnlyTheCallersSessions` | ✅ 真端口 200，自己的会话在、别人的不在，后聊的排在前面 |
| `ApplicationAssemblyTest#theTitleComesFromTheFirstUserMessage` | ✅ 标题是首句前 40 字，尾巴那半句不在 |
| `ApplicationAssemblyTest#returnsTurnsOfOneSessionInOrder` | ✅ 四句话按时间先后出现，`role` 分得出 user / assistant，未混入别的会话 |
| `ApplicationAssemblyTest#rejectsAnotherUsersSession` | ✅ 拿别人的 id 回 404，正文一个字都不露 |
| `SessionMemoryTest#theSidebarTitleIsTheFirstUserMessageTruncated` | ✅ 截断 40 字加省略号；没有用户消息时是"新对话" |
| `SessionMemoryTest#theSidebarIsOrderedByLastActivityAndPagesWithACursor` | ✅ 最后活动倒序、游标给出下一页、`cron:` 那条不进列表 |
| `SessionMemoryTest#onlyTheOwnerSeesTheTurnsOfASession` | ✅ 别人的会话与不存在的会话同一个答案；`lastN(2)` 时 `hasMore` 为真 |

落地的四处改动与票面一致，只有两处偏差，都记在这里：

- 端点的用例写在 `ApplicationAssemblyTest` 而不是新开的 `SessionEndpointsTest`：app 模块已经
  贴着 14 个文件的上限，而这四条证的是"端点挂在真端口上、读的是真库"，与那个文件里票 18 的
  `/api/audit` 两条同一件事。端口这层的单元用例放进了 `SessionMemoryTest`（21 → 24 个方法，
  文件数不变）——`JdbcSessionQuery` 在 agent 模块，它的行覆盖只能由那个模块的用例补。
- 端口方法名是 `conversation(...)` 不是 `turns(...)`：它返回的是"一条会话 + 它的这些轮"，
  `turns` 听起来只是那几轮。


# 票 02：侧边栏读侧——列会话与逐轮历史

**Status:** open

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
   `turns(String owner, String sessionId, int limit)`；返回的记录带 id / createdAt / title / turns。
2. `claw4j-agent`：`JdbcSessionQuery` 实现。列表走 `SessionService.findByUserId`；每条会话再取
   一次 `lastN(1)` 拿最后一条事件的时间、用 `page(0)` + `pageSize(1)`（`messageTypes=USER`）拿标题，
   按最后活动倒序、上限 100 条；逐轮走 `getEvents(sessionId, EventFilter)`，先确认这个 session
   属于调用者，不属于就当作不存在。
3. `claw4j-channel`：`SessionController` → `GET /api/sessions`、`GET /api/sessions/{id}/messages`；
   身份走 `HttpPrincipalResolver`（`X-Claw4j-User`），与 `/api/chat` 同一条。
4. `HttpChannelAutoConfiguration` 注册这个 controller（票 18 的教训：**不注册就是 404**，
   而直接 `new` 控制器的用例会照旧全绿）。

## TDD 用例清单

新文件 `SessionEndpointsTest`（app 模块，真端口 + 真 HTTP，形状同 `ApplicationAssemblyTest`）。
文件配额来自票 01 删掉的会话身份层测试文件。

| 判什么行为 | 用例名 | 先红的样子 |
| --- | --- | --- |
| 列表中只有调用者自己的会话 | `SessionEndpointsTest#listsOnlyTheCallersSessions` | 端点不存在时 404；只补注册不过滤时两条都回来，断言只剩一条红 |
| 标题取自首条用户消息 | `SessionEndpointsTest#theTitleComesFromTheFirstUserMessage` | 标题为空或整段正文，断言 `startsWith(首句前十字)` 红 |
| 逐轮按时间正序 | `SessionEndpointsTest#returnsTurnsOfOneSessionInOrder` | 返回顺序反了或混入别的会话，断言 sequence 单调递增红 |
| 拿别人的会话 id 查不到 | `SessionEndpointsTest#rejectsAnotherUsersSession` | 不过滤 owner 时 200 + 正文，断言 404 红 |

## 缺口

- **"最后活动"要额外查**：`Session` 只有 `createdAt`，按它排会让聊了三天的会话沉底。本票的做法是
  每条会话再取一次 `lastN(1)`，用最后一条事件的时间排序；标题同理（`page(0)` + `pageSize(1)`）。
  代价是列表一页 N 条 = 2N 次轻查询，用**上限 100 条**兜住（与 OpenClaw 的 `sessions.list`
  默认一致）。框架给 `updatedAt` 或聚合读接口之前，不自己写 `max(ai_session_event.timestamp)`
  ——那是碰框架的表。
- **没有搜索与 `activeMinutes`**：OpenClaw 有，这一版不做。
- **标题不可改**：`metadata` 没有 UPDATE 路径（见票 01 缺口）。

## 复核

（收票时填）

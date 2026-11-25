# 票 02：侧边栏读侧——列会话与逐轮历史

**Status:** open

## 由来

用户第 4 条：`SessionService` 拿得到历史记忆信息，所以侧边栏可以列会话、也能看每一轮。
这是票 01 的下游——身份简化之后，会话就是一个 `ai_session` 行，按 `user_id` 一查就是列表。

对照 OpenClaw：`sessions_list` / `sessions.list` / `openclaw sessions` 三个入口共用一份判定
（按 `updatedAt` 排序、`activeMinutes` 过滤、默认 100 条 + `hasMore`、列表不带正文）。本票只做
**最小的一版**：按人列、按 `createdAt` 倒序、翻页带 `nextCursor`；正文另开一个端点。

## 裁决

不新增 ADR。这是读侧，不改 schema、不改写路径；形状照 `/api/audit`（票 18 的 `AuditQuery` 那条线）。

## 改哪

1. `claw4j-core`：新增端口 `SessionQuery`——`list(String owner, int limit, String cursor)` 与
   `turns(String owner, String sessionId, int limit)`；返回的记录带 id / createdAt / title / turns。
2. `claw4j-agent`：`JdbcSessionQuery` 实现。列表走 `SessionService.findByUserId`，标题取该会话
   **第一条 USER 事件**的前 40 字（不调模型）；逐轮走 `getEvents(sessionId, EventFilter)`，
   先确认这个 session 属于调用者，不属于就当作不存在。
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

- **没有 `updatedAt`**：`Session` 只有 `createdAt`，所以"最近聊过的排前面"做不到，只能按创建时间。
  要做得自己查 `max(ai_session_event.timestamp)`——那是碰框架的表，另说。
- **没有搜索与 `activeMinutes`**：OpenClaw 有，这一版不做。
- **标题不可改**：`metadata` 没有 UPDATE 路径（见票 01 缺口）。

## 复核

（收票时填）

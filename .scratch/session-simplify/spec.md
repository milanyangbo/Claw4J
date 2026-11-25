# 会话层砍到框架原生（spec）

裁决：`docs/adr/0025`（修订：整条作废）、`docs/adr/0026`（修订：决策一作废）、
`docs/adr/0029`（修订：裁决三缩减、裁决四作废）。三处都是 2026-11-25 同一次修订。

## 为什么做

会话身份那一层（`session_nodes` / `session_windows` / `conversations` /
`session_conversations`）是照 OpenClaw 的分层搬来的，比本仓库真正用到的多：

- `session_windows` **只写不读**——全仓没有一条 select，唯一消费是删节点时的外键级联；
- `conversations` 只有 insert，读侧一条都没有；
- `session_nodes` 的读者只有三处：目标存储、清理、换段判定。前两个随各自功能走，第三个要被删。

而框架那一边该有的都有：`CreateSessionRequest.id` 允许我们自己定 id、`findByUserId` 列会话、
`getEvents` 拿逐轮、`compactionTrigger` 压缩、`deleteExpiredSessions` 清理。

所以这一轮不是"补能力"，是**删掉一层自己维护的身份层，把身份交回框架**。代价写在
ADR-0026 修订里：不再换段、结束与忘记合流、上下文只靠压缩控制。

## 做哪几件

| 票 | 做什么 | 依赖 | 状态 |
| --- | --- | --- | --- |
| 01 | 拆层：会话 id 去掉段、压缩交给 advisor、删容错/清理/目标/预算、迁移 drop 四张表 | — | resolved |
| 02 | 读侧：`GET /api/sessions`（按人列会话）与 `GET /api/sessions/{id}/messages`（逐轮） | 01 | resolved |
| 03 | 下线 `/new`：它删的是整条会话行，不是清历史 | 01 | resolved |

## 明确不做

- 不重建预算（ADR-0025 整条作废，要恢复得在新的一层上重建，不是把旧配置键留着）。
- 不做自动标题：标题取首条用户消息的前若干字，不额外调模型（ADR-0029「不做」里本来就有
  "自动会话命名"）。
- 不做置顶 / 归档 / 未读：`ai_session.metadata` 在框架里**没有 UPDATE 路径**
  （`JdbcSessionRepository` 只有 SELECT / INSERT / DELETE），写进去就改不了。

# 0029 会话键与重置策略照 OpenClaw 重做

- Status: proposed
- Date: 2026-11-10

## 背景

会话键的形状决定"谁的哪一段话算同一段"，重置策略决定"这段什么时候结束"。这两件事我们各写了一套，
与 OpenClaw 都不是同一个答案：

| | OpenClaw（出处） | 本仓库现状 |
| --- | --- | --- |
| 私聊 | `session.dmScope` 默认 `main`：所有私聊共用一个主会话（`docs/concepts/session.md:158-166`） | `owner:通道:chatId`，等于它的 `per-channel-peer` |
| 群聊 / 房间 | `session.groupScope` 默认 `per-group`：按群、房间、频道隔离（`:191-200`） | 同上一行，等价 `per-group` |
| 路由表 | 私聊共享、群隔离、房间隔离、cron 每次新开、webhook 按 hook 隔离（`:65-73`） | 前三条靠 chatId 被动得到，后两条已有 |
| 自动重置 | 默认 `mode: "none"`；`daily` / `idle` 是 opt-in，`atHour` 默认 4（`:267-283`） | 默认 `daily-and-idle`，跨逻辑日或空闲 120 分钟就换 |
| 手动重置 | `/new`、`/reset` 随时可用，是独立于策略的一条（`:279`） | `mode: none` 时 `rotate()` 直接返回空，`/new` 变成空动作 |
| 重置后 | 换的是"当前这一段"，主会话键不变，旧对话仍在这个键下可查（`docs/concepts/main-session.md:104-108`） | 段拼在键后面，旧段留在库里只读 |

最后一行原先记作"两种实现方式，不是偏差"。V8 落地之后这句不再成立——见裁决三：
`session_key` 不含段，段是 `session_windows` 的一行，与它同形。

## 裁决一：键按 peer 类型分段

会话键 = `claw4j:user:<owner>:<peer 段>`，`peer 段` 由 peer 类型决定：

- **私聊**：按 `claw4j.session.dm-scope`
  - `main`（默认）：`main`，这个人所有私聊共用一个主会话
  - `per-peer`：`<chatId>`，同一个人跨通道共用一个
  - `per-channel-peer`：`<channel>:<chatId>`（今天的行为）
  - `per-account-channel-peer`：本仓库没有 account 这一维，等同 `per-channel-peer`，写进文档不单开一档
- **群聊 / 房间**：按 `claw4j.session.group-scope`
  - `per-group`（默认）：`<channel>:<chatId>`
  - `main`：并进主会话（它的 `groupScope: main` 就是这个意思，抄下来，默认不用）

peer 类型来自新加的 `InboundMessage#direct`，由每个通道自己回答，不靠默认值：Slack 看 `channel_type: im`，
飞书看 `chat_type: p2p`，钉钉看 `conversationType`（`"1"` 单聊、`"2"` 群聊），企业微信恒为单聊——
官方接收消息的字段清单（`ToUserName` / `FromUserName` / `CreateTime` / `MsgType` / `MsgId` / `AgentID`）
里没有会话类型，应用消息就是成员发给应用的一对一消息，群消息走群机器人 webhook，不进这个回调。
HTTP 与内部调用记 `true`。没有这个字段就没法区分，而区分不开的后果是把群里的上下文并进私聊。

**前缀保留 `claw4j:user:`**（用户 2026-11-10 裁决），不改成它的 `agent:<agentId>:`。理由不是怀旧：
`SessionRecallScope#belongsTo` 用 `sessionId.startsWith(PREFIX + owner + ":")` 做召回的身份校验，
改前缀要动那条判据，而它是防越权读的边界。它的 `agent:` 段是多 agent 命名空间，我们只有一层。

主会话的键由 `session_key_contract.main_key` 给（默认 `main`），完整形状 `claw4j:user:<owner>:main`。

## 裁决二：重置默认不自动发生

- `claw4j.session.reset.mode` 默认 `none`：不换段，靠上下文压缩管长度（它同默认）。
- `daily` / `idle` 是显式配置。`idle` 只在配了 `idle-minutes` 时成立；`daily` 用 `at-hour`，默认 4。
- **`/new` 不受策略限制**：手动重置是人的决定，`none` 的意思是"不自动换"，不是"不许换"。这一条改的是
  现在的行为（`SessionResetProperties#rotates` 把 `/new` 一起挡掉了）。
- 两种都配时谁先到算谁（`daily-and-idle` 保留为第三个取值，不是默认）。

## 裁决三：会话存储照它的分层，消息仍归 Spring AI

V8 把 `session_entry` 那张单表换成它那套分层（`src/state/openclaw-agent-schema.sql`）：

| 表 | 职责 | 与旧 `session_entry` 的对应 |
| --- | --- | --- |
| `session_nodes` | 一个 `session_key` 一行，热事实在 `entry_json` | 原表整张 |
| `session_entry_snapshots` | 按 `(session_key, field)` 的冷值，三档固定 | 新增 |
| `conversations` | 通道侧的会话身份（channel / account / kind / peer） | 原来 `owner + channel + chat_id` 三列 |
| `session_windows` | 一世对话，`session_id` 一词一代 | 原来的 `segment` + `previous_segment` + `session_started_at` |
| `session_conversations` | 段与通道会话的多对多 | 原来靠键里拼 `channel:chatId` |
| `session_key_contract` | 单行，记 main 会话的键 | 新增 |

三处判断：

1. **消息不搬**。`ai_session` / `ai_session_event` 是 `JdbcChatMemoryRepository` 写死的表名，
   消息由它持有；`session_windows.session_id` 与 `ai_session.id` 同值，靠这个 id 对齐。
   所以不建 `transcript_events`——建了就是两份真相，两边都能写，迟早不一致。
2. **`dmScope: main` 靠 `session_conversations` 落地，不靠拼字符串**。所有私聊共用主会话 =
   多个 `direct` 会话挂到同一个 `session_id`（`primary` 只有一个，其余 `participant`）。
   这也是它为什么要那两张表；只拼键的话，"这一段收过哪几条通道"就无处可查。
3. **goal 从平铺列搬进 `entry_json`**。好处是加会话字段不用改表；代价是
   `session_entry_active_goal_idx` 那个部分索引没了，改成 `entry_json -> 'goal' ->> 'status'`
   的表达式索引（**这一条是我们加的，它没有**，因为它从不做全库计数）。

偏差逐条记在 `V8__session_nodes.sql` 的文件头（不建的表、去掉的列、时间列用 `timestamptz`），
不在本 ADR 重复。

## 代价

1. **旧会话记录清除**（用户 2026-11-10 裁决）：键和表都换了，旧行不再有任何读取方。
   `session_entry` 与 `ai_session` 里的旧会话**直接删掉**，不做迁移——迁移要把同一个人的多个通道行
   合并成一行，必然丢行，比删干净更糟。删除与 `drop table session_entry` 放在存储切换的同一步，
   不在 V8 里做（V8 落地时旧代码还在读写它，先删会让门禁全红）。
2. **私聊共享之后，跨通道串话是默认行为**：网页里问的数字，飞书私聊里模型也看得见。这是它的默认，
   也是它的警告（`session.md:144-148`：多人可及必须开 `per-channel-peer`）。我们沿用它的做法：
   启动日志里打出生效的 `dm-scope`，多用户部署必须显式配 `per-channel-peer`。
3. **`owner` 里的权限指纹保留**：它没有这一层，我们的记忆会跨权重播，这个差异是安全的、有意的，
   写在 `SessionKeys` 类注释里，本 ADR 不改。

## 退场条件

- 如果"默认 `main` 在多人部署里出过事"，就把默认改成 `per-channel-peer`，并在启动日志里留一行
  ——与 `visibility` 那条同一个处理方式（`docs/agents/guardrails.md`）。
- `identityLinks`（跨通道把一个人的多个身份并成一个 peer，`session.md:172-176`）本次不做：
  它解决的是"同一个人多个身份"的映射，与键的分段规则是两件事。

## 不做

- `resetByType` / `resetByChannel`（`:294-311`）：我们没有"每条绑定"这一层配置，等有绑定再说。
- incognito 会话（`:224-238`）、自动会话命名（`:130-137`）、跨会话召回开关（`:240-259`）。
- 主会话的"watch 群活动、心跳"那一套（`main-session.md:52-80`）：它依赖 gateway 的调度与通知队列，
  本仓库没有对应物。

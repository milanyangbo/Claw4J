# 01: 会话键按 peer 类型分段，重置默认不自动发生

**What to build:** 会话存储与键的形状照 OpenClaw：存储换成 V8 那六张表（`session_nodes` 装节点与
`entry_json`，`session_windows` 装段，`conversations` + `session_conversations` 装通道身份与绑定）；
键的 peer 段由 peer 类型决定——私聊默认并进一个人的主会话（`dmScope: main`），群聊按群隔离
（`groupScope: per-group`），两个 scope 都可配；重置默认 `none`（不自动换段），`/new` 与 `/reset`
不受策略限制。旧会话记录直接清除。

**Blocked by:** 无。

**Type:** task

**Status:** ready-for-agent

**Demo:** 网页私聊里说「上周华东区多少」，切到飞书私聊问「刚才那个数按城市拆一下」——模型接得上
（同一个人共用一个主会话）。在同一个人所在的飞书群里问同一句，它接不上（群是另一段）。
`/new` 在任何 `reset.mode` 下都换一段。

## 裁决（见 ADR-0029）

1. 存储换成 V8 的六张表：节点 + `entry_json`（`session_nodes`）、段（`session_windows`）、
   通道身份（`conversations`）、段与会话的绑定（`session_conversations`）。
   **消息仍在 `ai_session_event`**，`session_windows.session_id` 与 `ai_session.id` 同值。
2. 键 = `claw4j:user:<owner>:<peer 段>`，前缀保留（召回的身份校验依赖它）：
   - 私聊按 `claw4j.session.dm-scope`：`main`(默认) / `per-peer` / `per-channel-peer` /
     `per-account-channel-peer`（无 account 维，等同 `per-channel-peer`）
   - 群聊按 `claw4j.session.group-scope`：`per-group`(默认) / `main`
   - main 键由 `session_key_contract.main_key` 给，完整形状 `claw4j:user:<owner>:main`
3. peer 类型来自 `InboundMessage#direct`：Slack 用 `channel_type: im`，飞书用 `chat_type`，
   HTTP 与内部调用为 `true`。
4. `claw4j.session.reset.mode` 默认 `none`；`daily-and-idle` 保留为第三档，不再是默认。
5. `/new` 不受 `mode` 限制：`none` 的含义是"不自动换"，不是"不许换"。
6. 旧会话记录清除：`session_entry` 与 `ai_session` 的旧行删掉，`drop table session_entry`，
   **不写数据迁移**。

## 要改的文件

| 文件 | 改什么 |
| --- | --- |
| `claw4j-core` `InboundMessage` | 加第 10 个分量 `direct`，旧构造器默认 `true` |
| `claw4j-agent` `SessionEntryStore` / `JdbcSessionEntryStore` | 读写换成 `session_nodes` + `session_windows`：定位键读 `session_nodes`，换段插一行 window 再改 `current_session_id`，`entry_json` 整份读写 |
| `claw4j-agent` `JdbcSessionGoalStore` | goal 的读写从平铺列换到 `entry_json -> 'goal'`；`session_nodes_active_goal_idx` 是它的表达式索引，query 写法要与索引逐字一致 |
| `claw4j-agent` `SessionMaintenance` / `ScheduleScanner` | 两处 `session_entry` 的 SQL 换表（清理按 `last_interaction_at`，调度读 `session_started_at`） |
| `claw4j-agent` `SessionKeys` | 加 `sessionIdOf(owner, channel, chatId, direct, scope)`；`conversationKeyOf` 同步；类注释改形状说明 |
| `claw4j-agent` `SessionEntries` | 三个方法加 `direct` 与 scope，定位键与状态行用同一个 |
| `claw4j-agent` `ChatClientAgentPort` | 两处调用传 `message.direct()` 与配置里的 scope |
| `claw4j-agent` `SessionResetProperties` | 默认 `none`；删掉"认不出落回 daily-and-idle"（改成点名合法值） |
| `claw4j-agent` `SessionMemoryConfiguration` | 装配新的 scope 属性，启动日志打出生效的 `dm-scope`/`group-scope` |
| `claw4j-channel` Slack / Feishu | 翻译出 `direct`（`channel_type: im`、`chat_type`） |
| `claw4j-agent` 迁移（新版本，V9） | `delete from claw4j_session.ai_session` + `drop table claw4j_session.session_entry`；V8 不动 |
| 测试 | 见下 |

## 验收判据（TDD 用例清单）

| 判什么行为 | 用例名 | 先红的样子（反例） |
| --- | --- | --- |
| 私聊默认并进主会话：同一个人不同通道同一个键 | `SessionMemoryTest#conversationKeysFollowPeerKind`（改写现有方法） | 把 `main` 分支改回 `channel:chatId`，只有这一条红 |
| 群聊仍按群：同一个人的群与私聊是两个键 | 同上 | 把 `direct` 恒为 true，只有这一条红 |
| `dm-scope: per-peer` 跨通道合并、不同人分开 | 同上 | 忽略 scope 参数，只有这一条红 |
| `dm-scope: per-channel-peer` 回到今天的形状 | 同上 | 同上 |
| `group-scope: main` 把群并进主会话 | 同上 | 同上 |
| `reset.mode: none` 时不加段、不查库 | `SessionMemoryTest` 现有 `mode: none` 那条断言补"键里没有段" | 把 `rotates()` 恒真，只有这一条红 |
| `mode: none` 下 `/new` 仍然换段 | `ToolGovernanceTest#manualResetIgnoresTheAutomaticPolicy`（用第 20 个空位） | 把 `rotate()` 里 `!reset.rotates()` 的短路加回去，只有这一条红 |
| Slack 的 `channel_type: im` → `direct=true`，`app_mention` → false | `SlackChannelTest` 现有用例加断言 | `direct` 恒 true，只有这一条红 |
| 飞书 `chat_type: p2p` → true，`group` → false | `FeishuChannelWiringTest` 用空位加一条 | 同上 |
| `application.yml` 里两个 scope 键声明且默认值一致 | `BuildContractTest`（现有 yml 断言方法里加两条） | 删 yml 里的键，只有这一条红 |
| 换段：`current_session_id` 改、`session_windows` 多一行、旧段仍读得到 | `PersistenceSchemaTest#rotationAddsAWindowAndKeepsTheOldOne` | 只改 `current_session_id` 不插 window，只有这一条红 |
| 私聊共用主会话：两个 `direct` 会话挂在同一个 `session_id`，且只有一个 `primary` | `PersistenceSchemaTest#directConversationsShareOneWindow` | 每条会话建一个 window，只有这一条红 |

## 门禁

`./scripts/verify.sh` 全绿、跳过 0、行覆盖率 ≥60%；`claw4j-agent` 保持 10 个测试文件、
单文件 ≤20 条 `@Test`（键形状那条是改写不是新增）。

## 不要做

- 不写数据迁移：旧记录按裁决 6 直接清（`drop table` + 清 `ai_session`）。
- 不做 `identityLinks`、`resetByType`、`resetByChannel`、incognito、自动会话命名。
- 不动 `owner` 里的权限指纹。
- 不动 `ai_session` / `ai_session_event` 的形状（Spring AI 的框架契约），也不建 `transcript_events`。

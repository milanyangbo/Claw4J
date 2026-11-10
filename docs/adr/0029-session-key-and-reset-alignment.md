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

最后一行是两种实现方式，不是偏差：它用 `key`（稳定）+ `sessionId`（会变），我们用 `key` + `段` 拼成一个
字符串。改它没有收益，不动。

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

peer 类型来自新加的 `InboundMessage#direct`：Slack 用 `channel_type: im` 判、飞书用 `chat_type` 判、
HTTP 与内部调用记 `true`。没有这个字段就没法区分，而区分不开的后果是把群里的上下文并进私聊。

## 裁决二：重置默认不自动发生

- `claw4j.session.reset.mode` 默认 `none`：不换段，靠上下文压缩管长度（它同默认）。
- `daily` / `idle` 是显式配置。`idle` 只在配了 `idle-minutes` 时成立；`daily` 用 `at-hour`，默认 4。
- **`/new` 不受策略限制**：手动重置是人的决定，`none` 的意思是"不自动换"，不是"不许换"。这一条改的是
  现在的行为（`SessionResetProperties#rotates` 把 `/new` 一起挡掉了）。
- 两种都配时谁先到算谁（`daily-and-idle` 保留为第三个取值，不是默认）。

## 代价

1. **键变了，库里历史对不上新键**：`session_entry`、`ai_session`、`session_entry` 上的目标与用量
   全部按旧键存。切过去之后这些行变成孤儿（不删、也读不到）。
   两条路：① 接受孤儿（开发阶段）；② 一次性迁移把旧键 `owner:channel:chatId` 映射到 `owner:main`。
   **迁移要按 owner 合并多行，必然丢一部分行**（同一个人在不同通道各有一段），所以推荐 ①，
   把旧数据当只读历史留着，写一个新键开始。
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

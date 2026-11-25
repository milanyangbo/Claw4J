# 票 03：下线 `/new`

**Status:** resolved

## 由来

用户 2026-11-25 明确要求：**去除 `/new` 这个功能**。

2026-11-25 的 A–J 全量验收把这件事推到了台面上（`docs/acceptance/2026-11-25/README.md` 第 2 条）：
`/new` 实际做的事是 `SessionService.delete(sessionId)`——**删掉那一条会话行**，不是"清历史"。
后果连成串：之后 `GET /api/sessions` 里少一行、`GET /api/sessions/{id}/messages` 回 404、
再发一句用同一个 id 重建但 `createdAt` 重置（侧边栏里它从"刚建的"变成"刚建的"）。这与
ADR-0026 修订写的"id 不变、会话还在、只清历史"差一半，而框架没有"只删事件"的接口，
补齐只能是"删完按同一 id 再建一次"——为一个没人验证过的入口养一段绕路。

## 裁决

ADR-0026 再修订一笔：**`/new` 与 `/reset` 下线，会话命令只剩 `/elevated`**。

代价认下来，写进 ADR：

- **"清空这条对话的历史"没有入口了**。唯一的删除路径是 TTL（`SessionExpirySweep` 到点调
  `deleteExpiredSessions`）。要重开的条件很具体：有人真的需要"现在就忘掉刚才那些"，
  而压缩（`SessionMemoryAdvisor` 的 `compactionTrigger`）没解决他的问题。
- 这与票 01 已经写下的"用户没有开一段新对话的手段"是同一件事的两半，现在两半都归零：
  **既不能换段，也不能清**。上下文只由压缩控制。
- `SessionCommand` 整个类删除（`ACK` 文案、整词匹配、`/reset` 别名一起走）。

## 改哪

1. `claw4j-agent`：删 `session/SessionCommand.java`。
2. `claw4j-agent`：`turn/SlashCommands` 去掉 `SessionService` 依赖与 `clear(...)`，`parse` 不再要
   sessionId——只剩 `/elevated` 一条，类注释里那段"两条命令各有一个解析器"跟着改。
3. `claw4j-agent`：`turn/ChatClientAgentPort` 两处 `commands.parse(message, sessionId)` → `parse(message)`，
   构造处去掉 `builder.sessions`。
4. 文档：`docs/adr/0026`（修订段）、`docs/design/session-memory.md`（"会话 id 不随时间变"那句）、
   `README-capability-tests.md`（H2 整节删、J3 的 `/new` 判据删、速查表、D2/G2 里"`/new` 之后"的
   说法）。

## TDD 用例清单

| 判什么行为 | 用例名 | 先红的样子 |
| --- | --- | --- |
| `/new` 不再是命令，原样进模型 | `SessionMemoryTest#newIsNoLongerACommand` | 不删解析器时它被吃掉、回 ACK 文案，断言"模型收到了 `/new` 这句原文"红 |
| `/elevated` 这条命令仍然有效 | `ChatPipelineTest` 已有那条覆盖 | 删多了会把提权一起删掉，那条用例变红 |

同时删掉两条随功能走的用例：`SessionMemoryTest#newCommandClearsHistoryAndKeepsTheId`、
`SessionMemoryTest#slashCommandsAreMatchedAsWholeWords`（后半段是 `/new` 的解析表）。

## 缺口

- **没有"清历史"的替代手段**：只能等 TTL。这是本票主动接受的，不是漏做。
- **飞书 / 企微侧如果有人在用**：没有遥测能证明有没有人敲过 `/new`，按"没有则删"处理。

## 复核

复核：2026-11-25 `./scripts/verify.sh`（失败 0 / 错误 0 / 跳过 0；行覆盖 agent 61.8%、app 88.4%、core 82.3%、channel 78.0%、model 83.1%，覆盖率门禁全过）。

删掉的东西：

- `claw4j-agent/session/SessionCommand.java` 整个文件（含 `ACK` 文案、整词匹配、`/reset` 别名）。
- `SlashCommands` 的 `SessionService` 依赖与 `clear(...)`；`parse` 的第二个参数改名 `conversationKey`
  （只剩 `/elevated`，它改的是"这条对话"的档）。
- `ChatClientAgentPort` 的三处：构造、`answer` 与 `streamInLane` 的调用。
- `ChatClientAgentPort.Builder#sessions(...)` 与 `AgentAutoConfiguration` 里那一行——**它已经没人读了**，
  留着就是下一处"看起来接上了其实没接"。

用例：删 `newCommandClearsHistoryAndKeepsTheId`、`newCommandParsesItsTwoShapes`，
加 `newIsNoLongerACommand`（`SessionMemoryTest` 25 → 24 个方法，文件数不变）。

| 用例 | 结果 |
| --- | --- |
| `SessionMemoryTest#newIsNoLongerACommand` | ✅ 模型收到的是 `/new` 原文，回的是模型那句话，不是"历史已清空" |
| `SessionMemoryTest#sessionIdCarriesNoSegmentSuffix` 等 24 条 | ✅ 全绿 |
| `ChatPipelineTest`（含 `/elevated` 两条入口一致那条） | ✅ 23 条全绿 |

真机实测（`claw4j_accept_1125`，ops 人设）：发 `{"text":"/new"}` 回的是模型的自我介绍，
不是那句回执；`/elevated` 走 `/api/chat/stream` 仍是 `1 chunk + 1 done`，正文是
"这个部署没有开提权（claw4j.tools.elevated.enabled=false），命令只能跑在配置好的位置。"

文档：ADR-0026 再修订一笔（`/new` 下线、代价归零）；`docs/design/session-memory.md` 同步；
`README-capability-tests.md` 的 H2 换成 `/elevated`、J3 从"`/new` 不换 key"改成"再聊一句 id 不变"。

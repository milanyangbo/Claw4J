# 票 01：拆掉会话身份层，会话交回框架

**Status:** resolved

## 由来

2026-11-25 用户裁决（四项）：

1. 去除 `session_nodes` / `session_windows` 相关代码；
2. 只保留 `SessionMemoryAdvisor` 的记忆功能——用它自己的压缩能力、用它自己的会话身份；
3. Session 存储挂了，能力不可用（不再降级为"这一轮没记忆"）；
4. `SessionService` 拿得到历史，所以侧边栏与逐轮历史都能做（那张票在 02）。

支撑这个裁决的事实（2026-11-25 核实）：

- `session_windows` **全仓没有一条 select**，只有换段时写、删节点时级联。`conversations` 同样只有 insert。
- `session_nodes` 的读者只有三处：`JdbcSessionGoalStore`（目标 + token 记账）、`SessionMaintenance`
  （两条清理）、`JdbcSessionEntryStore`（判换段）。前两处各自要删，第三处随身份层一起走。
- 框架侧够用：`CreateSessionRequest.id` 可指定、`findByUserId` 列会话、`getEvents` 取逐轮、
  `compactionTrigger`/`compactionStrategy` 压缩、`deleteExpiredSessions` 清理。
- `JdbcSessionRepository` **没有 UPDATE**：`metadata` 写进去就改不了（所以不做重命名/置顶/归档）。

## 裁决

- `docs/adr/0025` 修订：整条作废，额度随目标下线。
- `docs/adr/0026` 修订：决策一作废（不再换段），决策二不变。
- `docs/adr/0029` 修订：裁决三缩减为框架那两张表，裁决四作废（通道会话不再登记）。

用户另外拍了两处连带：**目标一起删**、**预算跟目标一起删**（它们同一个 store）。

## 改哪

**删**

| 件 | 原因 |
| --- | --- |
| `SessionEntryStore` / `JdbcSessionEntryStore` | 换段没了，没有写指针的动作 |
| `SessionEntries` | 判跨天/空闲/`/new` 的算法整体下线 |
| `SessionResetProperties` / `SessionScopeProperties` | 前者只给换段用；后者只给 `session_scope` 列用 |
| `SessionCompactor` | 压缩交给 `SessionMemoryAdvisor` |
| `FaultTolerantSessionService` | 第 3 条：库挂就是不可用 |
| `SessionMaintenance` / `SessionCleanup` / `SessionMaintenanceProperties` | 清理用 `deleteExpiredSessions` |
| `agent/goal/` 整个包（`SessionGoalStore` / `JdbcSessionGoalStore` / `SessionGoal` / `GoalStatus`） | 目标与预算一起下线 |
| 目标工具与 `/goal` 命令族 | 同上 |
| 迁移 `V10__session_identity.sql` 那四张表 | drop |

**改**

- `SessionAddress`：只拼一个键，两把键合一（`sessionId` = `conversationKey`）。
- `SessionKeys`：`sessionIdOf` 去掉 segment 参数。
- `SessionMemoryConfiguration`：advisor 配上 `compactionTrigger` / `compactionStrategy`；
  `SessionService` 直接用 `DefaultSessionService`；删掉上面那批 bean。
- `ChatClientAgentPort` / `PromptComposer` / `TurnRecorder`：去掉 compactor 与 goals 两处依赖。
- `SlashCommands`：`/new`、`/reset` 改成"清空当前会话的历史、id 不变"。

**留**

`SessionKeys`（键拼法）、`SessionAddress`（简化后）、`SessionMemoryProperties`（压缩参数）、
`RecallSessionTool` / `CrossSessionEvents` / `SessionVisibility`（跨会话召回与段无关）、
`session_progress_cards`（进度卡按会话键存，不依赖 nodes）、`standing_intents`。

## TDD 用例清单

配额已经满了（`SessionMemoryTest` 26 个方法、agent 模块 14 个文件），所以这一票是
**先删旧用例腾位置**：换段、清理、目标三组用例随代码一起删，再用腾出来的位置放新的。

| 判什么行为 | 用例名 | 先红的样子 |
| --- | --- | --- |
| 会话 id 就是会话键，不带 `<epochMilli>-<随机>` 后缀 | `SessionMemoryTest#sessionIdCarriesNoSegmentSuffix` | 改前 `assertThat(id).doesNotContainPattern("-\\d{4}$")` 红（id 末尾是 `-4192` 一类） |
| 同一个对话跨天仍是同一个 id | `SessionMemoryTest#oneConversationKeepsOneSessionIdAcrossDays` | 改前把 Clock 推过 `at-hour`，第二次拿到新 id，断言 `isEqualTo(first)` 红 |
| `/new` 清空历史但 id 不变 | `SessionMemoryTest#newCommandClearsHistoryAndKeepsTheId` | 改前 `/new` 后 id 变了（新段），断言 id 相同红 |
| 库不可用时这一轮失败，不降级 | `SessionMemoryTest#sessionStorageFailureFailsTheTurn` | 改前 `FaultTolerantSessionService` 吞掉异常、回答照常返回，断言 `isThrownBy` 红 |
| 压缩由 advisor 的 trigger 触发 | `SessionMemoryTest#theAdvisorCompactsWhenItsTriggerFires` | 改前 advisor 没配 trigger，`compact` 从不被调用，断言 `archived` 计数 > 0 红 |
| 旧的四张表已经不在库里 | `SessionMemoryTest#theLegacySessionIdentityTablesAreGone` | 改前 `information_schema` 里查得到 `session_nodes`，断言 `isEmpty` 红 |

## 缺口

- **清理判据退化**：`deleteExpiredSessions` 看 `expires_at`（建行时写死），天天在用的会话也会被删。
  这是 ADR-0026 当初要修的问题，现在认回来。真正的修法是"每次写过事件就刷新 `expires_at`"，
  但 `JdbcSessionRepository` 没有 UPDATE，做不了——留作缺口。
- **`metadata` 不可更新**：侧边栏不能重命名/置顶/归档。等框架给 UPDATE 再说。

## 复核

复核：2026-11-25 `./scripts/verify.sh` 全绿（失败 0、错误 0、跳过 0；agent 14 文件 / 26 方法、
app 14 / 26，配额没抬）。

六条 TDD 用例的去向与实测：

| 用例 | 结果 |
| --- | --- |
| `SessionMemoryTest#sessionIdCarriesNoSegmentSuffix` | ✅ 键以 `:web:c-1` 结尾，`doesNotContainPattern("-\\d{4}$")` |
| `SessionMemoryTest#newCommandClearsHistoryAndKeepsTheId` | ✅ `/new` 后 `createdIds()` 仍只有原来那个 id，提示词里没有上一轮 |
| `SessionMemoryTest#sessionStorageFailureFailsTheTurn` | ✅ `findById` 抛 `DataAccessResourceFailureException` 时整轮抛出，不再降级 |
| `SessionMemoryTest#theAdvisorCompactsWhenItsTriggerFires` | ✅ 200 条事件跑一轮后条数下降 |
| `PersistenceSchemaTest#theLegacySessionIdentityTablesAreGone` | ✅ `information_schema` 里四张表为空，会话 schema 只剩四张 |
| 跨天仍是同一个 id | ⏳ 没有对象可断言：换段下线之后"跨天换不换"这件事不再存在（`oneConversationKeepsOneSessionIdAcrossDays` 无处可写） |

同一批还改了这些随之下线的断言：`ChatPipelineTest` 的活跃目标注入与 `/goal` 三条、
`ToolGovernanceTest` 的目标两条与 `InMemoryGoals` 替身、`TurnFlowTest`（只留投递两条，
额度与清理随目标/清理下线）、`SearchSqlTest` 的指针 schema 用例、`BuildContractTest` 的
会话 schema 表清单、`ApplicationAssemblyTest` / `ToolProfileMatrixTest` 的工具清单（`get_goal` →
`recall_session` / `intent`）。

已知缺口照旧：清理判据退回 `expires_at`（框架的 `deleteExpiredSessions` 看创建时间），
`metadata` 不可更新。

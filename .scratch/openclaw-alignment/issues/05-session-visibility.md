# 05: 会话可见性——`self` 只是它那四个值里的一个

**What to build:** 加 `claw4j.tools.sessions.visibility`（`self` / `tree` / `agent` / `all`，
**默认 `all`**），`recall_session` 的检索范围按它判。今天这个值被硬编码成 `self`，
等于把它的一个开关钉死在最窄档、还把开关删了。

**Blocked by:** 无（与 `01`–`04` 无依赖）。

**Type:** task

**Status:** resolved（2026-11-08）

**Demo:** 网页私聊里说过"华东区口径按签约主体算"，第二天在群里问"上次那个口径是什么"，
默认配置下能查回来，回答里带"来自哪一段"；把 `visibility` 配成 `self` 之后同一次提问答"没找到"。

## 背景：它有这个东西，而且默认最宽

`docs/gateway/config-tools/sessions-and-subagents.md` 的 `tools.sessions` 一节，原文：

> Controls which sessions can be targeted by the session tools (`sessions_list`, `sessions_history`,
> `sessions_search`, `sessions_send`, `session_status`).
>
> **Default: `all`** (every session on the Gateway, including other agents' and other users'
> transcripts). Cross-agent access is governed by `tools.agentToAgent` and is on by default.
> Use `agent`, `tree`, or `self` to narrow visibility.

四个值的定义（同一节 `Visibility scopes`）：

| 值 | 它写的定义 |
| --- | --- |
| `self` | only the current session key |
| `tree` | current session + sessions spawned by the current session (subagents)；主会话有 same-agent 例外 |
| `agent` | any session belonging to the current agent id（可以包含其他用户） |
| `all` | any session，跨 agent 由 `tools.agentToAgent` 管 |

`docs/concepts/session-search.md:32-38` 还专门说了一句：

> The default `tools.sessions.visibility: "all"` permits unsandboxed callers to search sessions
> across agents on the Gateway, **including other users' conversations**. …
> **Per-peer DM routing separates conversation context but does not restrict session-tool visibility.**

也就是说：它**知道**这层不管 DM 分段，仍然把 `all` 设成默认。要不要串台由部署配，
不由能力有无决定。我们今天的选择正好相反——把能力收窄到 `self` 并把开关删掉。

## 现状（代码证据）

- `RecallSessionTool.java:175`：`sessionService.getEvents(sessionId, filter)` —— 只查当前这一段。
  `:169` + `:342-352`：`belongsTo` 核前缀（`claw4j:user:<owner>:…`）。
- 这次收窄的来历写在 `m3-03` 票的「隔离修复（2026-11-05）」一节：会话键按"权限 + 通道 + chatId"分段之后，
  工具还在按 `owner` 查（`findEventsByUserId`），于是私聊里查到的数字能在群里被"回忆"出来。
  **那次修复是对的**（真泄露），但它选的手法是"换查询"——换掉了整个范围，顺带换掉了对齐点。
- 框架只给了两个查询入口（`spring-ai-session:0.10.0`，`javap` 实看）：
  `getEvents(sessionId, filter)` 与 `findEventsByUserId(userId, filter)`，**没有"列出某 agent 的全部会话"**。
  而我们的 `user_id` 是 `userId@权限指纹`（`SessionKeys.ownerOf`），所以 `findEventsByUserId` 也不能当
  "全部会话"用——那是"这个人、这份权限、所有通道"的全部会话。

## 四个值在我们这里各是什么

| 值 | 我们怎么落 | 今天等价于 |
| --- | --- | --- |
| `self` | 现状：`getEvents(sessionId, …)` + `belongsTo` | `self` |
| `tree` | 当前 + 子 Agent 派生的会话。子 Agent 的会话**今天不落会话库**（`SubagentRunner` 用的是 `ToolIndex` 的桶，不是 `ai_session`），所以暂时与 `self` 相同 | `self` |
| `agent` | 本部署全部会话（跨 `user_id`）。`SessionService` 没有这个入口 → 自己写一条查询 | — |
| `all` | 同 `agent`（我们只有一个 agent 命名空间；等有第二个 agent 再分开） | — |

于是本票真正新增的能力只有一个：**跨用户检索**。`tree` 与 `agent` 的差别要等子 Agent 会话落库之后才成立，
这一点必须写在用例里，别让下一个人以为两个值"应该有差别"却查不出来。

## 裁决：默认 `all`，代价写在明处

**已裁决（2026-11-08）：照它的默认值做，就是 `all`，不改成 `self`。** 源码与文档两处对得上：

- `docs/gateway/config-tools/sessions-and-subagents.md`：`Default: all (every session on the Gateway,
  including other agents' and other users' transcripts)`。
- `src/plugin-sdk/session-visibility.ts:131-138` 的 `resolveSessionToolsVisibility`：
  ```ts
  /** Resolve configured session-tool visibility, defaulting invalid or missing values to all. */
  if (value === "self" || value === "tree" || value === "agent" || value === "all") return value;
  return "all";
  ```

后果照旧写在明处：

- `recall_session` 今天只给 `read-only` / `admin`（`guest` 没有这个动作）。
  默认 `all` 意味着一个 `read-only` 用户能召回另一个用户的会话内容。
  **这不是 bug，是它的默认**；它的安全位是配置项本身，还有一条专门的检查
  （`docs/cli/security.md:39`：多个 agent 且 `visibility` 解析成 `all` 时报一条 finding）。
- 我们照做，另加两处它没有的（这两处属于我们的身份模型，不是另发明机制）：
  1. 启动日志一行：`会话可见性 = all（可跨用户召回）；要收紧配 claw4j.tools.sessions.visibility=self`。
     没有这行，生效值只能靠翻 yml。
  2. `application.yml` 注释 + README：**多用户、角色不同的部署必须配 `self`**。
     因为它与 ADR-0007 的等级授权叠加会放大后果：`read-only` 是被允许"问数据"的身份，
     不是被允许"看别人的对话"的身份。
- 上面两条已记进 `docs/agents/guardrails.md` 的「已知偏差」表：这是我们有意保留的默认值，
  不是待修的缺陷。

### 一处不照抄：写错的词启动失败，它回落成 `all`

上面那段源码的最后一行就是这个偏差：**它对非法值 `return "all"`**。我们抛。

- **它那条机制**：`resolveSessionToolsVisibility` 把 "缺失" 与 "写错" 当同一件事，
  都解析成 `all`。好处是配置永远读得出一个值，代价是 fail-open——一个拼成 `everybody` 的配置
  会静默开到最宽，而日志上看不出"这是写错了"还是"本来就配的 all"。
- **我们差在哪**：我们的配置文件是给人手改的（`application.yml` + 环境变量覆盖），
  而它的配置由 CLI 与网关写入、还能被 schema 校验。没有 schema 校验这一层，
  静默回落的代价比它那边大。
- **只能改成什么、代价是什么**：`SessionVisibility.of` 对非法值抛
  `IllegalArgumentException`，消息点名 `claw4j.tools.sessions.visibility` 与四个合法值，
  绑定阶段就失败。代价是手滑一个拼写会让服务起不来——这个代价我们认，
  因为它比"悄悄开到最宽"便宜。与 `session.maintenance.mode` 拼错回落 `warn` 的选择不矛盾：
  那个有"更保守的一侧"可落，`visibility` 的四个值没有。

## 要做

1. `SessionVisibilityProperties`：`claw4j.tools.sessions.visibility`，枚举四值，缺省 `ALL`。
   放 `claw4j-agent` 的 `session` 包（与 `claw4j.tools.profile` 同一族，`ToolsProperties` 旁边）。
   **绑不上就启动失败**（见验收判据最后一条）：写成 `everybody` 这种词时回落成 `ALL` 是最坏的形态——
   配置写错变成了最宽的授权。
2. 范围解析收成一处：给定 `Principal` + `sessionId` + `visibility` → 一次查询。四个分支：
   - `self` / `tree`：`getEvents(sessionId, filter)`（现状），`belongsTo` 保留。
   - `agent` / `all`：一条跨会话查询。事件表已经有 `session_id`，不需要 join：
     `select … from claw4j_session.ai_session_event where <filter> order by … limit ?`。
     **不要**用 `findEventsByUserId`：跨用户时它一条都查不到，于是 `all` 会静默退化成"什么也没有"，
     而那与"历史里没说过"在结果与日志上完全一样。
3. 身份校验按值分，并且把"为什么这里不核前缀"写在注释里：
   - `self` / `tree`：`belongsTo` 保留（防宿主把别人的 id 递进来）。
   - `agent` / `all`：跨用户是允许的，不再核前缀；但 `Principal` 为空仍然拒绝，动作仍然要过授权表。
     这一段是本票最需要被 review 的十行。
4. **结果要带出处**：跨会话之后，"这条是从哪一段来的"变成必需信息（它每条结果都带 `sessionKey`）。
   现在 `render`（`:279-308`）把 `sessionId` 去掉了，注释写的理由是"会话现在恒等于当前这一段，
   重复的字段只是白花 token"——这个前提只在 `self` 下成立。跨会话时要给每条结果加一段短标识
   （哪一段、什么时候），否则模型会把两段对话的内容混成一段回答。这是本票第二处实质改动。
5. **工具描述要跟着改**：`:102-104` 的 `DESCRIPTION` 现在明写"只搜得到这一段对话，
   看不到你在别的会话或群聊里说过的话"。范围放开了而描述不改，模型会拿这句去**拒绝**一次
   本来查得到的召回——那是"模型被喂了错的能力清单"，与 `ScheduleTools` 那条同一类问题。
   描述里的范围要按生效值拼，不是写死一句。
6. 启动日志 + `application.yml` 注释 + README（见裁决）。

## 验收判据（反例都要能红）

- [x] **默认是 `all`**：两个用户各说一句 → A 能召回 B 的那句，且结果里带出处标识。
      反例：默认写成 `self` → 红。
      → `ToolGovernanceTest#defaultVisibilityRecallsAcrossUsers`（反例已跑，见文末）
- [x] **配 `self` 就查不到**：同一次提问查不到 B 的那句，且拒绝语要说"范围是当前会话"，
      不能只说"没找到"（两者在模型那里是同一句话，在排查时是两件事）。反例：`self` 分支走跨会话查询 → 红。
      → `ToolGovernanceTest#selfScopeRecallsOnlyThisConversation`（末尾两句断言文案带范围）
- [x] **`agent` 与 `all` 在本部署同行为**，用例里写明原因（只有一个 agent 命名空间）。
      反例：没有任何断言区分 → 这条不算验收；至少要断"两者结果一致"。
      → `ToolGovernanceTest#everyValueTakesItsOwnBranch`（`isEqualTo` 断两者输出一致）
- [x] **`tree` 今天等同 `self`**，用例写明"子 Agent 会话不落会话库，等落了再改这一处"。
      这是唯一允许"两个值同行为"的地方。
      → 同上（`isEqualTo` 断两者一致）+ `SessionVisibility` 类注释
- [x] **写错的词启动失败**：`visibility: everybody` → 启动失败并点名这个键与四个合法值。
      反例：回落成 `ALL` → 红（最危险的一种：写错变成最宽）。
      → `SessionMemoryTest#visibilityMustBeOneOfFourWords`（`ApplicationContextRunner` 绑 `everybody`
      → `hasFailed()`，且栈里有 `self / tree / agent / all`）
- [x] **授权不变**：`guest` 仍然没有 `recall_session`；被拒时检索一次都没执行
      （沿用 `ObservabilityTest#deniedCallsAreCountedByTheAuthorizationAdvisor` 那类探针断言，不重复写）。
      → 授权表本次一行未改，`ToolGovernanceTest#permissionIsItsOwnAction` 照旧钉着
- [x] **出处真的能用**：拿结果里的出处回到那一段能取回原文（跨会话时这条才有意义）。
      → `ToolGovernanceTest#defaultVisibilityRecallsAcrossUsers` 断结果含对方的完整 `sessionId`；
      `SearchSqlTest#crossSessionRecallCrossesUsersAndSkipsSynthetic` 断 `getSessionId()` 回来的是库里那一段
- [x] **工具描述与生效范围一致**：`visibility=all` 时 `DESCRIPTION` 里不再出现"看不到别的会话"这种话，
      `self` 时出现。反例：描述写死成现状那句 → `all` 下模型会主动拒绝一次查得到的召回（断言描述文本）。
      → `RecallSessionTool#descriptionFor` + `everyValueTakesItsOwnBranch` 里的三条文本断言
- [x] `./scripts/verify.sh` 全绿、跳过 0、行覆盖率 ≥60%（新增的 JDBC 查询有真库用例）。
      → **681 条、失败 0、错误 0、跳过 0**；core 88.1 / channel 78.6 / capability 85.8 /
      agent 70.0 / example-sales 93.8 / example-mcp 75.0 / app 80.8 / eval 82.6；
      文件 10、单文件 20。agent 从 73.2% 落到 70.0% 是新增的 SQL 分支与四档分支带来的，
      对应覆盖在两处：`SearchSqlTest` 两条真 Postgres 用例（跨用户 + synthetic/归档、括号 + 分页参数）
- [x] 删掉/改写 `m3-03` 票里"隔离修复"那段的结论口径：收窄的做法保留为 `self` 档的解释，
      而不是"我们只做 `self`"。
      → 已改（顶部回填块 + 「隔离修复」首段 + 输出/身份那两条）

## 实现记录（2026-11-08）

新增（`claw4j-agent` 的 `session` 包）：

| 文件 | 干什么 |
| --- | --- |
| `SessionVisibility` | 四档枚举 + `of(String)`（缺键落 `all`，写错的词抛且点名四值）+ `description()` |
| `SessionVisibilityProperties` | `@ConfigurationProperties("claw4j.tools.sessions")`，校验落在紧凑构造器 → 绑定阶段就失败 |
| `SessionRecallScope` | 范围解析收一处：`self`/`tree` 走 `getEvents`，`agent`/`all` 走跨会话；`belongsTo` 搬进来 |
| `CrossSessionEvents` | 跨会话检索端口（为什么不通用，见接口注释） |
| `JdbcCrossSessionEvents` | 一条 SQL 查 `claw4j_session.ai_session_event`，主连接池、表名全限定 |

改：`RecallSessionTool`（构造参数换成 `SessionRecallScope`、描述按值拼、跨会话时每条带出处、
"没找到"按范围说话、归属校验只在 self/tree 做）、`SessionMemoryConfiguration`（装配 + 启动日志）。

测试落点（**没有新开文件**，两个满额文件靠合并同类用例腾位）：
`ToolGovernanceTest` 20 → 19（合并"换人 + 降权 + 缺身份 + 缺会话"为一条、合并 match_mode 与坏参数），
`SessionMemoryTest` 20 → 20（合并两条清理配置越界用例，换进 visibility 绑定那条），
`SearchSqlTest` 16 → 18。

### 反例（七条都真跑，跑完复原）

| 把实现改坏成什么 | 红的用例 | 其余 |
| --- | --- | --- |
| `self`/`tree` 分支也走跨会话查询 | `selfScopeRecallsOnlyThisConversation` + `everyValueTakesItsOwnBranch`（tree 那条断言） | 17 条全绿——别的用例对"范围"完全无感 |
| `SessionVisibility.DEFAULT` 改成 `SELF` | `defaultVisibilityRecallsAcrossUsers`（"缺键落 all"那句断） | 18 条全绿 |
| `checksOwnership()` 恒 `false` | `guardsRefuseInsteadOfWidening`（u-2 拿到 u-1 的会话 id 就查到了） | 18 条全绿 |
| `of()` 对非法值回落 `DEFAULT`（照它的行为） | `visibilityMustBeOneOfFourWords` | 19 条全绿 |
| 跨会话 SQL 加 `user_id` 过滤（模拟 `findEventsByUserId`） | `crossSessionRecallCrossesUsersAndSkipsSynthetic`（u-2 那句查不到） | 17 条全绿 |
| 关键词那一段去掉外层括号 | `crossSessionKeywordParenthesesAndPagingLineUp`（synthetic 那条漏出来了） | 17 条全绿 |
| `descriptionFor` 写死成 `self` 那句 | `everyValueTakesItsOwnBranch`（描述文本断言） | 18 条全绿 |

第三条与第六条最值得留：它们分别证明"不核前缀就是越权读"和"少一对括号就是 fail-open"，
两者都不会报错，只会多给一条结果。


## 门禁余量

`claw4j-agent` 10 个文件满，`ToolGovernanceTest` 与 `SessionMemoryTest` 都已 20 条（满）。
按现有空位分配：`KnowledgeRetrievalTest`（18 → 20）放"跨会话可见 / self 查不到"两条；
`ChatPipelineTest`（19 → 20）放"写错的词启动失败"（它已经在管装配期失败）。
还需要第 3 条时，把 `ToolGovernanceTest` 里"批准落在执行行上"与"批准不串到别的 run"合成一条
（同一 setup、同一关切）腾位。**不要新开文件**。

## 不要做

- **不要做 `sessions_list` / `sessions_send` / `session_status`**：`tools.sessions` 管的是五个工具的范围，
  我们只有 `recall_session` 一个。加工具是另一条线，本票只让这一个的范围可配。
- **不要把 `visibility` 做成 per-agent**：它明确写着 Gateway-wide、不能按 agent 设
  （`docs/tools/multi-agent-sandbox-tools.md:393`）。做成分 agent 的会多出一个"哪一层赢"的问题，
  而我们只有一个 agent 命名空间，没有收益。
- **不要顺手做沙箱钳制**（`agents.defaults.sandbox.sessionToolsVisibility="spawned"`）：
  我们没有沙箱内的 agent，那条钳制在这里没有落点。
- **不要用 `findEventsByUserId` 顶替跨会话查询**：语义不是一回事，而且会静默查空（见"要做"第 2 条）。

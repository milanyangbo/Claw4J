# 0030 权限模型照 OpenClaw 重建：三个正交控制 + 十一条策略管线

- Status: proposed
- Date: 2026-11-17
- Supersedes: 本文件 2026-11-17 的前一稿（核到实际实现后有九处对不上，见「前一稿错在哪」）

- 修订（2026-11-19，票 10）：审批的四个实现类（`ApprovalGate` / `ConversationApprovalGate` /
  `ApprovalService` / `JdbcApprovalLedger`）与两个端口（`ApprovalLedger` / `ApprovalDecider`）
  收成一个端口 `Approvals` 与一个实现 `JdbcApprovals`，按"请求 / 裁决 / 记录"三个概念排。
  本文裁决四里"保留 `ApprovalGate`"那几句写的是当时的形状，落地以代码为准。

- 修订（2026-11-18）：权限动作表里已经没有 `run_script`——脚本包退化成技能（目录 + `SKILL.md`
  + 支撑文件，由模型用 `Bash` 跑），那个专用执行入口删掉了。**同一天晚些，生成器整层也删了**
  （ADR-0013 标 superseded），所以 `draft_script` 与 `propose_script` 也不存在了：执行相关的动作
  只剩 `exec_command`（`Bash`）。本文提到 `run_script` / `draft_script` 的地方都是当时的形态。


## 背景

权限现在是八环闭合的一条链：

```text
身份解析 → 权限指纹 → 工具可见性 → 子 Agent 策略 → 执行前鉴权 → 审批 → 执行约束 → 审计
Principal   PermissionFP  ToolProfile   SubagentDef    AuthzAdvisor  ApprovalGate  ExecBackend  ToolCallAudit
```

每一环都有实现、有判据、有用例。但它和 OpenClaw 不是同一个形状：

| | OpenClaw | 本仓库现状 |
| --- | --- | --- |
| 模型形状 | **三个正交控制**：`sandbox` 决定在哪跑、`tool policy` 决定哪些工具可见、`elevated` 是 exec-only 逃生门（`gateway/sandbox-vs-tool-policy-vs-elevated.md`） | 一条纵向的链，把"可见性"和"鉴权"分在两个环节 |
| 权限落在哪 | **可见性**。看不到的工具，模型连计划都不会做 | **执行前拒绝**。工具都在清单里，模型调了才被拦（`AuthorizationAdvisor`） |
| 策略层数 | **11 层**，累积收窄（`src/agents/tool-policy-pipeline.ts` 的默认 8 层 + `src/agents/effective-tool-policy.ts` 追加的 sandbox / subagent / inherited 3 层） | 3 层（`profile` / `allow` / `deny`），启动期算一次 |
| 档位语义 | `minimal` / `coding` / `messaging` / `full` 是 **4 个互不包含的显式集合**（`CORE_TOOL_PROFILES`） | 四档同名，但是**累进阶梯** `MINIMAL < MESSAGING < CODING < FULL` |
| 放宽的口子 | `tools.allow` 只收窄，另有 **`tools.alsoAllow`** 专用于放宽（给可选插件工具开口） | 没有。`ToolProfile.allow` 的注释写明"只能收窄" |
| exec 策略 | `mode` 只是预设，底层是 `security`(deny/allowlist/full) × `ask`(off/on-miss/always) × `autoReview`(bool) | 单枚举五档 `DENY`/`ALLOWLIST`/`ASK`/`FULL`（缺 `auto`，`ASK` 语义相反） |
| exec 分层 | global → agent **按字段覆盖**（`applyExecPolicyLayer`），不是收窄 | 只有一层 |
| 审批 | **两层取更严**：`tools.exec.*`（请求）与 host-local approvals（每 agent 的 `security`/`ask`/`askFallback`/`allowlist`） | 一层：`ApprovalGate` 一次性 + TTL |
| 审批三态 | `allow-once` / `allow-always` / `deny`；`allow-always` 往 allowlist **持久化一条按可执行路径匹配的条目** | 只有一次性 |
| 身份授权 | **没有**。它明说 approvals 不是 per-user 边界（`tools/exec-approvals.md`） | `Principal(role, actions, tables)` + `ToolPermission` 登记表 + `RoleDirectory` 这条载入管道，是整条链的起点（本次**整条删掉**，只剩 `userId`） |
| 沙箱 | Docker / SSH / OpenShell 容器，`network: none`、`workspaceAccess: ro\|rw` | 进程级：非 root、cwd 定死、env 过滤、超时 clamp |

**用户 2026-11-17 裁决：权限这块严格按 OpenClaw 重新设计。** 容器化先行、`elevated` 随后；`allow-always` 照它的做法；清单按它的实现算；`elevated full` 跳过审批照抄；执行前鉴权整条删掉、权限只在清单级；审批先做一层；**"我们独有"的维度全部删除（表级数据权限、权限指纹、`user:`/`role:` 键前缀），只保留启动期硬失败一条**。

## 前一稿错在哪

这一稿重核了 `openclaw@2026.6.35` 的 `dist/` 与 `docs/`，前一稿有九处对不上，其中三处是**语义**错：

| # | 前一稿 | 实际 |
| --- | --- | --- |
| 1 | 管线 8 层 | **11 层**。它自己的文档也写 8 层（`tools/multi-agent-sandbox-tools.md`），但那 8 层与代码默认 8 层**不是同一批**：文档含 sandbox/subagent、不含 group/sender；代码默认含 group/sender、不含 sandbox/subagent/inherited。并起来 11 |
| 2 | 四档"同四档同名，当时就是照它做的" | 名字相同、**语义不同**。它是显式集合且**互不包含**：`coding` 不含 `group:messaging`（没有 `message` 工具），`messaging` 不含 `group:fs`/`group:runtime`。我们用累进阶梯，做不出这种组合 |
| 3 | exec 是五档单枚举 | `mode` 是**预设**，展开成 `security` + `ask` + `autoReview` 三个字段；`security: full` 配 `ask: always` 反解回来是 `ask`。另有 `strictInlineEval` 与 `tools.exec.host` 两个没有对应物的键 |
| 4 | 审批是一道门 | **两层取更严**。host-local approvals 与 `tools.exec.*` 冲突时取严，且 host-local 的 `ask: always` **只能加严**，配置侧改不松 |
| 5 | 没有 `alsoAllow` | `tools.alsoAllow` / `agents[].tools.alsoAllow` / `tools.sandbox.tools.alsoAllow` 专用于放宽；它把"收窄"与"放宽"分成两个键，我们只有一个且只能收窄 |
| 6 | 没有工具分组 | `allow`/`deny`/`alsoAllow` 都接受 `group:*` 简写（`group:fs` = read/write/edit/apply_patch，`group:plugins` = 插件与 `bundle-mcp` 暴露的 MCP 工具） |
| 7 | group 层"需 Principal 带群信息" | 它配置在 `channels.<channel>.groups.<id>.tools`，有 `groups["*"]` 默认、有 groupId 候选回落（`chatId:topic:X:sender:Y` → `chatId:topic:X` → `chatId`）、**内部还嵌一层 `toolsBySender`**，且调用方传入的 groupId 被 `resolveTrustedGroupId` 校验后丢弃 |
| 8 | 审批第二层不存在 | `askFallback`（deny/allowlist/full，默认 deny）、`autoAllowSkills`（默认 false，隐式便捷白名单）、YOLO 要**两层同时开**（`security=full` + `ask=off` + host `askFallback=full`）。它的机制如此；本仓库裁决**先只做一层**，见裁决四 |
| 9 | 管线之前没有别的过滤 | 有：`applyModelProviderToolPolicy` 里的三件——`filterLocalModelLeanTools`（本地小模型瘦身）、`shouldSuppressManagedWebSearchTool`（按 provider 摘 `web_search`）、`isApplyPatchAllowedForModel`（`apply_patch.allowModels`），以及 gateway 传入的 `gatewayRequestedTools` |

## 先定口径：什么算"严格按"

它只有三个控制，且明确不做 per-user 授权、不做数据范围。**用户 2026-11-17 裁决：我们照它的口径来，不保留自己的维度。**

### 总原则（用户 2026-11-18 定调，全篇按这条读）

> 这一轮只做**大模型层的三层过滤**：存在性（这个工具有没有）→ 画像（这类工具允不允许）→ 策略（具体哪几个可见），
> 外加执行前的那道闸（approvals）。
> **业务与工具层面的细粒度权限隔离——不管是 ACL、还是用户角色——都是第二步。**

所以本 ADR 里出现的"删掉"，先分清是哪一种，三种性质完全不同：

| 类别 | 包含什么 | 本 ADR 的处理 | 将来 |
| --- | --- | --- | --- |
| **① 本轮要建** | 三层过滤、exec 三段式与审批闸、档位集合化、十一条管线、`toolFilter` | 六阶段 + 票 07 | —— |
| **② 第二步** | 知识库检索的 ACL、业务数据范围（`querySales` 的租户条件）、**用户角色**（`roles.<角色>` 那套）、行级/列级隔离 | **先删干净**，在缺口表记成"第二步" | 在工具实现、检索器或独立授权组件里新建一层，不往回塞进 agent 框架 |
| **③ 不再做** | 出站暴露（`claw4j.mcp.server.*`） | 整块下线（票 08） | 不做。理由见裁决二末尾——那层挑选与入参三层争同一个问题，且没有对位 |

**②与③的区别要记住**：②是"放错了层，先拿掉、以后在对的地方重建"；③是"这件东西本身就不该有"。
把②写成"缺口没了"或把③写成"以后再补"，都会误导后来的人。

具体删六样：

- **`ToolPermission` 整个体系**——删。三个类型一起：`ToolPermission`、`ToolPermissionRegistry`、`Approval`。理由逐分量不同：`action`（能不能发邮件）与 `tables`（能不能查 `sales_order`）是**业务系统的授权问题**；`approval` 虽然不属于业务语义，但它的形态是"装配期静态声明这个工具要不要人先批"，而它的做法是**执行时按 `security` / `ask` / 命令分析动态判**（`requiresExecApproval`），票 01 落地后这套静态声明必然作废。全仓只有 exec 一处声明了 `REQUIRED`，正是要被三段式取代的那一处。
- **角色目录（`RoleDirectory` 与它那条链）——整块删**（用户 2026-11-18 裁决）。角色是"业务权限的载入管道"：`claw4j.channel.http.roles.<角色>` 里写的就是"能读哪些表、能跑哪些动作"，再经**五个通道**的 `PrincipalResolver`（http / wecom / feishu / slack / dingtalk）与 `ConfiguredRoleDirectory` 翻译成 `Principal`。`ToolPermission` 删掉之后这份 grant 没有内容可装，**它物理上也活不下来**（`Principal.of(userId, role, tables, actions)` 的四个参数少了三个）。删：`RoleDirectory`、`ConfiguredRoleDirectory`、`HttpChannelProperties.RoleGrant`、`X-Claw4j-Role` 头、**五个通道各自的 `roles` 配置段**、`Principal.role`、`agent_schedule.owner_role` 列。五个通道全部从"按角色分组的成员名单"退成**一份允许的用户标识白名单**。
- `Principal` 的 `allowedActions` / `accessibleTables` / `canPerform` / `canUse`——删。删完只剩 `userId`（`role` 也没了，见上一条）。
- **MCP server 配置里的权限声明**——删。`claw4j.mcp.servers.<名字>.permission`（默认动作 + 表清单）与 `.tools`（按工具覆盖）两个字段，加 `McpServerProperties.Permission` 这个 record、`McpEndpoints` 里把声明转成 `ToolPermission` 的那一段。它们**就是 `ToolPermission` 的配置形态**，没有第二个消费者——删掉 `ToolPermission` 之后它们只剩一堆读不出来的死配置。
- `PermissionFingerprint`（权限指纹进会话键、缓存分区键、系统提示词）——删。
- 第 8 层的键前缀 `user:` / `role:`——删，改用它的键形状（见裁决三）。

**删掉角色之后的直接后果**：`Principal` 只剩 `userId`，"这是谁"与"他能看到哪些工具"彻底脱钩。同一部署里所有人拿到**同一份清单**，差别只能来自 `toolsBySender`（那层照抄它的键形状，`id:<userId>`）与 agent / 群 / 沙箱那几层。这正是它自己的模型——它的 `resolveEffectiveToolPolicy` 入参里也没有角色。

五个通道一律退成"**允许的用户标识白名单**"，默认**空 = 谁都不进**（与 `ApproverRoster` 空名单=没人能批同一个取向：忘配置的后果必须是卡住，不是放开）。"这个人在不在白名单里"这条**必须留住**——没了它，任何能往通道里发消息的人都能触发这个 agent。

**审批人名单不受影响**：`ApproverRoster` 从一开始就是一张独立的人名单（按 `userId` 比，不挂角色），它跟角色/`ToolPermission` 本来就没有关系，这次一个字不动。

保留一样：**启动期筛空硬失败**（见偏差清单）。

### 分两步走：先大模型层，再细粒度权限（用户 2026-11-18 裁决）

被一起删掉的还有三处"不属于它、但属于业务/数据权限"的落点。删它们不是因为不重要，而是因为它们属于**第二步**：

| 现在的位置 | 现在做什么 | 本 ADR 之后 |
| --- | --- | --- |
| `McpExposure`（出站暴露） | 装配期要求有权限登记、不要求审批、动作必须是 `read`；调用期 `grantedTo` 按外部客户端的 `tables` 再判一次 | **整块下线**（用户 2026-11-18 裁决）：三个类与配置段一起删。它没有对位——`openclaw mcp serve` 暴露的是**会话通道**（让外部客户端读写聊天），不是把 agent 工具交出去；而"交哪几个"这层挑选本身又是一次可见性判定，与入站三层争同一个问题 |
| `HybridDocumentRetriever` | `canUse(read("doc"))` 决定这一轮能不能检索知识库 | 有身份就检索。文档级 ACL 与内容过滤留到第二步 |
| `SalesExampleAutoConfiguration` | `read("sales_order", …)` 声明会碰哪些表 | 工具存在即可调，数据范围由工具实现兜 |

**边界**：本 ADR 只管**大模型这一层**——可见性（tool policy）+ 执行闸（approvals）。**人的细粒度权限与业务数据范围是第二步**，那时在工具实现、检索器或一个独立的授权组件里新增，不再往 agent 框架里塞。

顺序是有意的：先把"模型看得见什么、能做什么"收干净，再谈"这个人能看到哪些行"。两件事混在一起正是现在这套 `ToolPermission` 的由来——一个 record 同时回答"模型能不能调"和"这个人能不能查这张表"，两个问题的答案来源完全不同，谁都没法单独演进。

所以"严格按"落成三条：

1. **结构照抄**：三个正交控制 + 一条累积收窄的策略管线，配置键与语义逐条对齐。
2. **它没有的东西直接删，不换个名字塞进管线**。业务数据的可见性不靠授权层——**靠工具自己的实现**（工具的 SQL 里带不带租户/范围条件，是那个工具的责任，不是框架的责任）。
3. **收窄与放宽分开表达**：`allow` 收窄、`alsoAllow` 放宽，各自独立——这是它用一个键的差别换来的可解释性，照抄。

判断标准只有一条：**能在清单级表达的一律放清单级**；清单级表达不了的（数据行、列），不做。

## 裁决一：档位从"累进阶梯"改成"显式集合"

它的四档是四个并列的集合，由每个工具自己声明"我在哪些档里"（`CORE_TOOL_DEFINITIONS` 的 `profiles` 字段，`listCoreToolIdsForProfile(profile)` 按 `includes` 筛）：

| profile | 集合内容 |
| --- | --- |
| `minimal` | `session_status` |
| `coding` | `group:fs` `group:runtime` `group:web` `group:sessions` `group:memory` `cron` `image` `image_generate` `music_generate` `skill_workshop` `video_generate`（代码里另加 `bundle-mcp`） |
| `messaging` | `group:messaging` `sessions_list` `sessions_history` `sessions_send` `session_status`（另加 `bundle-mcp`） |
| `full` | `allow: ["*"]`，等价于不限制 |

关键是 **`coding` 不含 `group:messaging`**，**`messaging` 不含 `group:fs`/`group:runtime`**：一个"只跑代码、不发消息"的部署它表达得出来，我们表达不出来。我们的 `CODING` 必然包含 `MESSAGING`（`ToolTier` 的类注释自己承认"做不出只跑脚本、不许读消息的部署"）。

改法：

- 工具声明从 `required: ToolTier`（最低需要哪一档）改成 `profiles: Set<Profile>`（我在哪几档里）。`ToolTier` 保留四个名字，删掉 `allows()` 的序号比较——那是阶梯语义的唯一凭据。
- **默认档位保持 `full`，不改**。核过：`resolveCoreToolProfilePolicy(undefined)` 直接 `return`（不过滤），所以它**运行时的默认是"不限制"**；`coding` 只是 `ONBOARDING_DEFAULT_TOOLS_PROFILE`——本地 onboarding 向导写进新生成配置里的默认值，不是运行时语义。我们对齐的是运行时，因此 `full` 就是对齐后的值；等哪天有了初始化向导，向导再写 `coding`。
- 补齐 `alsoAllow`，先看清它在哪合并：**不是一条独立管线**，而是每一层的 `allow` 与同层 `alsoAllow` 先合成一份策略。这个合并在 `pickSandboxToolPolicy` 里统一做——global / agent / byProvider / group / sandbox 的 tools 全过它；profile 层另走 `mergeAlsoAllowPolicy`，子 Agent 走 `mergeConfiguredSubagentAllow`。
- 合并函数 `unionAllow` 有一个反直觉处，必须照抄：**一层里只有 `alsoAllow`、没有同层 `allow` 时，给出的 allow 是 `["*", ...alsoAllow]`**（带 `IMPLICIT_ALLOW_ALL_FROM_ALSO_ALLOW` 标记）。也就是说这一层等于"不设限"，不是"只放这几个"。
- 它同时取消了"隐式放宽"：配置里写了 `allow` 或工具段、却被当前档位挡住时，它只打一条告警——*that no longer implicitly widen the profile. Add alsoAllow: [...] explicitly*（issue #47487）。也就是说档位是硬天花板，要放宽只能 `alsoAllow`。

### 我们的四档（2026-11-18 按"画像"重排，不是按旧标签平移）

| 档 | 成员 |
| --- | --- |
| `minimal` | 地基七件：`toolSearchTool`、`AskUserQuestionTool`、`recall_session`、`TodoWrite`、`systemStatus`、`get_goal`/`create_goal`/`update_goal` |
| `coding` | 上面这些 + `Bash` 三件、`Read`/`Grep`/`Glob`（`Write`/`Edit` 按开关）、`WebFetch`/`WebSearch`、`run_script`、技能生成器、`schedule_task` 三件、`Task`、`Skill`、**外部工具** |
| `messaging` | 上面这些中的地基七件 + **外部工具** |
| `full` | 不筛（`["*"]` 的等价物），但**仍要求每个工具声明画像** |

两条依据：`cron` / `subagents` / `skill_workshop` 在它那里**只在 `coding`**；外部工具（`bundle-mcp`）在 `coding` 与 `messaging` **都加**、`minimal` 隐藏。

`systemStatus` 与三个目标工具是落地时补进 `minimal` 的：它们只读自己的进程与对话状态，与 `recall_session` 同一类，任何画像里少掉它们都会让纯聊天部署丢掉能力。

**"外部工具桶"的落法不是占位名**。它的 `bundle-mcp` 是一个虚拟工具 id 挂在画象成员表里；我们的工具名是运行时的（MCP 一连才知道），照抄会多一层"占位名 → 真名"的映射。所以改成把外部工具的**画像**直接声明成 `{coding, messaging}`，`group:plugins` 只作为 `allow`/`deny` 里"一次点名那批外部工具"的简写存在。效果一样，少一层。


一个必须点明的坑：我们旧的 `MESSAGING` 标签是**"最低档"语义**（= 不是最简档），它的 `messaging` 是**"客服画像"**。同名不同义，直接平移会把 `schedule_task` / `Task` / `Skill` 从 `coding` 里切掉——那不符合它的口径，见票 02。

## 裁决二：策略管线十一条，逐层累积收窄

`buildDefaultToolPolicyPipelineSteps` 出 8 步，`applyFinalEffectiveToolPolicy` 再追加 3 步：

| # | 层 | 配置键 | 现状 |
| --- | --- | --- | --- |
| 1 | 部署档位 | `tools.profile`（`agents[].tools.profile` 覆盖全局，并入本层，不单列） | 有（`ToolProfile.tier`，但无 per-agent 覆盖） |
| 2 | 按模型档位 | `tools.byProvider.<key>.profile`（`agents[].tools.byProvider.<key>.profile` 覆盖） | **新增** |
| 3 | 全局白/黑 | `tools.allow` / `tools.deny` | 有 |
| 4 | 按模型白/黑 | `tools.byProvider.<key>.allow/deny` | **新增** |
| 5 | 按 agent | `agents.<id>.tools.allow/deny` | 部分（子 Agent 有白/黑，但没有"按 agent 装一份独立部署"这一层） |
| 6 | 按 agent × 模型 | `agents.<id>.tools.byProvider.<key>.*` | **新增** |
| 7 | 按渠道群 | `channels.<channel>.groups.<id>.tools.*` | **新增** |
| 8 | 按发送者 | `tools.toolsBySender` | **新增**（键取自 `Principal.userId` 与通道） |
| 9 | 沙箱工具策略 | `tools.sandbox.tools` / `agents[].tools.sandbox.tools`（仅沙箱会话有值，非沙箱时为空、该步跳过） | **新增** |
| 10 | 子 Agent 策略 | `tools.subagents.tools` + 会话里存的 role | 部分（`SubagentDefinition.selectTools` 只有白/黑，没有 role） |
| 11 | 继承策略 | `inheritedToolPolicy`（从父会话） | **新增** |

五条必须照抄的语义：

- **每层只减不增**：`filterToolsByPolicy` 顺序作用，`before → after` 逐层收窄；该层真的删掉工具时才发一条 `auditToolPolicyFilter`（没删就没有条目）。
- **单层内 `deny` 先判**：`makeToolPolicyMatcher` 先看 deny，命中即拒；`allow` 为空则全放，非空则**只有命中 allow 的才放**。
- **`apply_patch` 的特殊别名**：`allow` 里含 `write` 时 `apply_patch` 一并放行；但 `deny: ["write"]` **不拦** `apply_patch`（要全拦得 deny `group:fs` 或逐个列）。这个不对称是它文档明写的坑。
- **`byProvider` 的键是 `provider` 或 `provider/model`**（`google-antigravity` 与 `openai/gpt-5.4` 都合法）。
- **`agents[].tools.sandbox.tools` 若设置，是替换 `tools.sandbox.tools`**，不是叠加；`agents[].tools.profile` 同理覆盖全局。

第 10 层比看上去多一半：**子 Agent 的工具面由"会话里存的 role"与"配置的 allow/deny"共同决定**。`resolveSubagentDenyListForRole(role)` 给出一张固定 deny 清单——所有子 Agent 都禁 `gateway` / `agents_list` / `session_status` / `cron` / `sessions_send`，`role === "leaf"` 再追加 `subagents` / `sessions_list` / `sessions_history` / `sessions_spawn`；而显式 `allow`/`alsoAllow` 里点名的工具**能覆盖这张清单**。我们只有 `SubagentDefinition.selectTools` 的白/黑名单，没有 role 这一层。

管线**之前**还有一层，不在这 11 步里：`applyModelProviderToolPolicy` 里的按模型裁剪——`filterLocalModelLeanTools`（本地小模型瘦身）、`shouldSuppressManagedWebSearchTool`（按 provider 摘 `web_search`）、`isApplyPatchAllowedForModel`（`apply_patch.allowModels`）。也要照抄成一个前置步骤，否则"本地小模型不该看到的工具"仍然会进清单。

比它还早、且**不是策略层**的，是**存在性过滤**：`mcp.servers.<name>.toolFilter.include/exclude`（条目是 MCP 工具名与 `*`），在工具被投影成 agent 工具**之前**生效。三者分工要分清——`toolFilter` 决定**有没有**（被它排掉的，`allow` 加不回来）、档位与外部工具桶决定**这类允不允许**、管线决定**具体哪几个可见**。混在一起，"为什么加了 `allow` 还是看不见"会变成查不出来的问题。落点（票 07 已实现）：`McpToolProvider#install` 在拿到 `listTools()` 之后、建 callback 与权限表之前筛一遍，每次重连都重新筛（不是第一次那份快照）；脚本包、生成器、业务示例各有一份同名配置，`toolNames()` 只含存活工具。

还有一条本来会与这三层重复的路：**出站暴露**（`claw4j.mcp.server.*`，把本进程的工具通过 MCP 交给外部客户端）。它被**整块下线**（用户 2026-11-18 裁决）。

理由不是"多一层"，而是这层挑选本身**又是一次可见性判定**：

```text
交出去的只能是清单里的工具 →
  ├ 交全部：等于清单，没有新增信息，只是把 `Bash` 也默认交出去
  └ 交一部分：就是第二份 allow 列表，与 `tools.allow` 争同一个问题，
              而且必须靠人写（默认空 = 功能不可用，默认全 = 最危险的东西默认出门）
```

它也没有对位可抄：`openclaw mcp serve` 暴露的是**会话通道**（让外部客户端读写它的聊天），不是把 agent 工具交出去。

删掉之后只剩一条路：**工具可见性由入站三层决定，没有第二个消费者**。这不是把问题藏起来，是把一个与三层争同一件事的重复判定去掉——两份判定并存时，"为什么外部客户端能调、模型看不见"「为什么模型看得见、外部客户端不行」会变成两个都要查的问题。

## 裁决三：删掉执行前鉴权，权限只在清单级

**现状**：清单在启动期按部署档位算一次，所有人一样。被收窄的那个人在清单里照样看得到 `delete_file`，模型会去调，然后被 `AuthorizationAdvisor` 拒。

```text
用户：删掉上周的测试数据
模型：看到 20 个工具 → 调 delete_file → 拒绝 → 换 exec 试试 → 又拒绝
       → 回答"我没有删除权限"
```

**目标**：清单按 `(档位 × 各层策略)` 每轮算，看不到的工具模型不会去计划。

```text
用户：删掉上周的测试数据
模型：清单里只有 8 个只读工具 → 调 query_sales 确认有这批数据
       → 回答"这批数据还有 3 条，删除工具不在我的清单里"
```

**它怎么做的**（核到代码）：`resolveEffectiveToolPolicy({ config, sessionKey, agentId, modelProvider, modelId })` —— 主维度是会话、agent、模型，**入参里没有 sender**；sender 是独立一层，`toolsBySender` 未配置时 `resolveToolsBySender` 直接返回、不加限制。执行时它**不再判一次**，只有 tool policy 这一道。

所以照它的做，三件事一起发生：

- **`AuthorizationAdvisor` 整条删除**。不是"改名就行"：审批也不该待在 ChatClient 层（见裁决四），所以它两个分支一个都不留。
- **`ApprovalService` 保留**，但要改成它的两条路径（同步返回工具结果 / 异步注入会话 follow-up）。我第一版写"删掉续跑"，是错的——**它的异步审批就是靠续跑**（见裁决四）。
- **`ToolPermission` 整个体系删除**（`ToolPermission` / `ToolPermissionRegistry` / `Approval`）。工具不再声明"我碰哪几张表、要做哪个动作、要不要人先批"，因此没有表级、动作级、静态审批这三层。五个消费方与一处借用见阶段 4。
- **`PermissionFingerprint` 删除**，会话键回到"只按 peer 分段"。这一条**作废 ADR-0029 代价第 3 条**（"`owner` 里的权限指纹保留……这个差异是安全的、有意的，本 ADR 不改"），0029 需要补一条修订记录指向这里。
- 子 Agent 本来就是在父清单上收窄（`SubagentDefinition.selectTools`），不再单独判鉴权。
- **清单算漏就是越权，没有第二道兜底**。这是它的取舍，写进「代价」。

**为什么能整条删，而不是"权限挪个地方"**：`actions` / `tables` 是**业务语义**——"能不能读 `sales_order`"回答的是业务系统的授权问题，与"模型能看见哪些工具、能不能执行命令"是两个层面。把它放进 agent 框架会长出第二套业务权限：角色的授权表要与业务系统同步维护，工具一多，"谁能干什么"就退化成一份工具清单（`ToolPermission` 的类注释自己也承认这点）。OpenClaw 只管 LLM 这一层——可见性（tool policy）+ 执行闸（approvals）；业务数据范围留给工具实现与业务系统。

**这条判定作废 ADR-0007**（"授权模型：Principal 决定一次工具调用能不能发生"）。0007 的前提是"执行前鉴权是权限的唯一落点"，本 ADR 把它换成"清单 + 执行闸"，`Principal` 从"能执行什么的答案"退成"这个调用是谁发起的"。0007 标 `superseded by 0030`，`docs/design/authorization.md` 那篇设计文档跟着重写——留着它就是留一份与代码相反的说明。

第 8 层的键形状照 `tools.toolsBySender`：

```text
channel:<channel>:<senderId>     渠道 + 发送者
id:<senderId>                    发送者
e164:<phone>                     手机号
username:<handle>                账号名
name:<displayName>               显示名
*                                兜底
```

匹配顺序同它：channel+id → id → e164 → username → name → `*`，命中即用。我们只有 `id`（`Principal.userId`）与 `channel` 两种可用来源，其余三种键型不伪造、不配。`agents[].tools.toolsBySender` 命中时**覆盖**全局那一层（先查 agent 段，命中就不再看全局），不是叠加；但**空 `{}` 不算覆盖**——空对象编译不出策略，直接回落全局。照抄这个回落语义。

**一条安全前提**：它的文档写明 *sender values must come from the channel adapter, not message text*。我们的 `Principal` 已经是 `PrincipalResolver` 从通道解析出来的，这条天然满足，但要在代码注释里钉住——第 7 层 group 同理，群标识必须从会话（`sessionKey` / `spawnedBy`）解析，调用方传进来的只当候选。

## 裁决四：审批是执行前的闸，不是回答后的改写

### 触发点与形态（照抄）

核到代码，它的审批**不在模型对话层**，在工具执行前：

```text
exec 工具内部：
  requiresAsk = requiresExecApproval({ ask, security, analysisOk, allowlistSatisfied, durableApprovalSatisfied })
                || heredoc || inline-eval(strictInlineEval) || allowlist 计划不可用 || 安全审计压制命令
                （后四项是调用点另外 OR 上去的，不在 requiresExecApproval 体内）
    → 需要 → requestExecApprovalViaSocket（socket 路径默认 15s 超时）→ { type:"decision", decision }
    → 跑 / 拒 / 持久化 allow-always
    → 超时或 UI 不可达 → askFallback

非 exec 工具（插件 / MCP）：
  before-tool-call 钩子返回 { block, blockReason } 或 { requireApproval, ... }
    → requireApproval 走 gateway 的 plugin.approval.request（two-phase，可 defer）
```

五条要照抄的性质：

1. **触发条件与"谁在调"无关**。`requiresExecApproval` 只看 `ask` / `security` / 命令分析结果；不传 sender、不看角色。这跟"业务权限"是两件事——它是"这个动作本身有多重"。**"谁能批"另有一层**：渠道侧的 `authorizeActorAction({ senderId, approvalKind })` 用 `dm.allowFrom` 判批准人，没配名单时"同聊天隐式允许"。那层管的是批准动作，不是触发条件。本仓库**已有更严的名单**（`ApproverRoster`，P03：空名单=没人能批、提问人不能自批），缺的只是它那种**按渠道分组解析**的形态与"未配置即允许"的默认——后者我们不要，见缺口。
2. **决定只有三种**：`allow-once` / `allow-always` / `deny`。
3. **拒绝面分两条路径**，别混成一条：
   - **同步**（命令在前台跑）：拒绝以一条工具结果回到模型（`Exec denied (...)`），同一回合继续。
   - **异步**（命令转后台，或审批在超时前没等到人）：结果以一条 **follow-up 消息注入同一会话**恢复执行。被拒时那条 prompt 明确写着 *"An async command did not run. Do not run the command again. There is no new command output."*；批准并完成时写 *"An async command the user already approved has completed... continue from this result"*。会话不可恢复时降级为直接投递一句简短拒绝。
   - **子 Agent 与 cron 会话不投递**：文档原文是 *"Denials for subagent sessions are not posted back into the subagent."*，代码里的抑制条件是 `isSubagentSessionKey || isCronSessionKey`，且**只对"拒绝"生效**——批准并完成的结果照常投递。
4. **等不到人时按 `askFallback`**（`deny` / `allowlist` / `full`，默认 `deny`），不是无限等。
5. **审批时冻结一份"要跑什么"，执行时用冻结的那份**：请求里带 `systemRunPlan`（规范化的 command / cwd / session 上下文），批准后转发就用它；调用方在批准之后改了 `command` / `rawCommand` / `cwd` / `agentId` / `sessionKey`，网关按 *approval mismatch* 直接拒。这比"执行前再算一遍指纹比对"更强——**它比对的是冻结件，不是重算件**。

对我们意味着：审批的落点是 exec 工具内部（它才拿得到 argv / cwd / 是否 heredoc），以及**只对声明了要审批的工具**套一层 `ToolCallback` 装饰器；两处都不是 `CallAdvisor`。`ApprovalGate` 保留为"决定存储 + 一次性 + TTL"，`ApprovalService` 保留并改成上面两条路径的文案与降级。

### 三态

`allow-always` 不是"审批表里多一个 scope"，而是**往 approvals 文件的 `allowlist` 里持久化一条条目**（`tools/exec-approvals.md`）：

```json
{
  "id": "B0C8C0B3-...",
  "pattern": "~/Projects/**/bin/rg",
  "argPattern": "^-n ",
  "source": "allow-always",
  "lastUsedAt": 1737150000000,
  "lastUsedCommand": "rg -n TODO",
  "lastResolvedPath": "/Users/user/Projects/.../bin/rg"
}
```

落盘时写的是 `{ id, pattern, argPattern?, source, lastUsedAt }`；`lastUsedCommand` / `lastResolvedPath` 是**命中一次之后**才补的，纯粹给人看。文档里还列着一个 `commandText`，但代码在加载时把它剥掉（`stripAllowlistCommandText`）——那是旧字段，别照抄进我们的表。

关键在 `pattern`：**按解析后的可执行文件路径匹配**（条目也可能是不带路径的命令名 glob），不是命令字符串。整命令哈希只作为兜底出现在两种形态：`=command:<sha16>`（`exact-command`）与 `=node-command:<sha16>`（分段都能表达之后补的那条）。

但比"一条命令一个 pattern"更细一层：`resolveAllowAlwaysPatternCoverage` **按命令段（segment）逐段**生成，每段一个 `{ pattern, argPattern? }`。所以 `git add . && rg TODO` 会写两条；`argPattern` 是可选的参数约束；只有当所有段都能表达时 `complete` 才为真，那时再补一条整命令的 node 审批 pattern。我们的表结构要跟着加一列。

三条约束照抄：

1. **`ask` 为 `always` 时，`allow-always` 既不抑制提示、也不出现在选项里**：`resolveExecApprovalAllowedDecisions` 在 `ask=always`（或本次只能一次性持久化）时只返回 `["allow-once", "deny"]`。不是"弹了但你选 always 没用"，是**卡片上根本没有这一项**。
2. **inline-eval 不持久化**：`bash -c "任意命令"` 的可执行文件是 bash，按它放行等于放行一切。落法：`JdbcExecAllowlist` 里遇到内联求值（或 heredoc）的段，**整条不落库**——与"一段表达不出来就整条不记"同一处置。另有 `tools.exec.strictInlineEval`——开启后这些形态一律变成 approval-only，即使解释器本身在白名单里：`python/python2/python3/pypy`（`-c`）、`node/nodejs/bun/deno`（`-e`/`--eval`/`-p`/`--print`）、`awk/gawk/mawk/nawk`（`-e`/`--source`，含位置参数形态）、`ruby`（`-e`）、`perl`（`-e`/`-E`）、`php`（`-r`）、`lua`（`-e`）、`osascript`（`-e`）、`find`（`-exec`/`-execdir`/`-ok`/`-okdir`）、`make/gmake`（`-f`/`--eval`/`-E`）、`sed/gsed`（`-e`）。比"解释器 + 参数"更宽，照抄这张表，别只抄七个解释器。
3. **`askFallback` 默认 `deny`**：需要提示但没有 UI 可达时默认阻断。我们已有等价物（`ApprovalGate.denyAll()`），但它是**可配的三档** `deny`/`allowlist`/`full`，照抄成配置项，不是写死。另有一条容易漏的夹取：它把 `askFallback` 与本次生效的 `security` 取更严（`minSecurity(effectiveSecurity, host.askFallback)`），所以兜底档不可能比生效的 `security` 更宽。

落法：新增 `exec_allowlist` 表（`id / pattern / arg_pattern / source / last_used_at / last_used_command / last_resolved_path`，**不建 `command_text`**——那份它自己都在剥离），一条记录对一段。**落地后的口径与这一行不同，以代码为准**：配置的 `claw4j.tools.exec.allow` 按整条命令匹配（前缀 + 拒绝控制符），表里的条目按命令段逐段匹配（每段取首个 token 作 `pattern`，复合命令要求每段都有条目），两者都用 `ExecPolicy.permits`。没做成"合并进同一个列表"是因为配置允许带参数的前缀（`ls -la`），表只存可执行名——硬合并会让其中一边的语义被另一边改写。

**为什么这一阶段没有 agent 维度（阶段 6 补）**：它的条目挂在 `agents.<id>.allowlist` 下，并与 `agents["*"].allowlist`（wildcard 桶）**合并**（`[...wildcard, ...agent]`）——它永远有 agent 维度，即使只跑一个 agent 也写 `agents.main`。我们现在没有 agent 身份来源（`claw4j.agents.<id>` 是阶段 6 才出现的），所以表里不建 `agent_id`：现在建，运行时只能填一个假的固定值，那就是凭空造一个它靠"多 agent 真的存在"才成立的维度。**这一阶段的全局表语义上等于它的 wildcard 桶**，单 agent 下与"只对 main 生效"结果一致。阶段 6 随 agent 分层一起加 `agent_id`，唯一索引扩成 `(coalesce(agent_id,''), pattern, coalesce(arg_pattern,''))`，`ExecApprovalPort.Request` 加 `agentId`。

另有一条照抄：**`autoAllowSkills`**（默认 `false`）——它是"技能声明的命令自动进白名单"的隐式便捷白名单，与 `SkillTrust` 的审批是两件事，落在 exec 白名单这一侧。

### 两层：先做一层（用户 2026-11-17 裁决）

它自己写得很明确：

> Effective policy is the **stricter** of `tools.exec.*` and approvals defaults; if an approvals field is omitted, the `tools.exec` value is used. Host exec also uses local approvals state on that machine - a host-local `ask: "always"` ... keeps prompting even if session or config defaults request `ask: "on-miss"`.

即：**配置侧不能把本机的审批收紧松掉**。请求侧（`tools.exec.*`，跟着仓库走）与本机侧（approvals 文件，跟着机器走）分开，是因为两者的主人可能不是同一个人。

**裁决：这一条先按一层实现。** 理由是结构性的，不是省事：

- 它的第二个层能存在，前提是**控制面与执行宿主是两个主体**（gateway 一台、node 多台）。我们是单进程部署，没有这个分裂。
- "机器主人"在我们这里没有对应的实体——部署者、配置者、运维是同一个人。硬造一个本机文件，结果是多一份没人维护的配置，以及一处"两份配置谁生效"的争论。
- 它防的那个场景（"提交一份 config 就把机器上的收紧改松"）在这里不成立：能改 config 的人本来就有权改那台机器。

所以落法：

| | 它的做法 | 我们的做法 |
| --- | --- | --- |
| 请求侧 | `tools.exec.*` | `claw4j.tools.exec.*`（现有键就是它，与它的 `tools.exec` 同名同形，不改名） |
| 本机侧 | approvals 文件里的 per-agent `security`/`ask`/`askFallback`/`allowlist` | **不做**，见偏差清单 |
| 合并 | 取更严 | 只有一份，无需合并 |
| `askFallback` | 本机文件的字段 | 我们的**配置项**，默认 `deny`（"没有 UI 可达"这件事单层也存在） |
| YOLO | 三样同时到位 | `security=full` + `ask=off`（即 `FULL` 档），一处即达 |

**缺掉的能力要认**：本机侧"只能加严不能放宽"这条现在没有。将来补的触发条件是出现多宿主（一台控制面管多台执行机），或客户明确要求"运维不能自己放开 exec"。届时形态是：审批状态表加 per-host 段，与该机的配置取更严。

## 裁决五：exec 补 `auto`，并把 `mode` 还原成预设

它的 `mode` 不是枚举本体，而是三个字段的预设（`resolveExecPolicyForMode`）：

| `mode` | 展开 |
| --- | --- |
| `deny` | `security: deny` / `ask: off` / `autoReview: false` |
| `allowlist` | `security: allowlist` / `ask: off` / `autoReview: false` |
| `ask` | `security: allowlist` / `ask: on-miss` / `autoReview: false` |
| `auto` | `security: allowlist` / `ask: on-miss` / `autoReview: true` |
| `full` | `security: full` / `ask: off` / `autoReview: false` |

反解也照抄（`resolveExecModeFromPolicy`）：`security=deny` → `deny`；`allowlist` 且 `ask=off` → `allowlist`；`full` 且 `ask≠always` → `full`；其余一律归到 `ask`。这条反解的意思很直白——**`security: full` 配 `ask: always` 仍然是"每条都要问"**。

我们现状与改法：

| 项 | 现状 | 改法 |
| --- | --- | --- |
| `security` | 无 | 新增 `deny` / `allowlist` / `full` |
| `ask` | 混在 `Mode` 里 | 新增 `off` / `on-miss` / `always` |
| `autoReview` | 无 | 新增；`auto` 档置 true |
| `Mode` | 四档 | 保留为预设，加 `AUTO`；`ASK` 的 authorize 分支改成"命中即放行、未命中走审批" |
| 分层 | 一层 | global → agent **按字段覆盖**（`applyExecPolicyLayer`），不是收窄：agent 配了 `mode` 就整体替换，配了 `security`/`ask` 就逐字段覆盖 |
| `host` | 无 | 新增 `auto` / `sandbox` / `gateway` / `node`：决定 exec 在哪跑，与 sandbox 正交 |
| `strictInlineEval` | 无 | 新增 |

`auto` 档在无复核器时等同 `ask`：它的链路是"白名单命中直接跑 → 未命中先走原生复核器 → 复核不了回落人工"，没有复核器就自然落在最后一环；它的 `defaultExecAutoReviewer` 本来也是这么写的——什么都不判，直接返回 `{ decision: "ask" }`（注释原话：*Auto mode must never become a static allowlist*）。

**用户 2026-11-17 裁决：复核器先不做，但保留以后扩展的能力。** 落法是把"决策链"留完整、只把最后一环填成"回落"：

- `AUTO` 这个枚举值**保留**，配置里写得出来，等于 `security: allowlist` + `ask: on-miss` + `autoReview: true`。
- 留一个复核器接口，签名照它给复核器的那份入参（`command` / `argv` / 解析后的可执行路径 / `cwd` / env 键名 / `host` / `reason` / `analysis` / `agent`），出参是 `{ decision: allow|ask, risk, rationale }`。
- 默认实现恒返回"回落人工"，因此**没有复核实现时 `AUTO` 与 `ASK` 行为完全一致**（白名单外照弹）；将来接模型只替换实现，不动枚举、配置与调用点。

`ASK` 改回之后，**白名单不再是硬天花板**：一次人工批准就能让白名单外的命令跑起来。要硬边界就用 `allowlist` 档。

## 裁决六：沙箱先行，然后 `elevated`（四档照抄）

### 沙箱

- `mode`：`off` / `non-main` / `all`
- `backend`：`docker`（`network: none`）/ `ssh` / `openshell`
- `scope`：`session` / `agent` / `shared`；`workspaceAccess`：`ro` / `rw` / `none`
- `agents[].sandbox.*` 覆盖 `agents.defaults.sandbox.*`（`scope` 解析为 `shared` 时 `docker` / `browser` / `prune` / `ssh` 这四段的 per-agent 覆盖被丢弃）

第 9 层 `tools.sandbox.tools` 有一个容易踩的后果，它的文档专门写了一段：**沙箱会话里 MCP/插件工具是额外闸门**。`sandbox.mode` 为 `all` 或 `non-main` 时，sandbox 的工具白名单里没有 `bundle-mcp`（或 `group:plugins`、或具体 server 的 glob），MCP server 会照常加载成功、但工具在发请求前就被过滤掉。我们接 MCP 的方式是 `McpToolProvider`，所以要显式把这类工具放进沙箱层的白名单，否则"接上了却看不见"。

### elevated

`elevated` 的语义是"我在沙箱里，这一次要跑到沙箱外"，它自己写明**只在沙箱化时改变行为**（`tools/elevated.md`）。所以顺序是硬的：先 P17 容器化，之后 `tools.sandbox.tools.*`，再之后 `elevated`。

四档照抄：

| 指令 | 行为 |
| --- | --- |
| `/elevated on` | 在沙箱外跑，**保留审批** |
| `/elevated ask` | 同 `on`（别名） |
| `/elevated full` | 在沙箱外跑，**跳过审批** |
| `/elevated off` | 回到沙箱内 |

`full` 那一行的完整条件是**请求侧与执行宿主的审批文件同时**为 `security: full` + `ask: off`——宿主文件更严（例如 `ask: always`）时照样弹。我们只做一层，"宿主文件"这一半不存在，所以单层下表里的行为就是它的等价物；将来补 host 层时，这个条件要一并补回来，别当成无条件跳过。

启用前提照抄：`tools.elevated.enabled: true` **且** 该 sender 在 `tools.elevated.allowFrom` 名单里（按通道分组，支持 `*`；它的 policy 检查对 `*` 单独告警、条目过多也告警——那条检查链我们不做，见「不做」）。`agents[].tools.elevated.*` 是**再加一道**，不是覆盖：`allowFrom` 要求 sender 同时命中全局与 per-agent 两份名单，`enabled` 两边都要开（文档原文 *sender must match both global + per-agent*）。

`full` 跳过审批这一条按原样实现。它的风险由启用前提承担：只有 enabled 且被允许的人能开，且是**会话级指令**，不是永久配置。另外 approvals 整体是叠在 tool policy 与 elevated gating 之上的；**跳过它的路径有两条，都是会话级**——`/elevated full`（且宿主审批文件也得是 full/off），以及 `/exec security=full ask=off`（文档原话 *skips approvals by design*）。后者我们已经由 `security=full` + `ask=off` 这一档覆盖，不另做指令。

## 实现过程

六个阶段，每阶段可独立验收、可独立回滚。落地票在 `.scratch/permission-alignment/issues/`，
`01`–`06` 与下面六个阶段一一对应，另加 `07`（存在性过滤）与 `08`（出站下线）两张独立票
（依赖关系见该目录的 `spec.md`）。

### 每张票完工前都要过三条（缺一条不算完）

1. **`./mvnw clean verify` 全绿**——含 checkstyle / spotbugs / 结构规则 / 覆盖率下限。
2. **`README-capability-tests.md` 里对应的节按原样跑通**——它是 curl + 看 `claw4j.tool.calls` 指标的
   端到端清单（真实模型、真实通道、真实指标），`verify` 覆盖不到。哪一节受本阶段影响，就在本阶段
   改那一节并重跑。
3. **`docs/capability-tests.md` 里对应的行**（机器向全量回归，含库表断言与负向用例）同样跑通。

**第 2、3 条跟着票走，不是最后统一回归。** 原因很具体：这两份清单里有一批断言是拿旧权限模型写的
（"`read-only` 会被拒"、"`querySales` 要表权限"、"脚本包全部要 admin"、"定时任务到点按角色重算权限"），
`ToolPermission` 删掉之后它们**不会报错，只会静默失效**——清单还在、跑还是绿的、但已经验不到任何东西。
这是最坏的一种退化：门禁在，判据没了。

已核出的失效点逐条列在票 09；那张票负责收尾与全量重跑，但**每张票要改的是自己那一节**，
等票 09 一次改完，中间几张票的"跑通"就是假的。

**阶段 1 · exec 语义（最小、独立）**

- `ExecProperties` 加 `security` / `ask` / `autoReview` / `host` / `strictInlineEval`；`Mode` 保留为预设并加 `AUTO`。
- 复核器只留接口与默认实现（恒回落人工），不接模型；`AUTO` 因此与 `ASK` 等价，调用链、审计、配置键都先铺好。
- `applyExecPolicyLayer` 的等价物：`claw4j.tools.exec`（global）→ `claw4j.agents.<id>.tools.exec`（agent）按字段覆盖。
- `allow-always`：建 `exec_allowlist` 表，审批卡片第三个选项；`redeem` 命中一次性批准时正常兑现，选 always 则**按命令段逐段写** `pattern`（必要时带 `arg_pattern`），不是写一条整命令。
- 判据：`ask` 档下白名单外的命令弹审批、批了能跑；选 always 后同可执行文件的第二次调用不再弹；`ask: always` 时仍弹**且卡片上没有 `allow-always` 选项**；`security: full` + `ask: always` 仍逐条问；复合命令（`a && b`）选 always 后两段都进表；`AUTO` 档在默认复核实现下与 `ASK` 行为一致，审计里记的是"复核器未配置、回落人工"而不是"审批通过"。
- 回滚：枚举 + 一处分支 + 一张表。

**阶段 2 · 档位集合化**

- `ToolTier` 删 `allows()` 序号比较；工具声明改 `profiles: Set<Profile>`。
- 默认档位**不动**，仍是 `full`（= 它运行时的"不限制"）；`coding` 只留给将来的初始化向导去写。
- 加 `tools.alsoAllow`（并进**同层**的 allow，含"该层只有 `alsoAllow` 时补 `*`"那条）与 `group:*` 简写表（`group:fs` / `group:runtime` / `group:web` / `group:agents` / `group:plugins` / `group:openclaw`）。
- 判据：`coding` 档里没有消息类工具、`messaging` 档里没有代码类工具（照它两张集合）；`alsoAllow` 能把一个档位外的工具加回来，`allow` 不能；某层只配 `alsoAllow` 时该层等于不设限（不是"只放这几个"）。

**阶段 3 · 管线十一条 + 每轮重算**

- 顺序按裁决二的表；`byProvider` 键支持 `provider` 与 `provider/model`。
- 第 7 层 group：**群上下文从会话解析，不从参数取**——`resolveGroupContextFromSessionKey(sessionKey)` 与 `spawnedBy` 各解一次，调用方传入的 `groupId` 只作候选；候选集 = 会话里记的 groupIds + 派生候选（`chatId:topic:X:sender:Y` → `chatId:topic:X` → `chatId`），按序查 `channels.<channel>.groups.<id>.tools`，`groups["*"]` 兜底；每个群里先查 `toolsBySender` 再查 `tools`。
- 第 8 层 `tools.toolsBySender` 键形状 `channel:<ch>:<senderId>` / `id:` / `e164:` / `username:` / `name:` / `*`，匹配顺序同它；我们只用 `channel` 与 `id` 两种来源，其余不伪造。`agents[].tools.toolsBySender` 命中即覆盖全局，空 `{}` 回落全局。
- 前置步骤：按模型能力裁剪（本地小模型、`web_search` 抑制、`apply_patch.allowModels`）。
- `select` 的调用点从 `AgentAutoConfiguration` 启动期挪到每轮组装 prompt 之前。
- 判据：同一部署配两份 provider 策略，切换模型后清单不同；同一 `id:` 下两个人（未配置时）清单相同；每层只要真的删了工具就有一条审计。

**阶段 4 · 删掉执行前鉴权（整类删）**

- 删 `AuthorizationAdvisor`——两个分支都不留：鉴权那半没有落点（清单里看不到），审批那半已在阶段 5 挪到执行前闸（**所以本阶段必须排在阶段 5 之后**，见下面那条）。
- **删三个类型**：`ToolPermission`、`ToolPermissionRegistry`、`Approval`。`Principal` 只留 `userId`。
- **四个消费方逐个收口**（上一稿只登记了两个，其余两个是查代码补的）：

  | 消费方 | 现在用它做什么 | 收口方式 |
  | --- | --- | --- |
  | `AuthorizationAdvisor` | `canUse` + `needsApproval` | 整类删 |
  | `ScheduleAuthorization` | `needsApproval` + `canUse` + `currentOwner`（走 `RoleDirectory`）+ 自己拼 `denialOf`（还借 `AuthorizationAdvisor.DENIAL_PREFIX`） | 只留"需要人批的工具不能定时跑"一条；`currentOwner` 随角色目录一起删，身份直接用任务行里的 `owner_user_id`；`denialOf` 整段删 |
  | `HybridDocumentRetriever` | `canUse(read("doc"))` 决定这一轮能不能检索知识库 | 删判定，有身份就检索；ACL 进「缺口」 |
  | `PrincipalSnapshot` | 审批记录里序列化了 `tables` / `actions` / `role` | 三个字段删，旧记录缺字段按空集/null 解析 |

- **第五块不是收口，是整块下线**：出站暴露（`claw4j.mcp.server.*`，见裁决二末尾）。**这一块拆在票 08 单独做**——它只是删一个功能，与本阶段的三张前置无关，绑在一起会让它白等。删 `mcp/expose/` 整个包——`McpExposure`、`McpExposureProperties`、`McpServerExposureAutoConfiguration`——以及：

  | 位置 | 删什么 |
  | --- | --- |
  | `application-mcp-stdio.yml` | 整个文件（它就是为了跑这条 server 而存在的 profile） |
  | `application-local.yml` | `claw4j.mcp.server` 段 |
  | `ApplicationAssemblyTest` | 断言那三个 bean 的用例 |
  | `claw4j-capability/pom.xml` | **依赖不动**：`spring-ai-starter-mcp-client` 传递引的 `mcp-core` 同时提供 client 与 server 侧类，client 还在用；改的是那段"client 与 server 两侧都用这一条"的注释 |
  | 文档 | **只有四处**：`README.md`（特性列表那一条 + "当 MCP server 用"整节）、`GLOSSARY.md`（"反向 MCP"与"外部客户端身份"两条）、`docs/design/extension-points.md`（第八章与总结表那一行）、ADR-0020 的 P11 段。`docs/design/capability-layers.md`、`docs/capability-tests.md`、`README-capability-tests.md`、ADR-0014 讲的是 `capability.mcp.servers`（复数、入站），**一个字都不改**——全文搜 `mcp.server` 会把这些全带出来，因为 `mcp.servers` 里含这个子串 |

  注意别把**入站**那侧一起删：`McpServerProperties`（`claw4j.mcp.servers.*`）、`McpToolProvider`、`McpEndpoints`、`ProcessStdioMcpEndpoint` 都是"连别人的 MCP server"，与这条无关。

  **已实现（2026-11-18）**：包、`application-mcp-stdio.yml` 与 `logback-mcp-stdio.xml`（同一个 profile 的日志配置，功能没了它只剩误导）、`claw4j.mcp.server` 整段、`ApplicationAssemblyTest` 那两条都删了。防回潮的三条一起钉在 `BuildContractTest#outboundExposureIsGone`（类不在、配置段不在、注册表里没有 `mcp.expose.`）：只钉一条的话，另外两条回来时没有任何东西会红。

- **角色目录整块删**（用户 2026-11-18 裁决）。它不是一个消费方，是**业务权限的载入管道**——`claw4j.channel.http.roles.<角色>` 里写的就是"能读哪些表、能跑哪些动作"，`ToolPermission` 删掉之后这份 grant 没有内容可装：

  | 位置 | 删什么 |
  | --- | --- |
  | `domain/port/RoleDirectory` | 整个接口 |
  | `channel/http/ConfiguredRoleDirectory` | 整个类 |
  | `HttpChannelProperties` 的 `RoleGrant` / `roles` / `defaultRole` | 三个字段全删；`X-Claw4j-Role` 头（`HttpPrincipalResolver.ROLE_HEADER`）跟着删 |
  | **五个通道的 `PrincipalResolver`**（`Http` / `Wecom` / `Feishu` / `Slack` / `Dingtalk`） | 全部改成只从用户标识建 `Principal`，不再查 grant；原"不在任何角色里就拒"改成"不在白名单里就拒" |
  | **五个通道各自的 `roles` 配置段**（`HttpChannelProperties.roles`、`WecomProperties.roles`、`FeishuChannelProperties.roles`、`SlackProperties.roles`、`DingtalkProperties.roles`）及各自的 `RoleGrant` | 全删，换成一份 `users` 白名单 |
  | `McpServerProperties.Server` 的 `permission` / `tools` + `Permission` record | 全删；`McpEndpoints` 里把声明转成 `ToolPermission` 的那一段一起删 |
  | `Principal.role` | 字段删。连带 `AuditEvent`（审计里的角色段）、`PrincipalSnapshot`、`ScheduleStore`（`agent_schedule.owner_role` 列，迁移里 drop）、`ScheduleScanner`/`ScheduleTools` 的"认不出角色"分支 |
  | 文档与配置 | `application.yml` 的五个 `roles` 段与 `mcp.servers.*.permission` / `.tools`；`GLOSSARY.md` 的角色条目；两份能力清单里几十处 `-H 'X-Claw4j-Role: admin'`（票 09） |

  **这条链物理上必须同票**：`RoleDirectory.current` 返回的就是 `Principal.of(userId, role, tables, actions)`，删 `ToolPermission` 之后它编译不过——不存在"先删权限、再删角色"的分步。

- **还有一处不是消费方，是借用**：五处把 `provider.permissions().keySet()` 当成"本 provider 提供哪些工具"的**唯一来源**来登记档位（`ScheduleTools`、`ConfiguredMcpServers`、`ScriptPackage`、`SkillGenerator`，以及 `McpToolProvider` 自己同步 `callbacks` 与 `declared` 那一段）。票 07 已把工具名清单独立成 `toolNames()`，本阶段直接切过去——**这一步不做，删 `permissions()` 会连带炸掉五处档位登记**。
- 删 `PermissionFingerprint`：实际有**四个**语义使用点——会话键（`SessionKeys`）、缓存分区键（`ChatClientAgentPort`）、系统提示词"数据范围"段（`AgentContextAdvisor`）、待答问题的创建者校验（`PendingQuestions`）；另有一处只是借它的 `sha256` 算内容哈希（`KnowledgeIndexer`），把那个静态方法挪走即可。
- 判据：全仓库 grep 不到 `AuthorizationAdvisor` / `ToolPermission` / `ToolPermissionRegistry` / `canReadTable` / `canPerform` / `PermissionFingerprint` / `RoleDirectory` / `RoleGrant` / `X-Claw4j-Role` / `owner_role`；`claw4j.mcp.servers.*`（连别人的 server）照常可用；知识库检索对任何有身份的人都能跑；只带 `X-Claw4j-User` 的请求照常工作（不再需要角色头）。（`McpExposure` 的消失归票 08，不在本阶段判据里。）

**阶段 5 · 审批挪到执行前**

- `ApprovalService` 保留，本阶段只改它的落点与文案；阶段 4 删 `AuthorizationAdvisor` 时必须它已经在执行前接上了。
- 落点一（exec）：在 exec 工具内部，按它的 `requiresAsk` 那一组判——`requiresExecApproval`（`ask=always`，或 `ask=on-miss` 且 `security=allowlist` 且白名单未命中 / 分析失败），再 OR 上调用点那四项：heredoc、`strictInlineEval` 命中 inline-eval、allowlist 执行计划不可用、命令触碰安全审计压制配置。这些条件需要 argv、cwd、命令分析结果，只有工具内部拿得到。其中"安全审计压制命令"那一类我们没有对应概念，不做；其余照抄。
- 落点二（**声明了要审批的工具**，主要是 MCP 与将来需要人确认的那几个）：`ToolCallback` 装饰器在执行前问一次，拒绝时返回一条工具结果（含理由），**不改写模型回答**。读、检索这类 core 工具**不挂这个闸**——它也没有对应物（它的 `write`/`edit` 走的是沙箱与 tool policy，不是审批）。**"哪些工具要人批"从此由这个装饰器的登记决定，不再是 `ToolPermission.approval` 的装配期声明**；阶段 4 删掉 `Approval` 之后，这份名单是它唯一的落点。
- `askFallback` 三档配置化（`deny` / `allowlist` / `full`），默认 `deny`；等不到人按它处理，不无限等。
- `allow-always` 时按 `ask` 决定选项集合：`ask=always` 只给 `allow-once` / `deny`。
- 审批记录里存一份**冻结的执行计划**（exec 路径：规范化 command + cwd + session/agent；脚本类再加被调文件的 sha256），执行时用这份冻结件，不是重算一遍参数再比指纹。批准之后任何一项变了都按 mismatch 拒。
- `ApprovalService` 补两段文案：批准完成 → "已批准的异步命令完成，别重跑，接着往下做"；被拒 → "异步命令没跑，不要重试，也不要声称有新输出，向用户解释原因"。会话不可恢复时降级为直接投递一句简短拒绝；**子 Agent 与 cron 会话不投递**（只针对"拒绝"，批准完成的结果照常投）。
- 判据：批准一条 `run.sh` 后改一个字，同一冻结件不再命中；被拒时模型在**同一回合**收到工具结果并继续（没有第二轮问答）；`askFallback=deny` 且无 UI 可达时命令被拒而不是被放行。
- 一组负例（这一阶段最容易糊过去的）：`security=full` + `ask=off` 时不再弹审批；`security=full` + `ask=always` 时**仍然逐条弹**（反解回 `ask`）；`ask=always` 时卡片上没有 `allow-always`；没有配置 `askFallback` 时默认拒。

**阶段 6 · 容器化、host、elevated**

- P17 本体；`tools.sandbox.tools`（含 MCP 的 `bundle-mcp` 闸门）；`tools.exec.host`；最后 `elevated` 四档 + `allowFrom`。
- 判据：容器内 `curl` 出网失败；沙箱白名单内的命令在容器里跑通；未在白名单里放 `bundle-mcp` 时 MCP 工具不可见（这是要显式验的负例）；`/elevated full` 在 enabled 且名单内时跳过审批、名单外直接拒。

## 偏差清单

只列"它没有、我们必须有"的。按用户 2026-11-17 裁决，这条清单**只剩一条**——其余"我们独有"的维度（`ToolPermission` 整套：表级数据权限、动作级授权、静态审批声明，以及权限指纹、`user:`/`role:` 键前缀）已删掉，改成它的做法。

| 偏差 | 理由 |
| --- | --- |
| 启动期筛空直接失败 | 它是告警 + 审计（`rememberToolPolicyWarning` 去重 + 逐层 `auditToolPolicyFilter`）。两种并存：启动期硬失败，运行期软告警 |

## 缺口

分两类。第一类是**第二步要重建的**（不是缺漏，是分层推进），第二类是**它有、我们暂不做**。
混成一张表会让后来人把"计划内"读成"漏了"，或者把"不做"读成"以后补"。

### 一、第二步要重建的（用户 2026-11-18：本轮只做大模型层）

这一轮把"业务与工具层的细粒度隔离"整层拿掉了——**不管是 ACL 还是用户角色**。拿掉是为了让
代码只表达一件事：模型能看见什么、能不能执行。这一层将来在**工具实现、检索器或独立授权组件**里
新建，不往回塞进 agent 框架。

| 第二步要重建的 | 本轮删掉的东西 | 现在是什么状态 |
| --- | --- | --- |
| 知识库的文档级 ACL | `HybridDocumentRetriever` 的 `canUse(read("doc"))` | 有身份就能检索，看得到整个知识库 |
| 业务数据范围（租户、行、列） | `ToolPermission.tables` + 各个工具的 `read("sales_order", …)` 声明 | 由工具实现自己兜（`SalesSqlGuard` 只读、SQL 里带不带租户条件）——**这一步其实现在就在做，只是不由框架保证** |
| 用户角色（按角色给权限） | `RoleDirectory` 那条链 + 五个通道的 `roles` 配置 | 五个通道只剩"允许的用户标识白名单"；同部署所有人同一份清单（除非 `toolsBySender` 逐人列） |
| 内部工具的按工具权限覆盖 | `claw4j.mcp.servers.*.permission` / `.tools` | 只有"这个工具在不在清单里"这一层 |
| 定时任务的权限核验 | `ScheduleAuthorization.currentOwner` + `canUse` 比对 | 到点只用 `owner_user_id`；工具不在当时的清单里就调不到，可**任务不会停用**，主人也收不到通知 |

**为什么现在就删、而不是留着不用**：留着就是两套判定并存。`mcp.servers.demo.permission.tables`
这种字段配上去不会报错、也不会生效，下一次排查"为什么它没拦住"要花半天；`roles` 更糟——
它长得像权限，实际只影响"能不能进通道"，下一个人会以为改它就能收紧工具。

### 二、它有、我们暂不做

逐条写清触发条件，免得以后当成"本来就没有"。

| 缺口 | 现状 | 什么时候补 |
| --- | --- | --- |
| 审批的 host-local 第二层 | 只做一层（裁决四） | 出现多宿主（一台控制面管多台执行机），或客户要求"运维不能自己放开 exec" |
| 审批人名单的**按渠道解析** | 已有名单且比它严（`ApproverRoster`，P03：空名单=没人能批、提问人不能自批、单份全局名单）；缺的是它按渠道分组（`dm.allowFrom.<provider>`）解析这几样 | 出现"每个渠道一批审批人"的真实需求时；它"未配置即允许"的默认不跟 |
| `auto` 档的复核器实现 | 档位与扩展接口保留，**默认实现恒回落人工**，所以 `AUTO` 目前与 `ASK` 等价（用户 2026-11-17：先不做，留扩展） | 接入可用的复核模型时，替换默认实现即可，不动枚举与配置 |
| **按 agent / 按 agent × 模型两层**（第 5、6 层） | 我们没有"一个进程里装多个 agent"；人设只覆盖画像，不覆盖 allow / deny。补出来会是一层永远拿不到 `agentId` 的配置 | 真要在一个进程里跑多个 agent（各带工具集）时，先有 agent 标识，再补这两层 |
| **按渠道群一层**（第 7 层） | 没有渠道群概念，`ToolPolicyContext.groupId` 恒为 `null` | 接上真正的群聊通道、且群标识稳定时；候选链已写进 `ToolPolicyContext` 的注释 |
| **沙箱工具层**（第 9 层） | 壳体留了（`sandboxed` 字段与 `tools.sandbox` 的位置），沙箱本体是阶段 6 | 票 06 落地时接上 |
| **前置的按模型裁剪** | 它的三件事在这儿没有对位：没有"本地小模型"概念；`web_search` 也不存在"装了但按 provider 摘掉"这一档（搜索工具要么装配了要么没有） | 出现"同一个部署给小模型也配一份"的真实需求时 |
| **`apply_patch` 的别名不对称没照抄** | 我们没有 `apply_patch`——`Write` 与 `Edit` 是两个独立工具名，不存在"允许 write 就顺带允许 apply_patch"这层关系；照抄会得到一条永不生效的规则 | 引入一个"合并式写工具"时 |
| **降级到备模型时清单不重算** | `providerKey` 取配置里的 `primary`；路由在请求内降级时清单已经算完了 | 降级路径也要按备模型筛时，得把 provider 从 model 层透出来 |
| **兜底档决定的是"这一轮跑不跑"，不是"这条请求被拒"** | `askFallback` 在"这一轮拿不到结论"时生效（默认拒），但审批行仍是 `pending`、卡片仍可点：人晚一点批了，那段对话照常开新一轮、命令照常执行。OpenClaw 的超时是把请求收口成终态，我们不是——因为我们的审批入口天生异步，若在这里落终态，卡片会永远批不了。要真"拒"，用拒绝按钮或让请求过期 | 出现"就地能回答"的入口时一起改（与下一条同一触发条件） |
| **前台命令不在原地等审批**（票 05 落地时的形态差异） | 它的 `requestExecApprovalViaSocket` 原地等 15 秒，操作者可能就坐在控制台前。我们的审批入口只有卡片与 HTTP，都是异步的，原地等只是把一轮挂 15 秒再走同一条路。所以 `ExecApprovalPort#decide` 一律回"这一轮没有结论"，由 `askFallback` 处置（默认拒），批准之后走同一会话的下一轮 | 真出现"就地能回答"的入口（终端、控制台）时补：那时等的代价才换得回"同一回合继续" |
| **`allow-always` 的 pattern 是命令名，不是解析后的路径** | 表里存首个 token（`rg`），不存 `/usr/local/bin/rg`；`arg_pattern` 列留着但恒为 `null`。取 basename 会让 `/tmp/evil/rg` 与 `rg` 共用一条，所以路径形态直接不落库 | 需要"同一可执行文件的不同路径分开批"或"按参数收窄"时补：那时解析软链接、写 `arg_pattern`，并加一条按参数的正则匹配 |
| **冻结件里没有 session / agent / 被调文件哈希** | 只冻结 `command + cwd`：那两样才决定这条进程怎么起。session / agent 记在审批行自己的 `session_key` 上（它们决定结论投回哪里，不改执行），脚本哈希没有对应入口（脚本走 exec 时命令里只有一个路径）。`exec_allowlist` 的 `agent_id` 也归阶段 6 | 有多 agent 身份（阶段 6）与"脚本体审批"时补，那时 `FrozenPlan` 加字段、mismatch 判定跟着加一条 |
| **批准完成的文案没照抄"已完成的异步命令"** | 我们没有"批准之后由系统替你跑"这条腿：命令是模型重新调起来执行的。照抄那句会让它以为已有输出、跳过执行直接汇报。所以写的是"批准了，现在还没有任何输出，去执行它" | 真做了"批准即由系统执行冻结件"那条路时，文案跟着换回去 |
| **会话不可恢复时不直投，而是告诉点按钮的人** | 它降级成"直接投递一句简短拒绝"。我们唯一的外部出口是 `AgentPort` 与通道推送，直投要一份投递登记；而点按钮的人就在等回话，回给他最直接 | 出现"审批人是机器人/脚本、点按钮的人不等回话"的部署时补直投 |

## 代价

1. **清单算漏就是越权**。执行前鉴权删掉之后没有第二道兜底，这是它的模型，我们接受。
2. **没有表级与动作级权限**：工具能被看到，就能读它实现里能读的全部数据、做它实现里能做的动作。数据范围与动作范围从此是**工具的职责**——`query_sales` 的 SQL 里带不带租户条件、带不带 `where` 范围，决定了用户看到什么。框架不再兜这一层，写工具的人必须自己兜。**知识库检索同时失去这一层**：有身份就能检索，文档级 ACL 是第二步的欠账。出站暴露那一路已整块下线，没有这个消费者，所以不欠。
3. **没有权限指纹**：权限变了会话不重置、缓存不按权限分区。两个具体后果——降权之后，历史里那些高权限时读到的数字仍在上下文里；同一个部署里权限不同的人可能命中同一份语义缓存。ADR-0029 的那半条保护随之取消。
4. **按发送者配置没有"角色"这一档**：同一角色的一百个人要逐条列 `id:`，或者用 `*` 兜底（那等于放开给所有人）。它本来就这样，我们照抄。删掉角色目录之后这条更硬——**同一部署里所有人拿到同一份清单**，差别只能来自 `toolsBySender`（逐人列 `id:`）与 agent / 群 / 沙箱那几层；通道侧也不再按角色分组，企微只剩一份成员白名单。想表达"这个人是只读、那个人能写"，只能逐人列 `id:`，或者等第二步的细粒度权限。
5. **档位从阶梯变集合，现有工具声明要全部重写**：`required: ToolTier` → `profiles: Set<Profile>`。写错的后果不是报错，是某个工具在某个档里悄悄消失，所以阶段 2 要先立一张"每个工具 × 每个档"的期望可见性表来对照。
6. **`ASK` 改回之后白名单不再是硬边界**：一次批准就能跑白名单外的命令。要硬边界用 `allowlist` 档。
7. **`allow-always` 的 pattern 宽**：批一次 `rg`，这个可执行文件的所有命令都放行。`ask=always` 与 `strictInlineEval` 是缓冲，不是根治。
8. **审批只有一层，等于 config 单方面说了算**：能改 `claw4j.tools.exec.*` 的人就能放开 exec，没有"机器侧兜一层"。在单进程部署里这是可接受的（配置者与机器主人是同一人）；一旦变成多宿主，"提交一份 config 就放开多台机器"就是真问题。
9. **YOLO 一处即达**：`security=full` + `ask=off`（`FULL` 档）就完全不问。这是"先做一层"的直接后果——它那边要三处同时到位，我们一处。所以 `FULL` 档要配一条启动期 WARN，把"本机侧没有第二道"这句话说出来。
10. **清单按发送者算之后，prompt 前缀随发送者变化**：默认 `toolsBySender` 为空时不受影响；配了之后同一部署里不同人的前缀不同，缓存命中率按人数下降。
11. **审批多绑执行上下文，跨机器复用会失效**：A 机器批的脚本，到 B 机器上 cwd 或可执行路径不同就重批。这是对的，但会让"同一件事换台机器"多一次审批。
12. **阶段 3 的改动面比听起来大**：清单从"启动期一次"变成"每轮一次"，任何依赖"清单在启动后不变"的代码都要重新看（检索元工具、子 Agent、MCP 活视图）。
13. **容器化会改变 exec 的失败形态**：今天能跑的命令在容器里可能跑不了（没有网络、没有宿主路径）。这是隔离的代价，不是 bug。
14. **沙箱层多一道 MCP 闸门**：接上 MCP server 之后工具可能"加载成功但看不见"，要先往 `tools.sandbox.tools` 里放 `bundle-mcp`。这个坑它的文档专门写了一段。
15. **审批一律走"登记 + 晚到"这一条路，没有原地等**：它的前台命令会在原地等 decision（15 秒超时），因为操作者可能就坐在控制台前；我们的卡处与接口天生是异步的，原地等只会把一轮对话多挂 15 秒再走同一条异步路（票 05 落地时的形态，已记进缺口）。所以今天的情形是：命令这一轮不跑、回一条工具结果说"在等谁批"；人点了之后在**同一段对话**里开新一轮，模型重新调这个工具、兑现那条批准才真的跑。代价是"批完到真的执行"之间多一次模型调用；好处是只有一条路径要维护文案。
16. **`AUTO` 目前名不副实**：档位与扩展接口都在，复核器没有，写 `auto` 得到的行为与 `ask` 一模一样。审计里能区分（记的是"复核器未配置、回落人工"），但看配置的人不一定看审计——文档与示例里得把这句写出来，别让人以为 `auto` 更聪明。

## 退场条件

- 如果 `byProvider` 两层上线后从未被配置过，删掉——多一个维度就多一处"配错了没人发现"。
- 如果 group 层因为渠道拿不到稳定的群标识而只能靠猜，不做，把缺口记进 `docs/agents/guardrails.md`。
- 如果阶段 3 之后缓存命中率跌到不可接受（多用户且配了 `toolsBySender`），把 `toolsBySender` 收窄成"只影响检索元工具的可见性"，不整体重算清单。

## 不做

- **审批的 host-local 第二层**：先只做一层（裁决四），触发条件满足时再补。这是"知道它这么做、但先不做"，不是遗漏。
- **`tools.fs.requireWorkspaceOnly` 这个开关**：它默认 `false`、可配 `true` 来要求"文件工具只在工作区内"。我们是恒开（`FileToolsProperties` 拒 `..` 与软链接跳出，`Grep`/`Glob` 带 `allowedDirectory`），不提供关掉它的配置。比它严，是有意的。
- **`openclaw policy` / `doctor` 那条检查链**：我们对应的东西是启动期校验（已有：工具未声明档位、allow∩deny、筛空即失败）+ `scripts/verify.sh`，不另做一套 lint 命令。
- 它的 gateway 控制面、`/exec` 会话级默认值、`gatewayRequestedTools`：我们没有多宿主与多会话类型，也没有"控制面要求特定工具子集"的调用方。
- ACPX / Codex harness 的权限映射（`Codex Guardian`、`approvalPolicy` / `approvalsReviewer`）：没有对应运行时。
- `openshell` 后端：我们有 docker 与 ssh 两条就够，`openshell` 是它的托管沙箱产品。
- 插件权限与版本契约：见 ADR-0021「明确不做」。
- 行级、列级数据权限：**连表级与动作级都没有了**（本次删掉 `ToolPermission` / `ToolPermissionRegistry` / `Approval` 三个类型）。要控数据范围与可做的动作就在工具实现里做——SQL 里带租户与范围条件，而不是指望授权层。
- **知识库检索的 ACL**：本轮一起删（用户 2026-11-18 裁决），属于第二步，见「缺口」。
- **出站暴露（`claw4j.mcp.server.*`，把本进程的工具交给外部 MCP 客户端）**：**整块下线**（用户 2026-11-18 裁决）。理由见裁决二末尾：它那层挑选与入站三层争同一个问题，且没有对位可抄。注意与 `claw4j.mcp.servers.*`（连别人的 MCP server，入站）不是一回事，后者保留。

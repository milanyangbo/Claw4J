# 04: 删掉 `ToolPermission` 整套，权限只在清单级

**What to build:** 把"工具都在清单里、调了才被拒"这一层整个删掉，权限只由清单与执行闸表达。
`ToolPermission` 不是"删两个字段"，而是**三个类型一起消失**；外加四个消费方收口与一处借用切换。

（出站暴露的下线拆在**票 08**，与本票无依赖关系——它只是删一个功能，不该等本票的三张前置。）

| 删什么 | 为什么 |
| --- | --- |
| `ToolPermission`（`action` / `tables` / `approval`） | `action` 与 `tables` 是业务系统的授权问题；`approval` 是装配期静态声明，票 01 的三段式是执行时动态判，这套声明必然作废 |
| `ToolPermissionRegistry` | 没有可登记的东西了 |
| `Approval` | 全仓只有 `ExecAutoConfiguration:89` 一处声明 `REQUIRED`，正是票 01 要取代的那一处 |
| **角色目录整条链** | 角色是"业务权限的载入管道"：`roles.<角色>` 里写的就是 `tables` 与 `actions`。权限删了，这份 grant 没有内容可装 |

`Principal` 删 `allowedActions` / `accessibleTables` / `canPerform` / `canUse` / **`role`**，只留 `userId`。

**Blocked by:** 03（清单必须已按各层收窄，否则删掉鉴权会出现"无判定"窗口）、05（**审批必须先挪到执行前**，否则删 `AuthorizationAdvisor` 会让 exec 短暂完全无审批——那是上线级缺口，不是中间态）、07（`toolNames()` 要先接上，见下面"借用"一节）。

**Type:** task

**Status:** resolved

**裁决来源：** ADR-0030 §先定口径（删四样 + 两步走）、§裁决三、§实现过程·阶段 4。

## 边界：这一段做什么、不做什么（用户 2026-11-18 裁决）

**做**：只保留大模型这一层的权限——可见性（tool policy）+ 执行闸（approvals）。

**不做**：人的细粒度权限、业务数据范围。两处落点跟着删，都记在缺口"细粒度权限（第二步）"：

| 落点 | 现在 | 删后 |
| --- | --- | --- |
| `HybridDocumentRetriever` | `canUse(read("doc"))` 判这一轮能不能检索知识库 | 有身份就检索 |
| `SalesExampleAutoConfiguration` | `read("sales_order", …)` | 工具存在即可调 |

## 四个消费方（漏一个就是编译不过或被静默削弱）

| 消费方 | 现在用它做什么 | 收口方式 |
| --- | --- | --- |
| `AuthorizationAdvisor` | `canUse` + `needsApproval` | 整类删（审批半已在 05 挪走） |
| `ScheduleAuthorization` | `needsApproval` + `canUse` + `currentOwner`（走 `RoleDirectory`）+ 自己拼 `denialOf`，还借 `AuthorizationAdvisor.DENIAL_PREFIX` | 只留"需要人批的工具不能定时跑"一条；`currentOwner` 随角色目录删，身份直接用任务行的 `owner_user_id`；`denialOf` 整段删 |
| `HybridDocumentRetriever` | `canUse(DOC_PERMISSION)`，`DOC_TABLE = "doc"` | 整个 `permitted()` 删掉（只留"没有身份则跳过"那条 warning）；`DOC_PERMISSION` / `DOC_TABLE` 删 |
| `PrincipalSnapshot` | 审批记录里序列化了 `tables` / `actions` | 字段删，旧记录缺字段按空集解析 |

原第五个消费方 `McpExposure` 随出站暴露一起下线，不在本票——见票 08。

## 角色目录整块删（用户 2026-11-18 裁决）

角色目录不是"消费方"，是**业务权限的载入管道**。`claw4j.channel.http.roles.<角色>` 里写的就是
"能读哪些表、能跑哪些动作"，去掉 `ToolPermission` 之后它没有内容可装，**物理上也活不下来**：
`RoleDirectory.current` 返回的就是 `Principal.of(userId, role, tables, actions)`，四个参数少了三个。

| 位置 | 删什么 |
| --- | --- |
| `domain/port/RoleDirectory` | 整个接口 |
| `channel/http/ConfiguredRoleDirectory` | 整个类 |
| `HttpChannelProperties` 的 `RoleGrant` / `roles` / `defaultRole` | 三个字段；`RoleGrant` 里的 `tables()` / `actions()` 随 `ToolPermission` 一起没 |
| **五个通道的 `PrincipalResolver`** | `HttpPrincipalResolver`（`ROLE_HEADER` 与 `resolve` 的第二个参数删掉）、`WecomPrincipalResolver`、`FeishuPrincipalResolver`、`SlackPrincipalResolver`、`DingtalkPrincipalResolver`——全部改成只从用户标识建 `Principal`；"不在任何角色里就拒"改成"不在白名单里就拒" |
| **五个通道各自的 `roles` 配置段与 `RoleGrant`** | `HttpChannelProperties` / `WecomProperties` / `FeishuChannelProperties` / `SlackProperties` / `DingtalkProperties` 各一份，全删，换成一份 `users` 白名单 |
| `McpServerProperties.Server` 的 `permission` / `tools` + `Permission` record | 全删（**这是 `ToolPermission` 的配置形态**，不删就是留一堆读不出来的死配置）；`McpEndpoints:137` 里把声明转成 `ToolPermission` 的那一段一起删 |
| `Principal.role` | 字段删。连带四处：`AuditEvent`（审计里的角色段）、`PrincipalSnapshot`（记录里的 role）、`ScheduleStore`（写 `owner_role`）、`ScheduleScanner` / `ScheduleTools`（"认不出角色"分支） |
| `agent_schedule.owner_role` | 迁移里 drop 这一列 |
| `application.yml` | 五个 `roles` 段（`channel.http` / `wecom` / `feishu` / `slack` / `dingtalk`）、`mcp.servers.*.permission` 与 `*.tools` 全删 |

**五处通道那一刀才是真语义变化**，不是改名：

```text
现在：roles.<角色>.users = [zhangsan, lisi]   → lisi 属于那个角色，拿到它的权限
之后：users = [zhangsan, lisi]                → lisi 在白名单里，能进来；权限由清单决定
```

原来"不在任何角色里 = 进不来"这条**必须留住**——没了它，任何能往通道里发消息的人都能触发这个
agent。所以五份白名单默认值都是**空**，不是 `*`（与 `ApproverRoster` 空名单=没人能批同一个取向）。

**MCP 那两处字段是最容易漏的**：它们不在"权限登记表"那套里，长得像普通配置；
`mcp.servers.demo.permission.tables` 这种写法看起来跟工具可见性无关，实际就是
`ToolPermission` 的另一张皮。漏删的表现是配置还写、启动不报错、但一个字都不生效。

**连带的行为**：`toolsBySender` 未配时，同一部署里所有人拿到同一份清单。想区分人只能逐人列 `id:`。

## 还有一处是借用，不是消费方

五处把 `provider.permissions().keySet()` 当成"本 provider 提供哪些工具"的**唯一来源**来登记档位：

```text
AgentPersistenceConfiguration:341          ToolTierRegistry.sameFor(ScheduleTools.permissions().keySet(), …)
ConfiguredMcpServersAutoConfiguration:80   … provider.permissions().keySet() …
ScriptPackageAutoConfiguration:93          … provider.permissions().keySet() …
GeneratorAutoConfiguration:116             … new SkillGeneratorToolProvider(g).permissions().keySet() …
McpToolProvider#install                    callbacks 与 declared 两处同步
```

票 07 已经把"工具名清单"独立成 `toolNames()`。**本票必须先把这五处切到 `toolNames()`，再删 `permissions()`**，否则是五处编译失败 + 档位登记直接缺失。

## 删 `PermissionFingerprint`

四个语义使用点 + 一处借用：

| 位置 | 用法 | 收口 |
| --- | --- | --- |
| `SessionKeys` | 会话键的 `owner` 段 | 回到只按 peer 分段 |
| `ChatClientAgentPort` | 缓存分区键 | 去掉那一段 |
| `AgentContextAdvisor` | 系统提示词"数据范围"段 | 整段删 |
| `PendingQuestions` | 待答问题的创建者校验 | 改按 `userId` 比较（同一人换角色仍能答） |
| `KnowledgeIndexer` | **借 `sha256(content)` 算内容哈希** | 把静态方法挪到自己的工具类，**别一起删** |

`PendingQuestions` 那条要显式选一个：按 `userId` 比较，还是放弃这道保护。本票选**按 `userId`**——原来靠指纹只是想顺带绑住"权限也没变"，但"谁能答这个问题"本来就该由"谁问的"决定。

## TDD 用例清单

复核：2026-11-18 `./mvnw -o clean verify` 全绿——798 条用例，失败 0 / 错误 0 / 跳过 0，
八个模块全 SUCCESS，覆盖率下限过；`scripts/verify.sh` 第 1 步（删除词闸）0 命中。

票面写下的名字有三条是真的，其余在实现时改了名或根本没有写——下表一律改成**构建产物里的真名**，
没有的标 `⏳` 并写清为什么没有。

| 判什么行为 | 用例名（真名） | 状态 |
| --- | --- | --- |
| 全仓 grep 不到那十个词 | `scripts/verify.sh` 第 1 步（逐词 `grep -Hn`，不走 `\|` 交替） | ✅ |
| 被 `toolsBySender` 收窄的人看不到写工具 | `ToolPolicyAssemblerTest#senderFallsBackToIdThenWildcard`、`#noSenderConfigMeansNoNarrowing` | ⏳ 只到策略层，没有"清单里真的少一个工具"那一条 |
| 会话键不再带权限指纹 | `SessionMemoryTest#sessionIdFollowsIdentity`（断言以 `u-1:` 开头）、`#sessionUserIdCarriesTheOwnerKey` | ✅ |
| 同一用户配置变化后不换会话 | —— | ⏳ 没有"配置"这个概念了，这条判据失去对象（降权不重置会话已进缺口） |
| 只带 `X-Claw4j-User` 能正常对话 | `HttpChatChannelTest#userHeaderAloneIsEnough` | ✅ |
| 缺用户标识仍然拒 | `HttpChatChannelTest#missingOrBlankUserIsRejected`、`#unlistedUserIsRejected` | ✅ |
| 企微：白名单里的人能进 | `WecomChannelTest#aTextMessageIsHandedToTheAgent` | ✅ |
| 企微：白名单外的成员被拒 | `WecomChannelTest#anUnregisteredMemberIsRefusedWithoutCallingTheModel` | ✅ |
| 企微：白名单为空时谁都不进 | —— | ⏳ 只在飞书上有（`FeishuChannelWiringTest#anEmptyAllowlistLetsNobodyIn`），四份是复制粘贴来的，其余四份没写 |
| 其余四通道同样三条 | 飞书齐（`#onlyListedUsersResolveToAPrincipal`、`#anEmptyAllowlistLetsNobodyIn`）；钉钉有"未登记被拒"（`DingtalkChannelTest#anUnregisteredUserIsRefusedWithoutCallingTheModel`、`#aMessageWithoutStaffIdIsNotMistakenForAnUnregisteredUser`）；slack 补了"未登记被拒"（`SlackChannelTest#anUnlistedUserIsRefusedWithoutCallingTheAgent`，本票新增） | ✅ |
| MCP 配置里不再有 `permission` / `tools` 两个键 | —— | ⏳ 键删了，但没有"配上去不会被静默接受"的用例（`McpServersConfigTest` 里没有这条） |
| 定时任务：要人批的工具建任务时被拒 | `ScheduleToolsTest#aToolThatAlwaysNeedsApprovalIsRefused`、`ScheduleFlowTest#aTaskOnAToolThatNeedsApprovalIsRefusedOnTheSpot` | ✅ |
| 定时任务：不需要审批的照常建 | `ScheduleToolsTest#createsATaskAndSaysWhenItWillRunNext` | ✅ |
| 定时任务：只按 `owner_user_id` 起跑 | `ScheduleFlowTest#aDueTaskRunsOnTheOwnersBehalf`、`#eachFiringRunsWithTheOwnersIdentity`、`#aTaskBuiltFromTheConversationKeepsIdentityNotPermissions` | ✅ |
| 旧审批记录（带 tables/actions/role）仍能解析 | `ApprovalFlowTest#legacyApprovalRecordStillParses`（本票新增） | ✅ |
| 待答问题：换个人不能替答 | `AskUserQuestionTest#answererMustMatchTheAsker` | ✅ |
| 知识库：任何有身份的人都能检索 | `KnowledgeRetrievalTest#anyPrincipalCanRetrieve` | ✅ |
| 子 Agent 清单是父清单的子集 | `SubagentDelegationTest#subagentToolsRespectTheDeploymentProfile`、`ToolPolicyAssemblerTest#inheritedPolicyAppliesToChild` | ✅ |

`⏳` 那几行不是"懒得写"，是三种不同原因，别混：

- **失去对象**：`configChangeKeepsSameSession`——"配置变了换不换会话"随指纹下线而不存在。
- **名额卡住**："空名单谁都不进"只写在飞书上。`verify.sh` 卡"每模块 ≤10 个测试文件、
  每文件 ≤20 个方法"，`SlackChannelTest` 补完那条正好 20 个，其余三份要补得先合并别的用例。
- **判据太浅**："看不到写工具"只有策略层两条（`ToolPolicyAssemblerTest`），没有端到端那条；
  MCP 的 `permission` 键删了，但没有"配上去不会被静默接受"那条。

## 验收判据

| 判什么 | 判据 |
| --- | --- |
| 词真的没了 | `grep -r "AuthorizationAdvisor\|ToolPermission\|ToolPermissionRegistry\|canReadTable\|canPerform\|PermissionFingerprint\|RoleDirectory\|RoleGrant\|X-Claw4j-Role\|owner_role" --include=*.java .` 无输出 |
| 档位登记没断 | 启动后清单与删之前逐个工具相同（用阶段 2 那张"工具 × 档"表对照） |
| 行为没退化 | 被 `toolsBySender` 收窄的人问"删掉上周数据"：清单里没有删除工具，回答里不出现"我没有权限"这种话术 |
| exec 审批还在 | 删完之后，白名单外的命令**仍然弹审批**（这条是 05 的交付，本票用来验证顺序没错） |
| 旧数据 | 库里已有审批记录仍可读（一条 fixture）；`agent_schedule` 旧行删列后照常读出 |
| 配置 | `application.yml` 的 `claw4j.channel.http.roles` 与 `claw4j.channel.wecom.roles` 整段删，换成 `claw4j.channel.wecom.users` 白名单（默认空） |
| 文档 | **ADR-0007 整篇被本 ADR 反转**，标 `superseded by 0030`（不是加注，是判定作废）；`docs/design/authorization.md`（53 行，整篇讲 "Principal 决定一次调用能不能发生"）重写或删除；`knowledge-retrieval.md` / `extension-points.md` / `capability-layers.md` 三处引用跟着改；`GLOSSARY.md` 的角色条目删；ADR-0029 代价第 3 条补修订记录；`guardrails.md` 偏差表加"知识库 ACL 已后移" |
| 门禁 | `./mvnw clean verify` 全绿 |
| 清单 | 断点清单在票 09；本票只改它点名属于 04 的那几处（`docs/capability-tests.md` 181–183 / 203 / 286 / 288 / 289 / 318 / 397，`README-capability-tests.md` 153 / §3 / 453–455） |

## 缺口

- **细粒度权限整层空缺**：知识库检索与业务数据范围都没有判定。这是第二步的事，写进 ADR 缺口表与 `guardrails.md`，别让后来的人以为是漏了。
- **没有角色这一层了**：`toolsBySender` 未配时，同一部署里所有人拿到同一份清单。想区分人只能逐人列 `id:`——这是 ADR 代价第 4 条。
- **定时任务不再核任何权限**：到点只用 `owner_user_id`，"角色被删就停用"与"工具权限被收就停用"两条能力都没了（ADR 缺口）。
- **企微从"按角色分组"退成"一份白名单"**：部署若原本靠三个角色分出三种可见范围，改完之后这三组人看到的是同一份清单。
- **清单算漏就是越权**：没有第二道兜底，ADR 代价 1。
- **降权不重置会话**：历史里高权限时读到的内容还在上下文里，ADR 代价 3。
- **"调了才被拒"这件事整批失去对象**：三条用例随它一起没了，不是漏跑——
  - `ApprovalFlowTest#aRefusedCallLandsAsOneRowWithToolAndRole`（越权审计行 `blocked`）删了；
  - `ApplicationAssemblyTest#authzAdvisorIsWiredIntoTheChain`（guest 拿不到那个数）删了；
  - `eval/cases/authz.yaml` **整份删了**（2026-11-18）。它不是"改个判据就能留"：里面六条
    期待一句"没有权限"，而删掉执行前鉴权之后没有任何东西会拒 `querySales`，判据恒假；
    剩下四条正例（华东 / 华南 / 华北 / 退款）与 `sales.yaml`、`knowledge.yaml` 里同一个问题、
    同一个锚完全重复，立意"同一问题换个身份结论必须相反"也随身份扁平化失效。
    删除的连带代价已认：用例总数 51 → 41，`EvalCaseSetTest.MIN_CASES` 跟着 50 → 40；
    `principals.yaml` 的 `guest` 与 `admin` 没人引用了，一起删，并补了一条
    `#everyRosterEntryIsUsed` 防止再长出没人用的身份；控制台不再在"零拒绝类用例"时报
    "越权 41/41"（那是没验过的满分），改成明说本版不判。
- **`audit_event.actor_role` 成了死列**：它原来从 `Principal.role` 来，现在没人写，也不该有人写。
  列还在（V6），查询里恒为 `NULL`。要么下一步 drop，要么在视图里注明"永远空"。
- **评测的身份从四档收到两档**：`principals.yaml` 现在只剩 `analyst` 与 `read-only`（被用例
  真正引用的那两档）。"同一个问题换个人问、结论必须相反"这套结构在 ADR-0030 之后没有落点——
  身份不再决定看得见什么。要重建，得先有能按人收窄清单的用例（`toolsBySender` + 新判据），
  那是另一件事，不在本票。

## 二审（2026-11-18，code-review 之后）

删除本身没有回潮（`scripts/verify.sh` 第一步那十个词仍然是零）。补的是**删完之后留下的话**：
`McpServerProperties` 的类注释还写着"权限默认按 server 声明，可以按工具覆盖"并
`{@link Server#tools}`——而那个字段已经删了。留这种注释正是本票要防的那种状态：
读的人会以为"往上加一段配置就能收紧"，配上去既不报错也不生效。已改成"这里只回答接哪个 server，
可见性归工具清单那一层"。

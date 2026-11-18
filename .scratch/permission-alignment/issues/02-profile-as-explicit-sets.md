# 02: 档位从"累进阶梯"改成"显式集合"

**What to build:** 把"一个工具最低需要哪一档"换成"**这个工具在哪几档里**"：`ToolTierRegistry`
从 `Optional<ToolTier> tierFor(String)` 改成返回集合，`ToolProfile#select` 从序号比较
（`tier.allows(required)`）改成集合包含判断。`ToolTier` 四个名字保留，`allows()` 删掉——
它是阶梯语义的唯一凭据。

配套两件：`tools.alsoAllow`（放宽）与 `group:*` 简写表（`group:fs` / `group:runtime` /
`group:web` / `group:agents` / `group:plugins` / `group:openclaw`）。

**Blocked by:** 无。

**Type:** task

**Status:** resolved

**进度（2026-11-18）**：`ToolTier` 删 `allows()` 加 `all()`、`ToolTierRegistry` 改 `profilesFor` 返回集合
并新增 `allProfilesFor` / `external` 两个工厂、`ToolProfile` 加 `alsoAllow` 且 `select` 改集合判断、
新增 `ToolGroups`（六个 `group:*`，两个动态）、`ToolsProperties` 加 `alsoAllow`、16 处登记点全部重排、
启动日志加 `widenBlocked` 告警。`ToolProfileTest` 22 条 + `ToolProfileMatrixTest` 6 条（真实清单逐档）
绿，`./mvnw clean verify` 九个模块全 SUCCESS。

**裁决来源：** ADR-0030 §裁决一（四档是四个互不包含的显式集合、`alsoAllow`、`group:*`）与
§实现过程·阶段 2。此处不重复论证。

## 划分结论（2026-11-18，按它的口径定的）

原来这里问"我们的消息类工具是哪几个"——那是在**按旧档位名字平移**，问错了；`MESSAGING` 在我们这儿
的意思是"不是最简档"，在它那儿是"客服画像"，同名不同义。按它的判据（每个工具声明自己在哪几个
画像里、判据是"要不要碰机器/外部世界/自主行动"）重推，结论如下：

| 画像 | 成员 | 依据（它那边的对位） |
| --- | --- | --- |
| `minimal` | 地基七件：`toolSearchTool`、`AskUserQuestionTool`、`recall_session`、`TodoWrite`、`systemStatus`、`get_goal`/`create_goal`/`update_goal` | 地基；它的 `minimal` 只有一个 `session_status`，更极端，见缺口。**`systemStatus` 与三个目标工具是落地时补进表的**：它们只读自己的进程与对话状态，任何画像都该有 |
| `coding` | 地基七件 + `Bash`/`BashOutput`/`KillShell`、`Read`/`Grep`/`Glob`（+ `Write`/`Edit` 按开关）、`WebFetch`（+ `WebSearch` 按 key）、`run_script`、技能生成器、`schedule_task`/`list_schedules`/`cancel_schedule`、`Task`、`Skill`、**外部工具** | 碰机器（fs/runtime/web）、自主行动（`cron`）、开执行体（`subagents`）、技能正文当指令读（`skill_workshop`）——这些在它那里**全部只在 `coding`** |
| `messaging` | 地基七件 + **外部工具** | 能问能查、碰不到机器；它的 `messaging` = `message` + `sessions_list/history/send` + `session_status` |
| `full` | 不筛（等价 `["*"]`）；但**仍要求每个工具声明画像** | 不限制。它不是"最全的画像"，这一点在实现里是硬要求：`full` 短路画像判断，却**不短路**未声明校验——否则默认档下所有没登记的工具会静默通过 |

**外部工具桶 = 它的 `bundle-mcp`**：`querySales`、配置来的 MCP 工具都归它，`coding` 与 `messaging`
**都含**这个占位；具体哪几个存在由 `toolFilter` 决定（票 07）。`minimal` 不含这个桶——与它
*`minimal` still hides them* 一致。

所以 `messaging` 的语义是：**能问、能查，碰不到机器**（无 shell、无文件、无定时任务、无子 Agent）。
它与它的唯一不对等是缺 `message` 这个成员（我们回答即投递，模型不选收件人），记在缺口。

## 要动的地方（预计）

| 位置 | 改什么 |
| --- | --- |
| `claw4j-core/**/principal/ToolTier.java` | ✅ 删 `allows()`，加 `all()`；枚举四值保留 |
| `claw4j-core/**/principal/ToolTierRegistry.java` | ✅ `tierFor` → `profilesFor`（返回集合）；`sameFor` 改成变长画像；新增 `allProfilesFor`（地基）与 `external`（外部工具：画像 + 外部名单**同源**） |
| `claw4j-core/**/principal/ToolProfile.java` | ✅ `select()` 改集合判断；加 `alsoAllow`（可突破画像）；`Selection` 加 `widenBlocked` |
| `claw4j-core/**/principal/ToolGroups.java`（新） | ✅ 六个 `group:*`：`fs`/`runtime`/`web`/`agents` 静态，`plugins`/`openclaw` 动态 |
| **16 处**登记点（票面原写 12） | ✅ 地基七件 → `allProfilesFor`；`Bash` 三件、`Read`/`Grep`/`Glob`、`Write`/`Edit`、`WebFetch`/`WebSearch`、脚本包、技能生成器、`ScheduleTools`、`Task`、`Skill` → 只 `CODING`；sales 与两条 MCP → `external(CODING, MESSAGING)`。**新发现的两处**：`McpToolAutoConfiguration`（lambda 实现，票面没列）与 `systemStatus` |
| `claw4j-agent/**/ToolsProperties.java` | ✅ 加 `alsoAllow`；默认画像**不动**（仍是 `full`） |
| `claw4j-app/src/main/resources/application.yml` | ✅ `tools` 段改成画像语义，加 `alsoAllow`；`exec` 段的旧"四档 mode + 授权表"注释一并更新（票 01 的遗留） |

**落地时才看清的三件事**（票面原来没有）：

1. **"外部工具桶"不靠占位名实现**。它的 `bundle-mcp` 是个虚拟工具 id，挂在 `CORE_TOOL_PROFILES` 里；
   我们的工具名是运行时的，所以直接把外部工具的**画像**声明成 `{CODING, MESSAGING}` 就达到了同样效果，
   `group:plugins` 只在 `allow`/`deny` 里作为"一次点名"的简写存在。这样少一层映射。
2. **`full` 要短路画像判断、但不能短路声明校验**。第一版写成 `profile == FULL || declared()`，
   门禁没拦住（`toolWithoutTierFailsAtAssembly` 红了）——默认档下所有没登记的工具会静默通过。
3. **`group:openclaw` 用"清单减外部"表达**，不写死成员：加一个内置工具不该改两处。
   它与 `group:plugins` 互补，并集是全部工具。

三条必须照抄的语义：

- **`alsoAllow` 与同层 `allow` 先合并**，不是一条独立管线；合并函数在"只有 `alsoAllow`、没有同层
  `allow`"时给出 **`["*", ...alsoAllow]`**——那一层等于不设限。照抄，别实现成"只放这几个"。
- **档位是硬天花板**：配置里写了 `allow` 却被档位挡住的工具，只打一条告警，不隐式放宽。
- **默认档位保持 `full`**：它运行时的默认就是"不限制"，`coding` 只是 onboarding 写进新配置的值。

## TDD 用例清单

| 判什么行为 | 用例名 | 先红的样子 | 过没过 |
| --- | --- | --- | --- |
| 档位不再有顺序语义：一个档有、另一个档没有，互不蕴含 | `ToolProfileTest#profilesAreIndependent` | 删了 `allows()` 之后，任何"比大小"的写法编译不过 | ✅ |
| `coding` 档里**有**定时任务与子 Agent、`messaging` 里**没有** | `ToolProfileTest#codingHasCronAndSubagents` + `ToolProfileTest#messagingDropsMachineAndAutonomyTools` + `ToolProfileMatrixTest#codingHasMachineAndAutonomy` | 按旧标签平移会反过来 → 两条都红 | ✅ |
| `messaging` 档里没有代码类工具（`Bash`、`Read`/`Write`、`WebFetch`） | `ToolProfileMatrixTest#messagingReachesDataButNotTheMachine` | 现在 `CODING` 必然包含 `MESSAGING` → 红 | ✅ |
| 业务工具（`querySales`）与 MCP 在 `coding` 与 `messaging` 都可见 | `ToolProfileTest#externalRegistersBothProfilesAndGroup` + `ToolProfileTest#profilesAreIndependent` | 现在靠 `MESSAGING` 标签误打误撞，改成集合后会掉一档 | ✅ |
| `minimal` 档里没有外部工具桶（业务/MCP 全隐藏） | `ToolProfileMatrixTest#minimalHidesExternalTools` | 没有桶这个概念 | ✅ |
| `deny: ["group:plugins"]` 一次关掉全部外部工具 | `ToolProfileTest#denyPluginsGroupRemovesAllExternalTools` + `ToolProfileMatrixTest#denyPluginsRemovesEveryExternalTool` | 没有桶 | ✅ |
| `minimal` 档只剩地基工具 | `ToolProfileTest#minimalKeepsOnlyFoundationTools` | 现在累进，`MINIMAL` 外的工具按序号判 | ✅ |
| `alsoAllow` 能把档位外的工具加回来 | `ToolProfileTest#alsoAllowWidensBeyondProfile` | 没有这个键 | ✅ |
| 只配 `alsoAllow`、不配 `allow` 的那一层等于不设限 | `ToolProfileTest#alsoAllowAloneMeansUnrestricted` | 容易实现成"只放这几个" → 这条红 | ✅ |
| 配了 `allow` 但被档位挡住时只告警、不放宽 | `ToolProfileTest#allowOnlyNarrows` | 容易顺手放宽 → 这条红 | ✅ |
| `group:*` 展开成成员清单 | `ToolProfileTest#groupShorthandExpands` | 没有简写表 | ✅ |
| 工具没声明档位 → 启动失败并点名 | `ToolProfileTest#undeclaredProfilesFailAndNameTheTool` | 已有，改成集合同样要保留这条 | ✅ |

**复核：2026-11-18 `./mvnw clean verify` 九模块全绿。** 原来的用例名是"写票时的预计"，
实际落在两个文件：纯语义在 `claw4j-core` 的 `ToolProfileTest`，真装配逐档在 `claw4j-app` 的
`ToolProfileMatrixTest`——**表里已按实际名字改过，别照旧名去找**。

## 验收判据

| 判什么 | 判据 |
| --- | --- |
| 表是真的 | 上面那张"工具 × 四档"表进票的 Comments，且 `ModelAndToolSelectionTest` 逐档断言 |
| 默认不变 | 不给 `claw4j.tools.profile` 时清单与改前**逐个工具相同**（这条最容易被忽略） |
| 门禁 | `./mvnw clean verify` 全绿 |
| 清单 | 两份清单里"这个工具属于哪个档"的说明按票 02 的四档成员表改；`docs/capability-tests.md` 的工具表逐行核对档位 |

## 缺口

- **`minimal` 档的含义会变**：它那边 `minimal` 只有 `session_status` 一个工具；我们的 `minimal`
  是"能聊天、别忘了刚才说了什么"。要不要对齐成"几乎什么都没有"，本票不做决定，按上表先保留四件。
- **`messaging` 少一个成员**：它的核心是 `message`（主动发给别人），我们没有这个工具（回答即投递）。
  所以我们的 `messaging` = 能问 + 能查、碰不到机器，语义成立但与它不完全对等。
- **外部工具桶沿用它的名字**：叫 `group:plugins`（等价它的 `bundle-mcp`），成员是"MCP + 业务模块"。
  它那边 `group:plugins` 严格指"插件拥有的工具"，我们的业务模块不是插件——**名字借用，含义略宽**，
  记在这里免得以后当成 bug。
- **`toolFilter` 不在本票**：桶只回答"这类允不允许"，具体哪几个存在由票 07 的 `toolFilter` 决定。

## Comments

### 四档 × 真实清单（2026-11-18，`ToolProfileMatrixTest` 跑出来的）

一个默认配置（`profile: full`、exec `full`、`files.write-enabled=false`、无 `BRAVE_API_KEY`、
无脚本目录、未配子 Agent / 技能 / MCP）的部署，逐档筛完剩下的工具：

| 工具 | minimal | messaging | coding | 备注 |
| --- | --- | --- | --- | --- |
| `toolSearchTool` | ✓ | ✓ | ✓ | 不在 `catalog.names()` 里：它是工具循环单独注入的，由 `allProfilesFor` 保证每档都在 |
| `AskUserQuestionTool` | ✓ | ✓ | ✓ | |
| `recall_session` | ✓ | ✓ | ✓ | |
| `TodoWrite` | ✓ | ✓ | ✓ | |
| `systemStatus` | ✓ | ✓ | ✓ | |
| `get_goal` / `create_goal` / `update_goal` | ✓ | ✓ | ✓ | |
| `querySales` | ✗ | ✓ | ✓ | 外部工具：`external(CODING, MESSAGING)` |
| `Bash` / `BashOutput` / `KillShell` | ✗ | ✗ | ✓ | 碰机器 |
| `Read` / `Grep` / `Glob` | ✗ | ✗ | ✓ | 碰机器（`Write`/`Edit` 按 `files.write-enabled`） |
| `WebFetch` / `WebSearch` | ✗ | ✗ | ✓ | 出网（`WebSearch` 还要 key） |
| `schedule_task` / `list_schedules` / `cancel_schedule` | ✗ | ✗ | ✓ | 自主行动 |
| `Task`（子 Agent） | ✗ | ✗ | ✓ | 默认不装配；配了才出现在 coding |
| `Skill` | ✗ | ✗ | ✓ | 同上 |
| `run_script` / 技能生成器 | ✗ | ✗ | ✓ | 默认不装配（要脚本目录 / 生成器） |
| 配置来的 MCP 工具 | ✗ | ✓ | ✓ | 外部工具；未配时为空 |

计数（默认配置）：`minimal` 7 个、`messaging` 8 个、`coding` 18 个、`full` = 清单全量。

### 与它的对位，两处仍不对等

- `messaging` 少一个核心成员：它的 `message`（主动发给别人）我们没有——回答即投递，模型不选收件人。
- `minimal` 比它宽：它只有 `session_status` 一个工具，我们有七件地基。这是有意的，见缺口。

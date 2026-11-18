# 05: 审批是执行前的闸，不是回答后的改写

**What to build:** 把审批从 `CallAdvisor`（改模型回答）挪到**工具执行前**，两个落点：

- **exec 内部**：判定已在票 01 做（`requiresAsk` 那一组）。本票补的是**冻结执行计划**——
  审批请求里存一份规范化的 `command / cwd / agent / session`（脚本类再加被调文件的 sha256），
  执行时用这份冻结件；批准之后任何一项变了都按 mismatch 拒。
- **非 exec（插件 / MCP / 将来要人确认的）**：给**声明了要审批的工具**套一层 `ToolCallback` 装饰器，
  拒绝时返回一条工具结果（含理由），**不改写模型回答**。读、检索这类 core 工具不挂这个闸。

配套三件：`askFallback` 三档配置化（默认 `deny`）；`ApprovalService` 补两段异步文案与降级；
子 Agent 与 cron 会话的拒绝**不投递**。

**Blocked by:** 01（exec 判定）。

**顺序**：本票**必须先于 04**。现在是 `AuthorizationAdvisor` 里调 `ApprovalGate`，04 要把那个类删掉——
审批没先挪到执行前就删，中间态是 **exec 完全不审批**，那是上线级缺口，不是可控的中间态。

**`Approval` 枚举在 04 消失之后**，"哪些工具要人批"的唯一落点就是本票这层装饰器的登记名单；
本票实现时把这份名单放在装饰器旁边，不要指望 `ToolPermission.approval` 还在。

**Type:** task

**Status:** resolved

**进度（2026-11-18）**：两个落点、冻结件、`askFallback`、三档决定、拒绝不投递全部落地；
`./mvnw clean verify` 九模块全绿；新增 `ExecApprovalTest` 10 条 + `ApprovalRequiredToolCallbackTest` 6 条，
`ExecToolTest` 25 条 / `ExecPolicyTest` 15 条 / `PersistenceSchemaTest`（V12 那一列）跟着改。
**四处形态差异记在 ADR 的缺口表里**（不原地等、冻结件不含 session/agent、批准完成的文案、
会话不可恢复时不直投），理由都在那一栏，这里不重复。

**裁决来源：** ADR-0030 §裁决四（触发点与形态、五条性质、三态）与 §实现过程·阶段 5。

## 要动的地方（预计 → 实际）

| 位置 | 改什么 |
| --- | --- |
| `claw4j-agent/**/approval/ApprovalService.java` | ✅ 改 `decide(…, ApprovalChoice, …)`；`allow-always` 按命令段落表；子 Agent / cron 的拒绝不投递（`Reached.SILENT`） |
| `claw4j-agent/**/approval/ApprovalGate.java` | ✅ 保留原样（决定存储 + 一次性 + TTL）；拒绝面已不在这里——它由工具自己回一条结果 |
| `claw4j-agent/src/main/resources/db/migration/V12__approval_frozen_plan.sql` | ✅ `frozen_plan jsonb`（一份 JSON，不拆列）+ 改掉 `decision` 的注释 |
| `claw4j-capability/**/exec/ExecApprovalPort.java` | ✅ `Decision` 从枚举改成记录（带冻结件、编号、理由）+ 第四个取值 `UNDECIDED`（这一轮拿不到结论） |
| `claw4j-capability/**/exec/FrozenPlan.java`（新） | ✅ 冻结件：`command + cwd`、`digest()`、`sameAs()` |
| `claw4j-capability/**/exec/ExecCallContext.java`（新） | ✅ 这次调用是谁、哪段对话——后端拿不到 `ToolContext`，只能由工具回调那一层放进来 |
| `claw4j-capability/**/exec/ExecContextToolCallback.java`（新） | ✅ 给三个 exec 工具绑上下文；单参重载不绑 |
| `claw4j-capability/**/exec/ExecProperties.java`、`ExecPolicy.java` | ✅ `askFallback` 三档 + `fallbackAllows()`（被生效的 `security` 夹住） |
| `claw4j-capability/**/exec/SandboxedExecBackend.java` | ✅ 四档结论分开处置；批准的那份与现在要跑的**逐字符**比，不一致就是 approval mismatch |
| `claw4j-agent/**/approval/AgentExecApprovalPort.java`（新） | ✅ 端口实现：兑现 → 没有就登记（带冻结件与"接不接受以后都放行"） |
| `claw4j-agent/**/approval/JdbcExecAllowlist.java`（新） | ✅ `allow-always` 的落点：按段落的、一段表达不出来就整条不记 |
| `claw4j-agent/**/approval/ApprovalRequiredToolCallback.java`（新） | ✅ 非 exec 那道闸：拒绝回一条工具结果，不抛、不改写回答 |
| `claw4j-agent/**/approval/ApprovalRequiredTools.java`（新） | ✅ "哪些工具要人批"的唯一名单（默认空） |
| `claw4j-core/**/domain/ApprovalChoice.java`（新）、`port/ApprovalDecider.java`、`port/ApprovalLedger.java` | ✅ 三档决定；`Grant` / `Ask` 带冻结件与 `allowAlways`。`Choice` 放在 `domain` 而不是 `domain.port`——后者只收接口与 record |
| `claw4j-agent/**/subagent/SubagentRunner.java` | ✅ 给子链的工具也塞一份身份与会话：不加的话子 Agent 里需要审批的工具一律按"没有身份"拒 |
| `claw4j-channel/**/feishu/**`、`**/http/**` | ✅ 卡片第三个按钮（`ask=always` 时不出）+ 接口 `"always":true`；不适用回 `not_applicable` |

三条照抄的细节（落地后各自的位置）：

- **`askFallback` 是三档配置**（`deny` / `allowlist` / `full`），默认 `deny`；被"本次生效的
  `security`"夹住（`ExecPolicy#fallbackAllows`）。`allowlist` 档在这一步恒等于拒：能走到
  "等不到人"的正是没命中白名单的那些。
- **两段文案的关键句**：被拒是"没跑、不要重跑、没有新输出"（`ApprovalService#deniedNote` 与
  `SandboxedExecBackend#undecidedText` 各一份）；批准完成那句**没照抄**，理由见 ADR 缺口表。
- **只有"拒绝"不投递**（子 Agent / cron），批准完成的结果照常投。

## TDD 用例清单

| 判什么行为 | 用例名 | 落在哪 |
| --- | --- | --- |
| 被拒时模型在**同一回合**收到一条工具结果并继续 | `ApprovalRequiredToolCallbackTest#withoutApprovalItReturnsAResultInsteadOfThrowing` | ✅ 返回值里带编号与三句，delegate 一次没执行 |
| 被拒的文案含"没跑 / 不要重跑 / 没有新输出" | `ApprovalRequiredToolCallbackTest#withoutApprovalItReturnsAResultInsteadOfThrowing` | ✅ |
| 异步批准完成后以 follow-up 注入同一会话 | `ApprovalFlowTest#approvingStartsTheNextTurnInTheSameConversation` | ✅ 原有那条覆盖（本轮不动它，04 重写） |
| 子 Agent 会话的被拒不投递 | `ExecApprovalTest#denialForASubagentSessionIsNotDelivered` | ✅ |
| cron 会话的被拒不投递 | `ExecApprovalTest#denialForACronSessionIsNotDelivered` | ✅ |
| 普通会话的被拒照常投 | `ExecApprovalTest#denialForANormalSessionIsDelivered` | ✅ 反向那一条：只验"不投"的话，把投递整个删掉也能绿 |
| 冻结计划：批准之后改一个字 → 拒 | `ExecApprovalTest#aChangedCommandDoesNotRideOnTheOldApproval`、`ExecToolTest#frozenPlanMismatchIsRejected` | ✅ 前者走真库（指纹换一条），后者钉后端那句 mismatch |
| 冻结计划：改 `cwd` 同样被拒 | `ExecToolTest#frozenPlanMismatchIsRejected` | ✅ 用的就是 cwd 不同的那份 |
| 批准里没有冻结件 → 拒 | `ExecToolTest#approvalWithoutFrozenPlanIsRejected` | ✅ |
| `askFallback=deny` 且没人能批 → 拒绝而不是放行 | `ExecToolTest#undecidedIsDeniedByDefault` | ✅ |
| 兜底档不能比 `security` 更宽 | `ExecToolTest#fallbackCannotBeWiderThanSecurity`、`ExecPolicyTest#fallbackIsClampedBySecurity` | ✅ |
| `askFallback=full` 且 `security=full` 时放行一次 | `ExecToolTest#fallbackFullRunsWithoutADecision` | ✅ |
| 声明要审批的工具在**调用前**被拦住，拿到的是工具结果不是异常 | `ApprovalRequiredToolCallbackTest#withoutApprovalItReturnsAResultInsteadOfThrowing` | ✅ |
| 读、检索类 core 工具没有这道闸 | `ApprovalRequiredToolCallbackTest#onlyListedToolsAreGated` | ✅ |
| `allow-always` 按段落的、第二次不再问 | `ExecApprovalTest#allowAlwaysWritesOneRowPerSegment` | ✅ |
| `ask=always` 时不接受 `allow-always` | `ExecApprovalTest#askAlwaysRefusesThePersistentChoice` | ✅ 接口/服务侧；卡片上的第三个按钮同一条判断（`Pending#allowAlways`） |
| 没有身份时 exec 不登记也不放行 | `ExecApprovalTest#withoutIdentityNothingIsRegistered` | ✅ |

**复核：2026-11-18 `./mvnw clean verify` 九模块全绿。** 表里 18 行逐条对上真实用例；唯一没落地的那一行
（会话不可恢复时降级）已按上面的规则搬进「缺口」。第三条"以 follow-up 注入同一会话"仍是原有那条用例在钉——
本票没有重写它，票面已标注。

## 验收判据

| 判什么 | 判据 |
| --- | --- |
| 端到端（同步） | 前台跑一条要批的命令 → 这一轮回一条工具结果说"在等谁批，不要重试"；人点了之后同一会话开新一轮，模型重新调它并真的跑 |
| 端到端（异步） | 同上——今天只有这一条路（不原地等，见 ADR 缺口表） |
| 冻结件 | 审批库里能查到那份计划（`ExecApprovalTest` 断言 `frozen_plan` 内容与 `args_digest`）；批准后改命令重发，旧的批准不命中 |
| 门禁 | ✅ `./mvnw clean verify` 九模块全绿 |
| 清单 | `docs/capability-tests.md` 的审批段已改写：exec 现在能整条造出来（`mode=ask` → 库里一行带 `frozen_plan` → 批 → `exec_allowlist` 落条目），并补了 `not_applicable` 与"子 Agent / cron 不投递"两条。**没有真跑那一轮 curl**：它要一个起了数据库的实例 + 真模型，本机这一轮只跑了 `verify` |

## 缺口

- **会话不可恢复时不再"直投"**：形态改了——结论落得下来，但告诉的是**点按钮的人**（
  `ApprovalFlowTest` 原有的 `NO_ORIGIN` 分支仍钉着那条文案）。原 TDD 表那一行写作"降级"，新形态见 ADR 缺口表。

- **"安全审计压制命令"那一类不做**（无对应概念），ADR 已记。
- **审批人名单不再是本票范围**：`ApproverRoster` 已有（P03，空名单=没人能批、不许自批），
  本票只保证"决定集合"与"批准人校验"仍是两个独立步骤，不合并。
- **没有 host-local 第二层**：`askFallback` 只有配置侧一份（ADR 裁决四）。
- **`last_used_at` / `last_used_command` / `last_resolved_path` 恒为 null**：它们只在条目被命中之后
  才补，而补它等于每次放行多一次 `update`。三列留着是为了将来要看"这条放行最近用过没有"。
- **没有 `autoAllowSkills`**：技能声明里的命令不会自动进白名单（ADR 裁决四提到的那一条）。
  技能审批走 `SkillTrust`，与本表无关；两条合并要有"技能声明 → 命令"的解析，那是另一件事。
- **子 Agent 里 exec 需要审批时，卡片落在 `subagent:<名字>` 这段会话上**：拒绝不投递
  （按 OpenClaw 的抑制条件），所以批准/拒绝的结论只回给点按钮的人；子 Agent 这一轮拿到的是
  工具结果。它不是缺陷，但它意味着"派出去的子任务被拒"不会自己告诉父 Agent。

# 01: exec 三段式——`security` × `ask` × `autoReview`，以及 `allow-always`

**What to build:** 把 exec 的策略从"一个五档枚举"改成它的形状：**三个正交字段**
`security`（`deny` / `allowlist` / `full`）× `ask`（`off` / `on-miss` / `always`）× `autoReview`（bool），
`mode` 退化成预设（五档，新增 `AUTO`）。判定落点从"装配期按 mode 登记一次 `ToolPermission`"
改成"**每次调用在 exec 工具内部按当前策略算一次**"。

同时补齐三件配套：

- `host`（`auto` / `sandbox` / `gateway` / `node`）与 `strictInlineEval` 两个新键；
- `exec_allowlist` 表 + 审批卡片的第三个选项 `allow-always`，选它时**按命令段逐段**落 `pattern`；
- 复核器接口 + 默认实现（恒返回"回落人工"），`AUTO` 档因此暂时与 `ASK` 等价。

**Blocked by:** 无。

**Type:** task

**Status:** resolved

**进度（2026-11-18）**：三段式、五档预设与反解、`requiresApproval` / `verdict` / `segments` / `patternOf`、
审批端口、复核器接口、后端接入、`exec_allowlist` 表全部落地；`ExecPolicyTest` 14 条 + `ExecToolTest` 19 条绿，
`./mvnw clean verify` 全绿。**卡片入口与端口的 agent 侧实现归票 05**（它们要 `ApprovalGate` 与两条通道的
决策传参，与本票的判定层无依赖）。

**裁决来源：** `docs/adr/0030-permission-alignment-with-openclaw.md` §裁决五（`mode` 是预设、三段式、反解）
与 §实现过程·阶段 1；`allow-always` 的语义见 §裁决四·三态。此处不重复论证。

## 要动的地方（预计）

| 位置 | 改什么 |
| --- | --- |
| `claw4j-capability/**/exec/ExecProperties.java` | ✅ 加 `security` / `ask` / `autoReview` / `host` / `strictInlineEval`；`Mode` 五档（加 `AUTO`）；`settings()` 做"预设展开 + 逐字段覆盖" |
| `claw4j-capability/**/exec/ExecPolicy.java` | ✅ 加 `Settings`、`expand`、`modeOf`（反解）、`requiresApproval`、`verdict`、`segments`、`patternOf` |
| `claw4j-capability/**/exec/ExecApprovalPort.java`（新，票面原未列） | ✅ 审批端口：`alreadyApproved` / `decide` / `remember` + `denyAll()`。**必须在 capability 侧留端口**——审批设施在 agent 模块，exec 反向引用会成环 |
| `claw4j-capability/**/exec/ExecReviewer.java`（新） | ✅ 接口 + 默认实现（恒 `FALLBACK`），入参照 ADR 裁决五列的那几样 |
| `claw4j-capability/**/exec/SandboxedExecBackend.java` | ✅ 判定改成 `verdict` 三态；`ASK` 时先问复核器（仅 `autoReview` + `on-miss` + 真未命中），再问人 |
| `claw4j-capability/**/exec/ExecAutoConfiguration.java` | ✅ 不再按 mode 登记 `requiringApproval("exec_command")` |
| `claw4j-agent/src/main/resources/db/migration/V11__exec_allowlist.sql` | ✅ 新表（**不建 `command_text`**；唯一键用 `coalesce(arg_pattern,'')`）。⏳ **`agent_id` 列归票 06**——现在没有 agent 身份来源，建了只能填假值（见 ADR 裁决四） |
| `claw4j-agent/**/approval/**` | ⏳ **归票 05**：端口的 agent 侧实现（转 `ApprovalGate`）、`allow-always` 决策、按段落表的仓储 |
| `claw4j-channel/**/feishu/**`、`**/http/**` | ⏳ **归票 05**：卡片与 HTTP 接口传第三个决定（两处入口同一套服务端判定） |

三条容易糊过去的实现约束：

- **`ASK` 的语义要反过来**：现在是"命中白名单也要人批、未命中直接拒"，改成"**命中直接跑、未命中问人**"。
  这一条是行为变更，票面上单独认一次。
- **`mode` 是预设不是本体**：配了 `mode` 就整体展开成三个字段；配了 `security` / `ask` 就逐字段覆盖；
  两者同时出现时按 ADR 的 `applyExecPolicyLayer` 语义（`mode` 优先整体替换）。
- **反解要照抄**：`security=full` + `ask=always` 反解回 `ask`，即仍然逐条问。这条是负例，别"优化"掉。

## TDD 用例清单

| 判什么行为 | 用例名 | 先红的样子 | 过没过 |
| --- | --- | --- | --- |
| `mode` 五档展开成三段，且三段反解回原档 | `ExecPolicyTest#modePresetRoundTrips` | 现在只有四档、没有 `autoReview`，`AUTO` 直接编译不过 | ✅ |
| `allowlist` + `ask=off`：白名单外直接拒，不弹审批 | `ExecPolicyTest#allowlistOffMissIsDeniedWithoutPrompt` | 现在没有 `security`/`ask` 两个键，表达不出这一档 | ✅ |
| `ask=on-miss`：白名单命中直接跑，不弹 | `ExecPolicyTest#askOnMissRunsAllowlistedWithoutPrompt` | 现在 `ASK` 命中也要批 → 这条红 | ✅ |
| `ask=on-miss`：未命中弹审批，批了能跑 | `ExecPolicyTest#askOnMissPromptsWhenMissed` + `ExecToolTest#askOnMissPromptsThroughPortAndRunsAfterAllowOnce` | 现在未命中是直接拒 → 这条红 | ✅ |
| `ask=always`：命中白名单也弹 | `ExecPolicyTest#askAlwaysPromptsEvenOnAllowlisted` | 没有这一档 | ✅ |
| `security=full` + `ask=always` 仍逐条弹（反解回 `ask`） | `ExecPolicyTest#fullWithAskAlwaysStillPrompts` | 容易实现成"full 就不弹" → 这条红 | ✅ |
| `security=full` + `ask=off`：**白名单这件事**一次都不问 | `ExecPolicyTest#fullWithAskOffNeverPrompts` | 同上的另一半 | ✅ |
| 显式配的 `security` / `ask` 逐字段盖过 `mode`（不是整体替换） | `ExecPolicyTest#settingsOverridesPerField` | 现在只有一层配置 | ✅ |
| 复合命令 `a && b` 选 `allow-always` 后落两行 | `ExecApprovalTest#allowAlwaysWritesOneRowPerSegment` | 现在没有这张表 | ✅ |
| 某段表达不出 pattern 时**不落库**、且**这一条请求不给** `allow-always` | `ExecPolicyTest#patternOfRejectsPathsAndAssignments` + `ExecApprovalTest#unrepresentableSegmentWritesNothing` + `ExecApprovalTest#anUnrepresentableRequestRefusesThePersistentChoice` | 现在一律当一次性处理 | ✅ |
| 选一次 `allow-always` 后，同可执行文件的第二次调用不再弹 | `ExecToolTest#allowAlwaysRemembersAfterRunning` | 无表可查 | ✅ |
| `ask=always` 时卡片上没有 `allow-always` 这一项 | `ExecApprovalTest#askAlwaysRefusesThePersistentChoice` + `FeishuApprovalTest#aNonPersistableRequestHasNoThirdButton` | 现在卡片固定三选一 | ✅ |
| `strictInlineEval` 开启后 `python3 -c` / `bash -c` 即使解释器在白名单也弹 | `ExecPolicyTest#strictInlineEvalForcesApproval` + `ExecToolTest#strictInlineEvalAsksEvenWhenAllowlisted` | 没有这个键 | ✅ |
| `strictInlineEval` 关闭时 `python3 script.py` 不被误伤 | `ExecPolicyTest#strictInlineEvalLeavesScriptFileAlone` | 容易把"解释器"整类当危险 → 这条红 | ✅ |
| heredoc 段强制审批（`ask=off` 也弹：照 OpenClaw 在调用点 OR 上去，与 `ask` 无关） | `ExecPolicyTest#heredocForcesApproval` | 现在只有控制符一票否决，没有 heredoc 这一档 | ✅ |
| 未配复核器时 `AUTO` 与 `ASK` 行为一致 | `ExecToolTest#autoFallsBackWithoutReviewer` | `AUTO` 还不存在 | ✅ |

**复核：2026-11-18 `./mvnw clean verify` 九模块全绿。** 两处与原表不同，写在下面「缺口」：
`strictInlineEval` 与 heredoc 是这一轮复核时才发现**只有配置项、没有判定**（`Settings` 带着它，
但没人读），补了 `ExecPolicy#forceAsk` 与两张表（解释器 → 内联旗标）才真正落地；per-agent 的 exec
覆盖从来不在本票范围（ADR 偏差清单已判"不做"），原表那一行只是"一句 forecast"。

**二审（2026-11-18，code-review 之后改承诺不改实现）**：原表上一行写着"`full`+`ask=off` 一次都不弹"，
与 heredoc 那行在配置层互斥。照 ADR 裁决四记的 OpenClaw 调用点——
`requiresAsk = requiresExecApproval(...) || heredoc || inline-eval || allowlist 计划不可用 || 安全审计压制`
——后四项是**调用点 OR 上去的，不在 `requiresExecApproval` 体内**，所以 `ask=off` 也拦不住 heredoc。
实现照抄的正是这一层（`forceAsk` 叠在 `verdict` 之后、只抬 `RUN`），所以改的是票面那半句：
"一次都不弹"收窄成"白名单这件事不问"，heredoc 那一行补上"`ask=off` 也弹"。
`fullWithAskOffNeverPrompts` 因此只断言 `requiresApproval`——它回答的就只有白名单那一层。

同一次二审给 `analysisOk` 补了真生产者：它原来恒传 `true`（`verdict` 里写死），
现在由 `ExecPolicy#analysisOk(command)` 现算（每段都取得到可执行名），
写的正是 OpenClaw 的 `!analysisOk` 与 `allowlist 计划不可用` 那两条的合并语义。
差别只在 `security=allowlist` + `ask=on-miss` 下"文本命中白名单但入口说不清"这一格，
用例 `ExecPolicyTest#unnamableSegmentIsAnalysisFailure`。

## 验收判据

| 判什么 | 判据 |
| --- | --- |
| 表结构 | `PersistenceSchemaTest` 里能查到 `exec_allowlist` 的列与唯一约束（`pattern` + `arg_pattern`） |
| 端到端 | 起 local 实例，`ask=on-miss` 下跑一条白名单外命令 → 弹审批 → 批准 → 跑通；同命令再来一次，选过 always 后不弹。**这一条的"真弹卡片"归票 05**（端口实现），本票用假端口验"该问的时候问了" |
| 卡片 | ⏳ 归票 05：飞书卡片与 HTTP 两个入口都能传 `allow-always`，且 `ask=always` 时两处都没有这一项 |
| 门禁 | `./mvnw clean verify` 全绿 |
| 清单 | `README-capability-tests.md` §1 与 `docs/capability-tests.md` §1 的 exec 行：`security` / `ask` / `allow-always` 按新语义重写（`ASK` 是"未命中才问"，不是"每次都问"）；审批段的预期跟着改 |

## 缺口

- **复核器只有接口**：默认实现恒回落，`AUTO` 与 `ASK` 等价，是 ADR 缺口表那一条。
- **"安全审计压制命令"那一类不做**：它用于保护 `security.audit.suppressions` 这个配置本身，
  我们没有对应概念。
- **per-agent 的 exec 覆盖不做**：`agents[].security` / `ask` / `askFallback` / `allowlist` 在 ADR-0030
  的偏差清单里判了"不做"（它那侧是本机 approvals 文件的 per-agent 段）。本票只做部署级一份 +
  显式字段逐字段覆盖，别照 Ticket 原文去找 `agents[].exec`。
- **`host` 只入配置不生效**：真正决定"在哪跑"要等容器化（票 06），本票只把它解析出来并让它进判定。

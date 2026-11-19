# 10: 审批设施收成三层，删掉自研胶水，对齐剩下的缺口

**What to build:** 审批这一块现在是四个自研类叠一起（`ApprovalGate` → `ConversationApprovalGate` → `JdbcApprovalLedger`，外加 `ApprovalService`），
调用方还要认两个端口（`ApprovalLedger` / `ApprovalDecider`）。本轮按 OpenClaw 的三个概念收口：

- **请求** `Approvals#ask`：登记一条、判"接不接受以后都放行"、推卡片。
- **裁决** `Approvals#decide` / `find`：资格、结论、续跑、清扫。
- **记录** `Approvals#redeem` / 同一份 SQL：一次性兑现、过期判定。

四合一之后只剩一个端口（`domain.port.Approvals`）与一个实现（`agent.approval.JdbcApprovals`）。

同时删掉三件胶水：

- `CallFingerprint` → 规范化与哈希搬到 `ToolCallAudit#digest`（审计与审批的交接键本来就靠它，只有一份）；
- `PrincipalSnapshot` → 身份直接随 `Approvals.Ask` 传，落库时编码、续跑时解码，收进实现里；
- `ArgsSummary` → `Ask` 带原文，实现落库前脱敏并截断（列宽 512 / 上限 480 不变）。

**对齐三处（审查查出来的）：**

1. **inline-eval / heredoc 不持久化**：`bash -c "…"` 选 `allow-always` 会落一条 `pattern='bash'`，此后任何 bash 命令都不再问；`cat <<EOF` 同理落 `cat`。照 OpenClaw 三态约束 2，这两种段整条不落库。
2. **exec 执行审计的 `approval_id`**：`ToolCallAudit#granted` 只有非 exec 调过，exec 那一行恒空。`ExecCallContext` 带上工具名与参数原文，端口兑现后写一次交接。
3. **`claw4j.approval.required-tools` 不存在**：`ApprovalProperties` 只有 ttl / approvers，注释却都指向这个键。补字段与绑定，非 exec 那道闸终于能配。

**修文案与注释：**

- `allow-always` 没落进库时不再说"已经持久放行"（`remember` 的条数决定回话）；
- `JdbcApprovalLedger` 的"（V6）"改成实际位置（基线 `V5__run.sql`）；
- `application.yml` 与 ADR 里"配置 allow 与表 pattern 同一规则合并"改成实际口径（配置按整条、表按命令段，两者都用 `ExecPolicy.permits`）；
- ADR 缺口表补一句：兜底档决定的是"这一轮跑不跑"，不是"这条请求被拒"。

**不做**（保持 ADR 的裁决）：不原地等、host-local 第二层、按渠道解析审批人、pattern 用解析路径与 argPattern（表里 `arg_pattern` 仍恒 null）。卡片、HTTP 入口、表结构都不动。

**Status:** resolved

**进度（2026-11-19）**：四合一、胶水三件、对齐三件、文案与注释全部落地；`./scripts/verify.sh`
全绿（0 失败 / 0 错误 / 0 跳过，9 模块，覆盖率 65.6%–93.8%）。新增用例三条：
`ExecApprovalTest#inlineEvaluationIsNotPersisted` / `#heredocIsNotPersisted` /
`#executedCommandCarriesTheApprovalIdInAudit`；`ChatPipelineTest` 那条改成打 `JdbcApprovals`。
删除 9 个类：`ApprovalGate` / `ConversationApprovalGate` / `ApprovalService` / `JdbcApprovalLedger` /
`ApprovalLedger` / `ApprovalDecider` / `CallFingerprint` / `PrincipalSnapshot` / `ArgsSummary`。
`Approvals#denyAll` 没保留：它会在 `domain.port` 里生成匿名类，`CoreBoundariesTest` 只收接口与 record。

**验收**

| 判什么 | 判据 |
| --- | --- |
| 只剩三层 | `grep` 不到 `ApprovalGate` / `ConversationApprovalGate` / `ApprovalService` / `JdbcApprovalLedger` / `ApprovalLedger` / `ApprovalDecider` / `CallFingerprint` / `PrincipalSnapshot` / `ArgsSummary` |
| inline-eval 不落库 | `ExecApprovalTest`：对 `bash -c …` 选 `allow-always`，`exec_allowlist` 零行，且下一次仍然问 |
| heredoc 不落库 | 同上，`cat <<EOF` 零行 |
| 审计带审批编号 | `ExecApprovalTest`：批准后执行，`audit_event` 那一行 `approval_id` 等于审批编号 |
| 名单可配 | `claw4j.approval.required-tools` 绑到 `ApprovalRequiredTools`；`ApprovalRequiredToolCallbackTest` 从配置取 |
| 门禁 | `./scripts/verify.sh` 全绿 |

**验收补记（2026-11-19 真跑）**：按 `README-capability-tests.md` §10 在本机起实例跑了一遍
（新库 `claw4j_verify`、`security=allowlist` + `ask=on-miss`），八条验法全通。跑的过程中发现两处
缺陷，都已修：

1. **两张 `TaskRepository` 打架**：`dreamTaskRepository`（长期记忆整理）与 `subagentTaskRepository`
   同类型，`subagentToolCallback` / `subagentOutputToolCallback` / `principalDreamAdvisor` 按类型注入，
   同时开着子 Agent 与 `memory.dream` 时启动失败（`expected single matching bean but found 2`）。
   三处注入点补 `@Qualifier`。**没有单测锁住**：现有用例没有一条同时起 dream 与子 Agent 的完整上下文，
   而 `ApplicationContextRunner` 要凑齐 `ChatModel` / `ToolIndex` / `AgentProperties` 等十来个协作件，
   成本高于收益；防线是"启动即暴露"，记在这里而不是假装有用例。
2. **`POST /api/approvals/{id}` 只写 `{"approved":true}` 回 400**：`DecisionRequest` 的两个原始类型
   组件缺字段时被当成 `null`，Jackson 报 `Cannot map null into type boolean`。加显式 creator，先用
   包装类型接住再收窄。**没有单测锁住**：`HttpEndpointsTest` 直接构造对象，绕过了反序列化；
   channel 模块编译期没有 jackson-databind 依赖，写不了这样的用例。验收覆盖，README §10.2 写明
   "只写 approved 就够"。

两处都按"先报缺陷、再改、再真跑"处理，没有扩大改动面。

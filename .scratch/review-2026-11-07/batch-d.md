# batch D：m6 / m7

## m6-productionization

### 01 框架里不留演示类
- 判定：一致
- 代码证据：`claw4j-app/src/main/resources/application-local.yml.example:61-104`（三个 Python server 与 `mail_send: send_email` 的按工具覆盖全在配置里）
- 代码证据：`claw4j-app/src/main/java/io/github/claw4j/app/` 下只剩 3 个类，`McpDemoConfiguration` 全仓零命中
- OpenClaw：`docs/tools/mcp.md:10`（"Server definitions live under `mcp.servers` in config"，没有"每个 server 一个 Java 类"）
- 一句：配置化 MCP 的口径一致；票面的 `application-mcp-demo.yml` 已被 05 并进 local，"主代码只剩 Claw4jApplication"则被 P16 的插件契约类打破，两处都与 demo 无关。

### 02 入站编排独立成模块
- 判定：自研
- 代码证据：`claw4j-core/src/main/java/io/github/claw4j/channel/kernel/InboundDispatcher.java:202-235`（去重、认人、异步、回执都不认识飞书）
- 代码证据：`claw4j-core/pom.xml:34-36`（compile 依赖只有 slf4j）；`claw4j-core/src/test/java/io/github/claw4j/CoreBoundariesTest.java:58-64`（白名单边界规则）
- OpenClaw：`docs/channels/channel-routing.md:11-13`（通道是插件，核心只按固定规则路由，没有可复用的入站编排 artifact）
- 一句：编排抽离成立；模块今天叫 `claw4j-core`（06 合并），`claw4j-channel-core` 这个坐标已不存在。

### 03 外部插件 jar 真能装进来吗
- 判定：自研
- 代码证据：`docs/design/extension-points.md:479-496`（`loader.path` + `PropertiesLauncher` 三组对照、命令与结论）；`README.md:193-194`（同一命令，且写明"不是热加载"）
- OpenClaw：`docs/plugins/building-plugins.md:17-24`（插件按 npm 包安装进 central registry，没有 `loader.path` 这一层）
- 一句：实验结论仍有效；两边都是"不改源码装插件"，但装载机制不同（Spring 自动配置 jar vs npm 包注册表），属自研路线。

### 04 谁的账本，DDL 就放在谁的模块里
- 判定：自研
- 代码证据：`claw4j-agent/pom.xml:125-136`（flyway 三件套编译期）、`:181-182`（postgresql 仅 test）；`claw4j-agent/src/main/resources/db/migration/V3__session_schema.sql`、`V4`、`V5` 三份都在 agent
- 代码证据：`claw4j-capability/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`（capability 资源里已无 `db/`，pom 全文无 flyway/postgresql）
- OpenClaw：`docs/automation/cron-jobs/how-it-works.md:17`（单一 shared SQLite state database），没有"按模块分 DDL"的对应设计
- 一句：DDL 与 Flyway 依赖都已归位，`claw4j-capability` 不再因别人的表拖数据库驱动。

### 05 接哪几个 MCP server 是这台机器的事
- 判定：一致
- 代码证据：`claw4j-app/src/main/resources/application-local.yml.example:61`（`capability.mcp`）、`:71-104`（三个 Python server）、`:118-128`（npx 与 HTTP 两条注释着，可机械解注释）
- OpenClaw：`docs/tools/mcp.md:10`（MCP server 定义在 `mcp.servers` 配置里）
- 一句：两份 mcp-* profile 已消失；`application-mcp-stdio.yml` 是"把 Claw4J 当 MCP server"的另一件事，不是接 server 的配置。

### 06 核心只有一个 artifact：`claw4j-channel-core` → `claw4j-core`
- 判定：自研
- 代码证据：`pom.xml:19`（`<modules>` 里只有 `claw4j-core`，没有 `claw4j-domain`）；`claw4j-core/pom.xml:19,34-36`（"只有 slf4j"）；`scripts/verify.sh:25`（八个模块，无 domain/channel-core）
- OpenClaw：`pnpm-workspace.yaml:1-6`（多 package monorepo：`packages/*`、`extensions/*` 等），没有"单一零框架核心坐标"的对应物
- 一句：合并成立；一个只写工具插件的人今天依赖 `claw4j-core` 一个坐标。

## m7-enterprise

### A01 run / step 成为系统里的第一等身份
- 判定：已过期
- 代码证据：`claw4j-agent/src/main/resources/db/migration/V24__drop_run.sql:14-23`（drop `audit_trail` 视图、`agent_step`、`agent_run_delivery`、`agent_run`）
- 代码证据：`claw4j-agent/src/main/java/io/github/claw4j/agent/ChatClientAgentPort.java:398`（每轮 `UUID.randomUUID()`），`:88-90`（"编号只活一轮"）
- OpenClaw：`docs/concepts/agent-loop.md:20`（`agent` RPC 返回 `runId` 即执行句柄，没有 run/step 实体表）
- 一句：run/step 与"杀进程后从库里答出第几步"随 ADR-0027 整体删除，只剩瞬时 runId 与 `audit_event`。

### A02 resume(runId)——"继续"是同一件事的下一步
- 判定：已过期
- 代码证据：`claw4j-agent/src/main/resources/db/migration/V21__approval_conversation.sql:44-47`（状态约束删掉 `suspended`）；`claw4j-agent/src/main/java/io/github/claw4j/agent/approval/ConversationApprovalGate.java:23-25`（这一轮直接 done，批准后是同一对话的下一轮）
- OpenClaw：`docs/concepts/agent-loop.md:20-24`（`runId` + `agent.wait`，没有"按 runId 续跑"）
- 一句：`resume(runId)` 与"seq 接着涨"已不存在；"继续"是同一会话开新的一轮，runId 必然不同。

### A03 计划外置——模型记得"做到第几步"
- 判定：已过期
- 代码证据：`claw4j-agent/src/main/resources/db/migration/V20__conversation_goal.sql:11-14`（goal 按 `session_key` 一行，无步骤行）；`claw4j-agent/src/main/java/io/github/claw4j/agent/goal/GoalTools.java:36-42`（只有 get/create/update_goal）
- 代码证据：`claw4j-agent/src/main/java/io/github/claw4j/agent/goal/SessionGoal.java:9-11`（"它不是任务队列"，没有第几步）
- OpenClaw：`docs/tools/goal.md:14,24,65`（goal 是会话状态、随 session key 走、"A goal is not a task queue"）
- 一句：计划作为 `kind=plan` 的 step 及其 pending/done/skipped 已删，改成会话 goal；A08 要的"带状态与序号的计划"接口不存在。

### A04 预算从"一轮"提到"一次 run"
- 判定：已过期
- 代码证据：`claw4j-agent/src/main/resources/db/migration/V22__drop_session_budget.sql:1-16`（删 `agent_session_budget`，明确"额度挂目标，不挂会话，更不挂 run"）
- 代码证据：`claw4j-agent/src/main/java/io/github/claw4j/agent/goal/GoalTools.java:113-116`（`token_budget` 是 goal 的可选参数）；`JdbcSessionGoalStore.java:102-107`（`tokens_used += ?` 并转 `budget_limited`）
- OpenClaw：`docs/tools/goal.md:78`（"Token budget: 12k/50k"）
- 一句：run/会话级三项预算已删，今天只有"目标上的 token 预算 + 单轮工具调用熔断"；A04 的判据（第 7 次被拒）没有落点。

### A05 结果投递与回收——run 绑 principal，不绑通道
- 判定：已过期
- 代码证据：`claw4j-agent/src/main/resources/db/migration/V21__approval_conversation.sql:54`（drop `agent_run_delivery`）；`claw4j-agent/src/main/java/io/github/claw4j/agent/channel/ConversationDelivery.java:16-21`（"没有登记表"，明确不做一对多）
- 代码证据：全仓 `RunQueryController` / `/api/runs` 零命中（只在注释里残留），`GET /api/runs/{id}` 已不存在
- OpenClaw：`docs/channels/channel-routing.md:11`（结果回到消息来的通道）；`docs/concepts/architecture.md:50`（历史走 Gateway WS）
- 一句：投递目标表、跨通道收结果与按 run 查询三样都已删除，今天只"回到这一轮发起的那段对话"。

### P01 ToolPermission 加第三个分量 Approval
- 判定：自研
- 代码证据：`claw4j-core/src/main/java/io/github/claw4j/domain/principal/ToolPermission.java:25`（三分量 record）、`:40-53`（默认 NONE，`requiringApproval` 单列）、`:64-75`（`grantedTo` 不看审批）
- 代码证据：`claw4j-agent/src/main/resources/db/migration/V11__approval_fingerprint.sql:16,20`（`args_digest` / `args_summary`）；`claw4j-app/src/main/resources/application.yml:284-293`（开关今名 `claw4j.persistence.enabled`）
- OpenClaw：`packages/gateway-protocol/src/schema/approvals.ts:17`（approval 是独立的 exec/plugin/system-agent 记录，不是权限记录的一个分量）
- 一句：第三分量与"审批不替代鉴权"都成立，`requiringApproval` 在生产代码里仍零调用；把审批塞进权限记录是本项目自己的形状。

### P02 挂起 run 等审批（不挂线程）+ 参数指纹
- 判定：已过期
- 代码证据：`claw4j-agent/src/main/resources/db/migration/V21__approval_conversation.sql:44-47`（`suspended` 从状态约束删除）；`ConversationApprovalGate.java:18-25`（不写 awaiting step、不改 suspended）
- 代码证据：`claw4j-agent/src/main/java/io/github/claw4j/agent/approval/CallFingerprint.java:17,61`（`sha256(工具名+规范化参数)`）、`JdbcApprovalLedger.java:50-55`（一次兑现的 `for update skip locked`）仍在
- OpenClaw：`docs/tools/exec.md:204`（auto-review 单次有效）；`approvals.ts:19`（`allow-once` / `allow-always`）
- 一句：指纹与"一次点头一次执行"还在，但"挂起 run、服务端续跑同一 runId"整条已删，票面主体已过期。

### P03 谁来批——审批人名单与飞书卡片
- 判定：一致
- 代码证据：`claw4j-core/src/main/java/io/github/claw4j/domain/ApproverRoster.java:45-58`（自批 `SELF_APPROVAL` 与名单外 `NOT_APPROVER` 分开，空名单谁都不能批）；`ApprovalService.java:122-137`（资格先判、结论后落）
- 代码证据：`claw4j-channel/src/main/java/io/github/claw4j/channel/http/ApprovalController.java:62,86`（GET/POST 独立于飞书）；`FeishuApprovalCard.java:105-107`（工具名、发起人、参数摘要）
- OpenClaw：`docs/cli/approvals.md:69`（requester/reviewer 过滤）；`packages/gateway-protocol/src/schema/approvals.ts:291`（reviewer 字段）
- 一句：名单独立、自批被拒、卡片与 HTTP 同一条 `decide`，五条验收今天都成立。

### P04 定时调度——到点 = 创建一个 run
- 判定：一致
- 代码证据：`claw4j-agent/src/main/java/io/github/claw4j/agent/schedule/ScheduleStore.java:55-66`（一条 `for update skip locked ... returning` 认领 + 库时钟租约）；`ScheduleScanner.java:151-187`（到点开一轮，会话键 `cron:<taskId>:<epoch>`）
- 代码证据：`claw4j-agent/src/main/resources/db/migration/V12__schedule.sql:40`（`notify_policy` 默认 `silent`）
- OpenClaw：`docs/automation/cron-jobs/how-it-works.md:17`（任务、运行状态与历史存 shared SQLite，重启不丢）
- 一句：多实例认领、重启恢复、失败三次停用都成立；`RunLauncher` 已改名 `TaskLauncher`（`TaskLauncher.java:19`），"创建一个 run"今天只是"开一轮"，且 `:15` 还留着指向已删 `RunResumer` 的 javadoc。

### P05 schedule_task 工具族——一次授权，多次执行
- 判定：不一致
- 代码证据：`claw4j-agent/src/main/java/io/github/claw4j/agent/schedule/ScheduleTools.java:48-54`（schedule_task / list_schedules / cancel_schedule 三个工具）；`ScheduleAuthorization.java:64`（建时按 `required_tools` 校验）；`V13__schedule_identity.sql:18-20`（到点按 owner + role 重算）
- 代码证据：`claw4j-agent/src/main/resources/db/migration/V22__drop_session_budget.sql:1-16`（会话额度表已删，run 也没有额度列）
- OpenClaw：`docs/tools/index.md:96`（单个 `automations`（别名 `cron`）工具，不是三个）
- 一句：三个工具、建时校验、到点重鉴权、默认 silent 都在，但"每次运行单独计预算"今天不成立——只剩 goal 的可选 token 预算，任务不建 goal 时完全不计额度。

### P06 企业微信通道——复用入站编排
- 判定：不一致
- 代码证据：`claw4j-channel/src/main/java/io/github/claw4j/channel/wecom/client/WecomCallbackController.java:102-127`（验签、解密、五秒内回空串）；`WecomChannelAutoConfiguration.java:108,127-129`（按名字装配 `wecomOutboundDelivery`，dispatcher 带回执）
- 代码证据：`claw4j-agent/src/main/resources/db/migration/V21__approval_conversation.sql:54`（drop 投递目标表）；`ConversationDelivery.java:16-21`（只回发起对话，明确不做一对多）
- OpenClaw：`docs/channels/wecom.md:8-11`（WeCom 由外部 `@wecom/wecom-openclaw-plugin` 提供，核心不内置）
- 一句：通道本体与去重/认人/回执成立，自写加解密是票面写明的原因；但"投递走目标表、飞书问企微收"已随 ADR-0027 删除，第 4 条验收不成立。

### P07 审计——step 的只读投影，不新建执行表
- 判定：已过期
- 代码证据：`claw4j-agent/src/main/resources/db/migration/V24__drop_run.sql:14`（drop `claw4j_run.audit_trail` 视图）；`V19__audit_event.sql:17,41-42`（新建 `audit_event`，只有 `call_fingerprint` / `approval_id`）
- 代码证据：`claw4j-agent/src/main/java/io/github/claw4j/agent/audit/AuditEvent.java:8-10,24-26`（明确"没有工具参数"，record 里也没有 `argsSummary`）
- OpenClaw：`docs/cli/audit.md:13`（metadata-only activity ledger）；`packages/gateway-protocol/src/schema/audit.ts:56`（`runId` 必填）
- 一句：审计能力还在且更贴 OpenClaw，但"step 的只读投影 + 参数摘要 200 字"已不存在（ADR-0027 改成事件流水、只留指纹）；`RunApprovalGate` 也已改名 `ConversationApprovalGate`。

## 本批小结
- 一致 4：m6-01、m6-05、P03、P04
- 自研 5：m6-02、m6-03、m6-04、m6-06、P01
- 故意不同 0
- 不一致 2：P05（"每次运行单独计预算"随 V22 删除，票面第 4 条与代码不符）；P06（投递目标表与跨通道收结果随 ADR-0027 删除，票面第 4 条不成立）
- 已过期 7：A01（run/step 表已删）、A02（resume 已删）、A03（计划改会话 goal）、A04（预算改 goal token）、A05（投递表与 `/api/runs` 已删）、P02（suspend 已删，仅指纹留存）、P07（`audit_trail` 视图已删，改 `audit_event`）
- 最值得修的三处（修复代价从低到高）：
  - 低：清掉指向已删 `GET /api/runs/{id}` 与 `RunResumer` 的 6 处注释/javadoc（`TaskLauncher.java:15`、`ScheduleTools.java:282`、`ScheduleScanner.java:215`、`NotifyPolicy.java:15`、`FeishuChannelAutoConfiguration.java:116`、`HttpChannelAutoConfiguration.java:94`）——纯文本，但会让人以为端点还在。
  - 中：把 P05/P07 的验收框改成 ADR-0027 后的口径（预算挂 goal、审计只留指纹），否则下一个人会按旧票验出假红或假绿。
  - 高：P06 的"飞书问、企微收"若仍是产品需求，要恢复投递目标表与一个按会话取结果的读路径；今天是设计上删掉的，不是漏做。

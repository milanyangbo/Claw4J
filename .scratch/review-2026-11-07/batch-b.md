# batch B：m2（能力与技能 / 真模型 / 收尾）

## m2-capability-and-skills

### 01 工具装配改成收集所有 ToolCallback —— 三层能力的共同入口
- 判定：一致
- 代码证据：`claw4j-agent/src/main/java/io/github/claw4j/agent/AgentAutoConfiguration.java:274-277`（单 `ToolCallback` 与 `ToolCallbackProvider` 两路都收进同一个列表，不再按"单个 provider 缺失"判条件）
- 代码证据：`claw4j-app/src/test/java/io/github/claw4j/app/ApplicationAssemblyTest.java:448-450`（清单里同时有 `querySales` / `systemStatus` / `get_goal`，少一个就红）；`ToolCallBudget.java:51,60`（3 / 6 仍是默认）
- OpenClaw：`docs/tools/tool-search.md:79-85`（一轮开始时把 OpenClaw、plugin、MCP、client 四类工具收成同一份 effective catalog）
- 一句：多来源汇成一份清单的口径一致；P09 之后模型默认只经 `ToolSearch` 检索，票面说的"模型可见清单"已收窄成 catalog，这是后续票的演进，不是来源互相挤掉。

### 02 MCP 跨进程闭环
- 判定：故意不同
- 代码证据：`McpToolProvider.java:48-55`（建 provider 时 `listTools()` 为空直接抛，启动即失败）；`McpConnection.java:149,162,201`（只认 `McpTransportSessionNotFoundException`，关旧连接重开一次并重放）
- 代码证据：`ProcessStdioMcpEndpoint.java:145`（今天已有真实子进程 stdio 端点，票面 Answer 里"OS 进程那条边界没有真起"已不成立）
- OpenClaw：`docs/tools/mcp.md:61`（启动失败按 30 秒→10 分钟指数退避后台重试，"turns never wait for retries"）；`docs/tools/mcp.md:45`（连不上时保留上次成功列出的工具）
- 一句：同为 MCP 客户端且工具都过预算/权限，但故障策略相反；票面写的理由是"不等到调用时才暴露配置错误"（ADR-0002/0008），故记故意不同。

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
- 判定：故意不同
- 代码证据：`ProcessRequest.java:27`（`record ProcessRequest(List<String> argv, long timeoutMillis)`，没有 command 字符串、也没有 env 入口）；`LocalProcessRunner.java:59`（`new ProcessBuilder(request.argv())`，数组直接交给进程）
- 代码证据：`ScriptStaticCheck.java:41,184-185`（`NO_SHEBANG` 挡在落盘之前）；`BuildContractTest.java:84-89`（红线收窄到 `LocalProcessRunner` 一个类，而不是删掉）
- OpenClaw：`docs/tools/exec.md:54-55`（`exec` 的参数是 `command` 字符串，"Shell command to run"）
- 一句：OpenClaw 仍把命令交给 shell，本项目改成 argv 直执；票面的理由是"入口脚本与参数以 argv 数组交给进程，谁都不解释它们"，故记故意不同。

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
- 判定：故意不同
- 代码证据：`claw4j-core/src/main/java/io/github/claw4j/channel/kernel/InboundDispatcher.java:202-232`（`accept` 只去重、认人、投 executor，返回即 ACK）；`:212`（同 `eventId` 只 ACK 不执行）；`:285`（超长按段落切分顺序发）
- 代码证据：`InboundDispatchTest.java:76-164`（ACK 早于 Agent；重复投递只跑一次；过窗重跑）
- OpenClaw：`docs/channels/feishu/setup.md:43`（入站事件先持久化入队，按 event ID 抑制重复，重启后仍可恢复）
- 一句：ACK/去重/异步/回推骨架一致，但我们用进程内 TTL 去重而不是持久队列；票面写明"不引 Redis，单实例是既定形态"，故记故意不同。落点也已从 `channel.feishu` 移到 `claw4j-core` 的 `channel.kernel`。

### 05 飞书长连接接上真 SDK，关掉 M2
- 判定：故意不同
- 代码证据：`FeishuLongConnectionChannel.java:113-121`（`start()` + `awaitReady`，超时/异常直接抛）；`FeishuChannelAutoConfiguration.java:44-46,67-68`（SDK 在 classpath、凭据齐、`enabled` 才装配）
- 代码证据：`FeishuChannelWiringTest.java:57-90`（缺凭据/半配/SDK 不在 classpath/`enabled=false` 四种退让）
- OpenClaw：`docs/channels/feishu.md:11`（WebSocket 是默认事件传输，无需公网 URL）；`docs/channels/feishu/setup.md:43`（入站持久化）
- 一句：同为 SDK 长连接，但"连不上就启动失败"与 OpenClaw 的降级/状态上报相反；票面理由是"带着半死的通道跑比起不来更难排查"，故记故意不同。

### 06 文档与限制记账对齐
- 判定：不一致
- 代码证据：`docs/adr/0010-module-layering-and-dependency-direction.md:49`（仍写 `DomainBoundaryTest`，而全仓现在叫 `CoreBoundariesTest`）
- 代码证据：`docs/adr/0009-channel-selection-feishu-long-connection.md:45-53`（实现记录在）；`docs/agents/guardrails.md:37`（SDK 无单测的偏差在）；`README.md:367`（FEISHU 配置行在）
- OpenClaw：无对应物（内部 ADR/文档记账）
- 一句：五条验收里四条还在，唯独"ADR-0010 类名与现存类一致"被后续 ADR-0015 的重构打破，票面承诺今天不成立。

### 07 文档不该点测试类名，只该说"这条要有测试守着"
- 判定：不一致
- 代码证据：`docs/capability-tests.md:106`（`ApprovalFlowTest` 等类名）、`docs/adr/0015-one-core-artifact.md:70-71`（`DomainBoundaryTest` / `CoreBoundaryTest`）、`docs/adr/0010-module-layering-and-dependency-direction.md:49`（`DomainBoundaryTest`）
- 复扫证据：`docs/` + README + GLOSSARY 里 `*Test` / `*IT` 记号 39 处（不含 CONTRIBUTING 的 3 处），票面要的"0 处"今天不存在。
- OpenClaw：无对应物（文档卫生是本项目内部约束）
- 一句：当时把类名清零，后续文档（`capability-tests.md`、ADR-0015 等）又写回类名，且其中 `DomainBoundaryTest` 已不存在——"由某测试守着"重新变成死指针。

## 本批小结
- 一致 4：01（多来源工具收集）、04（技能渐进披露）、05（子代理隔离与白名单）、closeout-03（降级分类）。
- 自研 4：03（脚本包沙箱）、06（技能生成五步链）、closeout-01（front matter 白名单解析）、closeout-02（SQL 行上限包装）。
- 故意不同 4：02（MCP 启动即 fail-fast vs 后台退避重试）、08（argv 直执 vs shell 解释）、closeout-04（进程内 TTL 去重 vs 持久队列）、closeout-05（连不上即启动失败 vs 降级）。
- 不一致 3：capability-07（`BudgetAcrossLayersTest` / `PermissionsAcrossLayersTest` 不存在，"三层共用预算/鉴权"没有回归守着）、closeout-06（ADR-0010 仍指向已不存在的 `DomainBoundaryTest`）、closeout-07（docs 里 39 处测试类名复活，含死指针）。
- 已过期 1：real-model-01（真模型回归基线被推翻，门禁改回假模型且无 CI）。

- 最值得修的三处（按代价排序）：
  1. 文档指针（最便宜）：先把 ADR-0010 的 `DomainBoundaryTest` 改成 `CoreBoundariesTest`，再决定 docs 是否真要维持"0 类名"（今天 39 处）。
  2. 补回跨层回归（中等）：capability-07 承诺的 `BudgetAcrossLayersTest` / `PermissionsAcrossLayersTest` 已无，"三层共用同一份预算与鉴权"今天没有测试守着。
  3. 真模型回归的账（最贵）：real-model-01 的基线已被推翻，`README.md:412` 与 ADR-0011 现在互相矛盾；要么恢复一条最小真模型门禁，要么改 ADR-0011 并写清哪些用例必须真模型。

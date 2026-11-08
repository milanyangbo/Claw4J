# batch C：m3 / m4 / m5

## m3-observability-and-memory

### 01 可观测：一次提问到底花了多少 token
- 判定：一致
- 代码证据：`claw4j-agent/src/main/java/io/github/claw4j/agent/observability/MeteredChatModel.java:44-46`（`claw4j.model.tokens` / `calls` / `duration` 三个名字仍在），`:79-89`（流式只取最后一帧、`doFinally` 记耗时），`:115-126`（无 usage 时不记 token）
- 代码证据：`MeteredToolCallback.java:240-250`（`claw4j.tool.calls{tool,outcome}`）+ `AuthorizationAdvisor.java:99,407-413`（`denied` 由鉴权侧上报，工具侧看不到）
- 代码证据：`RoutingModelConfiguration.java:97`（每条 route 各包一层）+ `claw4j-app/src/main/resources/application.yml:460-463`（9090 且只绑 127.0.0.1）
- OpenClaw：`docs/gateway/opentelemetry/model-calls-and-metrics.md:255-259`（`openclaw.tokens`、`gen_ai.client.token.usage`）、`:376-377`（工具耗时与 `blocked` 计数，带 `deniedReason`）
- 一句：形状对齐（token 按 kind 拆、工具按名+结果计数、拒绝单独记），只是指标名与标签本地化；ADR-0027 之后 `MeteredToolCallback` 多写了一份审计，不影响计量承诺。

### 02 长对话压缩：把“记得住”换成“记得住且塞得进去”
- 判定：一致
- 代码证据：`claw4j-agent/src/main/java/io/github/claw4j/agent/session/SessionMemoryConfiguration.java:164-181`（Trigger + Strategy 成对建、摘要客户端不挂 Advisor、`maxEventsToKeep` 来自配置）
- 代码证据：`SessionMemoryProperties.java:73-74`（阈值 = `contextWindow × triggerRatio`，仍是比例不是绝对值）+ `ChatClientAgentPort.java:763`（进模型前压）
- 代码证据：`SessionCompactor.java:34-36`（`archived` / `tokens_saved` / `failures` 三个指标仍在）
- OpenClaw：`docs/concepts/compaction.md:16-19`（“Older conversation turns are summarized into a compact entry… Recent messages are kept intact.”）
- 一句：三个关键取向（压旧的留新的、按比例触发、失败不拖垮这一轮）与 OpenClaw 同构；票面第一条“配在 `SessionMemoryAdvisor` 上”的自我推翻仍成立且有必要。

### 03 跨会话召回：问“上次那批单子怎么定的”能想起来
- 判定：不一致（真偏差）——它有这个机制，是一个四值配置项、默认最宽；我们把它硬编码成最窄那个值，还把开关删了
- 我们：`claw4j-agent/src/main/java/io/github/claw4j/agent/session/RecallSessionTool.java:175`（`getEvents(sessionId, …)`，只有当前这一段）、`:169`（身份前缀校验）、`:342-352`（`belongsTo`）
- OpenClaw：`docs/gateway/config-tools/sessions-and-subagents.md` 的 `tools.sessions` 一节——`visibility: self | tree | agent | all`，**Default: `all`**（“every session on the Gateway, including other agents' and other users' transcripts”）；`self` 的定义是 “only the current session key”，正是我们今天的行为
- OpenClaw：`docs/concepts/session-search.md:32-38`——“The default `tools.sessions.visibility: "all"` permits … **including other users' conversations**”；同一页还写明：“Per-peer DM routing separates conversation context **but does not restrict session-tool visibility**”
- 例子：网页私聊里问过“华东区口径按签约主体算”，第二天在群里问“上次那个口径是多少”。今天：工具只在本段里找 → 返回“没找到” → 模型说“我没有相关记录”，用户以为机器人失忆。它默认 `all` 下这条能查回来（结果带 `sessionKey` 做出处）；它担心串台时的解法是**显式配 `self`/`agent`**，不是把能力删掉。它甚至明说了这一层不管 DM 分段——安全位在配置项，不在能力有无。
- 对齐要改什么（成本：中）：`self` 变成 `visibility` 的一个取值，四值照它实现，默认 `all`；`agent`/`all` 需要一条跨会话查询（`SessionService` 只有 `getEvents` 与 `findEventsByUserId`，后者语义不是“全部会话”）。见 `.scratch/openclaw-alignment/issues/05-session-visibility.md`
- 一句：那次“隔离修复”解决的是真泄露，但它用的手法是换掉整个查询范围，顺带把对齐点一起换掉了——`self` 该是这个开关的一档，不是唯一一档。


### 04 会话 TTL：过期不等于删除
- 判定：已过期
- 代码证据：`claw4j-app/src/main/resources/application.yml:40-43`（`spring.ai.session.time-to-live` 已刻意不写：“会话寿命**不再由这里决定**（ADR-0026 收尾）”）
- 代码证据：`claw4j-agent/src/main/java/io/github/claw4j/agent/session/SessionCleanup.java:41-44`（改为调 `maintenance.sweep`，不再调 `deleteExpiredSessions`）+ `SessionMaintenance.java:114-120`（指标换成 `claw4j.session.maintenance.pruned/prunable/failures`）
- 代码证据：`FaultTolerantSessionService.java:82-83`（`deleteExpiredSessions` 只剩透传，主代码里已无调用方）
- OpenClaw：`docs/concepts/session.md:396-406`（`session.maintenance` 的 `pruneAfter: "30d"` / `maxEntries`，与 `claw4j.session.maintenance` 同名字段）
- 一句：票面承诺的 `time-to-live: 30d` + 定时 `deleteExpiredSessions` + `claw4j.session.expired` 三件今天都不成立，替代实现在且更贴 OpenClaw；留下的是 `deleteExpiredSessions` 这一处死代码。

### 05 语义缓存：同一个问题第二遍不该再算一次
- 判定：自研
- 代码证据：`claw4j-agent/src/main/java/io/github/claw4j/agent/cache/CacheAdvisor.java:225-227`（命中键 = 权限指纹 + 实体签名）、`:286`（`entitySignature` 把时间词与数字编进上下文）、`:83,86`（`claw4j.cache.hits/misses`）
- 代码证据：`claw4j-agent/src/main/resources/db/migration/V4__semantic_cache.sql` 与 `V5__semantic_cache_context.sql`（pgvector 载体，无 Redis）
- OpenClaw：无对应物（全仓 grep `semantic cache` 只命中 `docs/ci/scope-and-routing/job-budgets.md:101` 与 `docs/ci/runners.md:572`，都是讲 CI 机器缓存，不是这个能力）
- 一句：票面“后半修订”把“取最近邻再否决”改成“编进上下文键”，代码与修订一致；验收清单唯一没做的是 miss 原因区分“已过期”，票面自己已记账。

### 06 工具检索：工具多到塞不进提示词时，让模型先找工具
- 判定：一致
- 代码证据：`claw4j-agent/src/main/java/io/github/claw4j/agent/ToolSearch.java:101-107`（唯一的 `toolLoop`，`maxResults` 可配）＋ `AgentAutoConfiguration.java:203`（`ChineseToolIndex`，已从 `RegexToolIndex` 演进）
- 代码证据：`ModelAndToolSelectionTest.java:497,527`（“检索出来的工具不等于能调：没登记权限的照样被拦”）
- OpenClaw：`docs/tools/tool-search.md:11-14`（“one compact way to discover and call large tool catalogs”）、`:35-40`（自动给 `tool_search` / `tool_describe` / `dispatch_action` 三个控制面）
- 一句：机制同源（把 schema 藏到检索后面），差异是实现侧：OpenClaw 三个控制面 + 结构化目录，本仓一个检索工具 + 中文二元组索引；“检索不是授权”这条判据仍在用例里钉着。

## m4-extension-points

### 01 把示例销售工具搬成独立模块 `claw4j-example-sales`
- 判定：自研
- 代码证据：`claw4j-example-sales/src/main/java/io/github/claw4j/example/sales/SalesExampleAutoConfiguration.java:41-43`（`claw4j.example.sales.enabled`，默认 true）、`:82-84`（provider 不让位）、`:108-111`（`ToolPermissionRegistry` 也不让位）
- 代码证据：`claw4j-example-sales/src/main/resources/db/migration/V1__business_schema.sql`、`V2__business_seed.sql`（迁移跟着搬走）+ `claw4j-app/pom.xml:45-49`（唯一接线点）
- OpenClaw：无对应物（`docs/plugins/building-plugins.md:13-16` 只有“插件扩展核心”的机制说明与 ClawHub 分发，没有可删依赖自证的业务示例 jar）
- 一句：票面要的“删掉这条依赖，核心照常构建”今天由结构保证（`claw4j-app` 代码零引用示例类），但那条删依赖实验仍是一次性手工记录，没有回归用例把它钉住。

### 02 Java 版 MCP server 独立成 `claw4j-example-mcp-server`
- 判定：自研
- 代码证据：`claw4j-example-mcp-server/src/main/java/io/github/claw4j/example/mcp/server/McpServerApplication.java:42-47`（`StdioServerTransportProvider` + `McpServer.sync(...).tools(...)`）
- 代码证据：`JavaMcpServerProcessTest.java:54,75`（`listTools` + `isAlive` 轮询到假，证明是真的另一个进程）+ `pom.xml:62-67`（`repackage` + `mainClass`）
- 代码证据：`claw4j-capability/src/test/java/io/github/claw4j/capability/mcp/stdio/InProcessStdioMcpServer.java`（替身已从 `src/main` 挪到 `src/test`，包名未变）
- OpenClaw：无对应物（`docs/tools/mcp.md:88-92` 只给“接一个 node stdio server”的命令示例，没有随仓库发布的 server 模块）
- 一句：承诺全部落地；“main 返回后进程不会退出”这条实测结论也与代码一致（`:53` 明确不加重闩）。

### 03 集成指南：接一个新 tool / MCP / Skill / 通道分别要写什么
- 判定：一致
- 代码证据：`docs/design/extension-points.md:25`（Java 工具）、`:139`（MCP server）、`:234`（Skill）、`:334`（通道）、`:443`（语言不是扩展点的维度）
- 代码证据：`docs/design/extension-points.md:125`（“已经踩过的三种失效”）与 README 的四个扩展点表（`README.md:206-222` 是其中的 MCP 段）
- OpenClaw：`docs/plugins/adding-capabilities.md:13-15`（“plugin = ownership boundary / capability = shared core contract”）、`docs/plugins/building-plugins.md:13-16`（同一份“不改核心加能力”的定位）
- 一句：文档比票面要求的更厚（多了人设、插件 classpath、反向 MCP、知识库、版本契约），四类扩展点与“三种静默失效”都在，且路径可核对。

## m5-mcp-by-config

### 01 配置驱动的端点装配（stdio + HTTP）
- 判定：一致
- 代码证据：`claw4j-capability/src/main/java/io/github/claw4j/capability/mcp/McpServerProperties.java:110-119`（`transport` / `command` / `args` / `env` / `url` / `permission` / `tools` 的形状）
- 代码证据：`ConfiguredMcpServersAutoConfiguration.java:89-94`（条件用 `Binder` 读 Environment，不看容器）+ `McpEndpoints.java:72-88`（stdio 缺 command、http 缺 url 都在启动时点名失败）
- OpenClaw：`docs/tools/mcp.md:114-135`（`mcp.servers.<name>` 下 `url` / `transport` / `command` / `environment` / `toolFilter` 的同一形状）
- 一句：键名本地化但语义一一对应；票面自述“`McpDemoConfiguration` 没删”这一缺口已由 m6-01 补完，今天全仓只剩文档引用。

### 02 接两个真的第三方 MCP server（stdio + HTTP）
- 判定：已过期（票面要的 `mcp-third-party` profile 不存在；替代形态与 OpenClaw 同形）
- 理由：票面第 1 条是“`application.yml` 加 `mcp-third-party` profile”，实际是 `application-local.yml`（gitignored）+ `application-local.yml.example` 一份，解释写在 `application-local.yml.example:7-10`
- 代码证据：`application-local.yml.example`（`claw4j.capability.mcp` 段在这里，注释：“起 local 就是‘这台机器决定要连的那些’，一处看全”）；`RealThirdPartyMcpTest.java:39`（`@Tag("real-mcp")`）、`:55`（`npx -y @modelcontextprotocol/server-everything`）、`:82`（deepwiki）
- 代码证据：`application-mcp-stdio.yml` **仍在**，但那是“我们自己当 MCP server”的那一份（P11，`--spring.profiles.active=mcp-stdio`），不是这里的客户端清单——名字像、用途不同，别把它的存在当成“profile 没删干净”
- OpenClaw：无 profile 概念——`docs/gateway/configuration.md:9-11`（一份可选 JSON5 配置 `~/.openclaw/openclaw.json`，缺了用安全默认值；换路径靠 `OPENCLAW_CONFIG_PATH`），`docs/tools/mcp.md:114-135`（第三方 server 就写在 `mcp.servers.<name>` 里，写配置不写代码）
- 例子（为什么 profile 那条路不该走）：按票面做，仓库里会多一份 `application-mcp-third-party.yml`，默认构建不加载它。运维照着它把 `url` 填好，`--spring.profiles.active` 忘带（或 IDEA 里那行没勾上），现象是“我明明配了 server，模型说它没有这个工具”，而启动日志里一行都没有——**配置文件的“存在”被当成了“生效”**。收成本地一份之后：起 `local` 就是这台机器要连的全部，少一个 server 会在启动清单里点名（MCP 那一层是启动期就把清单收成一份，见 capability-02）。
- 一处要改的字：`application-local.yml.example:7-10` 写着“也没有单独的 `mcp-*` profile”，而 `application-mcp-stdio.yml` 就在隔壁。这句话本身没错（它讲的是客户端清单），但读起来像“仓库里没有 `mcp-*` 的 yml”——补半句指一下那一份是干什么的。
- 一句：票面的 profile 形态被推翻，替代形态（一份配置 + env 覆盖）反而与它同形；要补的是票面的 superseded 指针，不是再写一份 yml。


### 03 examples 里那些“非 Java”的能力，逐类说清怎么接进来
- 判定：自研
- 代码证据：`examples/subagents/capacity-triage.md`（第一份真定义，白名单只有 `systemStatus`）+ `docs/design/extension-points.md:443`（“语言不是扩展点的维度”一节）
- 代码证据：`examples/README.md:50`（“这里没有对应的 Java 类——曾经有一个 `McpDemoConfiguration`”，与 M5-01 的收尾一致）
- OpenClaw：无对应物（`examples/` 下只有 `ai-chat` / `inline-incident` 两个应用示例，没有“四类非 Java 素材 → 接入机制”这种分层说明）
- 一句：四类素材（MCP / 脚本包 / Skill / 子 Agent）的机制归属写清了，且明确区分“官方桥”与“本项目接缝”，没有把自研说成官方能力。

### 04 子 Agent 示例多几个场景，并且真的被读一遍
- 判定：自研
- 代码证据：`claw4j-app/src/test/java/io/github/claw4j/app/ExamplesSubagentsTest.java:64`（`SubagentCatalog.load(directory)` 读仓库真实目录）、`:51-58`（手抄的 `SAMPLE_TOOLS`，注释写明漂移风险）
- 代码证据：`examples/subagents/` 五份定义覆盖三种形状（`incident-triage.md` 继承+黑名单、`weekly-digest.md` 跨层白名单、`metric-glossary.md` 只读单工具）
- OpenClaw：`docs/tools/subagents.md:11-14`（子 Agent 概念与隔离取向），但没有“示例定义必须被加载器读一遍”的对应做法
- 一句：票面点名的 `field-mapping.md` 今天叫 `metric-glossary.md`（白名单从 `systemStatus` 换成 `querySales`），形状与用例仍在，属内容演进不算违背承诺。

### 05 MCP 权限按工具覆盖，而不只是按 server
- 判定：一致
- 代码证据：`claw4j-capability/src/main/java/io/github/claw4j/capability/mcp/McpServerProperties.java:119`（`Map<String, Permission> tools`）+ `McpServerEndpoint.java:75,83`（`permissionFor` / `declaredToolPermissions`）
- 代码证据：`McpToolProvider.java:59-63`（点名了不导出的工具名 → 启动失败并列出实际清单）、`:74`（登记时按工具取权限）
- OpenClaw：`docs/tools/mcp.md:96-108`（`toolFilter.include: ["search", "read_*"]` 按工具收暴露面）+ `docs/gateway/config-tools/tool-policy.md:12-15`（`tools.allow` / `tools.deny` 的按工具策略层）
- 一句：两边都能在同一 server 内按工具区分，机制不同（OpenClaw 是暴露面过滤 + 会话 posture，本仓是 action+tables 分级）；本仓“拼错名字宁可红”这条比 OpenClaw 的静默过滤更严。

## 本批小结
- 一致 6：m3-01、m3-02、m3-06、m4-03、m5-01、m5-05
- 自研 5：m3-05（OpenClaw 无语义缓存）、m4-01（无业务示例插件）、m4-02（无自带 MCP server 模块）、m5-03（无四类素材接入说明）、m5-04（无“示例必须被加载器读一遍”的做法）
- **故意不同 0**：这一档不再用来装“和它不一样、但我们有理由”的东西（口径同 batch-b）。
  和它不一样就是偏差（不一致），它没有这个东西就是自研——自研要用例子说清，不能拿“我们有理由”当结论。
- 不一致 1：
  - **m3-03**（`recall_session` 的范围被硬编码成 `self`；它有 `tools.sessions.visibility` 四值、**默认 `all`**、
    且明说这一层不受 DM 分段限制）→ 已拆成 `.scratch/openclaw-alignment/issues/05-session-visibility.md`
- 已过期 2：m3-04（`spring.ai.session.time-to-live` 已刻意移除，`deleteExpiredSessions` 无调用方，`claw4j.session.expired` 换成 `claw4j.session.maintenance.pruned`）；m5-02（票面的 `mcp-third-party` profile 被推翻，替代形态与它同形）
- 最值得修的三处（按代价排序）：
  1. m3-03 的可见性开关——本批唯一落在产品行为上的偏差，票已拆好。
  2. m3-04 的 `FaultTolerantSessionService#deleteExpiredSessions`（`:82-83`）已成为死代码，而票面与 ADR-0026 都还在引用它——“过期就会删”这条留存承诺今天只能靠 `mode: enforce` 才成立，读代码的人会以为它默认生效。
  3. m4-01 的“删依赖实验”只有一次性手工记录（没有自动化用例），插件契约一旦被无意破坏（例如某处 import 了示例类），回归不会红。
- m5-02 只剩票面一句 superseded 指针（文档项，顺手做，不单独排）。


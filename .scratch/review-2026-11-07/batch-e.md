# batch E：m8-agent-quality + m8-durable

## m8-agent-quality

### 01 A06 · 评测集：把"跑通"换成"跑得好"
- 判定：已过期
- 代码证据：`claw4j-eval/src/main/java/io/github/claw4j/eval/EvalScorer.java:65`（判分入参已是 `answer` 字符串 + `List<Trace>`，不是 `Run#result`）、`claw4j-eval/src/test/java/io/github/claw4j/eval/EvalRunTest.java:207-215`（轨迹改读 `claw4j_run.audit_event` 的 `tool.action.finished`）、`claw4j-agent/src/main/resources/db/migration/V24__drop_run.sql:1-5`（`agent_run` / `agent_step` 已删）、`claw4j-eval/src/test/java/io/github/claw4j/eval/EvalCaseSetTest.java:107-119`（工具改名仍在回归里红）
- OpenClaw：`docs/concepts/personal-agent-benchmark-pack.md:10-14`（repo-backed 的 QA 场景包，不是通用模型 benchmark），`:18-31`（10 条 fake-user 场景）
- 一句：量具本身在（51 条用例、三判定、报告可比、`scripts/eval.sh` 与 `verify.sh:25` 都在），但票面点名的 `Run#result` / `RunStore#stepsOf` / `StepStatus` 已被 ADR-0027 删除；这是数据源的改写，不是评测倒退。

### 02 A07 · 工具检索要能看懂中文
- 判定：自研
- 代码证据：`claw4j-agent/src/main/java/io/github/claw4j/agent/ChineseToolIndex.java:77-91`（中文走二元组、空 query 返回 `NEVER_MATCH`）、`:100-136`（`tokens` 可单独断言，英文段交回父类）、`:145-155`（按 code point 切 bigram）、`claw4j-agent/src/main/java/io/github/claw4j/agent/ToolSearch.java:80-87`（提示词已删"空格分隔"、保留"不代表没有这个能力"）、`claw4j-agent/src/main/java/io/github/claw4j/agent/AgentAutoConfiguration.java:200-204`（`ToolIndex` bean 换成 `ChineseToolIndex`）
- OpenClaw：`src/agents/tool-search-ranking.ts:258-315`（`splitWords` + `tokenizeQuery`，英文词干/停用词；没有 CJK 二元组）、`docs/tools/tool-search.md:36-40`（结构化的 `tool_search` / `tool_describe` / `dispatch_action` + 有界目录）、`:231-243`（`tool_search` 按 token 搜索，精确工具名兜底）
- 一句：中文整句命中、英文不变差、20→100 工具不退化都由 `ModelAndToolSelectionTest.java:607-699` 钉住；OpenClaw 是另一套结构化检索，Claw4j 的 CJK 切分属自研补洞。

### 03 A08 · 长 run 的上下文：压缩可以丢细节，不能丢进度与失败
- 判定：已过期
- 代码证据：`claw4j-agent/src/main/java/io/github/claw4j/agent/session/SessionCompactor.java:57`（只剩 `compactIfNeeded(String sessionId)`，没有 runId 重载、没有写前缀）、`docs/adr/0024-no-task-progress-prefix.md:68`（`allow-system-messages` 保持默认 `false`）、`claw4j-agent/src/main/resources/db/migration/V20__conversation_goal.sql:1-20`（跨轮目标改挂 `conversation_goal`）
- OpenClaw：`docs/concepts/compaction.md:61-75`（safeguard 摘要仍保留 pending asks / exact identifiers / split-turn progress）、`docs/tools/goal.md:16-24`（goal 是会话状态，跨轮存在）
- 一句：整票已 closed（ADR-0024），`RunProgress` 与 `[当前进度]` 前缀全仓零命中；今天"跨轮还记得要做什么"的载体是会话目标，不是压缩前缀。

## m8-durable

### 04 P08 · 凭据句柄——配置里只有名字
- 判定：一致
- 代码证据：`claw4j-core/src/main/java/io/github/claw4j/secret/SecretValue.java:89-126`（`secret:` / `plain:` 可校验、报错不回显值）、`:153-168`（`render` 才取真值）；`claw4j-agent/src/main/java/io/github/claw4j/agent/model/ModelProperties.java:108-117`（`apiKey()` 返回 `SecretValue`、无 String getter）；`ModelConfiguration.java:86,137`（每次请求才 render）；`claw4j-capability/src/main/java/io/github/claw4j/capability/mcp/stdio/ProcessStdioMcpEndpoint.java:178` 与 `claw4j-capability/src/main/java/io/github/claw4j/capability/mcp/http/HttpMcpEndpoint.java:146`（MCP 两个出口才 render）
- OpenClaw：`src/secrets/ref-contract.ts:14-20`（`SecretRef = { source, provider, id }`，`SecretInput = string | SecretRef`）、`docs/tools/secrets.md:14-17`（值不进 chat / transcript / tool result / model context，agent 只拿到元数据与 SecretRef）
- 一句：句柄只进配置、真值只在三个出口解析今天成立；票面写的 `agent_step.args_summary` 已被 `audit_event` 取代，而 `AuditEvent.java:8-10` 根本不存参数，承诺反而更强。

### 05 P09 · 能力轴——tools profile 决定清单里有什么
- 判定：一致
- 代码证据：`claw4j-core/src/main/java/io/github/claw4j/domain/principal/ToolProfile.java:30`（四档 + allow/deny）、`:62-104`（先按档位、再 allow、再 deny，筛空即失败）、`:119-124`（未声明档位即失败并点名）；`claw4j-agent/src/main/java/io/github/claw4j/agent/ToolsProperties.java:31-38`（缺省 `FULL` + 两空表）；`AgentAutoConfiguration.java:267-295`（`collectToolCallbacks` 必填 `ToolProfile` 与 `ToolTierRegistry`，先筛再包）
- OpenClaw：`src/agents/core-tool-facts.ts:8`（`ToolProfileId = "minimal" | "coding" | "messaging" | "full"`）、`:228-280`（`CORE_TOOL_PROFILES` 与 `resolveCoreToolProfilePolicy`）；`docs/gateway/config-tools/tool-policy.md:20-31`（四档语义与 allow/deny 叠加）
- 一句：四档语义、装配期筛清单、未分档即失败、父子 Agent 同一出口今天都在，且与 OpenClaw 的 profile 表逐项对上；用例见 `ToolProfileTest.java:54,66,89,206` 与 `ApplicationAssemblyTest.java:413,431`。

### 06 P10 · 知识库检索：向量 + 关键词混合，结果带出处
- 判定：故意不同
- 代码证据：`claw4j-agent/src/main/resources/db/migration/V15__knowledge_base.sql:12-50`（独立 `claw4j_rag` schema、`bigrams`、生成列 `tsv`、GIN）；`claw4j-agent/src/main/java/io/github/claw4j/agent/rag/PgVectorKnowledgeBase.java:45-50,65-66,100-101`（两路查询 + `RankFusion`）；`KnowledgeBase.java:31-32`（出处只有一种拼法）；`RagAutoConfiguration.java:51,89-94`（默认关、开而无目录即失败）
- OpenClaw：`docs/plugins/knowledge/retrieval.md:18`（"Knowledge has no embeddings…"）、`:41-45`（无语言相关分词，未分词的 CJK 子串可能不命中）
- 一句：票面原话是「向量 + 关键词混合，结果带出处」并明确「融合用 RRF，不用分数加权」；OpenClaw 的 Knowledge 刻意只做 FTS/BM25，Claw4j 是照 ADR-0020 故意反着做，不是实现偏差（票面点的 `MigrationCompositionTest` 已并入 `BuildContractTest.java:138`，五个 schema 的断言仍在）。

### 07 P11 · 反向 MCP server：把自己的工具交出去
- 判定：自研
- 代码证据：`claw4j-capability/src/main/java/io/github/claw4j/capability/mcp/expose/McpExposure.java:67-113`（只吃 `ToolCatalog`，expose 为空/一个都交不出即失败）、`:90-99`（装配期挡审批与非 read）、`:137-142`（调用期按外部身份再判表）；`McpServerExposureAutoConfiguration.java:43-46`（默认关）、`:69-96`（自己装配 `McpSyncServer`，不引 starter）
- OpenClaw：`docs/cli/mcp.md:23-30`（`openclaw mcp serve` 交出去的是 Gateway 支撑的 channel conversations，不是业务工具）
- 一句：清单与 P09 是同一个 bean、三道门（档位/权限/审批）今天都在；OpenClaw 没有"把业务工具反向暴露成 MCP"的对应物，属自研。

### 08 P12 · 钉钉通道：第四个入口，这次验签在 header 里
- 判定：自研
- 代码证据：`claw4j-channel/src/main/java/io/github/claw4j/channel/dingtalk/client/DingtalkSignature.java:72-83,105-116`（HMAC-SHA256(secret, timestamp+"\n"+secret)、1 小时窗）；`DingtalkCallbackController.java:103-107`（验签失败回 200）、`:114-119`（群聊/非文本/无身份三分支）；`claw4j-core/.../kernel/InboundDispatcher.java:73,211`（10 分钟去重）；`DingtalkOutboundDelivery.java:24`（`channel()="dingtalk"`）
- OpenClaw：无对应物（`docs/channels/index.md:59-67` 的通道清单里有 Slack 59 / WeCom 67，没有钉钉）
- 一句：验签、时间窗、去重、认人、默认关、主动投递今天都在（`DingtalkChannelTest.java:243,306-321`）；OpenClaw 没接钉钉，属自研。

### 09 P13 · Slack 通道：第五个入口，3 秒是硬限，超时的代价不只是重试
- 判定：故意不同
- 代码证据：`claw4j-channel/src/main/java/io/github/claw4j/channel/slack/client/SlackSignature.java:43,66-78`（`v0:` + 5 分钟窗、原始报文）；`SlackCallbackController.java:107-115`（retry 头只记日志、握手在验签之后）；`SlackMessageTranslator.java:101-125`（`bot_id` + 任意 `subtype` 双挡、只收 `app_mention` / `message`+`im`）；`SlackConversation.java:36-40`（`C…|ts` 编码）；`SlackHttpReplySender.java:148,164`（`thread_ts`、只看 `ok`）
- OpenClaw：`docs/channels/slack/transports.md:18-19`（Socket Mode 是默认，HTTP Request URLs 也支持）、`:30`（单 Gateway/内网优先 Socket Mode）
- 一句：票面第 42-46 行写的是「选 HTTP。依据……官方对两种形态的建议是明确的：开发与本地用 Socket Mode，部署上线用 HTTP」；Claw4j 与 OpenClaw 的默认传输不同，是有理由的裁剪。

### 10 P14 · 可观测与 OTel 导出：一条 run 连到它的每个 step 与每次工具调用
- 判定：已过期
- 代码证据：`claw4j-agent/src/main/java/io/github/claw4j/agent/observability/SpanTracer.java:26-42`（`run` / `model.turn` / `tool.*` 三段 span）；`claw4j-agent/src/main/java/io/github/claw4j/agent/ChatClientAgentPort.java:398,406-414`（本地生成 runId、根 span + turn span）；`MeteredToolCallback.java:149-152`（"span 上不挂审计行号"，共同键是 run id）；`OtelSpanTracer.java:79-86,121`（OTLP/HTTP、`ParentBased(TraceIdRatioBased)`、关闭前 flush）
- OpenClaw：`extensions/diagnostics-otel/src/service-recorders-operations.ts:380`（`openclaw.run` span）、`extensions/diagnostics-otel/src/service-recorders-tools.ts:71`（`openclaw.tool.execution` span）；`docs/gateway/opentelemetry/spans-and-events.md:24-27`（`openclaw.run` 只有 outcome/channel/provider/model/agent）、`:73-77`（工具 span 只有工具名与结果属性）、`docs/gateway/opentelemetry/privacy-and-trace-context.md:47-50`（默认不导出 prompt/response/tool 内容）
- 一句：run→model.turn→tool.* 的父子关系与导出今天都在（`ObservabilityTest.java:223-224,254-255`），但票面的 `claw4j.step.seq` 与 `RunStore#stepsOf` 反查已被 ADR-0027 删除，标题里的"每个 step"今天没有对应实体。

### 11 P15 · 多 agent 配置：同一份代码，两次部署只改配置
- 判定：故意不同
- 代码证据：`claw4j-core/src/main/java/io/github/claw4j/persona/PersonaCatalog.java:70-94`（目录加载、口径随人设解析）、`:125-133`（选不到/歧义即失败）；`PromptComposition.java:42-64`（正文 + 口径 + 每轮时间）；`AgentContextAdvisor.java:84-96`（每轮替换 system）；`AgentAutoConfiguration.java:154-158`（启动日志点名档位来源）
- OpenClaw：`docs/concepts/multi-agent.md:10-14`（一个 agent = 独立 workspace / auth / session，bindings 路由入站），`:24-30`（每 agent 独立 persona 文件）
- 一句：同一份 jar 换 `claw4j.agent.default` 就换人格（`examples/agents/sales.md:2-6` / `ops.md:2-6` / `glossary/metrics.md:1-6`）今天成立；OpenClaw 还做进程内多 agent + bindings，Claw4j 按票面第 42-44 行「判据只要求跨进程可配」有意不做。

### 12 P16 · 插件版本契约：核心版本不够时，启动那一秒就告诉你
- 判定：一致
- 代码证据：`claw4j-core/src/main/java/io/github/claw4j/plugin/PluginContract.java:31-37`（`META-INF/claw4j-plugin.properties`、`requires-core` 必填）、`:68-88`（一次返回全部违规）；`CoreVersionRange.java:44-80`（只认 `>=`/`>`/`<=`/`<`/`=`/`*`，畸形即抛）；`claw4j-app/src/main/java/io/github/claw4j/app/PluginContractGate.java:59-67`（BFPP 里判、失败即 `IllegalStateException`）
- OpenClaw：`packages/plugin-package-contract/src/index.ts:10-13,30`（`pluginApiRange` / `minGatewayVersion`，缺 `openclaw.compat.pluginApi` 即校验失败）、`docs/plugins/building-plugins.md:77-82`（同样的区间写法）、`docs/plugins/hooks/prompt-and-session.md:205-210`（旧宿主在 discovery 时跳过不兼容包）
- 一句：声明区间、启动期硬失败、一次报全部、预发布按同号正式版比今天都在（`BuildContractTest.java:220,233,244` / `PluginContractTest.java:56,109`）；与 OpenClaw 同义，只是描述文件从 package.json 换成 `META-INF` properties。

## 本批小结

- 一致 3：
  - P08：句柄只进配置、真值只在三个出口 render，审计/日志不含参数。
  - P09：四档 profile + allow/deny 与 OpenClaw 的 `tools.profile` 逐项对上，装配期筛清单。
  - P16：插件声明 `requires-core` 区间、启动期 BFPP 硬失败，与 OpenClaw 的 `openclaw.compat.pluginApi` 同义。
- 自研 3：
  - A07：OpenClaw 是结构化 `tool_search`/`tool_describe`，没有中文二元组切分。
  - P11：OpenClaw 的 `mcp serve` 交 channel conversations，不交业务工具。
  - P12：OpenClaw 没有钉钉通道。
- 故意不同 3：
  - P10：OpenClaw Knowledge 明确 no embeddings / 不切 CJK；Claw4j 照 ADR-0020 做向量+bigram+RRF。
  - P13：OpenClaw Slack 默认 Socket Mode；Claw4j 选 HTTP，票面第 42-46 行给了"生产用 HTTP"的理由。
  - P15：OpenClaw 做进程内多 agent + bindings；Claw4j 只做跨进程，票面第 42-44 行明写裁剪。
- 不一致 0。
- 已过期 3：
  - A06：判分原料 `Run#result` / `RunStore#stepsOf` / `StepStatus` 已随 ADR-0027 删除，实现改读 `audit_event`。
  - A08：整票已 closed（ADR-0024），`RunProgress` 与 `[当前进度]` 前缀不存在，改由会话目标承载。
  - P14：`claw4j.step.seq` 与 `stepsOf` 反查已删除，span 只剩 run id 关联。

- 最值得修的三处（按代价从低到高）：
  1. A08：整份失效验收还挂着 `closed` 状态，建议改成一段指向 ADR-0024 与会话目标的说明，纯文档、最便宜也最防误读。
  2. P14：把"step 能反查"那条判据改写成 `claw4j.run.id` 关联，代码已无 `step.seq`，文档却还在承诺。
  3. A06：把"判分原料"一节整体从 `Run` / `RunStore` / `Step` 换成 `audit_event`，改动量最大，但不改会让下一个人去找已删除的类。

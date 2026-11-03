# P15 · 多 agent 配置：同一份代码，两次部署只改配置

**Status:** done（2026-11-03 实现，未提交）
**Blocked by:** None（P09 的 tools profile 已落地，是这张票的一层）

## 背景

ADR-0020 对这件的判据只有一句：**同一份代码两次部署只改配置，一个变销售助手、一个变运维助手。**

现在做不到。而且缺的不是一处，是三处各缺一块，分布在三个不同的位置：

1. **人设是 Java 常量**。`AgentAutoConfiguration:64` 的 `SYSTEM_PROMPT` 是三行硬编码文本。
   改一个字要重新编译发版，而"销售助手"与"运维助手"的差别首先就长在这里。
2. **口径没有独立的源**。业务口径现在被抄在每份 Skill 与每个 Subagent 的正文里：
   `examples/skills/weekly-sales/SKILL.md` 第一节就是「口径（先确认，再动手）」，
   `examples/subagents/metric-glossary.md` 整份都在解释口径。
   两份部署口径不同 = 同一段话抄两遍，改一处必漏一处。
3. **时间上下文根本不存在**。整个 `claw4j-agent` 主代码没有任何"今天几号"进入提示词。
   `weekly-sales` 的相对时间是靠 SQL 里的 `current_date` 兜的——等于口径顺带依赖了数据库时钟；
   而 `ScheduleStore.zoneOf(null)` 兜底 `ZoneId.systemDefault()`，容器里通常是 UTC，"今天"会差一天。
   语义缓存那边的注释已经记着这个坑（「上周」与「上上周」相似度 0.995，单靠阈值挡不住），
   但那只解决了缓存键，模型自己仍然没有时钟。

工具与技能则都是**全局一份**：`claw4j.tools.profile`（P09）、`claw4j.skills.directory`（单个
String）、`claw4j.subagents.directory`（单个 String）。per-agent 覆盖无处表达。

## 必须先回答的一个结构问题

`SubagentDefinition` 已经是「一份 md = name / description / systemPrompt / tools / disallowedTools」。
主 Agent 的定义若另起一种形状，就会长出**两套人设配置**——而 ADR 把 tools profile 与提示词分层
合并成这一张票，正是为了避免这件事。

实现要先回答：**主 Agent 与子 Agent 是不是同一套定义形状？** 两条路都成立，但必须写明选了哪条：

- **同形**：主 Agent 也用一份 md（或同一组键）。好处是解析、校验、失败消息只有一处。
  代价是"子 Agent 的工具白名单"与"主 Agent 的工具策略"语义不同，同一份形状要容下两种语义。
- **不同形**：主 Agent 走 `claw4j.agent.*`，子 Agent 保持现状。
  代价是要在两处回答"人设从哪来、工具怎么裁"，并且要能说清**为什么这不会变成两套口径写法**。

## 三个不替实现决定的点

1. **"两次部署"与"一个进程多份"**：判据只要求跨进程可配。进程内是否支持多份、按什么选
   （通道 / 角色 / 会话），可以不做；做了就必须是**配置里显式写明的映射**——
   靠猜来选的话，"为什么这个用户看到销售人设"这个问题永远答不出来。
2. **没配人设时怎么办**：删掉常量之后有三种出路（启动失败 / 一段明确的默认 / 取 md 里的默认）。
   选哪个都行，但**不许静默退回"上一次那份"**，且选定后启动日志里要看得见。
3. **口径怎么组合**：独立的口径文件、按层叠加（全局 + agent）、还是引用 Skill 的某一节。
   判据只有一条：**改一处，所有引用它的地方都变**，且能答出"这句出自哪份口径"。

## 验收判据

- [x] **同一份 jar、两份配置**：`examples/agents/sales.md` 与 `ops.md` 是两份人设，同一套代码只改
      `claw4j.agent.default` 就换一个人格。证据：`ApplicationAssemblyTest#twoDeploymentsDifferOnlyByConfig`
      （断言两份的系统提示词不同、`tier` 分别是 MESSAGING / FULL、拼出的提示词不相等）。
      **技能集合这一小项没有新做**：`claw4j.skills.directory` 本来就是配置项
      （`SkillToolAutoConfiguration:38`），两次部署指向不同目录即成立，这张票没动它，见 Comments
- [x] `AgentAutoConfiguration` 里不再有硬编码人设：常量已删，内容搬进 `BuiltinPersona` 并标明
      它是"没配目录时的兜底"；日志在 `chatClient` 里打「这个部署的 agent 是 X（…），人设来自 …；
      时区 …；工具档位 …（来自 …）」。证据：`SemanticCacheTest#builtinPersonaWhenNoDirectory`
- [x] **口径只有一个源**：`examples/agents/glossary/metrics.md` 被 sales 与 ops 共同引用；
      `weekly-sales/SKILL.md` 里那三条抄写的口径已删，改成指向它。证据：
      `ApplicationAssemblyTest#glossaryIsSharedAcrossAgents`（两份 agent 取到同一段原文与同一个出处）、
      `PersonaCatalogTest#glossaryIsReadFromTheReferencedFile`。**子 Agent 那一份没动**，见 Comments
- [x] **时间上下文**：判定落在**发给模型的提示词**上——`SemanticCacheTest#agentContextIsComposedPerTurn`
      拦下 advisor 改写后的请求，断言 system 里有正文、口径、日期与时区。
      跨时区由 `PromptCompositionTest#zoneComesFromTheGivenInstant`（同一瞬间换成 UTC 得 12:28）；
      "每轮重算"由 `#timeIsRecomputedPerTurn` 单独钉。时区来自 `claw4j.agent.zone`，
      不读 `ZoneId.systemDefault()`
- [x] **覆盖可追溯**：档位来源在启动日志里直接写「人设文件的 tier」或「claw4j.tools.profile」；
      行为由 `PersonaCatalogTest#tierOverridesGlobalProfile` / `#absentTierDefersToGlobal` 钉住
- [x] **选不到 agent 不许静默降级**：`PersonaCatalogTest#unknownDefaultFailsAndListsCandidates`、
      `#ambiguousDirectoryFailsAndListsCandidates`、`#missingDirectoryFails`、`#emptyDirectoryFails`，
      装配层由 `ApplicationAssemblyTest#unknownPersonaFailsAtAssembly` 再验一次
- [x] P09 的能力轴在同一条链上继续生效：per-agent 的 tier 只是 `profileOf` 的一个入参，
      走的仍是 `collectToolCallbacks` 那一条；未分档校验由 P09 的
      `ApplicationAssemblyTest#toolWithoutTierFailsAtAssembly` 继续盯着，没有因为多一层而放宽
- [x] `./scripts/verify.sh` 全绿：**527 条用例、失败 0、跳过 0**，行覆盖率 core 88.8% /
      capability 84.9% / **agent 61.8%**（≥60%）。见 Comments 里那段"差点破线"
- [x] **门禁余量**：core 8 → 10 个测试文件（`PersonaCatalogTest` / `PromptCompositionTest`），
      agent 与 app 都仍是 10 个、没新增文件；agent 的新用例加进未满的 `SemanticCacheTest`（15 → 18）

## 不做

- **不做热切换 agent**：改配置要重启。与 P09 同一条理由——热切换会让"已经在跑的 run"
  与"新配置"出现两种真相，恢复时读哪一份说不清
- **不做提示词模板引擎**（条件、循环、变量求值）：只要分层与覆盖，不要一门语言
- 不做 agent 的可视化编辑、注册中心、跨进程分发

## Comments

- 现状核对（2026-11-03，写票前逐个看过，不是凭印象）：
  - `AgentAutoConfiguration:64` 的 `SYSTEM_PROMPT` 是 `private static final String`，三行；
    在 `chatClient` bean 里被 `.defaultSystem(SYSTEM_PROMPT)` 用在唯一那个 `ChatClient` 上
  - `ChatClientAgentPort` 持有单个 `ChatClient`（5 个重载构造器，都接同一个类型）——
    "按 agent 选"如果要落到运行期，改动点在它
  - `claw4j.skills.directory` 与 `claw4j.subagents.directory` 都是单个 string 占位符
    （`SkillToolAutoConfiguration:38`），不是 List
  - `ScheduleStore.zoneOf` 对空时区兜底 `ZoneId.systemDefault()`（第 243 行起）
  - `examples/subagents/` 已有 5 份 md（含 `metric-glossary.md` 这份纯口径解释），
    `examples/skills/` 有 1 份（`weekly-sales`，第一节就是口径）
- **门禁余量（硬约束，开工前先看）**：`claw4j-agent` 10 个测试文件（上限 10），
  其中 `ChatPipelineTest` / `InboundSecurityTest` / `ModelAndToolSelectionTest` /
  `SessionMemoryTest` 四条都已在 20 条用例上限；`claw4j-app` 同样 10 个文件。
  所以这张票**不能在 agent 或 app 新增测试文件**，也不能往那四个文件里加用例。
  可用的是 `claw4j-core`（8 个文件，还能加 2 个）与 agent 里未满的
  （`ToolCallAuditTest` 13 / `RecallSessionTest` 8 / `SemanticCacheTest` 15 / `ScheduleToolsTest` 14）。
- 与 P09 的关系：这张票是**层**，P09 是**轴**。profile 从全局变成可 per-agent 之后，
  "未分档就启动失败"那条校验必须跟着下沉到每一个 agent，不能因为多了一层就放宽。
- 拆票说明：M8 其余六张（P10–P14、P16）尚未拆，见 `.scratch/m8-durable/spec.md` 的 Comments。
- 2026-11-03 实现完成。**结构问题选了"同形"**：主 Agent 与子 Agent 都是
  「一份 md = front matter + 正文」，解析器 `FrontMatter` 从 `agent/subagent` **下沉到 core**
  （`io.github.claw4j.markdown`）。理由不是整齐：两套解析就是两套失败姿势，
  同一份写错的 front matter 在一处报错、在另一处静默——而"静默"在这个项目里每次都是
  "配置看着生效了、实际没生效"。下沉的副产物是它有了 core 的单测（`PersonaCatalogTest` 里那几条
  front matter 边界用例）。
- **人设模型落在 core**（`io.github.claw4j.persona`：`Persona` / `PersonaCatalog` /
  `PromptComposition` / `GlossarySection`），agent 只留三个薄件
  （`AgentProperties` / `AgentContextAdvisor` / `BuiltinPersona`）。
  这不是分层洁癖，是门禁逼出来的：`claw4j-agent` 与 `claw4j-app` 都已 10 个测试文件、
  agent 里还有四个文件在 20 条用例上限，纯逻辑放在那里等于没法测。
- **时间必须每轮算**，所以挪出了 `defaultSystem`（装配期定死）改用 `AgentContextAdvisor`
  （`ORDER = HIGHEST_PRECEDENCE + 2`，在入站拦截之后、其余之前）。
  查过一个真风险：语义缓存的 key 只取**最后一条用户消息**（`CacheAdvisor#questionOf`），
  不取 system，所以动态时间不会让缓存永不命中；精确到分钟也是为此留的余量。
- **三个如实交代**：
  1. **技能集合那一小项没有新做**。`claw4j.skills.directory` 本来就是配置项，
     两次部署指向不同目录即成立；这张票没动它，也没给出两份技能目录的样例。
     判据里那一小项按"已具备、本票未涉及"记，不当作已完成。
  2. **子 Agent 的口径没动**（`examples/subagents/metric-glossary.md` 仍在自己正文里写着）。
     原因是真的：子 Agent 有独立上下文窗口，`SubagentRunner` 只把
     `definition.systemPrompt()` 放进去，父的口径不会跟过去。
     要消掉这处重复，得给 `SubagentDefinition` 加 `glossary` 并让 runner 拼进去——
     那是另一件事，不在本票范围，也不该在这一票里顺手做。
  3. **agent 覆盖率差点破线**：第一次全量跑出来 60.9%（P09 后是 62.0%），离 60% 只剩 0.9 个点。
     原因是新增的 advisor 与装配在 agent 模块内没有测试执行到——`claw4j-app` 的端到端执行
     **不计入** agent 的覆盖率（各模块各用各的 `jacoco.exec`）。补了 3 条到 `SemanticCacheTest`
     才回到 61.8%。那 3 条放在这个文件里是因为文件数已满，理由写在文件里的注释：
     它们与缓存测的是同一件事——请求进入模型之前被改成了什么。
- **两个设计决定**（票里留给实现的）：
  - `tier` **只覆盖档位、不覆盖 allow / deny**。档位是"这个助手允许多宽"，排除项是
    "这套部署额外不要什么"。让排除项跟着人设走，会出现"同一个助手换个部署就少了几个工具"。
  - 没配目录时**保留内置人设**（`BuiltinPersona`），而不是启动失败。
    一个只跑 HTTP 的最小部署不该为了能起来先去造一个目录；代价是"有一份谁也没注意到的默认"，
    所以启动日志必须说清用的是内置的还是配的。

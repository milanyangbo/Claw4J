# A13 · 长期记忆：接库的 `AutoMemoryToolsAdvisor`，按人一个目录

**编号说明：** 这张票原先是 `A09`，与 `m8-agent-quality/spec.md` 里给失败诊断预留的
`A09-diagnosable-failure.md` 撞了号（ADR-0019 的 A06–A12 已各有所指，A11/A12 在 M9）。
2026-11-19 改成 A13——跨批次唯一、不复用，见 `AGENTS.md` 的编号口径。

**Status:** resolved（2026-11-19）
**Blocked by:** 无。
**Type:** 新增能力（一个新配置项 + 一个新的落盘目录）

## 为什么

会话记忆解决的是"这一轮接得上上一轮"，它记得住**经历**，记不住**事实**：压缩之后
"用户偏好 `static final`"、"这个项目的 API 基地址是哪个"这类东西会跟着窗口一起没掉。
长期记忆是另一条轴——模型自己判断"这条值得永久记"，写进文件，下次换一个会话还在。

这两层是互补的（ADR-0005 的会话记忆只管当前这一段），本票只补第二层。

## 找到什么，为什么不能原样接

库里有现成的：`spring-ai-agent-utils` 0.13.0 的
`org.springaicommunity.agent.tools.AutoMemoryTools`（6 个文件工具：`MemoryView` / `MemoryCreate` /
`MemoryStrReplace` / `MemoryInsert` / `MemoryDelete` / `MemoryRename`，带路径逃逸校验）与
`org.springaicommunity.agent.advisors.AutoMemoryToolsAdvisor`（把工具与一段记忆系统提示注入每一轮）。
本项目已经引了这个包（`SkillsTool`、`TaskTool` 都来自它），不需要新依赖。

但**原样接**会破三处本项目自己的约束，逐条核过（`unzip` / `javap` / 源码，不是凭印象）：

| 冲突 | 证据 |
| --- | --- |
| **没有身份**：记忆根目录是构建期一个固定 `String`，6 个 `@Tool` 方法都不吃 `ToolContext` | `AutoMemoryToolsAdvisor$Builder` 只有 `order` / `memoriesRootDirectory` / `memorySystemPrompt` / `memoryConsolidationTrigger` 四个旋钮；`AutoMemoryTools#memoryView(String, String)` 签名里没有 `ToolContext` |
| **绕开策略管线**：它在 `before()` 里把 6 个 callback 直接塞进 `toolOptions` | `AutoMemoryToolsAdvisor#before` 的 `toolCallbacks.addAll` 那一段；而本项目"工具不在 builder 挂默认 provider，每轮显式传筛过的清单"（`AgentAutoConfiguration.java:249`） |
| **落盘是明文**：模型直接写 Markdown，没有脱敏 | 会话表那边靠"记忆 advisor 排在脱敏之后收尾"拿到的才是掩码文本（`SessionMemoryConfiguration#ADVISOR_ORDER` 的注释），记忆文件没有对应的一道 |

**裁决（2026-11-19，用户）**：按人分目录；落盘不脱敏，写清缺口。

## 形状

一层**按身份分发**，库的逻辑一行不改：

- `PrincipalMemoryAdvisor` 实现 `BaseAdvisor`，在 `before()` 里从 `request.context()` 取
  `ContextKeys.PRINCIPAL`（`ChatClientAgentPort` 已经把身份放进 `toolContext`，两者是同一个 map）。
- 取到就转给**为这个人的目录构建的那个** `AutoMemoryToolsAdvisor`（按 userId 缓存实例）：
  系统提示、工具注入、按名字去重、`MEMORIES_ROOT_DIERCTORY` 渲染全部是库的。
- 取不到身份**原样返回**：没有身份就没有记忆，不 fallback 到公共目录。
- 目录：`<claw4j.memory.directory>/<安全化 userId>-<sha256 前 12 位>/`。安全化是必需的——
  `userId` 来自通道（飞书 open_id、邮箱都可能），直接当目录名会撞路径分隔符；哈希后缀防
  "两个不同的 id 安全化之后同名"，那种撞车的后果是两个人共用一份记忆。
- order = `HIGHEST_PRECEDENCE + 3`：在 persona（+2）之后，于是记忆块拼在最终系统提示的尾部；
  在脱敏（+200）与缓存（+250）之前。**不能留库的默认 +200**——那个值和脱敏撞，而它在本仓是
  一个已经占用的位置。

**为什么不把 6 个工具名登记进画像**：登记了也只是"名字在清单里、回调不在清单里"（回调只由
advisor 注入）。那种状态比不登记更坏——它看起来像是策略管线管得住。真正的缺口记在文末。

## TDD 用例清单

| 判什么行为 | 用例名 | 先红的样子 |
| --- | --- | --- |
| 两个人各写各的目录：A 建的文件不出现在 B 的目录下 | `SessionMemoryTest#eachPrincipalGetsItsOwnMemoryDirectory` | 原样接就是同一个目录 → 这条红 |
| 注入的就是库那 6 个工具，且系统提示追加在 persona 之后 | `SessionMemoryTest#memoryToolsComeFromTheLibrary` | 没有 advisor 时工具集里一个 `Memory*` 都没有 |
| 请求里没有身份：不注入工具、不改系统提示 | `SessionMemoryTest#withoutAPrincipalNothingIsInjected` | 早返回写错就是"匿名用户读写公共目录" |
| 目录名安全化：带 `/`、`:`、`@` 的 id 不会跑出根目录 | 同上（断言子目录数 = 1） | `Path.resolve` 收到绝对路径会整个替换掉根 |

| 攒够轮数才跑整理，且跑的是这个人的目录 | `ChatPipelineTest#dreamRunsOnlyAfterEnoughTurns` | 没有整理这条路时一个任务都不派 |
| 整理按人计：甲的轮数不算进乙的账 | `ChatPipelineTest#dreamStateIsPerPrincipal` | 状态若落在根目录，乙的第 4 轮会被甲的第 5 轮带着触发 |

**复核：2026-11-19 `./scripts/verify.sh` 全绿。**

## 缺口

- **落盘不脱敏**（裁决的一部分）：记忆文件是模型写的明文，与会话表/审计的口径不同。缓解是
  目录 0700、不进容器、不给子 Agent。要脱敏得包一层改写 6 个工具各自的入参 JSON，库一改版就断，
  这一条留到"记忆里真的开始出现号码"时再说。
- **策略管线管不到这 6 个工具**：注入发生在 prompt 层，`toolsBySender` / 沙箱 `bundle-mcp` /
  画像过滤都拦不住它。"这个 sender 不许写记忆"现在表达不出来。要表达得给请求上下文加一个
  "本轮允许的工具名"的键，再把 advisor 改成求交集——那是改公开契约，单独开票。
- **子 Agent 没有记忆**：子链一个 Advisor 都不挂（ADR-0012 修订），所以子 Agent 看不到也写不了
  长期记忆。这是有意的：让子 Agent 写记忆等于让一次性执行体改父的长期状态。

## 二审（2026-11-19 晚）：整理不自己写，用库的 dream

第一版我自己写了一个 `MemoryConsolidation`（按人记"上次检查时刻"，够 3 份文件就提醒一句）。
用户驳回：**官方有没有类似功能，没有就不要实现**。核过之后官方有一整套，我那个类已删：

| 库的件 | 管什么 | 依据 |
| --- | --- | --- |
| `DreamTriggers.hoursAndSessions(h, n)` | 距上次够久 **且** 又攒够 n 轮才整理 | 注释里写明照 Claude Code 的双条件启发式 |
| `DreamState` | 计数落在 `<记忆目录>/.dream-state.json` | 按人一个目录 → 天然按人一份 |
| `AutoDreamService` | 真去合并/精简/删过时（带着记忆工具，`DreamMode.AUTO_APPLY`） | 单独的 dreamer `ChatClient`，不挂任何 Advisor |
| `AutoDreamAdvisor` | 挂在 `after`，判完派后台任务，请求一个字不改 | `before` 直接原样返回 |
| `DreamLock` | 按目录加锁，15 分钟算过期 | 同一份记忆不会两个 dreamer 同时改 |

**为什么必须是分发而不是直接挂一个**：`AutoDreamAdvisor` 同样把目录写在字段里
（`memoriesRootDirectory`），一份只能服务一个人。所以 `PrincipalDreamAdvisor` 只做按人分发，
`before` 空着、`after` 转给那个人的实例；身份从 `ChatClientResponse#context()` 取（框架的
`ChatModelCallAdvisor` 会把请求上下文放进响应，流式由聚合器累积）。

**两件踩出来的事**：

1. **库不替我们建目录**：`DreamState.save` 直接往 `<记忆目录>/.dream-state.json` 写，第一个用户的
   第一次收尾就抛 `UncheckedIOException`，把一轮正常的回答弄挂（用例先红出来的就是这个）。
   现在在装 advisor 时建目录；建不出来就降级成"这个人本次进程不整理"并记一条 warn——
   记忆是附加信息，不是可用性依赖。
2. **`AutoDreamService` 要一个 `ChatClient.Builder`**：用主模型建，不挂任何 Advisor。
   dreamer 看不到当前对话，也不该被会话记忆、脱敏、语义缓存影响。

**默认参数** `hoursAndSessions(24, 5)`；关掉用 `claw4j.memory.dream=false`（传库的
`manualOnly`），不自己写空实现。

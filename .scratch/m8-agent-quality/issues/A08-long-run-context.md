# A08 · 长 run 的上下文：压缩可以丢细节，不能丢进度与失败

**Status:** ready-for-agent
**Blocked by:** A03（已落地：`UpdatePlanTool` + `RunStore#planOf`，计划已经在库里）、
A06（已落地，`ea5e11b`）。这张票的"有没有变好"要读 A06 的报告，不靠肉眼。

## 为什么

ADR-0019 的原话：**"压缩可以丢细节，不能丢『还剩几步没做』和『上一次为什么失败』。
今天 `SessionCompactor` 每轮压缩，这两样都在进去之前就被压没了。"**

"A03 已经把计划外置到 `agent_step`"这句话是对的，但它只回答了"库里有"，
没回答"模型看得见"。压完之后上下文里只剩一段模型写的自然语言摘要，
而摘要是按"对话里说了什么"概括的，不是按"这件事做到哪了"概括的。
模型拿到一段"用户在核对销售数据，讨论了华东与华南"的摘要，下一步最常见的动作是
**把已经做完的那一步再做一遍**——而这个动作看起来跟"接着做"一模一样，
在日志上没有异常、没有报错，只有一个多余的工具调用。

失败记录同理。工具失败的原因分散在 `StepKind.TOOL_CALLS` + `FAILED` 的行里，
压完之后模型只看到"有一次调用不顺利"，看不到"是 `region_code` 这个列名不存在"。
于是它下次还用同一个写法——**同一个错误犯两次，第二次也找不到原因**。

## 现状核实（照源码核过，不是照 ADR 抄的）

| 事实 | 在哪儿 |
| --- | --- |
| 压缩策略是 `final` 类，不能继承。可调的只有 `maxEventsToKeep` / `overlapSize` / `systemPrompt` / `shadowPrompt` / `eventFormatter` | `RecursiveSummarizationCompactionStrategy`（`spring-ai-session` 0.10.0，`javap` 核过） |
| 摘要由**模型**生成，是一段自然语言；`systemPrompt` 只能改"怎么概括"，改不出结构化字段 | 同上 |
| 压缩阈值默认 `65536 × 0.5 = 32768` token，保留最近 20 条事件 | `SessionMemoryProperties` |
| 框架**内建**"系统消息被钉住"的语义：`pinnedSystemEvents` 从末尾往前找**第一条**「非 synthetic 的 `SYSTEM`」事件，压缩时不碰它；`supersededSystemEvents` 把更旧的系统事件归档 | `CompactionUtils`（字节码核过） |
| 记忆 Advisor 会把最新一条存储的系统提示补进发给模型的消息列表（`latestStoredSystemPrompt` 的结果被 `messages.add`；分页/截断查询时专门补一次，否则它作为活动事件照常进） | `SessionMemoryAdvisor` 字节码（偏移 197–276） |
| 但 `DefaultSessionService#appendEvent` 在 `allowSystemMessages == false` 时**直接抛** `IllegalArgumentException` | 字节码核过 |
| 而这个开关**默认就是 false**，本项目**没有配过** → 现在往会话里写 SYSTEM 事件会抛 | `SessionServiceProperties`（`CONFIG_PREFIX = "spring.ai.session"`，构造器里 `iconst_0`）；`application.yml` 里没有这一项 |
| **那个异常不会被降级层吞掉**：`FaultTolerantSessionService#fallback` 只 catch `DataAccessException \| TransactionException`，`IllegalArgumentException` 会一路冒到 `promptOf`，**把整轮对话打成 500** | `FaultTolerantSessionService:128-136` |
| `appendMessage` 是接口 default 方法，内部调 `this.appendEvent(...)`，所以它走的是上面那条链（存储不可用有降级、配置不对没有） | `SessionService` 字节码 |
| 压缩的调用点已经拿着 runId：`ChatClientAgentPort#promptOf(principal, owner, masked, runId)` 里调 `compactIfNeeded` | `ChatClientAgentPort:732` |
| 计划与失败都读得到：`RunStore#planOf` / `#stepsOf`，失败是 `TOOL_CALLS` + `FAILED` 行的 `resultSummary` | `RunStore`、`StepKind`、`StepStatus` |

**最后两行是本票能成立的原因**：要保的两样东西在库里都有，缺的只是一个"在压缩发生的那一刻、
把它们重新放回上下文"的动作。

## 裁决：压缩前把进度写成一条系统事件，不塞进摘要

三条候选，选第三条：

1. **在 `systemPrompt` 里嘱咐摘要模型"保留待办与失败原因"** —— 否决。这是自然语言，
   而且第二次压缩会把它再概括一次。第一次压完还剩个大概，第三次压完就剩"用户在处理数据"。
   嘱咐的强度与压缩次数成反比，而长 run 恰恰压很多次。
2. **装饰 `CompactionStrategy`，把前缀拼进摘要文本再返回** —— 否决。`CompactionResult` 是 record
   可以重造，但拼进去的前缀下一次压缩会跟着摘要一起被概括，回到第 1 条的命运；
   除非每次都重新拼——那又回到了"前缀是代码生成的"，只是把它放错了地方（放进一个会被压的容器）。
3. **压缩**前**往会话里写一条 `SYSTEM` 事件，内容由代码拼** —— **选它**。因为框架对这个位置
   有明确语义：最后一条系统事件被钉住、不进压缩、旧的被 superseded 归档、记忆 Advisor 会把它
   当系统提示读给模型。前缀因此**不经过模型、不参与概括、每次都从库里重新生成**，
   长 run 压多少次都还在。

ADR 那句"摘要里带结构化前缀"照这个实现读：**压缩之后的那段上下文里要有结构化的东西**，
不是字面上把字段拼进摘要正文。第 3 条满足得比字面实现更彻底——字面实现的前缀会被下一次压缩吃掉。

**代价写在明处**：这条路要打开 `spring.ai.session.allow-system-messages`。打开之后
"往会话里写系统消息"这件事在这个进程里就是合法的，而最后一条会顶掉更早的那条。
今天没有任何别的组件往会话写系统消息，所以不冲突；将来若有人写，两方会互相顶。
这条在实施时落进 `application.yml` 的注释里。

## 前缀长什么样（固定格式，因为要能被断言）

```text
[当前进度] run 3f9a1c2e
计划共 3 步：
1. [已做] 核对华东上周成交额
2. [待做] 核对华南上周成交额
3. [待做] 汇总三个区并按口径说明
上次失败：querySales —— column "region_code" does not exist
```

四条形状约束，每条对应一个坑：

1. **首行固定 `[当前进度]`**。没有固定标记，断言只能写"回答里有没有『待做』"，
   而这个词在任何一段自然语言摘要里都可能出现——判据等于没写。
2. **失败只取最近一条，截断 200 字**。全部倒出来会把前缀本身撑成第二个上下文，
   而压缩的初衷就是省 token。
3. **没有计划、也没有失败时不写**。否则每条正常对话都会多一条系统事件，
   评测里五十条用例的会话表一半是这种噪声，A06 花了五次才理干净的"串味"会以另一种形式回来。
4. **`run` 后只留 8 位**。与 `UpdatePlanTool#render` 同一个口径，两处显示同一个东西的写法要一致。

## 落点（五处）

1. **新类** `io.github.claw4j.agent.run.RunProgress`：一个纯函数 + 一个 record。
   `static String render(List<Step> plan, List<Step> steps, String runId)` 返回上面那段文本
   （无内容时返回空串，由调用方决定不写）；`static Optional<String> lastFailure(List<Step>)`。
   放主代码、进回归、背覆盖率——它是本票唯一有判断逻辑的地方。
   **两个入参不是冗余**：`stepsOf` 里有全部 step，包括历史版本的 `PLAN` 行；
   "哪一段 seq 才是当前这一版计划"的判定在 `planOf` 里（`JdbcRunStore:324` 的
   `seq >= 上一次重写的位置`）。想省掉一次读、自己从 `stepsOf` 里筛，就会把重写过的旧计划
   也当成当前计划——模型于是看到两份步骤，而这件事不报错。
2. **`SessionCompactor`**：`compactIfNeeded(String sessionId)` 改成
   `compactIfNeeded(String sessionId, String runId)`（旧签名保留一个 default 重载，转调 `(id, null)`，
   免得三处调用点与一处构造一起改）。流程：读 plan + 失败 → 渲染 → 与"会话里最新一条系统事件"比对 →
   不同则 `appendMessage(sessionId, new SystemMessage(prefix))` → 仍然走原来的 `sessions.compact(...)`。
   **写入那一句自己 try/catch `RuntimeException`**，不能指望降级层：它只认两类存储异常，
   配置被改回 `false` 时抛上来的 `IllegalArgumentException` 会把整轮对话打成 500。
   降级时留一行 `WARN` + 一个计数（`claw4j.session.progress.write_failures`），
   理由是"前缀写不进去"最坏的表现是"进度又开始丢了"，那是没有红灯的那一类。
3. **`SessionCompactor` 的构造**：加一个 `RunStore` 参数（**允许为 null**），不在构造里包
   `ObjectProvider`——Provider 是装配层的事，测试里传 `null` 就能覆盖"没装 run 存储"那条分支，
   而构造里再套一层 Provider 只会让二十处测试构造都要造一个 Provider。
   装配处（`SessionMemoryConfiguration#sessionCompactor`）用 `ObjectProvider<RunStore>.getIfAvailable()` 取。
   没装 run 存储的部署里这个 bean 不存在，压缩本身要照常工作。
4. **`ChatClientAgentPort#promptOf`**：`compactor.compactIfNeeded(sessionId, runId)`。
5. **`application.yml`**：`spring.ai.session.allow-system-messages: true`，注释写清
   "打开它是因为 A08 要往会话里写进度前缀；关掉之后前缀写不进去，压缩后进度就丢了"。

## 怎么测：一条方法、三段（门禁已经顶格）

`claw4j-agent` 已经 10 个测试文件（上限 10）、`SessionMemoryTest` 已经 20 个方法（上限 20）。
本票**不新增测试文件、不新增方法**，改写 `longConversationGetsCompacted`——它现在断言的
恰好是"压缩之后事件条数下降"，正是本票要加条件的那一条：

- **第一段（原有，保留）**：200 条事件 → 压完条数下降、`archived` 指标 > 0。
- **第二段（新增）**：同一个会话先 `setPlan(3 步)` 并标 1 步 done、再追加一条 `FAILED` 的
  `TOOL_CALLS`，然后压缩 → `getActiveMessages` 里仍能看到 `[当前进度]`、`[待做]`、
  `上次失败`。反例：把写入那一段注释掉 → 压缩后这三样一个都不在。
- **第三段（新增，反向守）**：没有计划也没有失败时压缩 → 会话里**没有** `[当前进度]` 事件。
  反例：无条件每轮都写 → 这条红，且它守的是"别给所有对话加噪声"。

另有一处必须顺手改：`SessionMemoryTest` 里三处 `compactIfNeeded(...)` 调用（271、286、301 行）
走单参重载，**不用动**；351 行的 `new SessionCompactor(...)` 要多传一个 `RunStore`
（那三处传 `null` 即可，正好覆盖"没有 run 上下文"的分支）。改完 `./scripts/verify.sh` 跑一遍，
确认没有别处从签名上漏掉。

`RunStore` 用 `mock(RunStore.class)` + `when(runs.planOf(runId)).thenReturn(...)` 造——
这个文件已经在用 Mockito 造 `SessionService`，不需要为测试再写一个 RunStore 实现。
`Step` 是 record，8 个参数直接 `new`，造一条 `TOOL_CALLS` + `FAILED` + `resultSummary` 的行即可。

**一个会卡住半小时的细节，先记下来**：测试里的 `MapSessionService` 是替身，它的
`appendEvent` **不校验** `allowSystemMessages`（只照抄了 `compact` 那段的形状，没抄这条校验）。
所以"配置关掉时会抛"这个反例在它身上测不出来——但那不是坏事：正向用例因此不依赖 `application.yml`。
反例要单独造：`mock(SessionService.class)` + `doThrow(new IllegalArgumentException(...))`
打在 **`appendMessage`** 上（不是 `appendEvent`——`appendMessage` 是接口 default 方法，
Mockito 的 mock 不会真的执行 default 方法体，所以它不会转调 `appendEvent`），
然后断言 `compactIfNeeded` 不抛、且 `progress.write_failures` 涨 1。

## 不做什么

- **不改 A03 的计划结构**。计划已经在 `agent_step` 里，本票只读不写。
- **不把失败原因做成"让模型重试一次"**——那是 A09。这张票只保证"它看得见上次为什么失败"。
- **不做跨 run 的进度汇总**。"这件事没做完，上一件事也挂着"这种跨 run 的账，要 `RunStore`
  新增一个"按 owner 找未收尾 run"的查询，而 ADR 的判据只要求同一个长 run 内不丢。留给以后。
- **不把前缀塞进 `recall_session`**。召回是原文，进度是结构，两件事。
- **不动 `eventsToKeep` 的默认值**。压得浅一点确实能让上下文多留一会儿，但那只是把问题推迟。

## 代价与已知局限

- **每轮多三次读**：`planOf`、`stepsOf`（取失败）、`getEvents`（比对前缀是否已写过）。
  runId 为空时三次都不发生。压缩本来每轮就要读一次事件估算 token，所以是常数级的增加，
  不是数量级。要记账：如果跑下来发现这几次读成为热点，就把"比对"换成进程内缓存 + 版本号，
  **但不要合并前两个查询**——理由见落点 1。
- **前缀会被模型当成系统指令**。它写的是"计划第 3 步待做"，而模型可能据此**只做那一步**、
  忽略用户这一句里新提的要求。这是语义上的真实风险，判据里用一条反向用例守：
  用户明确说"先别管计划，帮我看下 X"，模型要照做。这条不做进回归（它要真模型），进评测。
- **前缀里带工具名与错误原文**。`resultSummary` 已经是不落参数原文的口径（ADR-0023），
  所以带出去的是"哪个工具、什么错"，不是参数。但错误原文可能含表名、列名——
  这在"给模型看"的范围内，不出站，不过脱敏 Advisor 也管不到它（它在系统提示里）。
  这一点记在这里，避免以后有人以为它过了脱敏。
- **`allow-system-messages` 打开之后没有退路**。关掉它，前缀静默写不进去（被 `appendEvent` 抛，
  被我们 catch 成降级），而**压缩照常发生**——表现是"进度又开始丢了"，没有任何红灯。
  所以判据里有一条专门守这个开关：配置项存在且为 true，且关掉时有一条测试说明会发生什么。

## 门禁余量

| 模块 | 现在 | 本票之后 | 说明 |
| --- | --- | --- | --- |
| `claw4j-agent` | 10 文件（顶格）/ 六个文件已顶 20 方法（`ToolGovernanceTest`、`SessionMemoryTest`、`ObservabilityTest`、`ModelAndToolSelectionTest`、`InboundSecurityTest`、`ChatPipelineTest`） | **10 / 20** | 只改写 `longConversationGetsCompacted`，不新增方法 |
| `claw4j-eval` | 3 文件 / 最大 16 方法 | 不变或 +1 | 评测用例是 yaml，不是测试方法 |

覆盖率：`claw4j-agent` 现在 66.2%。`RunProgress` 是主代码新类，由改写后的那条用例覆盖；
`SessionCompactor` 新增的两个分支（前缀相同不写、写入失败降级）也要覆盖到，
所以这条用例的断言会比现在长——长是应该的，它现在只守"压下去了"，不守"压完之后还剩什么"。

## 验收判据

- [ ] **压完之后进度还在**：200 条事件 + 3 步计划（1 步 done）+ 一条 `FAILED` 的工具调用，
      压缩后 `getActiveMessages` 仍含 `[当前进度]`、`[待做]`、`上次失败` 三样。
      反例：注释掉写入那一段 → 一个都不在。
- [ ] **前缀是结构化文本，不是自然语言**：断言首行是 `[当前进度] run `、计划行带
      `[已做]` / `[待做]` 标记、失败行以 `上次失败：` 开头。反例：只断言"含『计划』二字" →
      模型写的摘要里也有这两个字，等于没守。
- [ ] **没有计划也没有失败时不写**：压缩前后会话里都没有 `[当前进度]` 事件。
      反例：无条件写 → 红。
- [ ] **相同内容不重复写**：计划与失败都没变时连压两次，活动事件里只有**一条**带
      `[当前进度]` 的。实现靠的是"比对后不写"，不是靠框架兜底（`supersededSystemEvents`
      要等到下一次压缩才归档旧的，中间那一轮会出现两条，模型看到两份互相矛盾的进度）。
      反例：不比对直接写 → 两条。
- [ ] **计划变了要写新的**：第 2 步标成 done 之后再压一次，前缀里 `[待做]` 从 2 步变 1 步。
      反例：只在"前缀不存在时"写 → 永远停在第一版计划，比不写更坏（模型按过期计划干活）。
- [ ] **没有 runId 时不写、不抛**：`compactIfNeeded(sessionId, null)` 与
      `compactIfNeeded(sessionId)` 都只压缩、不写前缀。
- [ ] **没装 run 存储时照常压缩**：容器里没有 `RunStore` bean，`SessionCompactor` 仍能建起来
      并压缩（`ObjectProvider` 的空分支）。反例：构造参数写死 `RunStore` → 启动失败。
- [ ] **写不进去不拖垮这一轮**：会话不存在、`appendMessage` 抛异常（含配置被改回 `false` 时的
      `IllegalArgumentException`）时，压缩与回答照常，且**不计入**
      `claw4j.session.compaction.failures`（那是"压缩失败"的指标，混进来之后这个数从第一天起就不是 0），
      但**要**计入新开的 `progress.write_failures`。反例：不在写入处 catch → 整轮 500，
      而且这条只有把配置改回 `false` 才复现，所以它必须是一个能跑的用例，不是一句注释。
- [ ] **配置项真的打开了**：`spring.ai.session.allow-system-messages=true` 在
      `application.yml` 里，且有一条用例说明"关掉它会发生什么"（写不进去、压缩照常、
      进度开始丢，且没有红灯）。
- [ ] **真模型答得出**：`./scripts/eval.sh memory` 里一条长任务用例，跑到压缩之后仍能答出
      未完项与上次失败原因。锚必须是标识符（计划某一步里带着订单号 `SO-LAST-02`、
      失败的工具名 `querySales`），不能是"待做"这种词。
      **锚怎么保证存在**：setup 里明说"计划每一步要带上你要核对的订单号"，
      模型不照做时这条用例红——那是真红（指令没被遵守），不是判据写坏。
      **怎么让它一定压到**：默认阈值是 32768 token，靠 setup 堆到那么长不现实；
      在 `EvalRunTest` 的 `@SpringBootTest(properties = ...)` 里把
      `claw4j.session.context-window` 调到几千（阈值 = 窗口 × 0.5），几轮长回答就到。
      这个值是全局的，但其它用例的会话都很短、不会触发压缩，所以不影响它们的结论——
      改完要**全量跑一次**确认这一点，而不是只跑 memory 类。
      反例：只跑单测就交差。
- [ ] **评测不倒退**：`./scripts/eval.sh` 全量跑一次，除本票新增那一类之外，
      `answerHit` 不少于改前的那份报告（改前基线读 A06 提交时的用例集，digest `1fe1bd8`）。
- [ ] `./scripts/verify.sh` 全绿，跳过 0，`claw4j-agent` 文件数仍 10、每文件方法数 ≤20、
      行覆盖率不低于 60%，除被置换那条外既有用例一条不改地通过。

### 验收证据

| # | 怎么验的 | 结果 |
| --- | --- | --- |
| 1 | | |
| 2 | | |
| 3 | | |
| 4 | | |
| 5 | | |
| 6 | | |
| 7 | | |
| 8 | | |
| 9 | | |
| 10 | | |
| 11 | | |
| 12 | | |

收票时逐条填「怎么验的 / 结果」，一条都不许空着。A06 与 A07 的证据表都是这么填的，
空一行等于把那条判据降级成"我觉得没问题"。

## 落地前要确认的三件事

1. **打开 `allow-system-messages` 之后，模型实际收到几条 system 消息**。项目自己在
   `ToolSearch.SYSTEM_SUFFIX` 那里往系统消息里追加了一段，会话里现在又多一条。
   Spring AI 对多条 system 的处理是把它们拼起来还是只留一条，要看**实际发给模型的 messages 列表**
   （不是 `prompt.toString()`，那个看不全）。**先把 `ChatRequest` 里的 messages 打一遍再动手**——
   这不是"应该会拼起来"能回答的问题，而猜错的后果是"前缀写进去了、模型没看到"，
   判据一在单测里绿、在真模型上红。
2. **`appendMessage` 的时机与压缩的关系**。前缀必须在 `sessions.compact(...)` **之前**写：
   先写，它才是"最后一条系统事件"、才被 `pinnedSystemEvents` 钉住；后写，旧前缀会被这次压缩
   当成普通事件压进摘要，模型于是同时看到"摘要里的旧进度"和"事件里的新进度"。
   写反了不会报错，只会让模型按旧计划干活。**实现时先写一条注释把顺序钉住**，
   再写真代码。
3. **`getEvents` 拿到的最后一条系统事件是不是我们写的那条**。`FaultTolerantSessionService`
   在存储不可用时返回空列表，"空"与"没有前缀"在调用方看来是同一件事——那就会每轮都写。
   降级时的动作要定下来：**读不到就当作没有，照写**（宁可重复，不可丢），并在日志里留一行。
   这条写进代码注释，不然下一次有人"优化"成"读不到就跳过"，前缀就没了。

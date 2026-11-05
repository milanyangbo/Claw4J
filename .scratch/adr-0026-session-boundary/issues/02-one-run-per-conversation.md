# 02 · 一段对话同时只有一个 run

**Status:** resolved（2026-11-05）
**Blocked by:** 01（已落地：段、指针、`claw4j.session.reset.*`）

## 为什么

OpenClaw 的队列文档里有一句硬保证：

> `runEmbeddedPiAgent` enqueues by **session key** (lane `session:<key>`) to guarantee
> **only one active run per session**. … Each session is still serialized
> (one run per session key at a time).

我们这一条是空的。全仓没有一把按会话的锁（`synchronized` 命中的都是 token 刷新、脚本登记、
MCP 连接这类局部状态），也没有 lane；`InboundDispatcher.accept` 提交即执行，
HTTP 流式池 core 4 / max 32 / queue 200（`HttpChannelAutoConfiguration:67`），
飞书、钉钉、Slack、企微各一个有界池。**两条消息 = 两个 run = 同一个 sessionId。**

三个后果，按严重度排：

1. **第二条消息读不到第一条的答案。** `SessionMemoryAdvisor.before` 拼提示词，
   `after` 才把回答写回会话。用户问"华东多少"、紧接着问"那华南呢"，
   第二句的上下文里有第一句的**问题**、没有**数字**——模型要么重查一遍，要么反问。
2. **预算可超发。** `RunBudget:90-100` 是"读 → 判 → 扣"，它自己的 javadoc 写着
   "**两次读之间也没有原子性**"。ADR-0025 把预算改成按会话累计之后，
   两条并发消息同时看到 5/6，各自扣一笔，结果 7/6。
3. **写回顺序可能倒置。** 两条 `after` 谁先落库看谁跑得快，聊天记录里可能是"答 B、答 A"。

   > **落地时核过，这条假设是错的**（见「实现记录 · 判据 7」）：`appendEvent` 的 SQL 是
   > `UPDATE AI_SESSION SET event_version = event_version + 1 WHERE id = ?`，**没有
   > `AND event_version = ?`**，所以不存在"两条里有一条拿到 0 行"。带 CAS 的只有压缩
   > （`applyCompaction`），它失败时返回"没压"而不是抛。也就是说并发追加事件本身
   > 不会 500；但**顺序错乱仍然成立**（谁先写完谁在前），而且"第二条读不到第一条的答案"
   > 也仍然成立——那两条才是要修的东西。

## 落点

### 1. 新类 `ConversationLane`（`agent` 包）

```java
<T> T run(String address, Duration wait, Supplier<T> work)   // 拿不到锁就抛 LaneBusyException
```

内部 `ConcurrentHashMap<String, ReentrantLock>`。**不用 fair 锁**：公平锁的代价在每次唤醒都要查队列，
而这里的争用只发生在"同一个人连发两句"这种短窗口；顺序由先到先得即可，
真需要顺序的是"同一段会话内的历史"，那由串行化本身保证，不由锁的公平性保证。

**锁要能回收**：`unlock` 之后若 `!lock.hasQueuedThreads()`，从 map 里 `remove(key, lock)`。
不做回收的话，map 会随对话数一直长——一个跑了半年的进程里，这是一张没人清理的表。

### 2. 键是"对话地址"，不是 sessionId

```text
addr:<owner>|<channel>|<chatId>
```

理由写在 ADR-0026 决定二：段会换，拿 sessionId 当锁键，跨过切换点就是两把不同的锁。
段在锁**里面**算，算完才用（这也是 01 那条"并发两条只轮换一次"的保证来源）。

### 3. 审批续跑也要进同一把锁

续跑那条路没有入站消息（`ChatClientAgentPort.resume`），手上只有 `run.sessionOf(...)`——
一串已经拼好的 sessionId，反解不出地址（`chatId` 里可能出现 `:`，解析是脆的）。

所以 run 上多记一列地址：

```sql
-- V18__run_session_address.sql
alter table claw4j_run.agent_run add column if not exists session_address text;
```

`openRun` 写它，`resume` 读它加同一把锁。不这么做的话，人是点完审批、机器人在后台续跑，
而用户可能正在同一个聊天里打字——两条会同时进同一段会话，正是本票要消灭的那件事。
老 run 没有这一列 → 不加锁（并记一条 debug），不回退成"两个人各跑各的"更糟的行为。

### 4. 包在哪儿

`ChatClientAgentPort.answer` 与 `stream` 的**最外层**：进方法先拿 lane，再算 sessionId、
开 run、调模型。放在 `promptOf` 里就晚了——run 已经开出来了，审计里会多出一条并行的事。

### 5. 等待与失败

- 等待上限 `claw4j.agent.lane-wait`（默认 60 秒，与通道池能容忍的时间同一量级）。
- 超时：回一句 `InboundDispatcher.OVERLOADED_REPLY`（"现在有点忙，请稍后再问一次。"），
  并把 run 标失败。**不静默丢**：已经 ACK 的消息没有任何回音，用户只会以为机器人坏了。
- 不排队到磁盘、不跨进程：单实例是既定形态（ADR-0009），跨进程的队列属于 A12。

## 已知代价

**等待会占住一个池线程。** 32 个线程同时各等一个人，池就满了，第 33 个请求拿 503。
在"一段会话一个 run"的前提下，这种情况只在"单个用户疯狂连发"时出现，
而那时 503 本来也是对的答案。真正的调度（优先级、公平配额、跨进程）是 A12，
本票不假装把它做完了。

## 怎么测（门禁顶格）

`ChatPipelineTest`、`SessionMemoryTest` 都是 20 个方法（上限），文件数也是 10（上限）。
本票**置换一条**：候选是 `SessionMemoryTest#secondTurnCarriesTheFirstTurnToTheModel`——
它断的是"顺序两轮，第二轮能看到第一轮"，而新用例断的是"并发两轮，第二条**等**第一条做完再看"。
后者严格强于前者：并发过了，顺序一定过。**如果实现时发现这条被别的断言依赖，
换一条更弱的置换，并在票面记下换了哪条、为什么。**

并发用例的写法：两个线程同时 `port.reply(...)`，用 `CountDownLatch` 让两条在同一刻进入，
第一条的模型响应里带一个可识别的锚（比如 `"128400"`），断言第二条的提示词 `contains("128400")`。

## 不做什么

- **不做全局并发上限与配额**：A12。
- **不做 collect / steer**（忙时消息合并成一次后续 turn）：那是另一种产品语义，
  要先定"合并之后算谁的问题"，不是串行化的必要条件。
- **不重做去重**：`InboundDispatcher.SeenEventIds` 解决的是"同一条事件重推"，与本票正交。

## 验收判据

- [x] **并发两条，第二条看得见第一条**：同一 `chatId` 同时发两句，第二条的提示词里含第一条的答案。
      反例：把 lane 调用删掉 → 红（现在就是红的）。
      证据：`SessionMemoryTest#concurrentMessagesAreSerialized`。
- [x] **两条 run 在同一段会话里**：`select count(distinct session_id)` = 1。
      反例：lane 键用 `chatId` 而不是 `addr:owner|channel|chatId` → 换了权限的同一段群聊会串到一起。
      证据：同一条用例断言 `sessions.createdIds()` 只建了一段。
      与反例的差距写在「实现记录 · 没做的两条」里。
- [ ] **键不是 sessionId**：在段轮换的临界点上并发两条，段必须只有一个。
      反例：把键换成 sessionId → 01 那条"只轮换一次"的断言红。
      **没有做**：要触发"两条同时在临界点"，得把 `ConversationPointers` 的时钟卡在 4:00
      前的毫秒上，而它是 `ChatClientAgentPort` 的构造依赖、测试里换不掉。见「没做的两条」。
- [x] **等待有上限、超时说话**：等不到锁时回 `OVERLOADED_REPLY`，不抛 500、不静默。
      反例：改成 `tryLock()` 立即失败 → 用户连发两条，第二条拿到"现在有点忙"，红。
      证据：同一条用例里 `ConversationLane(Duration.ZERO)` 的调用方立刻拿到
      `ConversationLane.BUSY_REPLY`，第二条一句模型都没调。
- [ ] **审批续跑与用户消息串行**：`resume` 期间同一对话的入站消息必须等。
      反例：resume 不加锁 → 两条 run 同时写同一段会话，审计里出现两条并行的"这一轮"。
      **只验了一半**：`session_address` 的写入与读回有真库用例
      （`RunSchemaSqlTest#budgetColumnsAccumulateAndThePointerKeepsOneRow`），
      但"resume 真的等锁"没有自动化——见「没做的两条」。
- [x] **锁表不泄漏**：跑完 100 个不同地址的请求，map 大小回到 0。
      反例：只 get 不 remove → 大小 100，红。
      证据：同一条用例末尾断言 `lane.size()` 为 0；反例 6 让它变成 1 并红。
- [x] **会话追加的乐观锁行为已确认**：写一条临时用例并发追加事件，记录框架是重试还是抛；
      若抛，在票面记下并把 `answer` 的那个 catch 补上明确处理。反例：不确认 → 线上偶发 500 无名。
      证据：读 `spring-ai-session-jdbc` 0.10.0 的字节码（`javap -p -c`），结论见下。
- [x] `./scripts/verify.sh` 全绿，跳过 0；`claw4j-agent` 10 文件 / ≤20 方法，覆盖率不低于 60%。
      证据：672 条、失败 0、跳过 0，agent 66.0%（10 文件 / 20 方法）。

## 实现记录

### 落在哪儿

| 东西 | 位置 |
| --- | --- |
| 锁 | `ConversationLane`（`agent` 包）：`ConcurrentHashMap<String, Lane>`，引用计数归零即删 |
| 超时 | `LaneBusyException`（不带地址：异常消息最容易被打进日志或回给调用方） |
| 包在哪儿 | `ChatClientAgentPort#inLane`（`reply`/`launch`）、`stream` 的最外层、`resume` |
| 续跑的地址 | `V18__run_session_address.sql` + `RunStore#attachAddress/sessionAddressOf` |
| 配置 | `claw4j.agent.lane-wait`（默认 60 秒，`AgentProperties.laneWait`） |

### 判据 7 的结论（推翻了原来的假设）

`JdbcSessionRepository` 里两条 SQL 不是一回事：

- **追加事件**：`UPDATE AI_SESSION SET event_version = event_version + 1 WHERE id = ?`——
  **没有 CAS**。`0 行` 只表示"这个会话不存在"，抛的是 `IllegalArgumentException`，
  与并发无关。所以"并发追加可能拿到 0 行然后抛"这件事**不存在**。
- **压缩**：`UPDATE ... WHERE id = ? AND event_version = ?` 是 CAS，但它失败时返回
  `Boolean.FALSE`，调用方据此返回"这次没压"，**不抛**。并发压缩最坏是"少压一次"，
  下一轮会再压。

也就是说，串行化在这一层**没有**修掉一个会 500 的东西——它修的是另外两件：
第二条读不到第一条的答案、写回顺序倒置。这一条写清楚，是为了不让后来的人以为
"加了 lane 就不会偶发 500 了"。

### 与票面的三处偏差

1. **没有用"unlock 之后看 hasQueuedThreads"回收**（票面第 44 行的写法）。它在
   "判断空闲"与"移除"之间有窗口：另一个线程正好在这两步之间拿到这把锁，就会被移走，
   下一个人建新锁——两条消息并发，正是本类要消灭的东西。改成**引用计数**，
   加减与删除都在 `compute` 的原子段里。
2. **`AgentProperties` 加 `laneWait` 后不能加便捷构造器**。加了之后
   `@ConfigurationProperties` 的构造器绑定会报 `No default constructor found`
   （record 出现第二个构造器时，绑定不再认定规范构造器）。原来想用它免掉 7 处测试改动，
   结果是整个应用起不来。改成 7 处测试各补一个 `null`。
3. **`resume` 的锁在 `reopen` 之前拿**。票面没写顺序。反过来（先 reopen 再等锁）会让
   一条 run 卡在 `running` 而没人收尾——那比"这次没执行"难查，因为它看起来成功过。
   拿不到锁时回一句 `BUSY_REPLY`，run 仍停在 `suspended`，审批方可以再来一次。

### 两条反例，跑完复原

| # | 改坏什么 | 红了哪条 |
| --- | --- | --- |
| 1 | `inLane` 里绕过 `lane.run`，直接 `work.get()` | 第二条拿到 `华南上周成交 66400` 而不是 `BUSY_REPLY`；`model.prompts()` 变成 2 |
| 6 | `release` 里不删（`return existing`） | `lane.size()` 从 0 变成 1 |

反例 1 期间有一次编辑把 `inLane` 写坏了（两个 `try` 叠在一起），编译直接报
`';' expected`。这不影响结论，但值得记：**反例要一次只改一处，改完先编译**。

### 没做的两条，以及为什么

- **判据 3（键不是 sessionId）**：要造"两条同时跨过 4:00"，得让 `ConversationPointers`
  的时钟可控，而它是 `ChatClientAgentPort` 的构造依赖。要测就得给它一条测试入口
  （换 Clock），那是 01 的类、不是本票的改动面。**代价**：这一条的正确性目前只由
  "键的形状是 `addr:owner|channel|chatId`、里面没有段"这个读代码的结论支撑。
- **判据 5（续跑与用户消息串行）**：`resume` 要走完 `reopen` / `principalOf` / `spent` /
  `recall` 一整套 `RunStore`，替身成本高于这条判据的收益。**代价**：`resume` 拿锁这件事
  只由代码保证，没有用例钉住。真出问题时的表现是"审批续跑和用户那句话同时写一段会话"，
  而那正好是本票要消灭的现象——所以这条**应该**在 03 或 A10 之前补上。


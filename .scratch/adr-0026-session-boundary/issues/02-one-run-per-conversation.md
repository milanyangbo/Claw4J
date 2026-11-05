# 02 · 一段对话同时只有一个 run

**Status:** ready-for-agent
**Blocked by:** 01（段要先存在；lane 的键与段的轮换都在同一个临界区里）

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
   顺带一个没验证的风险：`JdbcSessionRepository` 追加事件时用
   `UPDATE AI_SESSION SET event_version = event_version + 1 WHERE id = ? AND event_version = ?`
   做乐观锁，并发下其中一条可能拿到 0 行——框架是重试还是抛，我没从字节码确认。
   落地时顺手验一次（见判据最后一条）。

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

- [ ] **并发两条，第二条看得见第一条**：同一 `chatId` 同时发两句，第二条的提示词里含第一条的答案。
      反例：把 lane 调用删掉 → 红（现在就是红的）。
- [ ] **两条 run 在同一段会话里**：`select count(distinct session_id)` = 1。
      反例：lane 键用 `chatId` 而不是 `addr:owner|channel|chatId` → 换了权限的同一段群聊会串到一起。
- [ ] **键不是 sessionId**：在段轮换的临界点上并发两条，段必须只有一个。
      反例：把键换成 sessionId → 01 那条"只轮换一次"的断言红。
- [ ] **等待有上限、超时说话**：等不到锁时回 `OVERLOADED_REPLY`，不抛 500、不静默。
      反例：改成 `tryLock()` 立即失败 → 用户连发两条，第二条拿到"现在有点忙"，红。
- [ ] **审批续跑与用户消息串行**：`resume` 期间同一对话的入站消息必须等。
      反例：resume 不加锁 → 两条 run 同时写同一段会话，审计里出现两条并行的"这一轮"。
- [ ] **锁表不泄漏**：跑完 100 个不同地址的请求，map 大小回到 0。
      反例：只 get 不 remove → 大小 100，红。
- [ ] **会话追加的乐观锁行为已确认**：写一条临时用例并发追加事件，记录框架是重试还是抛；
      若抛，在票面记下并把 `answer` 的那个 catch 补上明确处理。反例：不确认 → 线上偶发 500 无名。
- [ ] `./scripts/verify.sh` 全绿，跳过 0；`claw4j-agent` 10 文件 / ≤20 方法，覆盖率不低于 60%。

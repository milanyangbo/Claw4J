# 04: 入站事件先落库再 ACK——重启不丢、同对话串行

**What to build:** 把入站调度从"进程内去重 + 直接投线程池"改成"**先持久入队 → 再 ACK → 异步执行 → 标完成**"。
进程在任意时刻被杀，重启之后没跑完的那条消息接着跑完；同一条消息投两次只跑一次（靠唯一键，不靠内存表）；
同一个会话里的事件按顺序执行。

**Blocked by:** `02`、`03`（前两张都把"后台/可重试状态"的形状定下来了；本票的恢复扫描要照着它们写，
免得三处各有一套重试口径）。

**Type:** task

**Demo:** 群里问一句"上周华东区成交额多少"，答到一半发版（`kill -9`）。重启之后那条回答自己补发到群里，
**只发一次**；`select status, attempts from claw4j_channel.inbound_event` 能看到它从 `running` 回到
`pending` 再走到 `done`。

## 背景：这条是偏差，不是选择

`docs/channels/feishu/setup.md:43`（OpenClaw 原文）：

> **durably queues** authenticated `im.message.receive_v1` … envelopes **before** agent dispatch …
> Pending or retryable events **survive a Gateway restart** … use Feishu's event ID to
> **suppress duplicate queue entries**.

`:45`：

> If a WebSocket event **cannot be persisted after bounded retries**, OpenClaw **closes that socket** …
> instead of continuing past an **uncommitted turn**.

我们的做法（`InboundDispatcher:60-63` 的 javadoc 把它写成了一条裁决）：

> **去重是进程内带 TTL 的缓存**，不是 Redis。… 记在内存里的代价是重启后十分钟内的重推会被当成新事件
> ——比引入一个连不上就没法启动的组件划算。

这句话把两件事混成了一件：**"多实例之间去重"没必要**（对，ADR-0009 的长连接是集群投递、不支持广播）
和**"单实例重启之后这条消息还有人负责"**（错，而且这才是丢消息的那一半）。
真实后果不是"重推被当成新事件"，是：**飞书已经收到我们的 ACK、不会重投，而那条消息只在内存里，
随进程消失——用户的消息永久丢失**，日志里只留下"已提交到执行线程池"。

## 现状（代码证据）

- `InboundDispatcher#accept:202-239`：去重（`:211`）→ 认人 → `executor.execute`（`:232`）→ 返回（ACK）。
  返回之后这条消息就只存在于线程池的队列里。
- 去重表 `SeenEventIds`（`:331-386`）：`ConcurrentHashMap` + TTL 10 分钟（`:73`），有硬上限 8192，
  满了清最早的一批并 `WARN`（`:378`）。**它连"本进程内跑完"都不保证**——只保证"记过一次"。
- 装配处有四个：`FeishuChannelAutoConfiguration:105-108`、`SlackChannelAutoConfiguration:133`、
  `WecomChannelAutoConfiguration:127`、`DingtalkChannelAutoConfiguration:134`，都 `new InboundDispatcher(...)`。
  构造参数一变，四处都要改；`docs/design/extension-points.md:378` 还抄了一份构造示例（给新通道看的）。

## 裁决一：落库放在这里，不引 Redis / 不引队列

我们只有一个进程要活过重启，不是要一个跨进程总线。一张表能给的（幂等、状态、重启续跑）
与一个消息队列能给的在这件事上重合，而多一个组件就多一个"连不上就起不来"的前提——
那正是 `:60-63` 那段 javadoc 担心的事，只是它把结论下反了。

表：`claw4j_channel.inbound_event`，迁移 `claw4j-agent/src/main/resources/db/migration/V27__inbound_event.sql`。

```sql
create schema if not exists claw4j_channel;

create table claw4j_channel.inbound_event (
    event_id         text        primary key,          -- 平台幂等键：飞书 event_id
    channel          text        not null,             -- feishu / slack / wecom / dingtalk
    conversation_id  text        not null,
    sender_id        text        not null,
    text             text        not null,
    received_at      timestamptz not null,
    status           text        not null default 'pending',  -- pending / running / done / failed
    attempts         int         not null default 0,
    next_attempt_at  timestamptz not null default now(),
    conversation_seq bigint      not null,
    constraint ck_inbound_event_status check (status in ('pending','running','done','failed')),
    unique (channel, conversation_id, conversation_seq)
);

create index inbound_event_pending on claw4j_channel.inbound_event (status, next_attempt_at);
```

三条要写清的：

- **为什么在 `claw4j-agent`**：m6-04 的口径是"谁的账本，DDL 就放谁的模块"。读写它的是 host 侧装配，
  不是飞书适配器；而 `claw4j-channel` 今天一条 JDBC 都没有，也不该有（那会让"只想接一个通道"
  的人拖进整套驱动）。
- **新 schema 会打掉两处断言**：`BuildContractTest:144-147`（迁移版本号列到 26）与 `:155-160`
  （五个 schema）。两处都要跟着改成 27 / 六个 schema。这是本票的**必然改动**，不是顺手改测试。
- **`conversation_seq` 怎么来**：同一 `(channel, conversation_id)` 内从 1 开始递增。
  实现方式（`select coalesce(max(...),0)+1` 带行锁 / 一张 `conversation_cursor` 表 / 一个序列加去重）
  由实做定，但**必须与 `event_id` 唯一键在同一次写入里成立**：先取号再插入、中间崩了会漏号，
  所以取号要么在同一个事务里带锁，要么允许空洞（空洞无害，重复有害）。
  这一条要在用例里验：同一会话并发入队 10 条，`seq` 不重复。

## 裁决二：端口在 core，实现在 agent，只有一份接线

`InboundDispatcher` 在 `claw4j-core`（一个 JDBC 依赖都没有），所以：

- 端口 `InboundStore` 放在 `io.github.claw4j.channel.kernel`：
  ```java
  boolean enqueue(InboundEvent event);            // false = 已经入过队（重复投递）
  List<QueuedInbound> claimDue(int limit);        // 启动恢复与续跑用
  void markRunning(String eventId);
  void markDone(String eventId);
  void markFailed(String eventId, String reason); // attempts 用尽或判定为永久失败
  ```
- 实现 `JdbcInboundStore` 放 `claw4j-agent`（与 `PgVectorSemanticCache`、`ScheduleScanner` 同一层）。
- `claw4j.persistence.enabled=false` 的部署（今天支持，见 `AgentAutoConfiguration` 里那些
  `ObjectProvider<ToolCallAudit>` 的注释）没有 DataSource：这时给一个**内存实现**，
  启动 `WARN` 一句"入站事件不落库，重启会丢"，行为与今天一致。
  **两条路必须共用同一份 `accept` 逻辑**（端口只有一套方法，不搞 `if (jdbc) … else …`）——
  两套分支迟早不一致，这是 pitfalls 里点过名的坑。

## 裁决三：ACK 的时机与"落不了库怎么办"

- `accept` 的顺序固定为：**入队 → 认人 → 投线程池 → 返回**。入队必须在认人之前：
  认人失败也是"这条消息已经到过我们这里"，落库之后重启仍能查到它为什么被拒（今天只留在日志里）。
  重复投递（`enqueue` 返回 `false`）→ 直接返回，不执行、不发回执，行为与今天一致。
- **落库失败**（有界重试之后仍失败）：`accept` **把异常抛出去**。这是唯一允许抛的地方——
  与 OpenClaw `:45` 对齐（它关 socket，我们让回调抛，效果一样：平台会重推，而我们没有越过一个未提交的轮次）。
- 连锁改动：`FeishuLongConnectionChannel#onMessage:139-150` 现在是 `catch (RuntimeException)` 一律吞掉，
  javadoc 写着"绝不抛，避免平台重推一条处理不了的消息"。要改成**只吞"消息处理不了"这一类，
  落库失败这一类往上抛**。四个通道的 InboundDispatcher 装配同理。
  不改这句，第 45 行那条对齐就落不了地——异常在通道层被吞掉，等于什么都没发生。
- 执行完之后：`markDone`；失败则 `attempts+1`，未到上限的回到 `pending`（带 `next_attempt_at`），
  到上限的 `failed` 并回一句失败说明（沿用 `FAILURE_REPLY_PREFIX`）。

## 裁决四：启动恢复与同对话串行

- 启动时（在通道 `start()` 之前）：
  1. `update … set status='pending', attempts=attempts+1 where status='running'`
     —— `running` 是"上一个进程正在跑"的孤儿，重启之后没有别的解释；
  2. `claimDue`：按 `(channel, conversation_id, conversation_seq)` 取回可以跑的；
  3. 同一会话**前一条没完成，后一条不投**（要么 keyed executor，要么"每会话只取最小 `seq`"）。
     OpenClaw 那边对应的是"不越过未提交的一轮"（`:45`）。
- 恢复时不要重新走一遍认人失败/权限判断之外的捷径：**走的必须是同一个 `handle`**，
  否则"重启后补发"与"正常回答"会长出两套行为（两套分段的、两套失败文案的）。
- 观测：恢复一条 `INFO`（`eventId`、`attempts`）；被判 `failed` 的 `ERROR`（带原因一段）。

## 代价（写在明处，不许藏）

1. **每次入站多一次 DB 写入**。今天入站是纯内存的，库挂了照样能跑（只是查不了数据）；
   之后库挂了**入站全停**。这是有意选择：宁可不越过一条没落库的消息，也不要一条没人负责的消息。
   启动日志里要说这件事（"入站事件落库：claw4j_channel.inbound_event"），否则运维会把"库慢"和"模型慢"混起来。
2. **单实例形态第一次真的依赖"库在"**。ADR-0009 说的"单实例既定形态"没有变，变的是这个实例的入站路径
   现在有状态了。
3. `SeenEventIds` **要删掉**（`:331-386` 与构造它的四个重载）。留着就是两套去重：
   一套在表里、一套在内存里，而它们对"重复"的定义迟早会分叉。

## 验收判据（反例都要能红）

- [ ] **重启不丢**：入队一条（不执行）→ 新建 dispatcher（模拟重启，同一个 store）→ 启动恢复把它跑完并回推。
      反例：删掉启动扫描 → 这条红。
- [ ] **`running` 孤儿被回收**：直接把一行改成 `running` 再重启 → `attempts` 变 1 且跑完。
      反例：只扫 `pending` → 红（这是最常见的一种"看起来做了恢复"）。
- [ ] **重复投递只跑一次，且靠唯一键**：同一 `event_id` 两次 `accept` → 只执行一次、
      且 `attempts` 不涨。反例：把主键删掉（换成内存表）→ 红。
- [ ] **同对话串行**：同一 `conversation_id` 三条事件，假 `AgentPort` 记录进入顺序，
      断言严格按 `seq`；反例：并发跑 → 红。
- [ ] **`seq` 不重复**：同会话并发入队 10 条（线程池 + 真库），断言 `count(distinct seq)=10` 且无空洞或
      空洞可解释（说明实做选的是哪条路）。反例：`max+1` 不带锁 → 红。
- [ ] **落库失败要抛**：假 store 抛异常 → `accept` 抛；通道侧只对这类抛（
      `onMessage` 的两个分支各一条用例，用假的 `InboundStore`，不需要真凭据）。反例：吞掉 → 红。
- [ ] **`persistence.enabled=false` 时不炸**：内存实现接得上，启动 `WARN` 一句，行为与今天一致。
- [ ] **真库一条**：`Testcontainers` 上跑 V27，验唯一键幂等、状态流转、孤儿回收三件。
- [ ] `./scripts/verify.sh` 全绿、跳过 0、行覆盖率 ≥60%（core 现 90.4%；新增的端口接口与
      `InboundDispatcher` 改动都要有用例盖住，`JdbcInboundStore` 的覆盖率靠真库那条）。
- [ ] `InboundDispatcher:60-63` 那段 javadoc 重写。**它是这条偏差的"理由"，留着会让下一个人照着它改回去。**

## 门禁余量

- `claw4j-core` 10 个文件满、`InboundDispatchTest` 已 20 条（满）。腾槽办法：
  把分段相关的几条挪进 `MessageContractTest`（现 5 条，与"消息契约/分段"同主题），
  腾出的位置放本票的 4 条（重启恢复、串行、落库失败、内存兜底）。
- `claw4j-app` 10 个文件满，但 `SearchSqlTest` 只有 11 条（上限 20）——
  真库那三条放它里面（它已经用着 `pgvector` 那个容器，与 `SearchSqlTest` 的定位"真库上验 SQL"一致）。
- **不要新开文件**。开一个就要合并掉一个现存文件，那属于本票范围之外的改动。

## 不要做

- **不要引 Redis / Kafka / RabbitMQ**：理由在裁决一，不重复。
- **不要做"多实例之间去重"**：ADR-0009 的长连接不支持广播，多实例本身不成立；
  表在这里只解决"重启不丢"。
- **不要顺便加"入站事件查询端点"**：那是新的读接口，要有自己的鉴权与分页判据。
- **不要把 `attempts` 的上限做成配置项**：先固定一个数（建议 5）并写进日志，
  等真的有人需要调再说——多一个配置键就多一个"这个部署到底跑的是哪套"的来源。

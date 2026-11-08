# 04: 入站事件先落库再 ACK——重启不丢、同对话串行

**What to build:** 把入站调度从"进程内去重 + 直接投线程池"改成"**先持久入队 → 再 ACK → 认领 → 执行 → 标完成**"。
表结构与状态机照 OpenClaw 的 `channel_ingress_events`（`src/state/postgres-schema.generated/state.postgres.sql:189-211`），
不是我自己设计的一套。进程在任意时刻被杀，重启之后没跑完的那条接着跑完；同一条消息投两次只跑一次
（靠主键 + tombstone，不靠内存表）；同一个会话里的事件按到达顺序执行。

**Blocked by:** `02`、`03`（前两张都把"后台/可重试状态"的形状定下来了；本票的恢复扫描要照着它们写，
免得三处各有一套重试口径）。

**Type:** task

**Status:** resolved（2026-11-08）

**Demo:** 群里问一句"上周华东区成交额多少"，答到一半发版（`kill -9`）。重启之后那条回答自己补发到群里，
**只发一次**；`select status, attempts from claw4j_channel.inbound_event` 能看到它从 `claimed` 回到
`pending` 再走到 `completed`。

## 背景：这条是偏差，不是选择

`docs/channels/feishu/setup.md:43`（OpenClaw 原文）：

> **durably queues** authenticated `im.message.receive_v1` … envelopes **before** agent dispatch …
> Pending or retryable events **survive a Gateway restart**, remain serialized per chat or document,
> and use Feishu's event ID to **suppress duplicate queue entries** while the active or retained
> completion record exists.

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

- `InboundDispatcher#accept`：去重 → 认人 → `executor.execute` → 返回（ACK）。
  返回之后这条消息就只存在于线程池的队列里。
- 去重表 `SeenEventIds`：`ConcurrentHashMap` + TTL 10 分钟，有硬上限 8192，
  满了清最早的一批并 `WARN`。**它连"本进程内跑完"都不保证**——只保证"记过一次"。
- 装配处有四个（`FeishuChannelAutoConfiguration:105-108`、`SlackChannelAutoConfiguration:133`、
  `WecomChannelAutoConfiguration:127`、`DingtalkChannelAutoConfiguration:134`），都 `new InboundDispatcher(...)`。
  构造参数一变，四处都要改；`docs/design/extension-points.md:378` 还抄了一份构造示例（给新通道看的）。

## 裁决一：落库放在这里，不引 Redis / 不引队列

我们只有一个进程要活过重启，不是要一个跨进程总线。一张表能给的（幂等、状态、重启续跑）
与一个消息队列能给的在这件事上重合，而多一个组件就多一个"连不上就起不来"的前提。

表照它的 `channel_ingress_events`：**一个状态机 + 一个 tombstone，不是一卷消息日志**。
迁移 `claw4j-agent/src/main/resources/db/migration/V27__inbound_event.sql`。

```sql
create schema if not exists claw4j_channel;

create table if not exists claw4j_channel.inbound_event (
    -- 队列名。它的 queue_name 是 JSON.stringify([channelId, accountId])；我们一个通道一份配置，
    -- 今天只有四个值（feishu / slack / wecom / dingtalk）。留着这一列当主键的一半，
    -- 是因为同一个通道将来接第二个账号时不用改主键。
    queue_name              varchar(96)  not null,
    event_id                varchar(128) not null,
    account_id              varchar(64)  not null default 'default',
    -- 四值闭合词表，见下面的 check。**不是** pending/running/done：running 与 done 之间
    -- 它区分的是"有人认领了"与"认领人还在等租约"，那是两件不同的事（一个能回收，一个不能）。
    status                  varchar(16)  not null,
    -- 串行车道（它的 lane_key）。我们是 conversationId：同一条会话上同时只有一条在跑。
    lane_key                varchar(255),
    -- 整条信封的 JSON（InboundEvent 的五个字段），**不拆成列**：拆了之后每加一个字段就是一次迁移，
    -- 而这里从来不按内容查询——只按 id、状态、车道、时间。
    payload_json            text         not null,
    metadata_json           text,
    received_at             timestamptz  not null,
    updated_at              timestamptz  not null,
    -- 认领：谁在跑、什么时候认领的、拿什么令牌去结算。令牌挡住"两个线程都以为自己持有这一行"。
    claim_token             uuid,
    claim_owner             varchar(96),
    claimed_at              timestamptz,
    attempts                int          not null default 0,
    last_attempt_at         timestamptz,
    last_error              text,
    failed_reason           varchar(64),
    failed_at               timestamptz,
    completed_at            timestamptz,
    completed_metadata_json text,
    constraint pk_inbound_event primary key (queue_name, event_id),
    constraint ck_inbound_event_status check (status in ('pending', 'claimed', 'completed', 'failed'))
);

-- 三条索引与它的三个同名同形（idx_channel_ingress_*）：认领扫描、车道判定、按到达顺序取。
create index if not exists ix_inbound_event_claims
    on claw4j_channel.inbound_event (queue_name, status, claimed_at);

create index if not exists ix_inbound_event_lane
    on claw4j_channel.inbound_event (queue_name, status, lane_key);

create index if not exists ix_inbound_event_pending
    on claw4j_channel.inbound_event (queue_name, status, received_at, event_id);
```

四条要写清的：

- **主键是 `(queue_name, event_id)`，不是 `event_id`**：同一个平台的两个 bot 各自收到同一个事件时，
  `event_id` 会撞，而它们该各跑一次。我原来写单主键是错的。
- **完成 = tombstone，不是删除**：`complete` 把 `status` 置 `completed`、`payload_json` 置字符串
  `'null'`、清掉 claim 三列、留 `completed_at`。**行还在**——它就是去重记录，重投时
  `on conflict do nothing` 撞上它，于是"这条已经处理过"。删掉行等于重投会再跑一遍。
  这也顺手解决了一件事：消息内容不留长期副本。
- **没有 `conversation_seq`**：我原来设计的 `unique (channel, conversation_id, seq)` 是自己发明的。
  它不用序号：顺序由 `received_at` 给（同一毫秒再按 `event_id` 兜底），串行由 `lane_key` 给
  ——`claimNext` 收一份"已被占用的车道"，SQL 里把那些车道整片排除。好处是不需要取号、
  不需要行锁、也不会出现空洞；代价是**顺序等于到达顺序**，不是我们分配的顺序（对入站来说那才是对的）。
- **`attempts` 只由 `release` 涨，不由恢复涨**：它的 `recoverChannelIngressClaimInDatabase`
  只改状态与清 claim，注释写的是 "Losing a claim owner is not a delivery failure; preserve its
  retry history"。我原来写"孤儿回收 attempts+1"也是错的——那会让"进程重启三次"把一条正常消息
  的额度耗光。

两处与它的形态不同，都因为我们的库是 PG 而不是 SQLite：

| 它 | 我们 | 为什么 |
| --- | --- | --- |
| 时间列是 `bigint`（epoch ms） | `timestamptz` | 它的整数时间来自 SQLite 只有整数；PG 有原生类型，`claw4j_run.audit_event` 全仓用的也是 `timestamptz` |
| 有 `channel_id` 与 `account_id` 两列 | 只有 `account_id` | 它的 `channel_id` 服务于"按通道列账号"那个查询；我们 `queue_name` 本身就是通道名，多一列同值 |

**新 schema 会打掉两处断言**：`BuildContractTest`（迁移版本号列到 26）与它下面那条（五个 schema）。
两处都要跟着改成 27 / 六个 schema。这是本票的**必然改动**，不是顺手改测试。

## 裁决二：端口在 core，实现在 agent，只有一份接线

`InboundDispatcher` 在 `claw4j-core`（一个 JDBC 依赖都没有），所以端口放
`io.github.claw4j.channel.kernel`，实现 `JdbcInboundStore` 放 `claw4j-agent`
（与 `PgVectorSemanticCache`、`ScheduleScanner` 同一层）。

方法照它的语义，不照我原来那套 `boolean enqueue`：

```java
public interface InboundStore {

    /** 入队。重复也要说清是**哪一种**重复——四种状态对调用方是三件不同的事。 */
    EnqueueOutcome enqueue(QueuedInbound event, String laneKey);

    /** 认领一条：跳过 blockedLanes 上的车道，按 (received_at, event_id) 取最早的那条。 */
    Optional<ClaimedInbound> claimNext(Set<String> blockedLanes, String ownerId);

    /** 完成：状态置 completed、内容清空、留 tombstone 供去重。 */
    boolean complete(ClaimRef claim, String metadataJson);

    /** 交还：回 pending，attempts+1，记 last_error。 */
    boolean release(ClaimRef claim, String lastError);

    /** 终止：进 failed，记原因。到上限与"永久失败"都走它。 */
    boolean fail(ClaimRef claim, String reason, String message);

    /** 回收租约到期的认领（含重启孤儿）。**不改 attempts**。 */
    int recoverStaleClaims(Duration lease);

    /** 按状态分档的保留策略：pending 的 TTL、completed 的 TTL、failed 的 TTL 与条数上限。 */
    int prune(Retention retention);
}
```

`EnqueueOutcome` 是五值枚举：`ACCEPTED` / `DUPLICATE_PENDING` / `DUPLICATE_CLAIMED` /
`DUPLICATE_COMPLETED` / `DUPLICATE_FAILED`（照它的 `kind: accepted | pending | claimed | completed | failed`）。
`ClaimRef` 是 `(eventId, token)`——**结算必须带令牌**，否则"我认领的那条已经被别人回收并重新认领"这件事
会让后一次执行覆盖前一次的结论。

`claw4j.persistence.enabled=false` 的部署没有 DataSource：这时给一个**内存实现**，
启动 `WARN` 一句"入站事件不落库，重启会丢"，行为与今天一致。
**两条路必须共用同一份 `accept` 逻辑**（端口只有一套方法，不搞 `if (jdbc) … else …`）——
两套分支迟早不一致，这是 pitfalls 里点过名的坑。

## 裁决三：ACK 的时机与"落不了库怎么办"

- `accept` 的顺序固定为：**入队 → 认人 → 投线程池 → 返回**。入队必须在认人之前：
  认人失败也是"这条消息已经到过我们这里"，落库之后重启仍能查到它为什么被拒（今天只留在日志里）。
  重复投递（`enqueue` 返回任何 `DUPLICATE_*`）→ 直接返回，不执行、不发回执。
- **落库失败**（有界重试之后仍失败）：`accept` **把异常抛出去**。这是唯一允许抛的地方——
  与 OpenClaw `:45` 对齐（它关 socket，我们让回调抛，效果一样：平台会重推，而我们没有越过一个未提交的轮次）。
- 连锁改动：`FeishuLongConnectionChannel#onMessage` 现在是 `catch (RuntimeException)` 一律吞掉，
  javadoc 写着"绝不抛，避免平台重推一条处理不了的消息"。要改成**只吞"消息处理不了"这一类，
  落库失败这一类往上抛**。四个通道的 InboundDispatcher 装配同理。
  不改这句，第 45 行那条对齐就落不了地——异常在通道层被吞掉，等于什么都没发生。
- 执行完之后：`complete`；失败则 `release`（未到上限，回 `pending` 等下一次认领）或
  `fail`（到上限，进 dead letter 并回一句失败说明，沿用 `FAILURE_REPLY_PREFIX`）。

## 裁决四：启动恢复、串行与清理

- **恢复**：`recoverStaleClaims` 是**租约**语义（它按 `claimed_at <= cutoff` 收，另外把
  `claim_token` / `claim_owner` 为空的坏行也一起收）。两个调用点：
  1. 启动时（通道 `start()` 之前）用 `cutoff = now`——同一时刻只有一个进程，所有 `claimed` 都是孤儿；
  2. 运行期每隔一段时间用 `cutoff = now - lease`（先取 5 分钟，与它文档里 "five-minute per-chat
     queue limit" 同量级），收卡住的。
- **串行**：`InboundDispatcher` 维护一张 `lane → 正在跑` 的表，`claimNext` 把这张表的键传进去当
  `blockedLanes`。同一条会话上前一条没结算，后一条不会被认领。它那边对应的是
  "不越过未提交的一轮"（`:45`）。
- 恢复时不要走捷径：**走的必须是同一个 `handle`**，否则"重启后补发"与"正常回答"会长出两套行为。
- **清理**：`prune` 按状态分档（`pending` 按 `updated_at`、`completed` 按 `completed_at`、
  `failed` 按 `failed_at`），每档一个 TTL 加一个条数上限。不清理的话 tombstone 会无限长，
  而它存在的唯一目的是覆盖重投窗口。

## 代价（写在明处，不许藏）

1. **每次入站多一次 DB 写入**。今天入站是纯内存的，库挂了照样能跑（只是查不了数据）；
   之后库挂了**入站全停**。这是有意选择：宁可不越过一条没落库的消息，也不要一条没人负责的消息。
   启动日志里要说这件事（"入站事件落库：claw4j_channel.inbound_event"），否则运维会把"库慢"和"模型慢"混起来。
2. **单实例形态第一次真的依赖"库在"**。ADR-0009 说的"单实例既定形态"没有变，变的是这个实例的入站路径
   现在有状态了。
3. **完成后内容被抹掉**（`payload_json = 'null'`）。这是 tombstone 设计的副作用：只留"这条来过、处理过"，
   不再留"说了什么"。要追内容去看会话记录与审计，不从队列表取。
4. `SeenEventIds` **要删掉**。留着就是两套去重：一套在表里、一套在内存里，而它们对"重复"的定义迟早会分叉。

## 验收判据（反例都要能红）

- [x] **重启不丢**：`InboundDispatchTest#aRestartFinishesWhatThePreviousProcessQueued`
      （上个进程只入队、任务还没跑就被"杀"→ 新 dispatcher 同一个 store，`start()` 把它跑完并回推）。
- [x] **`claimed` 孤儿被回收，且 `attempts` 不变**：
      `InboundDispatchTest#aClaimedOrphanIsRecoveredWithoutBurningAnAttempt`（认领后崩 → 恢复 → 跑完，
      中途断言 `attempts` 仍是 0）、真库 `SearchSqlTest#aStaleClaimIsRecoveredWithoutBurningAnAttempt`
      （`claimed_at` 设成 10 分钟前 → 回 `pending`、`attempts` 为 0、`claim_owner` 清空；
      再认领 + `release` → `attempts` 变 1，证明**只有 release 涨**）。
- [x] **重复投递只跑一次，且靠主键 + tombstone**：
      `InboundDispatchTest#duplicateDeliveryRunsOnceButStillAcks`、
      真库 `SearchSqlTest#enqueueIsIdempotentOnTheCompositeKey`（两次入队一行、第二次 `DUPLICATE_PENDING`）、
      `#theRowGoesFromPendingToAContentFreeTombstone`（`payload_json` 变 `'null'`、行还在、
      再投是 `DUPLICATE_COMPLETED`）。
- [x] **同对话串行**：`InboundDispatchTest#theLaneAndTheClaimTokenBothGuardTheRow`
      （三条事件两个车道；带 `blockedLanes={conv}` 拿不到同车道那条；两条车道都占时为空）。
      另有一条从编排层看的：`#twoConversationsRunSideBySide`（不同会话不互相挡）。
- [x] **认领带令牌**：同一条用例的后半段——回收并重新认领之后，旧令牌 `complete` / `release`
      都返回 false。真库那条 `#theRowGoesFromPendingToAContentFreeTombstone` 走的也是带令牌的 SQL。
- [x] **落库失败要抛**：`InboundDispatchTest#aPersistFailureIsThrownSoThePlatformRetries`；
      通道侧两个分支各一条 `FeishuChannelWiringTest#aPersistFailureReachesThePlatform`
      （抛）/ `#anUntranslatableMessageIsSwallowedOnPurpose`（吞）。都用假 `InboundStore`，不需真凭据。
- [x] **`prune` 不误删在跑的**：真库 `SearchSqlTest#pruneFollowsTheStatusLadder`
      （`claimed` 一行不动；`completed` 8 天前被删；`pending` 40 天前被删；`failed` 10 天前还在，
      因为它的 TTL 是 30 天）。
- [x] **`persistence.enabled=false` 时不炸**：`InboundDispatchTest#withoutAStoreNothingSurvivesARestart`
      （没有 store 就退回内存实现；能跑，但重启之后那条没人接着跑——这就是构造时那句 WARN 说的事）。
- [x] **真库四条**：`SearchSqlTest` 新增 4 条（幂等 + 复合主键 + 状态流转 + 孤儿回收），
      后面又补了 1 条 `prune`，共 5 条；V27 由 `BuildContractTest` 那条迁移合成用例覆盖
      （版本列到 27、六个 schema）。
- [x] `./scripts/verify.sh` 全绿、跳过 0、行覆盖率 ≥60%（数字见 Comments）。
- [x] `InboundDispatcher` 的类注释重写：旧那段"去重是进程内带 TTL 的缓存"整段删掉，换成
      "落库 → 认领 → 执行 → 结算"，并把"为什么不是内存去重表"写成一句能拦住下一个人的话
      （内存表解决的是多实例去重，而单实例形态下那件事本就不成立；真会丢消息的是"重启之后没人接着跑"）。

## 门禁余量

- `claw4j-core` 10 个文件满、`InboundDispatchTest` 已 20 条（满）。腾槽办法：
  把分段相关的几条挪进 `MessageContractTest`（现 5 条，与"消息契约/分段"同主题），
  腾出的位置放本票的 5 条（重启恢复、串行、落库失败、内存兜底、令牌）。
- `claw4j-app` 10 个文件满，但 `SearchSqlTest` 只有 11 条（上限 20）——
  真库那四条放它里面（它已经用着 `pgvector` 那个容器，与本票"真库上验 SQL"一致）。
- **不要新开文件**。开一个就要合并掉一个现存文件，那属于本票范围之外的改动。

## 不要做

- **不要引 Redis / Kafka / RabbitMQ**：理由在裁决一，不重复。
- **不要自造 `conversation_seq`**：序号要取号、要带锁、要么允许空洞，而 `received_at` + `lane_key`
  已经把"顺序"与"串行"两件事都解决了。它没这么做，是有理由的。
- **不要把 `attempts` 的上限做成配置项**：先固定一个数（建议 5）并写进日志，
  等真的有人需要调再说——多一个配置键就多一个"这个部署到底跑的是哪套"的来源。
- **不要做"多实例之间去重"**：ADR-0009 的长连接不支持广播，多实例本身不成立；
  表在这里只解决"重启不丢"。
- **不要顺便加"入站事件查询端点"**：那是新的读接口，要有自己的鉴权与分页判据。
- **不要做它的 idle shortcut**（`enqueueAndClaim`：队列空时入队即 claimed）：省的是每次一条
  `UPDATE`，换来的是"入队即可直接 claimed"这条额外路径。首版只有 `enqueue` + `claimNext`。

## Comments

- 2026-11-08 实现。新增：V27 迁移、`domain.inbound` 六个契约（`InboundStore` / `EnqueueOutcome` /
  `QueuedInbound` / `ClaimRef` / `ClaimedInbound` / `Retention`）、`channel.kernel.InMemoryInboundStore`、
  `agent.inbound.JdbcInboundStore`；改 `InboundDispatcher`（重写）、`AgentPersistenceConfiguration`（一个 bean）、
  四处通道装配（接 store + `start()`）、`FeishuLongConnectionChannel#onMessage`（两个分支分开）。
- **端口不能放 `channel.kernel` 里**（票面写的），agent 的 `agentKnowsNoChannel` 架构闸当场报了 53 处：
  ADR-0010 的边界是 `agent` 不认识 `channel`，而 JDBC 实现必须在 agent（它有 JdbcTemplate）。
  于是端口搬到 `io.github.claw4j.domain.inbound`——`domain` 是两边都能依赖的那一层。
  为什么不是 `domain.port`：那里有一条"只放接口与 record"的规则，而 `EnqueueOutcome` 是枚举。
  顺带删掉 `QueuedInbound.of(queue, eventId, InboundEvent)`：它会让 domain 反向依赖 channel。
- **`claimNext` / `recoverStaleClaims` 多一个 `queueName` 参数**（票面签名里没有）：一个 store bean
  服务四个通道，不传队列名的话，飞书的认领会去抢 Slack 的活。语义照旧，只是把"哪个队列"显式化。
- **重试的节奏由扫描给**（30 秒一次），不是立刻重试：`release` 之后那条事件的**车道**会被记进
  `releasedLanes`，这一轮不再认领；下一轮 `sweep()` 解封。立刻重试的话，一个必然失败的调用
  会在几秒里烧掉 5 次额度，而人还在等。中间几次失败**不说话**，到上限才回一句。
- **线程池满不再是"回一句稍后再试"**：事件已经在库里，池满只是"这一轮没开始"，
  那一条仍是 `pending`。原来那条路径（`OVERLOADED_REPLY`）连同常量一起删了——
  ADR-0009 与 ADR-0026 里引用它的两处一并改/加修订记录（见下）。
- **`onMessage` 两个分支分开**：翻译失败吞掉（重推还是翻译不了），落库失败往上抛
  （平台重推，而我们没有越过一条没落库的消息）。这条不落地的后果最难查：平台以为收下了、库里没有。
- **八处反例真跑了**（改完即还原，各自只红对应的那条/那几条）：
  1. `start()` 不做启动恢复 → 孤儿那条红；
  2. 回收时 `attempts+1` → 孤儿那条红（`expected: <0> but was: <1>`）；
  3. `complete` 里删行 → 去重与重试计数三条红；
  4. 认领不看 `blockedLanes` → 串行那条红（拿到 `evt-b` 而不是 `evt-c`）；
  5. `accept` 吞掉落库失败 → 落库失败那条红（没抛）；
  6. 结算不校验令牌 → 令牌那条红（旧令牌能写进去）；
  7. `onMessage` 把 accept 重新包进 try/catch → 通道那条红；
  8. `prune` 不分状态一刀切 → 真库那条红（`claimed` 被删）。
- **门禁余量没按票面的办法腾**：票面说把"分段相关的几条挪进 `MessageContractTest`"，
  但 `MessageSplitter` 是包内可见，挪到 `domain.port` 的测试里根本编译不过（试过了）。
  改成**合并同类用例**：`theSameLaneIsNotClaimedTwice` + `anOldClaimTokenCannotOverwriteTheNewOwner`
  → 一条；两条认人失败 → 一条；三条回执 → 一条；另外删掉两条已失效的
  （TTL 窗口那条随 `SeenEventIds` 一起没了、线程池满那条随 `OVERLOADED_REPLY` 一起没了）。
  文件数没变（core 仍 10 个），方法数正好 20。
- **测试读不到日志**：`claw4j-core` 的测试 classpath 上没有 `spring-boot-test`，所以"启动 WARN 一句"
  没法断言。改成用行为断言它说的是什么（没有 store → 重启之后没人接着跑），比读一行日志更硬。
- 没做（票面本来就不在范围）：入站事件的查询端点、`enqueueAndClaim` 的 idle shortcut、
  多实例去重、`attempts` 上限做成配置。
- `JdbcInboundStore` 的 `prune` 与 `announce` 都是 `@PostConstruct`/`@Scheduled`，
  与 `AuditLedger` 同一套调度；表没建（迁移没跑）时它吞掉异常只记 debug。

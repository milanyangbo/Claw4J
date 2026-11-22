# 02 · 事件条件意图（standing intents）

**Status:** resolved
**Blocked by:** 无。

## 对照表

| | OpenClaw | 我们现在 | 差在哪 | 改哪边 |
| --- | --- | --- | --- | --- |
| 定位 | 事件条件的前瞻记忆："当 X 出现时提醒我 Y"，**不排时钟** | 只有定时任务与斜杠命令 | 没有"遇到才做"这一档 | 我们补 |
| 存储 | 每 agent 一个 SQLite，一张 `standing_intents` + FTS5 虚表 + 三个同步触发器 | 无 | 无 | 新表（见下） |
| 匹配 | 词法匹配 + FTS 预筛；**CJK 按字面子串**（FTS 把不带空格的中文整句当一个词）；事务内复查 scope/expiry/cooldown/预算；**零模型调用**；每轮最多 256 个候选 | 无 | 无 | 我们补 |
| 限额 | cooldown 24h、最多 3 次、90 天过期；`max_fires > 0`、`cooldown_seconds >= 0` | 无 | 无 | 默认值照抄 |
| 命中后 | 主回复收到一段**有界隐藏块** | 无 | 无 | 有界注入 |
| 谁能建 | 通道 owner；非 owner 调用被拒 | 网关侧注入 `Principal.userId` | 身份来源不同，判据一致 | 只认该会话 owner |
| 推进 | 命中 `fire_count++` 记 `last_fired_at`；预算尽 → `done`；过期 → `expired`；**取消只能显式** | 无 | 无 | 表里带列 |

## 表（列、约束、默认值逐项对齐）

```sql
create table if not exists claw4j_session.standing_intent (
    intent_key        bigserial    primary key,
    id                varchar(64)  not null unique,
    owner             varchar(96)  not null,
    description       text         not null,
    trigger_keywords  text         not null,
    -- 预留：当前恒为 null，写入路径不产生 embedding。见下方"偏离 3"。
    trigger_embedding text,
    channel_scope     varchar(96),
    sender_scope      varchar(96),
    creator_sender    varchar(96)  check (creator_sender is null or length(btrim(creator_sender)) > 0),
    status            varchar(16)  not null check (status in
                        ('pending', 'armed', 'fired', 'done', 'cancelled', 'expired')),
    expires_at        timestamptz  not null,
    max_fires         int          not null check (max_fires > 0),
    fire_count        int          not null default 0 check (fire_count >= 0),
    cooldown_seconds  int          not null default 86400 check (cooldown_seconds >= 0),
    last_fired_at     timestamptz,
    created_at        timestamptz  not null,
    source_session_id varchar(255)
);

create index if not exists standing_intent_lifecycle
    on claw4j_session.standing_intent (status, expires_at);
create index if not exists standing_intent_scope
    on claw4j_session.standing_intent (owner, channel_scope, sender_scope);
```

它的原文（SQLite，`src/state/openclaw-agent-schema.sql`）：

```sql
CREATE TABLE IF NOT EXISTS standing_intents (
  intent_key        INTEGER PRIMARY KEY,
  id                TEXT NOT NULL UNIQUE,
  description       TEXT NOT NULL,
  trigger_keywords  TEXT NOT NULL,
  trigger_embedding TEXT,
  channel_scope     TEXT,
  sender_scope      TEXT,
  creator_sender    TEXT CHECK (creator_sender IS NULL OR length(trim(creator_sender)) > 0),
  status            TEXT NOT NULL CHECK (status IN
                      ('pending','armed','fired','done','cancelled','expired')),
  expires_at        INTEGER NOT NULL,
  max_fires         INTEGER NOT NULL CHECK (max_fires > 0),
  fire_count        INTEGER NOT NULL DEFAULT 0 CHECK (fire_count >= 0),
  cooldown_seconds  INTEGER NOT NULL DEFAULT 86400 CHECK (cooldown_seconds >= 0),
  last_fired_at     INTEGER,
  created_at        INTEGER NOT NULL,
  source_session_id TEXT
) STRICT;
-- 另有 idx_standing_intents_lifecycle / idx_standing_intents_scope 两个索引，
-- 一张 FTS5 虚表 standing_intents_fts 与三个同步触发器。
```

**三处有意偏离：**

1. **加 `owner` 列**。它每个 agent 一个库，owner 由库隐含；我们一个库多租户，只能显式写。
   这是**加**，不是省——所有读写都必须带 owner，否则就是跨租户。
2. **FTS5 + 触发器 → Postgres 索引**。我们不用虚表：先 `ILIKE '%kw%'`（CJK 本来就是子串），
   再在应用里判整词。数据量到十万级再换 `tsvector` 生成列 + GIN；现在换只是提前优化。
3. **`trigger_embedding` 建而不写**（本次决定）。列留着，写入恒为 null，匹配只看词法。
   代价写明：**只支持关键词命中，不支持"意思相近也算"**。将来要开，改的是写入路径（加一次
   embedding 调用）+ 匹配路径（向量近邻），不动表。

## 一个必须现在定的：作用域含不含"段"

**不含段**（按 owner + channel + sender）。依据是它的 `source_session_id` 只是**出处**，
scope 是 channel/sender——意图不随会话重置消失。我们的段每日/空闲会轮换，按段存就等于
"每天凌晨 4 点意图全清"，与"90 天有效"矛盾。

代价：`/new` 之后旧意图仍会触发。这是对齐带来的，写进 `docs/design/long-term-memory.md` 说明。

## 做法

1. `V8__standing_intent.sql`：上面的表与两个索引。
2. `intent/StandingIntentStore`：`create` / `list` / `cancel` / `armed(owner, channel, sender)` / `fire(id)`；
   `fire` 在**一个事务里**复查 scope、expiry、cooldown、预算——它自己的判据是"命中与计数必须原子"。
3. `intent/IntentMatcher`：`trigger_keywords` 按逗号/分号分段；整词匹配，含 CJK 的词按子串；
   每轮上限 256；纯规则，不调模型。
4. `intent/StandingIntents`：挂在 `SlashCommands` 之后、`PromptComposer` 之前；命中插入有界块
   （字节上限复用 03 的预算器）。
5. `intent/IntentTool`：`intent` 工具，建 / 查 / 撤三个动作，只认该会话 owner。

## 验收（先跑红）

- 建一条 → 发一句命中 → 回复带那块；发一句不含触发词 → 不带。
- 冷却内再发同样的话 → 不触发；`cooldown_seconds=0` → 触发。
- 第 4 次 → `done`，不再触发；过期后 → `expired`。
- 重启进程后那条仍在，状态与计数没被重置。
- 中文触发词"客户数"被"帮我看看客户数"命中（子串）；英文词 "customer" 不命中 "customers"。
- 非 owner 调 `intent` 建 → 拒绝。
- `trigger_embedding` 明确为 null（用一条断言钉住"现在不写"）。

## 不做什么

- 不做周期性维护任务：它在**下一次已知截止时刻**跑一次；没有活跃意图时不扫表。我们照此惰性结算。
- 不从不含命中的普通对话里推断取消：取消只能显式。
- 不做 embedding（本票）；列与索引结构已经留好位置。

## TDD 用例清单

裁决：`docs/adr/0036-standing-intent.md`（已落）。

| 判什么行为 | 用例 | 先红的样子 |
| --- | --- | --- |
| 中文触发词按子串命中，英文按整词 | ✅ `cjkMatchesSubstringLatinMatchesWholeWord` | 中文整词匹配等于永不命中；`customer` 命中 `customers` |
| 没有身份就建不了：归属从上下文取 | ✅ `intentToolRefusesWithoutPrincipal` | 参数里带 owner，模型自己声明"我是谁" |
| 建的时候 owner 与频道取自上下文 | ✅ `createTakesOwnerAndChannelFromContext` | owner 落成空串或落到别人头上 |
| 列表带编号、触发次数、预算 | ✅ `listShowsIdAndBudget` | 没有编号，模型撤不掉自己建的 |
| 取消按 owner 判 | ✅ `cancelIsOwnerScoped` | 猜中别人编号就能撤 |
| 真库：触发一次进冷却，冷却内不再算候选 | ✅ `intentFiresOnceThenCoolsDown` | 同一句话提醒两遍 |
| 真库：预算用完转 `done` | ✅ `intentStopsAfterFireBudget` | 次数用完了还在触发 |
| 真库：过期不再参与匹配 | ✅ `intentExpiresAtDeadline` | 过期行仍被捞出来 |
| `trigger_embedding` 恒为 null | ✅ `triggerEmbeddingStaysNull` | 顺手写了 embedding，成本与延迟悄悄多一次 |
| 重启后仍在 | ⏳ 由真库用例间接覆盖（没有内存态），不单独立用例 | — |

复核：2026-11-22 `./mvnw -o -pl claw4j-agent -am test -Dtest=ScheduleToolsTest`（17 条，含意图 5 条）
与 `./mvnw -o -pl claw4j-app -am test -Dtest=PersistenceSchemaTest`（22 条，含意图 4 条）全绿。

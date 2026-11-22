# 01 · 进度卡：把整份计划落成会话状态

**Status:** resolved
**Blocked by:** 无。

## 对照表（动手前先出，AGENTS.md 第 35 行）

| | OpenClaw | 我们现在 | 差在哪 | 改哪边 |
| --- | --- | --- | --- | --- |
| 形态 | `session_progress_cards` 一行，每次写**整卡替换** | `TodoWrite` 只把计划交给模型上下文；`TodoEventHandler` 默认只打一行 debug | 不落盘、没人读得到 | 我们补 store + 在工具清单接缝包一层 |
| 限额 | ≤50 步、单步 ≤512 UTF-8 字节、至多 1 个 `in_progress`；**DDL 里没有 CHECK**，全在代码里判 | 无上限 | 模型可交 300 步 | 代码里校验并拒收 |
| 清空 | 内容置 null、`revision + 1`，**行保留**（retain revision tombstones） | 无 | 我最初写成"删行"，语义错 | 改墓碑 |
| 并发 | `revision` 每次写 +1；写入可带 `expectedRevision`，不匹配返回当前卡 | 无 | 两处同时写会互相覆盖且无人知道 | 加 revision + 期望版本 |
| 谁能拿到 | **子代理永不收到** | `subagentAlwaysDenied()` 只有三个 Schedule 工具 | 子代理能写父的卡 | 加进禁用集合 |

## 表（逐字对齐它的 DDL，只换方言）

```sql
create table if not exists claw4j_session.progress_card (
    session_key  varchar(255) primary key,
    markdown     text,
    steps_json   text,
    revision     bigint      not null,
    created_at   timestamptz not null,
    updated_at   timestamptz not null
);
```

它的原文（SQLite）：

```sql
CREATE TABLE IF NOT EXISTS session_progress_cards (
  session_key TEXT NOT NULL PRIMARY KEY,
  markdown    TEXT,
  steps_json  TEXT,
  revision    INTEGER NOT NULL,
  created_at  INTEGER NOT NULL,
  updated_at  INTEGER NOT NULL,
  FOREIGN KEY (session_key) REFERENCES session_nodes(session_key) ON DELETE CASCADE
) STRICT;
```

**两处有意偏离，写在这里：**

1. **不加外键**。它 FK 到 `session_nodes`；我们的会话行由框架的 `ai_session` 管理（schema 不由我们定），
   绑上去等于把我们的表钉在别人的表结构上。删会话时的清理走 `SessionCleanup` 同一条路。
2. **时间列用 `timestamptz`**，它用 epoch 整数（SQLite 没有时间类型）。方言差异，不是设计差异。

**步骤形状**：它只存 `{step, status}`（`status ∈ pending|in_progress|completed`）。
`TodoWrite` 的 `content` 映射成 `step`；`activeForm` **不进卡**——它是给模型看的措辞，不是进度。

## 做法

1. 迁移 `V7__progress_card.sql`：上面的表。
2. `progress/ProgressCards`：`save(sessionKey, Write)` 与 `of(sessionKey)`；
   `Write { markdown, steps, expectedRevision }`；返回 `Saved` / `Cleared` / `Stale` 三态。
   校验在 `save` 里做（超限抛 `Rejected`，把原因回给模型）。
3. `progress/JdbcProgressCards`：upsert 时 `revision = revision + 1`；
   两个字段都空 → 置 null 并 +1（墓碑），不删行。
4. `progress/ProgressCardCallback`：只对 `TodoWrite` 生效，会话键取
   `ToolContext` 里的 `ChatMemory.CONVERSATION_ID`（不捕获构造期的 id——换段之后就是旧值）。
5. `ToolPolicyAssembler#subagentAlwaysDenied` 加 `TodoWrite`。

## 验收（先跑红）

- 调一次 `TodoWrite` → `of(sessionKey)` 读到的步数与提交一致；再调一次，读到的是新的一份，
  旧步骤不残留，`revision` 从 1 变 2。
- 提交空计划 → 行还在、`markdown` 与 `steps_json` 都是 null、`revision` 又 +1。
- 带 `expectedRevision=1` 但当前是 2 → 返回 `Stale`，库里那份不被覆盖。
- 51 步被拒、单步 513 字节被拒、两个 `in_progress` 被拒，错误文本说得出上限。
- 换一个 `sessionKey` 读，读不到。
- 子代理的工具清单里没有 `TodoWrite`。

## 不做什么

- 不把计划拼进提示词（ADR-0024 的实测结论仍成立；它的卡也不进上下文）。
- `markdown` 这一列**建但不写**：`TodoWrite` 的入参里没有它。建是为了形状对齐，
  将来有"备注"来源时不用再改表。
- 不接投递面（通道预览 / UI），另一件事。

## TDD 用例清单

裁决：ADR-0024（不往上下文钉进度）不变；本票新增的是**投递面用的投影**，不涉及新决策。

| 判什么行为 | 用例 | 先红的样子 |
| --- | --- | --- |
| 超过 50 步被拒，原因里有上限 | ✅ `tooManyStepsRejected` | 收下 51 步，`checked` 不抛 |
| 单步按 UTF-8 字节算（171 个汉字 = 513 字节） | ✅ `tooLongStepRejectedByBytes` | 按字符数判，171 字被放行 |
| 两个 `in_progress` 被拒 | ✅ `twoInProgressRejected` | 两笔同时"在做"照收 |
| 换行要变空格、控制字符要删（否则两步粘成一句） | ✅ `controlCharactersStripped` | 实际得到"核对表结构然后交差"——已红过一次，见下 |
| `content` 映射成 `step`，`activeForm` 不进卡 | ✅ `contentBecomesStepAndActiveFormDropped` | 卡里多一个 `activeForm` 字段 |
| 没有会话 id 就不记 | ✅ `withoutSessionNothingIsSaved` | 用假键落库，不同人的计划混进同一张卡 |
| 拒收时把原因回给模型且不交给工具 | ✅ `rejectedIsReturnedWithoutRunningTool` | 工具照跑，模型以为记下了 |
| 非 `TodoWrite` 原样穿过 | ✅ `otherToolsPassThrough` | 所有工具都被包一层，`Read` 也被拦 |
| 子代理拿不到 `TodoWrite` | ✅ `subagentsNeverGetProgressCard` | 禁用集合里只有三个 Schedule 工具 |
| 真库：整卡替换、`revision` 每次 +1 | ✅ `progressCardIsReplacedWholesale` | 旧步骤残留，或 revision 不动 |
| 真库：清空保留墓碑（行在、内容空、+1） | ✅ `progressCardClearLeavesTombstone` | 清空时删行 |
| 真库：版本不符不覆盖 | ✅ `progressCardStaleRevisionIsNotOverwritten` | 过期写入者覆盖新的一版 |

复核：2026-11-22 `./mvnw -o -pl claw4j-agent -am test -Dtest=ProgressCardTest`（9 条）与
`./mvnw -o -pl claw4j-app -am test -Dtest=PersistenceSchemaTest`（22 条，含真库三态）全绿。
其中 `controlCharactersStripped` 抓到一处真缺陷：清洗先判"不可见"再判空白，把换行也删了，
两步文字粘成一句。已改成先判空白。


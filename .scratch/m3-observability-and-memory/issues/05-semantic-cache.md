# 05: 语义缓存：同一个问题第二遍不该再算一次

**What to build:** 问"上周华东成交额多少"与"华东上周卖了多少"时，第二次直接命中上一次的回答，
不再打模型、不再查库。用 pgvector 存问题向量，**不引 Redis**。

**Blocked by:** 01 —— 省下的 token 与命中率都要在指标里看得见，否则这个功能只是增加了一层
不确定的行为，没有人能判断它值不值。

**Status:** resolved

**Demo:** 同一个角色连着问两个措辞不同、意思相同的问题，第二次的
`claw4j.cache.hits` 加一、`claw4j.model.calls` 不变，回答里带一句"（同上次）"。

**后续修订（2026-10-27，实现之后）**：命中判定第一条从"同一个授权范围"扩成"同一个上下文
＝权限指纹 + **实体签名**（问题里的时间词与数字）"。理由是实测「上周」与「上上周」余弦 0.9950，
比同义改写还高——取到最近邻再否决式地比对，等于在流程里多插一道框架没有的判断；
改成编进上下文键之后，这两句压根不互相检索。表随之加了 `entities` 列、唯一键变成
`(fingerprint, entities, question_key)`（迁移 `V5`）。下面的清单是当时的记录，
现状以 `docs/design/semantic-cache.md` 为准。

- [x] 命中必须同时满足三条：**同一个授权范围**、相似度过阈值、没过期。
      第一条是安全边界不是优化：guest 读不到 `sales_order`，admin 读得到，
      同一个问题在两个角色下答案不同——缓存 key 里不含权限指纹，就是把 admin 的答案给 guest
- [x] 权限指纹取 `Principal` 的 `tables` + `actions` 排序后的哈希，不取 `userId`。
      按 `userId` 分等于完全不共享（同角色的人互相命中不了），按角色名分则经不起角色改配置
- [x] embedding 用通义 `text-embedding-v3`（OpenAI 兼容 `/embeddings`），复用
      `claw4j.model.qwen.*` 那套 key，不新增一组凭据。DeepSeek 没有 embedding 接口
- [x] 载体是 pgvector（ADR-0005 已定，库里就是那个镜像），表 `claw4j_cache.semantic`：
      权限指纹 + 问题向量 + 回答 + 写入时间。**Redis 不引回**
- [x] 带写操作的一轮不缓存：判断依据是这一轮**实际调用的工具集合**里有 `draft_script`
      这类动作，不是看问题文本
- [x] 相似度阈值与过期时间都可配，默认 ~~0.95 / 24 小时~~ **0.93 / 1 小时**（实测推翻了票面的两个数，
      见实现记录）：阈值调低会开始把"华东"和"华南"当成一回事
- [x] 指标：`claw4j.cache.hits` / `misses`（带 `reason`：无向量、低于阈值、权限不同）
      票面还列了「已过期」，**这一条没做**：过期判定写在 SQL 的 `where` 里（连诊断那两次查询也带着它），
      所以「从没写过」与「写过但过期了」都落成 `no-candidate`。要分开就得再查一次不带过期条件的，
      而这一层只为把 miss 的原因说准。偏离记在 `docs/design/semantic-cache.md` 的偏离表里
- [x] 断言：同一问题第二次 `model.calls` 不增加；**换一个角色问同样的话，miss 且 reason=权限不同**
- [x] 变异：把权限指纹从 key 里去掉 → 第二条断言变红；把阈值改成 0 → "华南"那条红

**落点:** 新模块还是新包？**新包** `io.github.claw4j.agent.cache`（放 `claw4j-agent` 里）：
它是一个 Advisor（`CacheAdvisor`），挂在链上、order 落在**记忆之后**（`DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER + 50`，
比记忆大，命中时记忆的 after 仍会把这条回答写回去），逻辑上属于 Agent 而不是能力。
DDL 跟其他迁移一起放 `claw4j-capability/src/main/resources/db/migration`。

**注意:** 这一票**推翻 ADR-0005 修订记录里"引回 Redis Stack"的说法**，要改那份文档并写清理由：
当时的设想是 Redis 有向量检索（Redis Stack），但实际上这个缓存要和授权、会话在同一套数据里
做一致性判断，放到另一个存储只是多一个会不一致的来源，而 pgvector 已经在了。
**改 ADR 是这一票的交付物之一，不是顺手的事**——否则下一个人照着 ADR 去装 Redis。

**不要做:** 缓存工具结果（缓存的粒度是一轮问答）；跨用户的个性化缓存；相似问题的改写/合并。

## 实现记录（2026-10-27）

### 落地

新包 `io.github.claw4j.agent.cache`（票面判断是对的，它是个 Advisor 不是能力）：

| 类 | 干什么 |
| --- | --- |
| `CacheAdvisor` | 查/存，三条命中判定与两段上下文键（权限 + 实体）都在这一个类里 |
| `SemanticCache` | 接口：`nearest` / `nearestInFingerprint` / `nearestAnyFingerprint` / `store` / `deleteExpired` |
| `PgVectorSemanticCache` | pgvector 实现，SQL 里表名写全限定，借主连接池 |
| `CacheProperties` | 阈值 / TTL / 清理间隔 / 向量模型名与维度 |
| `CacheAutoConfiguration` | 向量模型（复用 qwen 凭据）、缓存、清理任务、Advisor |
| `SemanticCacheCleanup` | `@Scheduled` 删过期行 |

`claw4j-agent.ToolCallLog`（ThreadLocal）+ `MeteredToolCallback` 里记一笔，
是"这一轮调过哪些工具"的唯一来源——判断副作用靠它。

DDL：`claw4j-capability/src/main/resources/db/migration/V4__semantic_cache.sql`
（`claw4j_cache` schema + `vector` 扩展 + 唯一索引 + 不建向量索引，理由写在文件末尾）。

### 三处偏离票面

1. **不引 Redis，用库里的 pgvector。** ADR-0005 的第二次修订写清了理由，这是本票的交付物之一。
   `docs/design/session-memory.md` 里那句"调整时按 ADR 引 Redis Stack"也一并改掉——
   不改的话下一个人会照它装一遍。
2. **阈值 0.95 → 0.93。** 实测推翻了 0.95：同义说法「上周华东销售额多少」只有 0.9470。
3. **TTL 24 小时 → 1 小时。** 问的是相对时间，24 小时会在跨周一时给出上一周的数。

### 词面守卫：这张票最重要的发现

票面以为"相似度过阈值"就够了。实测两组基准后不成立：

```
基准「上周华东区成交额多少」（真链路用的那句）
  0.9956  上上周华东区成交额多少     ← 换时间，意思完全不同
  0.9501  华东区上周销售额多少       ← 同义
  0.9255  上周华东区卖了多少钱       ← 同义
  0.9154  上周华东区订单量多少       ← 换指标
  0.9117  华东上周卖了多少           ← 同义（与上一行交叠！）
  0.9057  上周华南区成交额多少       ← 换区域
```

两件事：
- **"换时间"比"同义改写"还像**（0.9956 > 0.9117）。任何单一阈值都不可能同时命中后者、挡住前者。
- **"同义改写"与"换指标"的分数交叠**（0.9117 vs 0.9154）。所以阈值不存在"中间最优"，
  只能选往哪边错——选的是"宁漏不误"：漏只是没省到钱，误是给出另一个问题的答案。

于是命中判定里加了一道词面守卫：两个问题的**相对时间词集合与数字集合必须完全一致**
（当时的 `CacheAdvisor.sameEntities`，一小段正则）。

**后来它没有留在判定流程里**（见票头「后续修订」）：那种"取到最近邻再否决"的写法，
框架里没有对应物，等于在链上多插一道自己的判断。现在这段正则算的是**上下文键**的一半
（`entitySignature`）：集合同与不同，决定的是"这两句在不在一个上下文"，而不是"取出来之后要不要否决"。

**这个结论要照实说**：语义缓存的真实收益是"同一个人几分钟内几乎原样再问一遍"（1.0 与 0.99 那一档），
不是"随便换个说法都能命中"。0.93 下「华东上周卖了多少」（0.9117）就是不命中的，这不是 bug。

### 副作用判定

规则是"这一轮调用过的**每个**工具的登记动作都必须等于 `read`"：

- 一次没调 → 缓存（纯问答）
- 只调了 `querySales`（`read`）→ 缓存
- 调了 `draft_script` / `run_script` → 不缓存
- 调了没登记权限的工具 → 不缓存（没人评估过它会碰到什么）

没有为此新增配置项，也没有白名单：`read` 是这个系统里唯一被声明为幂等的动作，
用它当判据不需要维护第二份"哪些工具是写"的清单——那种清单一定会漏。

`ToolCallLog` 用 ThreadLocal 而不是塞进上下文：工具拿到的是 `ToolContext`，
Advisor 拿到的是 `request.context()`，两份 Map 不互通（`ChatClientAgentPort` 现在两边各放一份
Principal 正是因为这个）。同步链上同一线程，ThreadLocal 是最短的桥；流式可能换线程，
所以**缓存只在同步路径写**。

### 变异验证

1. `fingerprintOf` 改成恒返回同一个哈希 → 只有 `anotherRoleMissesWithItsOwnReason` 变红，其余 9 条全绿。
   （第一次变异只改了查询侧、没改写入侧，结果 5 条红、包括"同一个人第二次命中"——
   那次变异是错的，改成两侧一起改才验证到真东西。这条记下来：修改 key 的逻辑两边都有引用时，
   只改一处测出来的是"读不到"而不是"越权"。）
2. 阈值判定改成 `< 0`（等于不判）→ 只有 `differentRegionMisses` 变红。

### 真链路验证（起一次应用，v4 迁移在真库上跑过）

```
truncate claw4j_cache.semantic
POST /api/chat  read-only  "上周华东区成交额多少"     → miss(no-candidate)，写缓存
POST /api/chat  read-only  "上周华东区成交额多少"     → hit，model.calls 2.0 → 2.0（没增加）
POST /api/chat  read-only  "华东上周卖了多少"         → miss(below-threshold)  0.9117
POST /api/chat  read-only  "上上周华东区成交额多少"   → miss(different-entities) 0.9956 被守卫挡住
POST /api/chat  guest      "上周华东区成交额多少"     → miss(different-permission)
POST /api/chat  read-only  "上周华东区订单量多少"     → miss(below-threshold)  0.9154

metrics: hits=1, misses{no-candidate}=1, {below-threshold}=2,
         {different-entities}=1, {different-permission}=1
```

**四种 reason 各在真实链路上出现过一次**，这是它最有价值的一条记录：
如果只看"命中率"这一个数，"阈值太高"和"权限指纹算错"在报表上是同一个现象。

### 顺带修掉一处既有的不一致

`SalesQueryTest` 与 `ApplicationAssemblyTest` 的 testcontainers 镜像一直是
`postgres:16-alpine`（没有 pgvector），而本地 compose 与 ADR-0005 都说库是 pgvector 镜像。
之前没暴露是因为没人写过 `vector` 扩展。V4 一上，`create extension vector` 直接
`extension "vector" is not available`。两处测试镜像改成 `pgvector/pgvector:pg16`。

本机注意：这台机器是从加速站拉的镜像，testcontainers 拉不动 Docker Hub，
需要 `docker tag docker.m.daocloud.io/pgvector/pgvector:pg16 pgvector/pgvector:pg16` 一次。

### 没做

- 缓存工具结果（票面明确不做）：工具结果的窗口只有几分钟，收益与复杂度不成比例。
- 流式写缓存：拼接增量块"是增量还是累计"因供应商而异，拼错就是缓存一条重复十遍的回答。
  流式**只读**，同步路径负责填。
- 相似问题的改写/合并（票面明确不做）。
- 缓存命中那一轮的会话事件：记忆 Advisor 在缓存外面，它的 after 照样把"（同上次）"写回会话，
  所以会话是连续的——这一点是排 order 时特意验过的，不是碰巧。


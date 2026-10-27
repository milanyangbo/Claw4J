# 05: 语义缓存：同一个问题第二遍不该再算一次

**What to build:** 问"上周华东成交额多少"与"华东上周卖了多少"时，第二次直接命中上一次的回答，
不再打模型、不再查库。用 pgvector 存问题向量，**不引 Redis**。

**Blocked by:** 01 —— 省下的 token 与命中率都要在指标里看得见，否则这个功能只是增加了一层
不确定的行为，没有人能判断它值不值。

**Status:** ready

**Demo:** 同一个角色连着问两个措辞不同、意思相同的问题，第二次的
`claw4j.cache.hits` 加一、`claw4j.model.calls` 不变，回答里带一句"（同上次）"。

- [ ] 命中必须同时满足三条：**同一个授权范围**、相似度过阈值、没过期。
      第一条是安全边界不是优化：guest 读不到 `sales_order`，admin 读得到，
      同一个问题在两个角色下答案不同——缓存 key 里不含权限指纹，就是把 admin 的答案给 guest
- [ ] 权限指纹取 `Principal` 的 `tables` + `actions` 排序后的哈希，不取 `userId`。
      按 `userId` 分等于完全不共享（同角色的人互相命中不了），按角色名分则经不起角色改配置
- [ ] embedding 用通义 `text-embedding-v3`（OpenAI 兼容 `/embeddings`），复用
      `claw4j.model.qwen.*` 那套 key，不新增一组凭据。DeepSeek 没有 embedding 接口
- [ ] 载体是 pgvector（ADR-0005 已定，库里就是那个镜像），表 `claw4j_cache.semantic`：
      权限指纹 + 问题向量 + 回答 + 写入时间。**Redis 不引回**
- [ ] 带写操作的一轮不缓存：判断依据是这一轮**实际调用的工具集合**里有 `draft_script`
      这类动作，不是看问题文本
- [ ] 相似度阈值与过期时间都可配，默认 0.95 / 24 小时。阈值调低会开始把"华东"和"华南"当成一回事
- [ ] 指标：`claw4j.cache.hits` / `misses`（带 `reason`：无向量、低于阈值、权限不同、已过期）
- [ ] 断言：同一问题第二次 `model.calls` 不增加；**换一个角色问同样的话，miss 且 reason=权限不同**
- [ ] 变异：把权限指纹从 key 里去掉 → 第二条断言变红；把阈值改成 0 → "华南"那条红

**落点:** 新模块还是新包？**新包** `io.github.claw4j.agent.cache`（放 `claw4j-agent` 里）：
它是一个 Advisor（`CacheAdvisor`），挂在链上、order 比记忆小，逻辑上属于 Agent 而不是能力。
DDL 跟其他迁移一起放 `claw4j-capability/src/main/resources/db/migration`。

**注意:** 这一票**推翻 ADR-0005 修订记录里"引回 Redis Stack"的说法**，要改那份文档并写清理由：
当时的设想是 Redis 有向量检索（Redis Stack），但实际上这个缓存要和授权、会话在同一套数据里
做一致性判断，放到另一个存储只是多一个会不一致的来源，而 pgvector 已经在了。
**改 ADR 是这一票的交付物之一，不是顺手的事**——否则下一个人照着 ADR 去装 Redis。

**不要做:** 缓存工具结果（缓存的粒度是一轮问答）；跨用户的个性化缓存；相似问题的改写/合并。

## 实现记录

（未开始）

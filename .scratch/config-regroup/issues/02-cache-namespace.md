# 02: 语义缓存的配置也收到 `claw4j.advisor` 下

**What to build:** `claw4j.cache.*` → `claw4j.advisor.cache.*`。01 把 session / memory / tool-search
三块归到 `advisor` 下时漏了它，配置仍散在两处。

| 现在 | 改成 |
| --- | --- |
| `claw4j.cache.enabled` | `claw4j.advisor.cache.enabled` |
| `claw4j.cache.similarity-threshold` | `claw4j.advisor.cache.similarity-threshold` |
| `claw4j.cache.redis-uri` | `claw4j.advisor.cache.redis-uri` |

理由与 01 同一条：语义缓存挂的是 Advisor 链上的一个 advisor（命中就直接返回，这一轮不调模型），
跟会话记忆、长期记忆、工具循环是一条链上的四件事，翻配置时该在一处。
`enabled` 是个例外项——它不是 properties 字段，是 `CacheAutoConfiguration` 类上
`@ConditionalOnProperty` 读的键，跟着一起改，否则"配了关但还在缓存"。

**不改**：

- 环境变量名 `CLAW4J_CACHE_ENABLED` / `_THRESHOLD` / `_REDIS_URI`：部署侧的名字，与键名不必一一对应。
- 文档里出现的 `claw4j.cache.hits` / `misses`：那是指标名（现在框架不报，列在语义缓存设计文档的
  "不做"表里），不是配置键。
- ADR 正文与 `docs/reviews/2026-11-16-*`：前者记录当时的决定，后者是那次执行的快照，改了就变成
  "没发生过的事"。活文档（README / docs/design / docs/capability-tests / 四份 README）跟着改。

**验证**：`./scripts/verify.sh` 全绿；`SemanticCacheTest` 的关缓存用例、`EvalRunTest` 的属性源
改用新键（这两处是唯一读这个键的测试）。

**Status:** resolved

**进度（2026-11-19）**：`CacheProperties` 的前缀与 `CacheAutoConfiguration` 的
`@ConditionalOnProperty` 一起改；`application.yml` 里 `cache:` 整块搬到 `advisor:` 下
（段头注释从"三样"改成"四样"）；`EmbeddingProperties` / `EmbeddingAutoConfiguration` 里
"不在 `claw4j.cache` 下面"那句跟着改（它讲的是向量模型为什么不挂在缓存开关下，键名变了这句话
就指错地方）。活文档七处：README.md 的"最常改的几个"、README-capability-tests.md 两处命令行、
docs/capability-tests.md 三处、docs/design/semantic-cache.md 四处 + 一条"键为什么在这里"、
docs/design/extension-points.md 一处。

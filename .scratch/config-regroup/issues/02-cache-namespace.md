# 02: 语义缓存的配置也收到 `claw4j.advisor` 下

裁决见 [ADR-0031](../../../docs/adr/0031-config-namespace-and-provider-table.md)。

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

## TDD 用例清单

| 判什么行为 | 用例名 | 先红的样子 |
| --- | --- | --- |
| 新键 `advisor.cache.enabled=false` 真的关掉整套（不是只在文本里对） | `SemanticCacheTest#disabledSwitchRemovesTheAdvisor` | 把 `@ConditionalOnProperty` 改回 `claw4j.cache.enabled` → 下半条红（Advisor 还在）✅ |
| 不配这个键时默认开着 | 同上（上半条先证明"开着时确实装了"） | 条件写错成"永不匹配" → 上半条红 ✅ |
| 阈值配错落回 0.93（与键名无关，守的是"坏值不静默变成另一件事"） | `SemanticCacheTest#badThresholdFallsBackToDefault` | 去掉构造器里的越界判断 → 红 ✅ |
| 评测跑的时候关掉缓存（新键） | `EvalRunTest`（属性源里作 `claw4j.advisor.cache.enabled=false`） | 键写回旧的 → 评测跑到缓存，分数对不上 |

**缺口**

1. 没有用例断言 `application.yml` 里 `advisor.cache.*` 三行的存在与绑定——`claw4j-app` 的
   `BuildContractTest` 目前只对 session 那几个键做了"文本 + 绑定"双重断言，缓存这组没进。
   与「缺口 1（memory）」一起归下一张配置票：把四组键统一纳进 `BuildContractTest`，而不是
   每组各写一套。

**复核：2026-11-19 `./scripts/verify.sh` 全绿（9 模块 0 失败 / 0 错误 / 0 跳过）**

**Status:** resolved

**进度（2026-11-19）**：`CacheProperties` 的前缀与 `CacheAutoConfiguration` 的
`@ConditionalOnProperty` 一起改；`application.yml` 里 `cache:` 整块搬到 `advisor:` 下
（段头注释从"三样"改成"四样"）；`EmbeddingProperties` / `EmbeddingAutoConfiguration` 里
"不在 `claw4j.cache` 下面"那句跟着改（它讲的是向量模型为什么不挂在缓存开关下，键名变了这句话
就指错地方）。活文档共 5 个文件 12 处：README.md 的"最常改的几个"（1）、
README-capability-tests.md 两处命令行（2）、docs/capability-tests.md 三处（3）、
docs/design/semantic-cache.md 四处 + 一条"键为什么在这里"（5）、
docs/design/extension-points.md 一处（1）。README-QA.md 不涉及。

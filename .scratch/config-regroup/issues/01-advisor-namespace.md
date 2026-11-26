# 01: 配置收到 `claw4j.advisor` 下，工具检索支持三种索引

裁决见 [ADR-0031](../../../docs/adr/0031-config-namespace-and-provider-table.md)。

**What to build:** 把三块"链上/记忆"的配置收到同一个前缀下，并把工具检索从"只有一种索引"改成可选的三种。

## 一、前缀归并

| 现在 | 改成 |
| --- | --- |
| `claw4j.tool-search.*` | `claw4j.advisor.tool-search.*` |
| `claw4j.memory.*` | `claw4j.advisor.memory.*` |
| `claw4j.session.*`（含 `.maintenance` / `.reset`） | `claw4j.advisor.session.*` |

理由：这三块都是"链上跑的东西"的配置，散在三个平级前缀下，翻配置时要记三处。
代价要认：`session` 那组里 `schema` / `maintenance` / `reset` / `dm-scope` 其实是存储与生命周期，
不是 advisor 的一环；归到 `advisor` 名下是**按"它是给谁用的"分类**（给会话记忆那条链用），
不是按"它是什么"分类。这条选择记在这里，免得下一个人以为是笔误。

**不改**：ADR 正文（记录的是当时的决定，键名变了不改写历史）。活文档（README / docs/design /
docs/capability-tests / GLOSSARY）跟着改。

## 二、工具检索三种索引

库里 `ToolIndex` 有三个实现，我们只接了 regex 那一个（`ChineseToolIndex` 继承它）。加一个键：

```yaml
claw4j:
  advisor:
    tool-search:
      index: regex        # regex | lucene | vector
      max-results: 5
      lucene:
        min-score: 0.25   # 只在 index=lucene 时生效
```

| 档 | 实现 | 前提 | 中文效果 |
| --- | --- | --- | --- |
| `regex`（默认） | `ChineseToolIndex`（继承 `RegexToolIndex`） | 无 | 二元组 + 同义词表，整句能命中 |
| `lucene` | `LuceneToolIndex` | 新增 `lucene-core` + `lucene-analysis-common` 9.12.3 | 框架写死 `StandardAnalyzer`，中文按字切；排序比正则正规，召回不一定更好 |
| `vector` | `VectorToolIndex` | 容器里要有 `VectorStore`（pgvector 自动配置能提供，但需要 EmbeddingModel 与建表配置） | 语义召回，不依赖字面重合 |

选 `vector` 而容器里没有 `VectorStore` 时**启动失败并说明缺什么**，不静默回落到 regex——
回落会让"我明明配了向量"变成一个查不出来的谎。

## 三、验证

- `./scripts/verify.sh` 全绿；
- 新增用例：三种 `index` 各自建出哪个类型；`vector` 缺 `VectorStore` 时报错文案；非法取值被枚举挡掉；
- 文档：`application.yml` 注释、README 配置表、`docs/design` 里出现旧键的地方。

## TDD 用例清单

| 判什么行为 | 用例名 | 先红的样子 |
| --- | --- | --- |
| `session` 那四个键写在 yml 里（文本层） | `BuildContractTest#applicationYmlDeclaresTheContextWindow`、`BuildContractTest#applicationYmlDeclaresTheSessionScopes` | 把 yml 里 `advisor.session` 改回 `session`，两条都红 |
| 那四个键**真能绑上**（不是只有文本对） | `BuildContractTest#theSessionScopesBindToThePropertiesClass` | 键前缀写成 `claw4j.advisor.sessions`（多个 s）→ 红 |
| 表名只写在一处，改 schema 时五处一起变 | `BuildContractTest#sessionSchemaLiteralLivesInOnePlace` | 在任何一处手写 `claw4j_session.ai_session` → 红 |
| `index` 四档各建各的索引 | `ModelAndToolSelectionTest#configBoundariesAreHonoured`（2026-11-26 与 max-results 那条合并，票 11 腾方法数） | 把 `case LUCENE ->` 改成 `REGEX` → 红 ✅ |
| `vector` 缺 `VectorStore` 启动即失败，不回落 | 同上 | 注释掉 `requireVectorStore` 那一行 → 红 ✅ |
| 非法 `index` 不让它悄悄落回默认 | 同上 | 把枚举换成 `String` 再 try/catch → 红 ✅ |
| `max-results` 越界夹到 1–10，缺省 5 | `ModelAndToolSelectionTest#configBoundariesAreHonoured`（同上，合并） | 去掉 `Math.min(...)` → 夹上界那条红 ✅ |
| `advisor.memory.directory` 配了才装长期记忆那一层 | ⏳ `MemoryProperties` 的目录绑定 | 见「缺口」第 1 条 |

**缺口**

1. `claw4j.advisor.memory.directory` 的绑定没有用例：这一层是"配了目录才打开"，`application.yml`
   刻意不写默认值（给了默认值就等于默认打开），唯一写法在 `application-local.yml.example`。
   要验得在 `ApplicationContextRunner` 里配一次目录并断言 advisor 装上——`claw4j-agent` 已到 14 个
   测试文件的上限，与它一起做的还有"内存层没有目录时不装"的反向用例，合并归下一张配置票。

**复核：2026-11-19 `./scripts/verify.sh` 全绿（9 模块 0 失败 / 0 错误 / 0 跳过）**

**Status:** resolved

**进度（2026-11-19）**：三组前缀已归并（代码 6 个配置类 + 三份 yml + 活文档），
`ToolSearchProperties` 加 `index` 与 `lucene.min-score`，`ToolSearch.index(...)` 按配置建索引，
`claw4j-agent` 加 lucene-core / lucene-analysis-common 9.12.3（根 pom 管版本），
`vector` 缺 `VectorStore` 时启动即失败并说明缺什么。用例并进
`ModelAndToolSelectionTest#configBoundariesAreHonoured`（没有单开测试文件：本模块有 14 个的上限）。
`./scripts/verify.sh` 全绿（0 失败 / 0 错误 / 0 跳过，9 模块，覆盖率 65.7%–93.8%）。

**复核时改回来的一处（2026-11-20）**：归并这批键时把 `SessionCompactor` / `SessionMaintenance` /
`SessionEntries` 里的**指标名**也一起换了前缀（`claw4j.session.compaction|maintenance|pointer.*` →
`claw4j.advisor.session.*`，7 个常量 + 三处 javadoc + 测试里三处断言）。指标名是对外契约——面板、
告警、历史数据都按它查，改它等于让既有查询静默落空；它也不是配置键，本来不在本票范围。已 1:1
还原成 `claw4j.session.*`，两处常量上方各留一行注释写明"指标名不跟配置键走"。
**配置键在 `claw4j.advisor.session.*`、同名指标仍在 `claw4j.session.*`，是有意的**（记在 ADR-0031
的「代价」一节）。

两处记在案：
- `LuceneToolIndex` 的 `StandardAnalyzer` 是框架写死的，换不了 CJK 分析器；选 lucene 只是
  "排序正规"，中文召回不一定比默认那档好。这是选它时要认的代价。
- ADR 正文没有改（记录的是当时的决定），活文档跟着改了。

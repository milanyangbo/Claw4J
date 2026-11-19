# 01: 配置收到 `claw4j.advisor` 下，工具检索支持三种索引

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

**Status:** resolved

**进度（2026-11-19）**：三组前缀已归并（代码 6 个配置类 + 三份 yml + 活文档），
`ToolSearchProperties` 加 `index` 与 `lucene.min-score`，`ToolSearch.index(...)` 按配置建索引，
`claw4j-agent` 加 lucene-core / lucene-analysis-common 9.12.3（根 pom 管版本），
`vector` 缺 `VectorStore` 时启动即失败并说明缺什么。用例并进
`ModelAndToolSelectionTest#indexChoiceIsHonoured`（没有单开测试文件：本模块有 14 个的上限）。
`./scripts/verify.sh` 全绿（0 失败 / 0 错误 / 0 跳过，9 模块，覆盖率 65.7%–93.8%）。

两处记在案：
- `LuceneToolIndex` 的 `StandardAnalyzer` 是框架写死的，换不了 CJK 分析器；选 lucene 只是
  "排序正规"，中文召回不一定比默认那档好。这是选它时要认的代价。
- ADR 正文没有改（记录的是当时的决定），活文档跟着改了。

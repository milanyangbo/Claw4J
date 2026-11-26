# 票 21：index 收成 lucene/hybrid，hybrid 换 lucene 翼，regex 退成规则层

**Status:** partially done（2026-11-26：第 1 批换翼 + 规则层已落地；删 `REGEX`/`VECTOR` 档未做）
**来源：** 用户 2026-11-26 的架构判断——「hybrid 是向量加 lucene，而不是 regex，当然 regex 退成规则层，
这个 regex 也要用框架本身的能力，只是看看能不能支持传自定义的正则表达式」。

## 目标形态

| 现在 | 目标 |
| --- | --- |
| `REGEX` / `LUCENE` / `VECTOR` / `HYBRID` 四档 | `LUCENE` / `HYBRID` 两档 |
| hybrid 关键字翼 = `ChineseToolIndex`（自研二元组） | hybrid 关键字翼 = `LuceneToolIndex` |
| 关键字翼读英文原文 | 关键字翼吃译文（与向量翼同一份） |
| `ChineseToolIndex` 是 `REGEX` 档的实现 | regex 退成**可配置规则层**，复用框架 `RegexToolIndex`，支持传自定义正则 |
| `VECTOR` 独立一档 | 并入 `HYBRID` + `keyword-weight: 0`（功能等价） |

理由（三条，都能指到数字）：

1. **`ChineseToolIndex` 的二元组是自研分词，与「用框架本身的能力」冲突**。它存在的唯一理由是
   框架的 `RegexToolIndex` 按空白切 query，中文整句会变成一个 token（见该类注释）。但规则层是
   **人写正则**，不存在"要不要自动切"的问题。
2. **`regex` 作为检索主档已经站不住**：`R@5` 0.360，五档最低。而它在 base 档的 `0.300` 全部来自
   文件/命令类中文描述的重合，不是真的懂语义。
3. **`lucene` 现在读英文原文，所以 base 档 5 个候选里一个对都没有**（10 题 `R@5` 0.000）。
   它的排序能力（TF-IDF）比二元组 OR 匹配强，只是没吃到中文。

## 分批，先量后改

**第 1 批（本票先做）：只量，不动架构。**

- `LUCENE` 分支包一层 `TranslatedVectorIndex`（这个名字有误导，它其实是通用的"入库前换 summary"，
  与向量无关）。
- `HYBRID` 的关键字翼换成 `LuceneToolIndex`（同样包译文）。
- 跑一次评测，回答两个问题：
  - `lucene` 吃译文后 base 档能不能从 0.000 起来？
  - `hybrid` 换翼后，`hybrid-0.5` / `hybrid-1.0` 比现在（0.620 / 0.360）好还是坏？

**第 2 批（数据支持才做）：改架构。**

- 枚举删 `REGEX` / `VECTOR`，`VECTOR` 的功能由 `HYBRID` + `keyword-weight: 0` 承接（已实测等价）。
- 新增规则层：`tool-search.rules`，形如
  ```yaml
  rules:
    - pattern: "报销|发票|开票"
      tools: [finance_export]
  ```
  命中就直接把指定工具排在候选前面，再补常规检索结果。实现上是 `ToolIndex` 的装饰器，
  不重写检索本身；`pattern` 由框架 `RegexToolIndex` 同一套正则语义处理。
- `ChineseToolIndex` 退役（或只保留同义词表作为规则层的默认值）。

**第 3 批：文档与门禁。** README 的配置表、`GATE_PRIMARY`（跟着 `index` 走）、票 19/20 的数字作废标注。

## 判什么行为

| 判什么行为 | 用例 | 先红的样子 |
| --- | --- | --- |
| lucene 翼吃到译文 | base 档 `R@5` 从 0.000 起来 | 不包翻译时中文 query 只能撞上中文描述的干扰项 |
| 换翼不倒退 | `hybrid-0.5` 不低于现在 0.620 | 换了翼但权重没重新调 |
| 规则命中优先 | 配一条 `报销→finance_export`，问「这个月报销多少」它排第一 | 规则只扩 token、不指定工具时，仍旧靠相似度 |
| 删档不破装配 | 只配 `index: lucene` 或 `index: hybrid` 都能起 | 还有 switch 分支引用被删的枚举值 |

复核：2026-11-26，第 1 批。

| 配置 | 换翼前 R@5 | 换翼后 R@5 |
| --- | --- | --- |
| `regex`（未动） | 0.360 | 0.360 |
| `lucene`（接上译文） | 0.380 | **0.820** |
| `hybrid-1.0` | 0.360 | **0.820**（= `lucene`） |
| `hybrid-0.5` | 0.620 | **0.880** |
| `hybrid-0.0`（未动） | 0.800 | 0.820（语义抖动） |

三个结论，都推翻了我自己之前的判断：

1. **`lucene` 的 `base` 档 0.000 → 0.900**。原来 10 道题每题都返回满 5 个、全错——是读英文原文
   的必然结果，不是阈值筛空。（我上一轮说"一个都没返回"，错的，已更正。）
2. **"纯向量最优"不成立**。票 20 那轮 `hybrid-0.5` 只有 0.620，是因为翼是读英文的二元组；
   换 Lucene 翼后 0.880，五档最高。生产默认因此从 `keyword-weight: 0.0` 改回 `0.5`。
3. **base 档的"pinned 工具题"不是题库设计错误**。它们现在全中（1.000），说明当年是能力不足。
   票 20 与本 README 里"那是题库设计问题"的归因已改口。

生产默认：`index: hybrid` + `keyword-weight: 0.5`，`GATE_PRIMARY = "hybrid-0.5"`，基线已重钉。
门禁：`ModelAndToolSelectionTest` 26/26 绿（改了三处断言：lucene 档与 hybrid 关键字翼现在都包翻译层，
新增 `untranslated()` 辅助穿过去断言内层是 `LuceneToolIndex`）。

**第 2 批（删 `REGEX`/`VECTOR` 档、加规则层）没做**——它要动枚举与所有 switch、还要为新配置项
设计格式，改动量与第 1 批不是一个量级，且第 1 批已经把分数拿到 0.880。留着等下一张票。

## 第 2 批之一：规则层（2026-11-26 落地）

按用户裁决的顺序先做规则层（新增能力、不动现状），删档位另外一步——两者混在一起时，规则层设计
要调就没法回退。

配置：

```yaml
claw4j:
  advisor:
    tool-search:
      rules:
        - pattern: "报销|发票|开票"
          tools: [finance_export]
```

实现是 `RuleToolIndex`（`ToolIndex` 装饰器），由 `ToolSearch.index` 包在**最外层**：

| 决定 | 为什么 |
| --- | --- |
| 包在最外层，不包在融合之内 | 规则是断言不是相关性。包在 `HybridToolIndex` 里面，RRF 会按名次重排，置顶当场失效，而现象只是"规则好像不太灵" |
| 命中的是整句 query（`find`），不是整句相等 | 一条 `报销\|发票\|开票` 就够，不必为每种说法写一条 |
| 不参与 RRF、不给 relevanceScore | 与相似度不是一个量纲；混进去之后"为什么它排第一"说不清（与票面的缺口一致） |
| 不命中原样透传（返回内层同一个响应对象） | 证明它只增不减：不可能让任何候选消失 |
| 规则点名的工具不在清单里 → 跳过 + 一行 WARN | 配置错误不该让整轮对话失败，但也不能静默 |
| 正则编译在构造时做，写错就启动失败 | 留到检索时会被内层 catch 成一次空结果，与"确实没有这个工具"分不开 |
| 模型传了 `categoryFilter` 时按它过滤 | 那是模型明确的收窄，规则不该推翻它 |
| 没配规则时不包这一层 | 空转一层会让所有既有装配断言多一层壳，收益为零 |

默认 `rules: []`：规则跟着业务走，本仓没有可指的业务工具，写一条假的只会污染检索评测基线。
因此**评测分数不受本批影响**（`hybrid-0.5` 仍是 0.880）。

判据（用例都在 `ModelAndToolSelectionTest`，见下）：

| 判什么行为 | 用例 | 先红的样子 |
| --- | --- | --- |
| 规则命中把工具提到最前，内层命中跟在后面 | `ModelAndToolSelectionTest#rulePutsItsToolFirst` | 规则包在融合里面时，RRF 把置顶重排掉 |
| 不命中透传、点名的工具不存在就跳过、正则写错就起不来 | `ModelAndToolSelectionTest#rulesStayOutOfTheWay` | 前者会让候选悄悄少一个；后者让配置错变成"这条规则永远不生效" |
| 配置键能绑上（两级下标）、缺 `tools` 就启动失败 | `ModelAndToolSelectionTest#configBoundariesAreHonoured` | 键写错时不会报错，只会静默变成空表 |
| 三条模块边界闸仍生效 | `ModelAndToolSelectionTest#moduleDependenciesPointOneWay` | —（见下面"腾名额"） |

**腾名额的代价要说清**：`claw4j-agent` 已经 14 个测试文件、单文件 26 个方法，都到顶。
新增两条用例之前把 `agentKnowsNoChannel` / `agentHasNoModelImplementation` / `modelDependsOnCoreOnly`
合并成 `moduleDependenciesPointOneWay`（三条同形规则，守的都是"模块只许单向依赖"），
并在 Javadoc 里保留三条规则各自的 ADR 与症状。没有新增测试文件——那会直接顶破 14 这个数。

**第 2 批之二（未做）**：删 `REGEX` / `VECTOR` 枚举，`VECTOR` 的功能由 `HYBRID` + `keyword-weight: 0`
承接（2026-11-26 实测两者同为 0.820）；`ChineseToolIndex` 退役或只保留同义词表作为规则层的默认值。
要改所有 `switch`、配置绑定与四处测试断言，单独一步做。

## 缺口

- **规则层不做持久化与热更新**：配置改了要重启。评审时如果要求热更新，那是另一张票。
- **不做规则与检索的分数融合**：命中规则就是直接置顶，不参与 RRF。理由：规则是人写的强断言，
  与"相似度"不是一个量纲，混进来会让"为什么它排第一"说不清。

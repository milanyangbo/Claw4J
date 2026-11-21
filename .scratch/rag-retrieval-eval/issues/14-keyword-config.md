# 票 14：词项规则配置化（正则 / 上限 / 权重）

状态：**已完成（2026-11-21）**。八项默认值与配置化之前逐字一致；后来这八项挪到
`keyword.substring` 下（票 15 加引擎时），键名变更记在该票。

## 一、起因

票 06 加了 `SHORT_MODEL`（一个字母 + 1~2 位数字），修好了 4 条查价格表的题。
但它是 `private static final Pattern`，跟 `ASCII_ANCHOR`、`CJK_RUN`、两个上限、两个权重一起
写死在 `KeywordDocumentRetriever` 里。

问题不在"这两条正则对不对"，在"它替部署方做了领域假设"：

| 场景 | 写死的那套 |
| --- | --- |
| `A1`、`A2`、`K4` | 认得出 |
| `AB-12`（两字母 + 横线） | 认不出 |
| `Q3`（财季）、`H2`（氢气） | 误认，白占一个锚点名额 |
| 中文型号「甲型」「三号机」 | 认不出 |
| 英文提问（12 个字符只有一两个词） | 整句长度上限按汉字定的，英文下偏小 |

按本仓已定的规矩（不具通用性的东西不要写死，参数能独立配置，哪怕是正则），这些值全部走配置。

## 二、范围

`claw4j.rag.keyword.*`，八项，默认值与改动前**完全一致**（行为零变化）。

> 票 15 实施时这八项挪到了 `claw4j.rag.keyword.substring.*`（与 `engine` 分开，避免"切到 bm25
> 之后调它们还有用"的误解）。下表是当时的键名，内容没变。

| 键 | 默认 | 原来写在哪 |
| --- | --- | --- |
| `anchor-pattern` | `[A-Za-z0-9][A-Za-z0-9._-]{2,}` | `ASCII_ANCHOR` |
| `short-model-pattern` | `(?<![A-Za-z0-9])[A-Za-z]\d{1,2}(?![A-Za-z0-9])` | `SHORT_MODEL` |
| `cjk-pattern` | `[\u4e00-\u9fff\u3040-\u30ff]+` | `CJK_RUN` |
| `max-anchors` | 4 | `MAX_ANCHORS` |
| `max-terms` | 12 | `MAX_TERMS` |
| `whole-max-chars` | 12 | `WHOLE_QUERY_MAX_CHARS` |
| `whole-weight` | 3 | `WHOLE_WEIGHT`（整句与锚点共用） |
| `bigram-weight` | 1 | `BIGRAM_WEIGHT` |

`whole-max-chars` 是**超出原始清单的一项**：用户列的"两个上限"是锚点与词项数，但整句长度上限
同样是汉字假设（12 个英文字符只有一两个词），留着写死就与这次改动自相矛盾，所以一并提出来。

**不变的三件事**：锚点仍与整句共用 `whole-weight`（拆成两个权重会调出"锚点比整句还重"这种
没法解释的组合）；长短锚点仍共用名额；bigram 仍排在锚点之后切。

## 三、实现

- `RagProperties.Keyword`：八个字段 + 默认常量，空/非正数回落默认（与 `Hybrid`、`Rerank` 同形状）
- `KeywordDocumentRetriever.Rules`：把配置编译成 `Pattern`，**语法错在构造时抛**
  （配错一个括号的表现原本是"全文那一路一条都不命中"，不报错，很难归因）
- 构造器接收 `RagProperties.Keyword`；`searchTerms(String)` 保留为"用默认规则"的入口，
  只给日志与单测——两个入口共用同一份 `terms(query, rules)`，不做第二套切分
- `application.yml` 给出八项与注释

## 四、不做

- **不做 df / IDF 过滤**（按罕见度筛锚点，BM25 的思路）。它才是"换领域不用改正则"的方案，
  但要多一次统计，而且小语料上阈值定不准（68 条题、几百段，df 阈值怎么定都是拍）。
  等语料上一万段再谈，那一次连同 `pg_bigm` 一起做。
- 不动检索链路、不动评测集、不跑评测。

## 五、验收

- [x] 默认值下词项与改动前逐条相同（原有 8 条单测不改一行仍全绿）
- [x] 改 `short-model-pattern` 能认出 `AB-12`
- [x] 改 `whole-weight` / `max-terms` / `whole-max-chars` 生效
- [x] 正则非法 → 启动即抛（`PatternSyntaxException`，JDK 那条消息自带正则原文与位置）
- [x] `claw4j-rag` 全量单测通过

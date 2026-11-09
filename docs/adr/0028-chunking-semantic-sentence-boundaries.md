# 知识库分块：照 LangChain `SemanticChunker` 的句子级语义边界，token 只做上限

---

status: accepted
date: 2026-11-09

---

现在的切块是 `TokenTextSplitter(800)` 一把梭：按 token 数硬切、尽量落在标点上。后果能看见：
**只有标题的段落单独成块**（占掉一个检索位），**语义被切断**（讲退货条件的话被切成两半，
互不相关的两段被合成一块）。这份 ADR 把边界判据从"字数"换成"相邻句子的语义差异"。

**算法不自己发明**：照 LangChain `SemanticChunker`（`langchain_experimental.text_splitter`，
算法出自 Greg Kamradt 的 notebook，LlamaIndex 的 `SemanticSplitterNodeParser` 同款）。
我们只改一处（中文断句正则），再叠一条用户要求的规则（句首转折词）。

## Context

### 官方（Spring AI 2.0.2）只有一件事可做

把 `2.0.2` 的全部 jar 逐个扫 `split`（2026-11-09，本机 `.m2`）：

```
spring-ai-commons/2.0.2:  transformer/splitter/TextSplitter.class
                          transformer/splitter/TokenTextSplitter.class
                          transformer/splitter/TokenTextSplitter$Builder.class
```

**就这两个类，没有别的模块有切块器**。`TextSplitter` 是抽象类，唯一扩展点是
`protected abstract List<String> splitText(String)`；`TokenTextSplitter` 的参数是
`chunkSize(800) / minChunkSizeChars(350) / minChunkLengthToEmbed(5) / maxNumChunks(10000) /
keepSeparator(true) / punctuationMarks`。**语义切块在官方没有对应物。**

### OpenClaw 是结构切块，不是语义切块

`packages/memory-host-sdk/src/host/markdown-chunks.ts`：行级切，`#` 标题与 `- ` 列表项各起新
entry，预算按 `tokens × CJK 加权字符估算`，带 overlap，`MEMORY_CHUNKING_VERSION = 6`；
标题块与后一段合并；分块实现变 → index identity 变 → 下次搜索自动重建；`memory.search.chunking`
已进"死键"清单（不可配）。**它不做 embedding 语义切分。**

### 开源有现成算法，但在 Python 生态

| 实现 | 位置 | 关键参数（默认值） |
| --- | --- | --- |
| LangChain `SemanticChunker` | `langchain_experimental.text_splitter`（不在 core） | `buffer_size=1`、`breakpoint_threshold_type='percentile'`、`breakpoint_threshold_amount=None`（缺省按类型取：percentile 95 / standard_deviation 3 / interquartile 1.5）、`number_of_chunks=None`、`sentence_split_regex='(?<=[.?!])\\s+'`、`min_chunk_size=None`；另有 `gradient` 阈值类型 |
| LlamaIndex `SemanticSplitterNodeParser` | `llama_index.core.node_parser` | 同样是句子向量 + `breakpoint_percentile_threshold`（默认 95） |
| LangChain4j | `DocumentSplitter` 家族 | **没有语义切块**：只有段落 / 行 / 句 / 词 / 字符 / 正则 / 递归；`DocumentBySentenceSplitter` 用 OpenNLP 句子检测器，中文还要另下 `chinese-sent.bin` |

三条结论：**① 算法有成熟的公开实现，不用自己想；② Java 生态没有能直接依赖的**（LangChain4j
没有 embedding 断点，Spring AI 没有切块器），所以只能照它的算法自己写；③ 默认值也有出处，
我们不发明阈值。

### 这份 ADR 推翻了自己的第一版（同日）

第一版写的是"**段落**为原子单元、不做中文断句"，还自创了一个"相邻段距离 ≥ 0.35"的绝对下限。
两条都不成立：段落级做不到"结论与例外不混块"（用户要的正是这个），而"0.35"是我拍的、
开源里没有这个参数。2026-11-09 晚按用户要求与开源实现重写。

## Decision

### 一、算法：逐步照 LangChain，只换一个参数

| 步 | LangChain 怎么做 | 我们怎么做 |
| --- | --- | --- |
| 1 切句 | `re.split(sentence_split_regex, text)`，默认 `(?<=[.?!])\s+` | **同一个参数，换值**：`(?<=[。！？；…!?;])\s*`（理由见第二节） |
| 2 分句组 | `buffer_size=1`：每句与前 1 句、后 1 句合成一组（组内拼接后 embed），用来平滑单句噪声 | 照抄 `1` |
| 3 向量 | 每组的 embedding | 照抄，一次批量 `EmbeddingModel#embed(List)` |
| 4 距离 | 相邻两组的余弦距离 | 照抄 |
| 5 阈值 | `percentile`，`breakpoint_threshold_amount` 缺省 95 | 照抄 **95** |
| 6 断点 | 距离 > 阈值的相邻组之间断开 | 照抄 |
| 7 最小块 | `min_chunk_size` 缺省是 **`None`**（不合并） | **照默认不合并**。理由在下一条：实测语料每份 92–176 字，若按 350 合并，每份文档都会并成一块，语义边界一个都留不下 |
| 8 最大块 | **它不管上限** | 我们用已有的 `chunk-size`（token 上限）硬切，交回 `TokenTextSplitter` |

阈值类型固定用 `percentile`（LangChain 与 LlamaIndex 的默认）：它按本文档自身的距离分布取
第 95 百分位，**跨文档自适应、不需要绝对值**。`standard_deviation` / `interquartile` / `gradient`
三种不实现——多一种就多一个"结果为什么变了"的答案源，真需要时是一次独立裁决。

### 二、唯一一处适配：中文断句正则

LangChain 默认正则 `(?<=[.?!])\s+` 要求句末标点**后面还有空白**。中文没有词间空格，
`"…可退货。然而生鲜不适用。"` 整段切不开——直接套用等于没切（这条可以用一行用例钉住）。
它把这个正则做成构造参数 `sentence_split_regex`，所以**换值属于它设计内的用法**，不是我们改它算法：

```
(?<=[。！？；…!?;])\s*
```

英文 `.` 不进这个集合：`v2.0`、`3.5%`、`1.2.3` 不该断句。换行也当句末（原正则的 `\s+` 已覆盖）。
切出的片段若只有空白或标点，丢掉。**不引分词/断句库**（jieba、HanLP：2MB 词典 + 首次加载
几百毫秒，而且分词边界 ≠ 句子边界；OpenNLP 的中文模型要单独下载，等于给"跑测试"再加一道门槛）。

### 三、叠加一条开源没有的规则：句首转折词

用户 2026-11-09 明确要求"检测到转折词（如『然而』）时自动触发边界切分"。LangChain 与
LlamaIndex **都没有这条**（它们只有向量距离）。所以它是一处**明确不照开源**的规则，
按 `alignment.md` 记账：

| 规则 | 判据 | 出处 |
| --- | --- | --- |
| 句首转折/转场词 → 新块 | 句子去掉前导空白与标点后，以词表里的词开头 | 用户要求，开源无对应物 |

```
转折：然而  但是  不过  可是  另一方面  反过来说  相比之下  与此相反
转场：此外  另外  同时  与此同时  接下来  最后  总之  综上  因此  所以
```

- **只认句首**：句中引用（"政策里提到的『然而』并不存在"）不触发。子串匹配会把引用、举例、
  否定句全切碎。
- 词表是常量，不进配置（与下面第四条一致）。**退场条件**：这一条被实测证明误切多于收益，
  就删掉它、只留向量断点；不要为了救它再叠规则。

### 四、词表、阈值、最小块都不进配置

对齐 OpenClaw 把 `memory.search.chunking` 做成死键的做法：这些值属于**实现身份**，
由 `claw4j_rag.index_meta` 那一行承载。改它们 = 改代码 + 升版本 + 重建。理由：旋钮越多，"结果为什么变了"
越难答；而这三样恰恰是"看起来能调、实际没有依据调"的典型。

### 五、落点：两层——纯函数在 `claw4j-core`，框架壳在 `claw4j-agent`

**这一层分两个包放，不是两个能力**：对外只有一个入口（`TextSplitter` 的子类），
里面那层是纯算法。分开的理由是"算法不该知道 Spring AI 存在"——它只吃字符串与向量、
吐边界下标，能被单独拿去用（`claw4j-core` 的定位就是"只依赖 JDK"）。

| 层 | 包 | 内容 | 依赖 |
| --- | --- | --- | --- |
| 算法 | `io.github.claw4j.chunk`（`claw4j-core`，新包） | 切句、`buffer_size` 分组、相邻组距离断点、`min_chunk_size` 合块、句首转折词判定 | **只有 JDK** |
| 框架壳 | `io.github.claw4j.agent.rag` | `SemanticTextSplitter`：取文本 → 批量 `embed` → 调算法 → 超长交 `TokenTextSplitter` → 组装 `Document` | Spring AI（`EmbeddingModel` / `TextSplitter`） |

```java
// core：纯函数，String[] / double[] 进，边界下标出
public final class ChunkPlanner {
    public static List<String> sentences(String text);
    public static List<String> groups(List<String> sentences, int bufferSize);
    public static List<Integer> breakpoints(List<double[]> vectors, double percentile);
    public static List<int[]> blocks(List<String> sentences, List<Integer> cuts, int minChars);
}

// agent：官方扩展点的实现
public class SemanticTextSplitter extends TextSplitter {
    @Override
    protected List<String> splitText(String text) { ... }
}
```

**上限那一步在壳里**：`chunk-size` 是 token 数，要真 tokenizer（jtokkit，随框架来），
core 不能依赖它，所以"超长再硬切"由 `SemanticTextSplitter` 调 `TokenTextSplitter` 完成。

**判据 3 怎么答**（`alignment.md`：每加一个类先回答"它哪个部件对应"）：

- 对外那层：`DocumentTransformer` / `TextSplitter`——实现官方扩展点，与
  `KeywordDocumentRetriever`、`WeightedScoreJoiner` 同类。
- core 那层：**没有官方对应物**，它属于"这一层本来是新增能力"的内部实现（见 Context 第一节：
  语义切块官方没有）。拆成两个包不改变这个性质，也不能拿它去证明"我们没自研"——
  承认是新增，记账在 Consequences 第 7 条。

**`claw4j-core` 的边界规则跟着改**：`CoreBoundariesTest` 里原来那条"`text..` 只碰 JDK"的规则
随包一起删过（包空了），这次把它换成 `chunk..` 加回去——规则与 `@AnalyzeClasses` 的包列表两处
都要改，只改一处会让规则匹配零个类然后**报绿**（该文件 javadoc 里写过这个坑）。

### 六、降级分两层，不整篇退化

| 情况 | 结果 |
| --- | --- |
| `embed()` 抛异常 / 超时 | **转折词 + 标题 + token 照跑**（向量断点那条丢了），WARN 一行（带文档路径） |
| `embed()` 返回条数与组数不符 | 同上（宁可不用向量断点，也不要错位配对——错位的断点比没有断点糟） |
| 句子数 < 3 | 不调 `embed()`（等于没有相邻组），只按转折词 + 最小块 + token |
| 整段没有句末标点 | 切句得 1 句 → 交 `TokenTextSplitter` 硬切 |
| 空文档 / 纯空白 | 0 块，WARN 一行（与现状一致） |

### 七、切块身份：`claw4j_rag.index_meta`（照 OpenClaw 的 KV 形状，不给文件行加列）

现在跳过一份文件只看 `content_hash`。切块算法一变，hash 没变的文件**永远不重建**，
库里混着两种边界且没有任何迹象。OpenClaw 的做法是**一份索引身份**，不是每行一个标记：

```ts
// packages/memory-host-sdk/src/host/memory-schema-base.ts:16
export const MEMORY_INDEX_META_TABLE = "memory_index_meta";
// 表结构（memory-schema.test.ts）：key TEXT PRIMARY KEY, value TEXT NOT NULL
// 一行 key='memory_index_meta_v1'，值是 JSON：
//   { model, provider, providerKey, sources, scopeHash, chunkTokens, chunkOverlap,
//     chunkingVersion, embeddingInputFormatVersion, ftsTokenizer, provenanceVersion, vectorDims }
//   （extensions/memory-core/src/memory/manager-reindex-ops.ts:197 组装后 writeMetadata）
```

而 `memory_index_sources` 只有 `id / path / source / hash / mtime / size`——**没有分块版本列**。
所以照它的形状，这次建一张 KV 表，**不动 `claw4j_rag.source`**：

```sql
create table if not exists claw4j_rag.index_meta (
    key   text primary key,
    value text not null          -- 值用 text（OpenClaw 也是 TEXT）：只有我们写、我们读，按字符串比较就够
);
```

- 一行 `index_meta_v1`，值是 `{"chunkingVersion": 1, "chunkTokens": 800}`。
  只放我们真有的身份，不抄它的 `model` / `provider` / `scopeHash`——换 embedding 模型会因维度
  不同直接报错，不需要靠这一行去触发重建。
- **不用 `jsonb`**：`jsonb` 读回来的文本形状（空格、键序）与写进去的不一定一致，按字符串比较
  就成了"看运气"；而这一行只有我们写。这也正是 OpenClaw 用 TEXT 的原因。
- 启动同步时：这一行与当前实现对得上 → 逐文件比 `content_hash`；对不上（或没有这一行）→
  **全量重建**（忽略 hash），建完写回。
- 词表 / 阈值 / 最小块改动同样升 `chunkingVersion`：它们的身份在这一行里。
- **上一版 ADR 写的是 `alter table claw4j_rag.source add column chunking_key`**——那是我自己
  加的形状（目的相同、载体不对：OpenClaw 的源头表没有这一列），已撤。

### 八、刻意不做

| 不做 | 理由 |
| --- | --- |
| overlap | LangChain 的 `SemanticChunker` 没有 overlap；重叠会让同一段文字进两个块、检索重复命中 |
| `number_of_chunks` | 那是"先定块数再反推阈值"，与"按语义断"相反 |
| `standard_deviation` / `interquartile` / `gradient` | 只留一种阈值类型；多一种就多一个变量 |
| `MultiQuery` 式的 LLM 判边界 | 每份文档多 N 次模型调用，且不可复现（同一份文档两次切法不同，出处就漂了） |
| 分词/断句库 | 见第二节 |

## Consequences

1. **启动更慢，且比原来贵一档**：每份文档要对**每个句子组**做 embedding（`buffer_size=1` 意味着
   组数 ≈ 句数）。40 份语料 ≈ 40 次批量调用；只在启动同步跑一次。这是语义切块的固有成本，
   LangChain 文档也这样写（"every sentence gets embedded"）。
2. **`percentile 95` 总会切**：哪怕整篇一个主题，它也会把"相对最不连贯的那 5%"切开——
   这就是开源实现的行为（LangChain 与 LlamaIndex 默认都是 95）。不额外加"绝对下限"去堵它，
   那是我上一版自创的东西。要更少断点就把 95 调高，但那是改实现 + 升版本，且必须先有实测。
3. **`95` 这个数还没有本项目实测支撑**：它照 LangChain 的默认，但没在我们的语料上量过。
   落地时量了一次（`rag-corpus` 40 份，每份 92–176 字）：**语料太短，`min_chunk_size=350` 会把
   每份文档合成一块**，语义边界一条都留不下来；所以最小块照 LangChain 的缺省（`None`，不合并），
   只留 `percentile=95` 一个未实测的数。要动它，先在语料上量断点分布。
4. **转折密集的文档会出碎块**：不合并小块是有意的（LangChain 缺省就是不合并），连续转折（"…。
   然而…。但是…。"）仍会切得比较碎。这是规则的结果，不是缺陷。
5. **词表会误判**：句首"同时"在"同时满足三个条件"里是并列条件而非转场。误切代价是块变碎，
   漏切代价是语义混块；词表按**少切**配（不放大批"第一步/第二步"这类次序词）。
6. **升级会触发一次全量重建**（`index_meta` 里还没有 `index_meta_v1` 那一行 → 全部按"身份不符"
   处理），花启动时间，不是 bug。
7. **退场条件两条**：框架出语义切块（`TextSplitter` 的新实现 / 新切块模块）→ 删
   `SemanticTextSplitter`；Java 生态出现可依赖的语义切块库（LangChain4j 补上）→ 换依赖。
   它是本项目第二处"官方没有、我们补一层"的能力，按 `alignment.md` 记账。

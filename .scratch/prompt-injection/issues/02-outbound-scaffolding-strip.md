# 02: 出站剥离——包装标记与纯文本工具调用块不该让用户看见

**What to build:** 在消息发往通道之前，把三类东西剥掉：① 外部内容的包装头行与边界标记；
② 内部脚手架（`<<<BEGIN_UNTRUSTED_CHILD_RESULT>>>` 这类）；③ 模型输出里的**纯文本工具调用块**
（`[tool:x]`、`<function=…>`、`<|channel|>commentary to=`）。

**Blocked by:** 票 01（`01-external-content-boundary`）。没有那层的包装，就不存在"剥包装"这件事；
但 ③ 与本票自身独立，可以先做。

**Type:** 出站清洗（不改变模型看到的东西）

**Status:** draft（等 review，未写实现）

**裁决来源：** 2026-11-17 01:35 用户定「出站剥离另开一票」——它与票 01 的入口侧不在一处，
混在一起会让两边都验不清。

## 问题

**先纠一个定位**：这不是"别让用户看见噪声"的洁癖。OpenClaw 的威胁模型把包装逃逸记成
**T-EVADE-002（Content wrapper escape）**，它的 **Recommendations 只有一句**：

> Output-side validation in addition to input-side wrapping.

也就是说：**输入侧的包装必须配输出侧的校验，否则包装只是一种"看起来有防护"的形态**。
逃逸的手法它列了三种——tag manipulation、context confusion、instruction override——
而"模型把边界标记原样复述出去"正好给攻击者提供了可复制的模板：下一个页面只要照着这个模板写，
就能伪造出一段"看起来是我们包的"内容。剥掉它，等于不给这个模板。

再往下才是观感问题。票 01 包上去的东西，模型一旦复述工具返回，用户就会在回答里读到：

```
<<<EXTERNAL_UNTRUSTED_CONTENT id="9f3c1a7b2e8d4f60">>>
Source: Web Fetch
---
……
<<<END_EXTERNAL_UNTRUSTED_CONTENT id="9f3c1a7d2e8d4f60">>>
```

对用户来说这是噪声；对**下游**来说更糟——如果这段文字落进一个群，另一个 agent 或别家的解析器可能
把 `<<<BEGIN_UNTRUSTED_CHILD_RESULT>>>` 当成真的边界，或者把 `[tool:Bash]` 当成一次真调用。

第三类单独说：**纯文本工具调用块**。有些模型会把自己的工具调用意图写成文字（`[tool:Bash]{"cmd":"ls"}`、
`<function=Bash>…</function>`、`<|channel|>commentary to=Bash`）。这些文本发到通道里，人看着是乱码，
机器看着是**一条可执行的指令**。OpenClaw 的 `stripPlainTextToolCallBlocks` 专门删它。

## OpenClaw 的做法

两处，职责不同：

| 位置 | 剥什么 | 什么时候 |
| --- | --- | --- |
| `src/infra/outbound/sanitize-text.ts` | 包装头行、`<prompt-data>` / `<untrusted-text>` 标签、`<<<BEGIN/END_UNTRUSTED_CHILD_RESULT>>>`、`<<<BEGIN/END_OPENCLAW_INTERNAL_CONTEXT>>>`、`<system-reminder>` / `<previous_response>` 整块、纯文本工具调用块 | 发给通道之前 |
| `src/auto-reply/reply/strip-inbound-meta.ts` | 入站元数据块（`Conversation info (untrusted metadata):` 等）与时间戳前缀 | UI 显示、历史回放 |

两条的判据一致：**AI 面向的脚手架只在当轮给模型看，不留在用户可见的历史里**。`strip-inbound-meta`
那条还多一个作用——不让这些块在历史里**累积**，否则十轮之后提示词里全是元数据。

值得抄的两个实现细节：

- **未闭合的块也要处理**：`stripDelimitedRuntimeBlock` 除了匹配成对的 begin/end，还单独匹配
  `begin` 之后到文末的整段（`unmatchedBeginRe`）——流式截断或模型只说了一半时，标记不能留在输出里。
  **这一条我们没有照抄**：删到文末会把正文整段吃掉，我们只删那一行标记、正文留下。
  代价是截断场景里可能残留半个标记，但比"用户少看到一整段"好。
- **先判"有没有"，再做正则**：`stripPlainTextToolCallBlocks` 开头有三个粗判（`[tool:`、`<|channel|>`、
  `<function=`），一个都不命中就直接返回原串。出站是热路径，别每条消息跑一堆正则。

## 我们的落点

出站有三条路，都要过一遍：

| 路径 | 形态 | 怎么接 |
| --- | --- | --- |
| 非流式回答 | `AgentPort#reply` 返回 `OutboundMessage`（整段） | 返回前整段清洗一次 |
| 流式回答 | `AgentPort#stream` 的 `onChunk`（**增量片段**，`doOnComplete` 只发一个空串） | 每一片喂给一个**有状态的**清洗器，逐片出 |
| 主动推送 | `ReplySender#send`（审批、定时任务结果） | 装饰器包一层，整段清洗 |

**不建议写进 `OutboundMessage` 的紧凑构造器**：那是 core 的域记录，做文本处理会把清洗规则埋进一个
本该只表达"要发一条消息"的类型里；而且流式要状态，record 不能有状态。

**流式是这票的真难点，我第一版写错了，这里改正。** `ChatClientAgentPort#stream` 里确实有一个
`StringBuilder answer` 在累积全文，但**片段是边收边发的**，最后没有"带全文的一帧"可洗
（`doOnComplete` 发的是空串）。所以不能"最后再洗一次"，只能：

- **逐片清洗 + 跨片状态**（推荐）：一个 per-stream 的 `OutboundScaffoldingStripper`，记住上一片末尾
  是否有"可能是一个标记前缀"的残余（比如以 `<<<EXTERNAL_UNTR` 结尾），把它留到下一片再判断。
  代价是要处理三种收尾：正常结束、异常中断、超时——三种都必须把暂存的尾部**吐出来**，否则那截文字
  永远丢了。
- 逐片无状态清洗（简单但会漏）：标记被切开时两半都洗不掉，等于没做。
- 流式不洗、只洗非流式（最省）：流式回答里仍会露出标记。**不推荐**，流式恰恰是长回答才用。

推荐第一条。它与票 01 的"只包一次"是同一类要求：**状态要跟着一次流走，不能挂在单例上**。

## TDD 用例

| 判什么行为 | 用例名 | 先红的样子 |
| --- | --- | --- |
| 包装头行与边界被剥掉，正文留下 | `OutboundTextTest#wrappersAreStripped` | 只删标记不删 `Source:` 那行 → 残留 |
| 未闭合的开始标记也只删那一行 | `#unclosedBlockKeepsBody` | 照它的做法删到文末 → 正文整段没了，red |
| 纯文本工具调用块被删，正常文本不动 | `#plainTextToolCallsAreStripped` | 正则太宽 → 把正文里举的例子也删了（用例放一句正常提到 `[tool:` 的话） |
| 用户自己写的 `<prompt-data>` 不被误剥 | `#userAuthoredTagsSurvive` | 无差别删标签 → 用户问"这个标签是什么"时答案被吃掉 |
| 没有标记时不改一个字符 | `#cleanTextIsUntouched` | 顺手 `trim()` / 改换行 → 逐字节比对红 |
| 流式：标记被切在两片之间也能剥干净 | `#straddlingMarkerAcrossChunks` | 无状态逐片洗 → 两半都留下 |
| 流式：中断时暂存的尾部被吐出来 | `#pendingTailIsFlushedOnAbort` | 只等下一片 → 那截文字永远丢了 |

第四条是本票最容易做错的地方：**我们的回答里可能合法地包含 `<prompt-data>` 这个词**（用户在问这套机制）。
判据要落在"整行匹配包装头 + 紧随其后的标签"上，而不是见到标签就删——OpenClaw 的
`unwrapPromptDataWrapperLines` 就是逐行判 `isPromptDataHeaderLine` + 下一行是开标签，两个条件同时成立才删。

倒数第二条钉的是跨片状态：把 `<<<EXTERNAL_UNTRUSTED_CONTENT id="abc">>>` 从中间切成两片喂进去，
输出里必须一个字符都不剩。

## 验收判据

| 判什么 | 判据 |
| --- | --- |
| 真的剥了 | 让模型 `WebFetch` 一个页面并要求"把工具返回原样贴出来"，回答里没有 `EXTERNAL_UNTRUSTED_CONTENT` |
| 正常内容没被搞坏 | 同一轮里"抓页面 → 总结"的正文完整；一段含 `<prompt-data>` 字样的普通技术说明照常输出 |
| 流式那条 | 流式请求下最终回答不含标记（中间片段允许残留，见缺口） |
| 门禁 | `scripts/verify.sh` 全绿 |

## 缺口

- **跨片状态机的收尾**：正常结束、异常中断、超时三条路都要把暂存的尾部吐出来。这条最容易漏，
  漏了的表现是"回答最后少了半句话"，而不是报错——所以有一条专门的用例钉着。
- **历史回放不剥**：我们没有"把入站元数据塞进 user message"的设计，所以不需要 `strip-inbound-meta`
  那一半；但**会话记忆**里会存模型的回答，如果那时已经剥过，历史自然干净——前提是本票先落地。
- **不做"检测到纯文本工具调用就重试模型"**：只删，不纠正。重试属于另一层（`loop-detection` 那类）。

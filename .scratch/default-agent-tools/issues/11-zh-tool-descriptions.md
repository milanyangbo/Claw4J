# 票 11：工具描述本地化——运行时自动译成中文，开关可配

**Status:** open

> 依赖票 09：改完之后"好了多少"只有那张对照表能回答。09 没落地时本票只能判"通了"。

## 由来

票 05 的根因第 2 条：检索是词面匹配，而框架自带的工具描述**只有英文**。票 04 给网页那一族搭了
同义词桥（`页/链接/网址/浏览 → fetch,url`），桥是逐个搭的、描述一改就静默失效。

票 10 把基座工具常驻之后，`Glob`/`Read` 这类不再靠检索——但**长尾还在**：MCP、技能、子 Agent、
业务工具的描述大多是英文，中文问法照样撞不上。用户 2026-11-26 的裁决是：不要人写映射表，
`zh-description` 配"要不要转"，**代码里运行时自动转**，并可指定哪些工具转。

## 裁决

`docs/adr/0021-explicitly-not-doing.md`（「**领先 2**：工具可见性」）再修订一条：
**给模型看的工具描述可运行时本地化**，开关与作用范围可配；本地化只改描述文本，不改工具名、
不改权限、不改计量口径。

## 配置

```yaml
claw4j:
  advisor:
    tool-search:
      # 要不要把英文的工具描述译成中文。默认关：它每次部署会花掉若干次 utility 调用。
      zh-description: false
      # 只译这些工具（名字来自 @Tool(name=…)）。空 = 自动判定：描述里没有汉字的才译。
      # 名字写错不报错也不翻——与 pinned 不同，它没有"必须有"的语义。
      zh-description-tools: []
```

**为什么要自动判定**：绝大多数工具的描述是英文，逐个人工登记就是又一张会漂的映射表。判据简单到
不需要配置——`description` 里的 CJK 字符少于 6 个，就当成"没有中文"。已经中文化的描述（CJK ≥ 6）
不再翻，免得把"中文 + 英文"再译一遍。

**`zh-description-tools` 的作用是收窄，不是开关**：非空时只在名单里挑"确实没有中文"的那些。
它存在的理由是钱——工具集大了以后，一次部署全量译一遍是几十次调用。

## 译成什么样

```
<中文一句>
<原文>
```

保留原文：工具描述里常有参数名、路径语义、边界条件，译丢一句就是模型误用一个工具。代价是每轮
描述 token 约 1.6 倍——**只对真译了的工具付这份钱**，且这笔钱是固定值，不随对话轮数增长。

翻的规矩写死在提示词里（不开放配置）：一句简体中文、保留工具名/参数名/代码标识符原样、
不加解释、不换行、只输出译文。

## 落在哪

`AgentAutoConfiguration#collectToolCallbacks` 的**出口**，与 `MeteredToolCallback` 同一位置：

```java
tools = withTranslatedDescriptions(tools, translator);   // 策略筛完之后再包
```

三条理由：

1. **索引与模型必须看到同一份**。索引是从 `ToolCallback` 的 `ToolDefinition` 建的
   （`DisclosureToolCallingAdvisor` 的 `indexTools` 就吃这个），包在回调上，两边自动同源；
   包在索引层的话，"全塞模式"下模型看到的还是英文，而那正是 `mode=auto` 在工具少的部署上的常态。
2. **筛完再翻**。被档位/`deny` 挡掉的工具不该花这次调用。
3. 与计量同一个装饰器位置，没有第二处需要记得同步。

翻译器本身：`ToolDescriptionTranslator`，拿 `ChatModel`（`roles.utility`，票 F7 那条链）与一个
进程内缓存 `ConcurrentHashMap<toolName, Entry(sha256(原文), 中文)>`——原文变了就重译。

**不落表**：重启之后重译一遍（几十次小调用、每次几十 token）。V9 留作缺口——等它成为成本问题，
或工具有几百个时再说。少一次 schema 变更，这一版就能早一天有数。

**失败口径**：译不出来就用原文 + 一个工具一条 WARN，不抛、不启动失败。与 `claw4j.rag.enabled`
那条相反，理由要写在代码注释里：中文化是增强，缺了只是命中率低一点；而启动失败会把整个服务挡在门外。

## 首轮延迟

译是懒的（第一次拿到这份清单时），30 个工具串行 ≈ 几秒，所以**并发译**：一个工具一次调用、
互不依赖，失败各自退原文。译完进缓存，后续轮次零调用。预热（启动后后台译）不做，见缺口。

## TDD 用例清单

`claw4j-agent` 已到顶（14 文件；`ModelAndToolSelectionTest` 与 `ObservabilityTest` 各 26 条），
所以本票**第 0 步是合并**，把 26 降下来再放新用例：

- `ModelAndToolSelectionTest#maxResultsIsClamped` + `#indexChoiceIsHonoured` → 合成一条"配置边界"
  （同一入口：`ToolSearchProperties` 的构造与绑定；同一种断言：越界/缺省落到哪）→ **−1**
- `ObservabilityTest#searchCounterSeparatesHitFromMiss` + `#narrowedMissIsTagged`
  → 合成一条"三个数各记什么"（同一入口：`MeteredToolIndex#search`；同一形状：读某个标签的计数）→ **−1**

腾出 2 个位置，放 3 条（第 3 条并进第 2 条）：

| 判什么行为 | 用例 | 先红的样子 |
| --- | --- | --- |
| 关着时一个调用都不发生、描述逐字不变 | `ModelAndToolSelectionTest#zhDescriptionOffChangesNothing` | 开关没接上（永远翻）时，假模型的调用计数 > 0，断言红 |
| 英文描述被换成"中文 + 原文"，且索引里也是这一份 | `ModelAndToolSelectionTest#zhDescriptionRewritesBothModelAndIndex` | 只包了模型那一路时，`ToolIndex` 里那条 summary 还是英文，断言红 |
| 译失败退原文、只告警一次、不抛 | 并入上一条（第二段） | 抛出去时整轮请求失败；每轮重复告警时日志被刷满 |
| 已有中文的描述不翻 | 并入第一条（第二段：给一条中文描述，断言调用计数不变） | 无条件翻时它会被"中文 + 原文"污染，答案变长 |

验收在票 09 那张表上：同一份题、同一索引，`zh-description` 前后各跑一次，比 Recall@k 与 MRR。

## 缺口（本票不做）

- **落表缓存**（V9）：重启重译。等工具上百个、或 utility 调用成为可感成本时再开。
- **批量翻译**：一次请求译多个工具能降调用次数，但要处理模型输出格式的失败路径。先一次一个，
  简单、可测、失败隔离。
- **译得准不准**：一次样本看不出来。票 09 的对照表只回答"命中率变没变"，回答不了"意思对不对"——
  后者要么人工抽检，要么等 `tool-retrieval-benchmark` 那类题面里带参数约束的题。
- **反向**（中文描述译成英文）：同样的机制，等真有中文描述的工具再说，现在没有。

# 票 24：`SYSTEM_SUFFIX` 对齐行业形状——分两刀，先删动作例子

**Status:** parked（2026-11-26 22:4x 起挂起。两条理由：① 用户把重心指回 irrelevance 达标，本票改的是措辞形状、
不影响那个数；② 它第一刀的唯一证据来自已删除的 `eval/tools/selection`，做完也验证不了）
**裁决来源：** 用户 2026-11-26「ToolSearch.SYSTEM_SUFFIX，可以参考行业内，是怎么写的」。
**no ADR needed**（不引入新概念、不改"该不该检索"的归属，只换措辞）。
**上游：** 票 23 第 2 批（L2 四组）已量出 A 组比 B 组多的 4 条全是 `recall_session`，起因指向本票第一刀要删的那个"例如"。

## 一、行业里的四条真文本

> 出处分两类。**一手**的是能直接读到的原文（jar 源码、文档里的字段）；**二手**是别人转述的，
> 字面可信但在这台机器上复核不了。

### 1. Claude Code 的运行时注入（`ToolSearch` 那一层）｜二手

> The following deferred tools are now available via ToolSearch. Their schemas are NOT loaded —
> calling them directly will fail. Use ToolSearch with query `"select:<name>"` to load tool schemas
> before calling them:

后面跟一串工具名。**两句半**：现在的状态（定义未加载）、直接调的后果（会失败）、怎么用（带上名字）。

### 2. OpenAI 客户端执行的 `tool_search`（`description` 字段）｜一手（官方文档示例代码）

> Find the project-specific tools needed to continue the task.

**一句。** 没有"必须"，没有"凡是要用工具的事"，没有反例。

### 3. Spring AI 自带的 `ToolSearchTool` 描述（我们本来就有这一层）｜一手（`spring-ai-tool-search-tool` jar 源码）

> Search for tools in the tool registry to discover capabilities for completing the current task.
> Use this when you need functionality not provided by your currently available tools.
> The search queries against tool names, descriptions, and parameter information to find the most
> relevant tools.
> Returns references to matching tools which will be expanded into full definitions you can then invoke.

**四句**，全是陈述句。注意第二句把"什么时候用"定义成**能力缺口**（not provided by your currently
available tools），不是"凡是能办的事"。

### 4. Anthropic 的定位：这段不该由你写｜二手（工程博客，经中文转述）

Tool Search Tool 是 hosted tool，自带描述；官方给的适用判断是**机制量**（工具 10+ 个、定义超
10k tokens、多 MCP server），不是提示词量。转述里给的收益是"token 降 85%"，但那**不是**
"选错工具变少"——两个指标，转述没分别给数，只能当方向读。含义是：**要不要检索由配置决定，
不由提示词劝**。

**共同形状**：陈述机制（有什么、会怎样、怎么用）／不陈述义务／不举动作例子／不列例外清单／1–4 句。

## 二、我们的 `SYSTEM_SUFFIX` 逐句对照

| 现在的句子 | 行业有吗 | 判 |
| --- | --- | --- |
| "你现在只看得见一个叫 toolSearchTool 的「找工具」工具，其余能力全在它后面。" | 有（Claude Code 第一句同构） | 留 |
| "看不到合适的工具**不代表没有这个能力**…" | 有（"are NOT loaded" 说的是同一件事） | 留，是 A07 的锚点，有测试守 |
| "在你检索之前，'我没有那个工具'这句话**一定**是对的，检索之后往往就不成立了。" | 无 | 删（与上一句同义，纯修辞）——第二刀 |
| "**凡是要用工具才能办的事，第一步就是调 toolSearchTool。**" | 无 | 见下面"票 04 的冲突"——第二刀 |
| "用户在消息里给了网址、文件路径、要跑的命令、邮件、数据口径的，就是要用工具" | 无 | 同上——第二刀 |
| "只有纯闲聊、纯常识、纯解释类的问题才不需要检索。" | 无 | 删（例外清单反作用：清单之外的一切默认该查）——第二刀 |
| "需要向用户确认口径时也可以先检索（两件事不冲突…）" | 无 | 删（决策表的补丁；L2 里没有一条错与它对应）——第二刀 |
| "检索时**用一句自然语言说清你要什么能力**（例如「帮我找之前聊过的内容」「打开这个网页」），不用自己拆关键词——中文和英文它都认。" | 有（"Be specific and include relevant keywords"），但**没有动作例子** | 删"例如"——**第一刀**；"不用拆关键词"是票 07 时代（regex 档）的遗留，两档都吃译文后不再成立，也随第一刀删 |

## 三、为什么分两刀：票 04 会被第二刀撤掉

下面是这轮核对里最该先说的一件事。

**"给网址/文件路径就是要用工具"那句清单，是票 04 的修复本身。** 票 04 的根因写得很明白：

> 2. **提示词把检索限定在业务动作上**：原文是"遇到查数、统计、比对这类业务问题时，先检索一次"，
>    模型读成"业务问题才检索"，于是"看个网页""读个文件"直接判为"我没有这个能力"。

修法之一就是把触发条件从"业务问题"扩到"给了网址、文件路径、命令"。而**守它的只有四条断言**
（`ModelAndToolSelectionTest` 545–548：`contains("网址")`、`contains("文件路径")`）——
没有任何评测量"给了网址却不查"。所以第二刀删掉那句，等于撤票 04 的修复，而我没有手段证明没撤坏。

分刀：

| 刀 | 改什么 | 有没有证据 | 风险 |
| --- | --- | --- | --- |
| **第一刀** | 删两个动作例子 + "不用拆关键词" | 有：L2 里 A 比 B 多的 4 条全是 `recall_session`，而这两句正好把"查历史""看网页"教给了模型 | 低。唯一没量的是"检索 query 变差"——但 `gold.yaml` 的题是人写的，不是模型生成的 query，本来就不覆盖 |
| **第二刀** | 删义务句、触发器清单、例外清单，压成 3 句 | 只有形状证据（行业都这么写），**没有本项目的效果证据** | 高。撤票 04，且当前没有量"该查不查"的手段 |

## 四、第一刀：文本与用例

现在（66–76 行）：

```
检索时**用一句自然语言说清你要什么能力**（例如「帮我找之前聊过的内容」「打开这个网页」），不用自己拆关键词——中文和英文它都认。
```

改成：

```
检索时**用一句自然语言说清你要什么能力**，不用自己拆关键词——中文和英文它都认。
```

| 判什么行为 | 用例 | 先红的样子 |
| --- | --- | --- |
| 提示词不再点名具体动作（这是 L2 里唯一有因果链的改动） | `ModelAndToolSelectionTest#chineseQueryNeedsNoSpaces` 第四段加 `doesNotContain("打开这个网页")` / `doesNotContain("之前聊过的内容")` | 现有文本两个都有，`doesNotContain` 现在必红 |
| 票 04 的三条锚点不许被顺手删 | 同方法 545–548 行现有 `contains("不代表没有这个能力")` / `("网址")` / `("文件路径")` | 删例子时容易把整句一起重写掉——这三条是唯一守票 04 的东西 |
| "不用拆关键词"随第一刀去掉后，不许再有分词要求 | 同方法现有 `doesNotContain("空格")` | 已经守着，不改 |

## 五、第二刀：文本（待前置评测，现在不动）

```
**你一开始只看得见 toolSearchTool 一个工具，其余工具都存在，但定义还没加载进来——看不到不代表没有。**
要用的时候先检索，命中之后那些工具才会变得可调用。
检索时说清你要什么能力就行，不必拆关键词，中文英文都认。
```

进第二刀的前置条件（缺一不做）：**先补一组"该调的场景"**。现在 `eval/tools/selection` 四组全是
"不该调"，撤掉义务句之后"该查不查"没有任何数看着。至少要覆盖票 04 那四问（网址、文件路径、
要跑的命令、邮件）与 BFCL `simple_python` 的 95.25%（门 95，只剩 1 题余量）。

## 六、第一刀怎么判"改好了"

| 方向 | 用哪个数 | 现在 | 判据 |
| --- | --- | --- | --- |
| 不该查却查 | L2 A 组 `recall_session` 条数 | 4 条（A 组总乱调 6/60 = 10.0%） | 降到 0–1 条算有方向；掉不到说明例子不是主因，要回头再看 B 组为什么是 2 |
| 该查不查 | BFCL `simple_python` | 95.25% | 不低于 95；这条与例子无关，跑它是为了确认没连坐 |
| 检索本身 | `eval/tools/retrieval` `R@5` | 0.880 | 不动（gold 是人工 query，不受提示词影响）——跑它只是证明"不动" |

命令：`./eval/tools/bfcl/run.sh`（640 题，1 分 43 秒）+ L2 四组（约 8 分钟，需 `.env`）+
`./eval/tools/retrieval/run.sh`。

## 缺口

- **噪声比效应大**：BFCL 同代码两跑差 0.4–0.8pt，L2 60 题一格 1.7pt。所以第一刀的判据只能是
  "`recall_session` 从 4 条降到几条"这种计数，不能是"准确率涨了零点几"。
- **例子删了之后 query 质量没人量**：gold 是人写的问句，检索评测不覆盖模型自己写的 query。
  要量得先有一份"模型生成的 query → 是否命中 gold"的数据，本票不做。
- **第二刀缺的不是措辞而是量具**：`eval/tools/selection` 现在只有"不该调"的题；没有"该调"的题，
  义务句删不删就只能靠读起来像不像行业，那不是判据。
- **`ToolSearchTool` 的描述仍是 Spring AI 的**：行业主流位置是写在工具描述里，我们改不了框架
  那份。要挪过去得换掉 `ToolCallback`，收益不明、风险不小，本票不碰。

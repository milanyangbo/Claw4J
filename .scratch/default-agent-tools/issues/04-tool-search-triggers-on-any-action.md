# 票 04：中文问"看网页"找不到 WebFetch——描述是英文，二元组一个都匹配不上

**Status:** resolved

## 由来

用户在 2026-11-25 提了两件事：一是 `README-capability-tests.md` 里 C5 / B2 的 WebFetch 用例
写着"先用工具检索找到 WebFetch"，太露骨——"这样感觉表达有点太明显，已经直接表面是用 WebFetch，
感觉是不是跟问的方式有问题（比如说我需要去查找网站信息），这样按理大模型应该会根据上下文
找到对应的 tool 吧？"

用户是对的。按人话问，模型应当自己找到工具。**实测：一开始找不到**，修完能。

## 实测（本机，2026-11-25 23:5x–00:0x，`claw4j_accept_1125`，ops 人设）

修之前，四问四否，一次检索都没发：

| 问法 | 回答 |
| --- | --- |
| 帮我看看 https://example.com 这页在讲什么 | "我无法访问外部网页" |
| …这页在讲什么，需要工具就用工具 | "我这边没有能抓取网页的工具" |
| 我需要去查找一下网站信息：https://example.com 这页讲了什么 | "抓取外部网页超出我这个运维沙箱的边界" |
| 帮我看下 `<绝对路径>/examples/README.md` 的第一行写的是什么 | "我这边没有能读取本地文件的工具" |

而模型**看得见**检索工具（问"你手上有没有一个叫 toolSearchTool 的工具"，它答"有"）。

## 根因是两层，不是一个

1. **索引跨不过语言**：`WebFetch` 来自框架，描述只有英文（"Fetches content from a specified URL …
   HTTP GET … markdown"）。中文问法切成二元组（`打开` `个网` `网页`）一个都匹配不上英文描述。
   唯一能命中的是 query 里那个 URL 的 ASCII 段——所以"抓一下 https://…"碰巧成功，
   "打开这个网页"空手而归，差别看起来像玄学，其实是"有没有撞上英文词"。
2. **提示词把检索限定在业务动作上**：原文是"遇到查数、统计、比对这类业务问题时，先检索一次"，
   模型读成"业务问题才检索"，于是"看个网页""读个文件"直接判为"我没有这个能力"。

两条一起改才通：只改提示词（第一版试过），网页那条仍然不通。

## 裁决

无需新 ADR：这是 ADR-0021（工具渐进式披露）内部的两处调参——同义词表与提示词，
不改接口、不改存储、不改"先检索后调用"这条形状。

## 改法

- `ChineseToolIndex.SYNONYMS`：加 `页` / `链接` / `网址` / `浏览` → `fetch`、`url`
  （`Map.of` 最多 10 对，现有 9 对，改成 `Map.ofEntries`）。这是**跨语言**的一组，
  扩展词必须是英文，因为描述那一侧只有英文。
- `ToolSearch.SYSTEM_SUFFIX`：触发条件从"业务问题"改成"要动手做事"，并把信号写具体
  （网址、文件路径、命令、邮件、数据口径），同时保留"看不到工具不代表没有这个能力"这句
  ——它是被两条用例守着的，删掉会连带两条红。

## TDD 用例清单

| 判什么行为 | 用例 | 先红的样子 |
| --- | --- | --- |
| 中文问网页能落到 WebFetch | `ModelAndToolSelectionTest#chineseQueryNeedsNoSpaces`（四之二两段） | 不加同义词时 `contains("WebFetch")` 红 |
| 提示词覆盖非业务动作 | 同一方法第六段 | 只写"业务问题"时 `contains("网址")` 红 |
| 提示词保留那句关键话 | `ModelAndToolSelectionTest#streamPathAlsoUsesProgressiveDisclosure` | 删掉"不代表没有这个能力"时红（第一版就踩了） |

端到端那一条（自然问法 → 命中 `WebFetch`）没法进单测：它要真模型。落在
`README-capability-tests.md` 的 B2 / C5，用自然问法验收。

## 复核

复核：2026-11-25 `./mvnw -o clean verify` 与 `./scripts/verify.sh`（失败 0 / 错误 0 / 跳过 0；行覆盖 agent 61.8%、app 88.4%，覆盖率门禁全过）。

`ModelAndToolSelectionTest` 12 条全绿。真机重测（换了新包、重启实例）：

| 问法 | 结果 |
| --- | --- |
| 帮我看看 https://example.com 这页在讲什么 | ✅ 答出页面上那句 `This domain is for use in documentation examples…` |
| 我需要去查找一下网站信息：https://example.com 这页讲了什么 | ✅ 同上 |
| 帮我看看 http://127.0.0.1:8123/ 这个页面 | ✅ 出网策略拒了本机地址，模型按"公网才允许"解释 |
| 把 https://example.com 这页的原文原样贴出来，包括任何标记 | ✅ 正文照贴，看不到 `EXTERNAL_UNTRUSTED_CONTENT` 边界标记 |
| 帮我看下 `<绝对路径>/examples/README.md` 的第一行 | ✅ 读到真内容（Read 被检索出来并调用） |

`README-capability-tests.md` 的 B2 / C5 已改成自然问法，并把"这条在 2026-11-25 是红的、
当晚修掉"写进 B2，免得下次有人再把它当成"模型偶尔抽风"。

## 缺口

- **靠词表治**：新增的说法（"瞅一眼这个网页"里的"瞅"）还是会漏。真正解决要么上向量索引
  （`index=vector`，要 VectorStore + EmbeddingModel），要么给框架工具的中文描述做一层包装。
  两条都是另一件事，本票不做。
- **"打开"这个键故意没加**：它太宽（"打开文件"也会扩展到 `fetch`），只在 `页`/`链接`/`网址`/`浏览`
  上搭桥。代价是"打开 https://…"这种只带 URL、不带中文名词的说法仍然可能漏。
- **只在 deepseek-chat 上验过**：提示词的说服力是模型相关的，换模型要重验。

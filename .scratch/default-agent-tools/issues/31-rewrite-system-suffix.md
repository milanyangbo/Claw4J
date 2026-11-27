# 票 31：按行业写法重写 SYSTEM_SUFFIX，去掉 toolSearchTool

**Status:** doing
**裁决记录：** `docs/adr/0041-system-suffix-without-search-tool.md`
**无需 ADR：** 只改一段提示词文案（有 ADR-0041 记录理由）。

## 背景：这句话在它出现的时机上是错的

`ToolSearch.SYSTEM_SUFFIX` 现在三句：

```
**工具不止眼前这些：需要的能力不在这里，就用 toolSearchTool 取出来。**
**取不到就直接说明，不要拿相近的工具硬做；参数不全就问用户，不要猜。**
检索时说清你要什么能力就行，不必拆关键词，中文英文都认。
```

问题不在措辞，在**时机**。`DisclosureToolCallingAdvisor.initialize()` 把它拼进 system message 之后，
工具循环的后面每一轮都带着它。于是：

| 轮次 | 模型眼前有什么 | 这句话成立吗 |
| --- | --- | --- |
| 第 1 轮 | 只有 `toolSearchTool` | 成立（但 `toolSearchTool` 自己的描述已经说了这件事） |
| 第 2 轮起 | 检索回来的 N 个工具 + `toolSearchTool` | **"就用 toolSearchTool 取出来"是重复动作指令** |

第三句"检索时说清你要什么能力"更只在检索之前有意义——检索已经发生完了。

同一件事在 BFCL 上量到过：把整段注入 FC 通道，模型在 34 道题里去调了一个**不存在的**
`toolSearchTool`（票 24）。产品里那个工具存在，所以不会报错，但它换来的是**多一次
没有必要的检索往返**，而不是更好的判断。

## 行业怎么写（已查原文，不是二手解读）

| 来源 | 写到什么 | 没写什么 |
| --- | --- | --- |
| Anthropic《Writing effective tools for agents》 | 描述要像"介绍给新同事"：对象域、输入、输出、**何时该用**；边界靠**命名空间**划 | 通篇没有 "do not use for…" 这类否定清单 |
| OpenClaw `## Tooling` / `## Execution Bias`（2026.6.35 发布包） | "Available tools are policy-filtered"；"Actionable request: act in this turn" | 不在工具表里写"用 X 去取 Y"——取用是 `toolSearchTool` 自己的描述职责 |
| BFCL prompt 形态 | "If none of the functions can be used, point it out" | —— |
| MCP 规范 `annotations` | `readOnlyHint` / `destructiveHint` / `openWorldHint` 由**机器可读字段**表达 | 不用自然语言逐条描述危险动作 |

四份文本的共同点：**每一句都是"什么时候用它 / 什么时候换另一个"的正向条件**，
没有一份要求模型"再去取一次"。取的动作由工具描述和清单本身负责。

## 要改成什么

四条，全部在任何一轮都成立，且一个字都不提检索通道：

```
**能用工具查到或做到的事，就用工具，不要凭记忆答。**
**这里的工具做不了这件事，就直接说明做不到，不要拿相近的工具硬做。**
**调用前把必填参数凑齐；缺什么问用户，不要猜。**
**会改变外部状态的操作（发送、删除、覆盖），动手前先说清要做什么。**
```

删掉的三处与理由：

1. **"就用 toolSearchTool 取出来"** —— 时机错（票面「背景」那张表），且 `toolSearchTool`
   的描述里已经写了它是干什么的。写两遍不会让它更准，只会让模型在拿到工具之后再检索一遍。
2. **"检索时说清你要什么能力，不必拆关键词"** —— 这句是对**检索查询**的指导，而 suffix 是
   **模型决策**阶段的上下文。它属于 `toolSearchTool` 的 `description`，不属于 system。
3. **"工具不止眼前这些"** —— 保留"目录不止这些"这层**事实**，但改成中性陈述
   （"这里的工具做不了"已隐含"眼前这些不是全部"），不再附带动作指令。

## 验收（不看提分，这次不为分数改文案）

| # | 判据 | 为什么 |
| --- | --- | --- |
| 1 | `SYSTEM_SUFFIX` 不含 `toolSearchTool`、`检索`、`取出来` | 本票的全部目的；断了才算改到 |
| 2 | 不含否定清单式举例（"不用于查配料"这种） | 负例是开放集合，写不完（上一轮已论证） |
| 3 | 每句在任何一轮都成立（不含"还在""接下来"这类时序词） | suffix 会跟着工具循环进后面每一轮 |
| 4 | BFCL `--with-prompt` 的 `irrelevance` 不低于裸跑 3 个点（裸跑 77.08%） | 不是要求提分，是要求这次改写**不引入新的误导** |

第 4 条写的是"不低于 77.08% − 3 = 74.08%"。上一轮四档全落在 72–77 之间，
所以这条线的意思是"不比现有的最差那档更差"。**不追分，只查有没有变坏。**

## TDD 用例

| 判什么行为 | 用例 | 先红的样子 |
| --- | --- | --- |
| 提示词里不许再出现检索通道 ✅ | `ModelAndToolSelectionTest` 的 `doesNotContain("toolSearchTool")` | 现在第一句就是它，改之前必红 |
| 不许出现"检索"这个动作词 ✅ | 同上 | 第三句整句都是 |
| 必须有"许可不用工具"的出口 ✅ | 同上 `contains("不要拿相近的工具硬做")` | 去掉这句，模型会拿相近的硬做 |
| 必须有"参数不猜" ✅ | 同上 `contains("问用户")` | 去掉这句，模型开始编参数 |
| 不许出现时序词（"接下来""之后"）✅ | 同上 `doesNotContain("接下来")` / `doesNotContain("之后")` | suffix 每轮都在，时序词在后几轮自相矛盾 |
| 导出文件与常量逐字相同 ✅ | `SystemPromptExportTest` | 手抄一份就会漂（票 24 / 30 的老坑） |

复核：`./mvnw -o -Peval -pl claw4j-eval -am test -Dtest=SystemPromptExportTest`；
`set -a && . ./.env && set +a && ./eval/tools/bfcl/run.sh --with-prompt`（640 题，约 4 分钟）。

## 缺口

- **这条改动不保证提分**。上一轮四档实测说明提示词在这一层最多打平，所以本票只求"不误导"。
- **`toolSearchTool` 的 `description` 没有跟着改**：如果模型在第 2 轮之后还想取新工具，
  它靠的是那个描述。可以在票 32 一起看（那是框架给的类，要么包一层改描述，要么保持不动）。
- **`destructiveHint` 那条**：现在写在提示词里。MCP 规范的做法是机器可读字段，
  我们自己的 `mcp_stdio.Tool` 还没暴露 `annotations`（票 30 记的缺口）。

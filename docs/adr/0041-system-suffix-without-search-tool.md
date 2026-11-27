# ADR-0041：SYSTEM_SUFFIX 只讲用不用工具，不讲怎么把工具取回来

**日期**：2026-11-27
**状态**：accepted（票 31 落地）

## 背景

`ToolSearch.SYSTEM_SUFFIX` 原来是三句，第一句是：

> **工具不止眼前这些：需要的能力不在这里，就用 toolSearchTool 取出来。**

这句话在**它出现的时机上**是错的。`DisclosureToolCallingAdvisor.initialize()` 把它拼进
system message，之后工具循环的每一轮都带着它。而"取"这件事只发生在第 1 轮：

| 轮次 | 模型眼前有什么 | 这句话 |
| --- | --- | --- |
| 第 1 轮 | 只有 `toolSearchTool` + 常驻的三个 | 成立，但 `toolSearchTool` 自己的 description 已经写了这件事 |
| 第 2 轮起 | 检索回来的工具 + `toolSearchTool` | **"就用它取出来"是让人再取一遍** |

第三句「检索时说清你要什么能力」更只在检索之前有意义——那时检索已经发生完了。

代价在 BFCL 上量到过：把整段注入 FC 通道，模型在 34 道题里去调了一个不存在的
`toolSearchTool`（票 24）。产品里那个工具存在，不会报错，换来的是多一次没有必要的往返。

## 行业怎么写（查的是原文）

| 来源 | 写了什么 | 没写什么 |
| --- | --- | --- |
| Anthropic《Writing effective tools for agents》 | 描述像"介绍给新同事"：对象域、输入、输出、**何时该用**；边界靠命名空间划 | 通篇没有 "do not use for…" 这种否定清单 |
| OpenClaw 2026.6.35 `## Tooling` / `## Execution Bias` | "Available tools are policy-filtered"；"Actionable request: act in this turn" | 工具表里不写"用 X 去取 Y"——取用是控制工具自己 description 的职责 |
| MCP 规范 `annotations` | 危险动作由 `readOnlyHint` / `destructiveHint` / `openWorldHint` 这类**机器可读字段**表达 | 不用自然语言逐条描述危险 |
| BFCL prompt 形态 | "If none of the functions can be used, point it out" | 不要求模型"再去取一次" |

四份文本的共同点：每一句都是"什么时候用它 / 什么时候换另一个"的**正向条件**，
没有一份在系统提示里指挥取用动作。

## 裁决

`SYSTEM_SUFFIX` 改成五句，**全部在任何一轮都成立**，且一个字都不提检索通道：

```
**能用工具查到或做到的事，就用工具，不要凭记忆答。**
**这个目录里的工具不止眼前这些；眼前这些是按本次请求筛出来的。**
**这里的工具做不了这件事，就直接说明做不到，不要拿相近的工具硬做。**
**调用前把必填参数凑齐；缺什么问用户，不要猜。**
**会改变外部状态的操作（发送、删除、覆盖），动手前先说清要做什么。**
```

删掉的两处：

1. **"就用 toolSearchTool 取出来"** —— 时机错（见上表）。取用靠 `toolSearchTool` 的 description。
2. **"检索时说清你要什么能力，不必拆关键词"** —— 这是对**检索查询**的指导，属于控制工具的
   description，不属于决策阶段的 system。索引能整句命中这件事由索引保证，不需要模型配合。

保留但改写成陈述的一处：

- **"目录不止眼前这些"** 仍然要在：工具被藏起来时模型看到的清单是筛过的，不说它会以为
  这就是全部（票 10 实测它直接答"我这边没有能查这个的工具"）。但只陈述事实，不带动作。

## 代价

- **这条改动不承诺提分。** 上一轮四档实测（票 30）：裸跑 77.08%、完整四句 72.08%、
  两句变体 75.83 / 76.67%，提示词在这一层最多打平。本票求的是"不再误导"，不是"更准"。
- **`toolSearchTool` 的 description 没动。** 模型在第 2 轮之后若还想取新工具，靠的是它；
  那是框架的类，要改得包一层。留在缺口里。
- **危险动作仍靠自然语言提醒。** MCP 规范的做法是机器可读的 `annotations`，我们的
  `mcp_stdio.Tool` 还没暴露；补上之后这句可以删。

## 一个实现侧的坑（同时修了）

`SYSTEM_SUFFIX` 是 `public static final String` 由字面量拼成，**是编译期常量**，javac 会把值
内联到每个引用点。于是改了它之后，只要引用方（`SystemPromptExportTest`）自己没被重编，
导出的仍是上一版文案——**测试绿、文件写、`--with-prompt` 照跑，量的却是不存在的提示词**。
票 31 踩过一次（改了五句，导出还是旧的三句，287 字节一个不差）。

修法：导出测试改成反射读字段（`ToolSearch.class.getField("SYSTEM_SUFFIX").get(null)`），
运行时解析，绕开内联。

## 什么情况下重新考虑

- 我们自己的 240 句标注做出来之后：那时能分清"改了提示词让误调少了"还是"让漏调多了"，
  才谈得上用提示词调参。
- `toolSearchTool` 的 description 可以自己控制之后：查询措辞的指导应该搬去那里，一次一处。

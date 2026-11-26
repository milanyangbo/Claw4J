# 票 10：基座工具进默认 pinned——不再等模型猜中词

**Status:** open

## 由来

票 05 的受控实验（2026-11-26）里，同一句「examples 目录下有哪些 .md 文件」在默认部署下 0/5：
模型不去检索，直接答"我没有列目录的工具"。票 06 给了 `pinned` 这个开关，但**默认是空**——
理由当时是"常驻哪些是部署的事，代码里不预置工具名"。

结果就是：能力摆在那儿，默认部署用不上。用户 2026-11-26 裁决：把常驻工具、**重点是 Spring AI
提供的那些**，放进 `pinned` 配置。

## 裁决

无需 ADR：这是配置默认值，不是行为代码。票 06 那条"代码里不预置任何工具名"仍然成立——
名单写在 `application.yml`，部署方一行就能改；`pinned` 的语义（每轮都在清单里、不经过检索）不变。

## 名单

```yaml
claw4j:
  advisor:
    tool-search:
      # 基座工具：模型每轮都可能用，而"搜不到就明显失灵"的那种。
      # 名字来自库（agent-utils / exec / webtools）的 @Tool(name=…)，改不了；
      # 与 AgentToolsAutoConfiguration.READ_ONLY_TOOL_NAMES、ExecAutoConfiguration.TOOL_NAMES 同源。
      pinned: [Read, Grep, Glob, Bash, WebFetch, TodoWrite]
```

**为什么是这 6 个**：它们是"说人话就该直接用"的动作——读文件、找文件、搜内容、跑命令、看网页、
记计划。票 05 的实验里，前五条失败全部落在这几个上。

**为什么不把"Spring AI 提供的"全放进去**（下面这些也来自库，但不上默认名单）：

| 工具 | 不进默认的理由 |
| --- | --- |
| `Write` / `Edit` | 默认关着（`claw4j.tools.files.write-enabled=false`），写进 pinned 等于每次启动一条"不在清单里"的 WARN |
| `WebSearch` | 要 `BRAVE_API_KEY`，没配就不装配——同上，会稳定告警 |
| `BashOutput` / `KillShell` | 跟着 `Bash` 的后台进程走，没有 `Bash` 也就不需要；有 `Bash` 时检索一次即可 |
| `Task` / `TaskOutput` | 子 Agent 委派，一轮里通常用一次 |
| `AskUserQuestionTool` | 要 `claw4j.tools.ask.enabled`；它自己也只在"要问人"的那一轮用 |
| `Skill` / `Memory*` | 技能与长期记忆各有开关，且都是"用的时候才用" |

判据一句话：**默认名单只放"无条件装配 + 每轮都可能用"的**，其余留给检索。常驻是有成本的
（每轮多 6 段描述，约 1–2k token，固定值不随工具集增长），别顺手把长尾也塞进来。

## 要跟着改的一处断言

`ApplicationAssemblyTest#toolSurfaceDefaultsToAutoAndWrapsGrouping` 现在断的是
"`pinned` 是空的——不预置任何工具名"。这条要改成"默认就是那 6 个，且与
`READ_ONLY_TOOL_NAMES` / `TOOL_NAMES` 同源（不是又抄一份字面量）"。

**不是为了让用例变绿**：原来那条断言的意图是"别把某次调试的名单写死在代码里"，而现在的意图是
"默认部署要能干活"。两者不冲突，但必须说清是哪一条变了。

## 一个已知的副作用

工具策略把某个常驻工具挡掉时（比如 `messaging` 画像没有 `Bash`），`DisclosureToolCallingAdvisor`
会对这个名字**告警一次**（`warnedPinnedNames` 去重，不刷屏）。这是对的——"配了却用不上"应该被
看见；但验收文档里要写一句，免得有人把它当故障。

## TDD 用例清单

`claw4j-agent` 的两个文件都已到 26 条上限，本票**只改既有用例、不加方法**（票 07 收票时定下的规矩）。

| 判什么行为 | 用例 | 先红的样子 |
| --- | --- | --- |
| 默认名单就是那 6 个 | `ApplicationAssemblyTest#toolSurfaceDefaultsToAutoAndWrapsGrouping` | 名单改了而断言没改时红；名单与 `READ_ONLY_TOOL_NAMES` 漂开时也红 |
| 名单里的名字都真的存在 | 同一方法内加一句：每个名字都能在 `ToolCatalog` 里找到 | 拼错一个字母（`Glob` 写成 `GlobTool`）时红——它只会告警一次，不红就没人发现 |
| `mode=off` 时 pinned 不影响全塞 | 并入 `ModelAndToolSelectionTest#offModeNeverHidesTools` | pinned 被当成"只留这些"时红 |

## 缺口（本票不做）

- **`mode` 的阈值该不该跟着改**：pinned 之后"露多少"的算术变了（常驻 6 个 + 检索）。
  `reveal-below=10` 是否还合适，等票 09 的对照表出来再说——现在改就是拍脑袋。
- **pinned 与 `categories` 的关系**：常驻工具不进检索，所以也收窄不到它们。现在没有冲突
  （收窄只作用于检索结果），但如果将来做"按组常驻"，两者要一起设计。

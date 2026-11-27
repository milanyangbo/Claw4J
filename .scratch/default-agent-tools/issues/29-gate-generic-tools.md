# 票 29：常驻工具分层——只读的留下，通用工具改成"点名才给"

**Status:** open
**裁决来源：** 用户 2026-11-27 的连续三轮：先问"不用于 X 这种描述是不是特殊处理"，
再要求"看 OpenClaw 怎么处理"，最后认可本方案与三条验收线（「好的」）。
**裁决记录：** `docs/adr/0039-pinned-tool-tiers.md`（含退场条件）。

## 为什么不是改描述

- **负例是开放集合**："不用于查配料 / 作曲家 / 画家 / 作家……"写不完，而且每加一个工具就要重写一遍。
- **提示词已经试过**：票 24 把 `SYSTEM_SUFFIX` 调到 89.58%，但那是"1 个不相干的候选"的场景；
  票 28 换成 5 个"有点像"的候选，同一个模型掉回 76.67%。**提示词治态度，候选治事实。**
- **阈值分不开**：票 25 量过，gold 与非 gold 的向量分完全重叠（中位 0.454 vs 0.485），
  加下限要丢一半召回。

所以只剩一条路：**别让它们在默认情况下出现在模型眼前**。

## 对照表（OpenClaw → 我们 → 差在哪 → 改哪边）

| OpenClaw | 我们现在 | 差在哪 | 改哪边 |
| --- | --- | --- | --- |
| 提示词首句 `Available tools are policy-filtered.` | `pinned` 六个恒可见 + `Mode` 滑档 | 没有"按工具"的准入，只有"按数量"的 | 改我们：pinned 分层 |
| `TOOL_DIRECTORY_HYDRATION_KEYWORDS`：run/command/build/test/git → `exec`；url/fetch → `fetch`/`browser` | `rules: []` 空着，机制在（`Rule(pattern, tools)` 命中置顶） | 机制有，没人填；而且**只置顶、不排除** | 改我们：填 rules + 加排除 |
| `expandDirectoryHydrationGroups` 点亮一族 | `categories` 有分组，但没用于扩候选 | 缺"同族一起给" | 先不做，票 30 再定 |
| profile：minimal / coding / messaging / full | 无 | 与本票正交 | 不做 |

## 第 1 批：只改配置（零代码，当天可量，可一行回退）

`claw4j-app/src/main/resources/application.yml`：

1. `pinned` 从 6 个减到 3 个：`[Read, Grep, Glob]`。
2. `rules` 从 `[]` 改成三条（触发词是闭集，不是负例）：

```yaml
rules:
  - pattern: "跑|执行|命令|脚本|编译|测试|安装|部署|终端|git|shell"
    tools: [Bash]
  - pattern: "网址|链接|网页|抓取|抓一下|URL|url|http"
    tools: [WebFetch]
  - pattern: "待办|清单|记一下|TODO|todo"
    tools: [TodoWrite]
```

量一次 240 条 + 88 题，看第 1 批吃到多少。**吃到 84% 就停，不进第 2 批。**

## 第 2 批：加一道反向闸（代码）

第 1 批只能减少"常驻"那一路；Bash 仍可能从检索的 top5 里进来。第 2 批补两件：

1. **未命中就剔除**：一个工具配了 gate 且问句没命中它的触发词 → 从候选里去掉。
2. **兜底**：专用候选不足 3 个时，把被剔掉的补回来——宁可多给一个，也别给空。

落点：`ToolSearchTool` 取到检索结果之后、返回给模型之前加一层
`ToolGate`（新类，`claw4j-agent/toolaccess`），输入是 `(question, candidates)`，
输出是过滤后的候选。它**不碰索引**——索引只负责"像不像"，准入是另一件事。

## TDD 用例清单

| 判什么行为 | 用例 | 先红的样子 |
| --- | --- | --- |
| 常驻只剩三个只读工具，通用工具不许再进 | `ApplicationAssemblyTest#toolSurfaceDefaultsToAutoAndWrapsGrouping` ❌ | 它现在断的就是六个；改 yml 立刻红，这就是"先红" |
| 没有触发词时 Bash 不出现在候选 | `ToolGateTest#bashIsHiddenWhenNoTriggerMatches` ❌ | 没有这个类；不剔除时"现在几点"仍能翻到 Bash |
| 触发词命中时 Bash 进候选 | `ToolGateTest#bashIsPromotedWhenTriggerMatches` ❌ | 同上 |
| 闲聊问句里 WebFetch 不出现在候选 | `ToolGateTest#webFetchIsHiddenForChitchat` ❌ | 无闸时"讲个笑话"能翻到 WebFetch |
| 专用候选不足 3 个时补位，不返回空 | `ToolGateTest#fallsBackWhenCandidatesTooFew` ❌ | 硬剔除会把"看看这个文件多大"变成零候选 |
| gate 配置写错（工具名不存在）启动就报 | `ToolGateTest#rejectsUnknownToolInGate` ❌ | 静默忽略等于"我明明配了却不生效" |

复核：`python3 eval/tools/live/test_live.py`；`./eval/tools/live/run.sh`（240 条）；
`./eval/tools/retrieval/run.sh`（88 题）；`./scripts/verify.sh`。

## 验收（三条，缺一条就算没过，回滚见 ADR）

1. 240 条 `bfcl` 从 **76.67% ≥ 84%**；
2. 88 题里 **22 道 gold 是通用工具的题一道都不能掉**（`在仓库里跑一遍单元测试`→Bash、
   `把这个网址的正文抓下来`→WebFetch、`列个待办`→TodoWrite ……）；
3. `base` 档 `R@5` 不低于 **0.500**。

## 缺口

- **量具偏差**：`live/` 是把 5 个候选**直接当 tools 摆给模型**，而线上是"pinned 常驻 +
  模型自己调 `toolSearchTool` 搜"。这次改动同时动了两边，`live` 的涨幅不能直接当线上收益读。
  真正对齐线上的量具是"先调 toolSearchTool 再决策"，那张票（31）还没开。
- **描述压成一行**（药盒说明书那条）本票不做：一次改两个变量，240 条的前后差归因不了。
  另开票 30，与"同族扩展"一起。
- **rerank** 留作后手：等本票落地后，剩下那 30 条"专用工具沾边"（作曲家→歌手、
  配料→按食材）再上精排。

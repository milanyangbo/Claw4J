# 票 13：加厚 zh-en 题库——5 题判不了中文化，还藏着一道死题

**Status:** resolved

> 依赖票 09（题库与自研指标）、票 12（`ir_measures` 口径与基线）。
>
> **开工时又发现两件更严重的**：`business` 里 4 道题指向 `mail_*` / `github_*`（评测上下文没接
> MCP，同样永远 miss），以及**自研指标把"空手"当命中**的 bug。都在这张票里修了，见「落地记录」。

## 由来

票 11 收窄中文化之后复测，`zh-en` 五道题逐格相同，于是写"没有可测收益"。但那条结论的
**精度不够**：5 题里一格就是 0.20，任何真实收益都淹没在量化误差里。票 09 与票 12 的缺口里
都记着同一件事——先加厚这一档，再谈它值不值。

2026-11-26 拉真实工具清单与描述时发现两个更严重的问题：

1. **死题**：`tools-memory-preference` 的 gold 是 `MemoryView`，而 `ToolCatalog` 里根本没有这个
   工具（19 个里没有）。它永远 miss，把 `zh-en` 的 R@1 白扣一格——不是"检索不好"，是题写错了。
2. **分类错**：现有 5 道 `zh-en` 里只有 1 道真的是"中文问英文描述"。`systemStatus`、
   `recall_session`、`intent` 三个工具的描述**本来就是中文**（平台自研的），拿它们出 `zh-en`
   测的是"中文问中文"，该归 `business`。这一档之前的分数（0.60）之所以不难看，一部分原因就是
   它在测一件更容易的事。

## 裁决

无需 ADR：评测数据，不改线上行为。

## 工具目录（2026-11-26 实测，19 个）

```
AskUserQuestionTool  recall_session  Task  TaskOutput  Skill  intent
schedule_task  list_schedules  cancel_schedule  WebFetch  Read  Grep
Glob  TodoWrite  systemStatus  Bash  BashOutput  KillShell  querySales
```

`Write` / `Edit` / `WebSearch` / `Memory*` / `mail_list_unread` **不在这份目录里**（条件装配或
被画像挡掉），所以不能拿它们出题。

## 出什么题

`zh-en` 档的定义是**中文问法 × 英文描述**，所以新题集中在长尾（基座那几个已有 `base` 档覆盖）：

**哪些工具能出 zh-en 题，由描述语言决定**（2026-11-26 逐个打印核实）：

| 描述是英文（能出 zh-en） | 描述是中文（不能，归 business） |
| --- | --- |
| `AskUserQuestionTool` `TaskOutput` `Skill` `TodoWrite` `BashOutput` `KillShell` `WebFetch` `Read` `Grep` `Glob` `Bash` | `recall_session` `Task` `intent` `schedule_task` `list_schedules` `cancel_schedule` `systemStatus` `querySales` |

`Task` 看着像英文名，描述其实是中文（"把一个需要好几步的任务交给专职 Subagent…"），所以它出
`TaskOutput` 的题可以，出不了 `Task` 的 zh-en 题。

base 档已覆盖 `Glob`/`Grep`/`Read`/`Bash`/`WebFetch`，所以 zh-en 的 19 道新题集中在长尾六个
（每个 3 种问法）+ 1 道 `WebFetch` 变体，加上原有的 `tools-webfetch-address`，共 20 道：

| 工具 | 三种问法方向（不点工具名、不出现英文关键词） |
| --- | --- |
| `AskUserQuestionTool` | 动手前先问清风格 / 让我自己挑一个 / 不确定就先问别猜 |
| `TaskOutput` | 派出去那件事有结果了吗 / 那个子任务跑到哪一步 / 把它的输出给我 |
| `Skill` | 用现成技能转文字 / 有现成办法就直接调 / 按技能说明走一遍 |
| `TodoWrite` | 记成清单做完划掉 / 列待办标已完成 / 分三步先记下来 |
| `BashOutput` | 后台命令输出到哪了 / 那个进程打印了什么 / 没跑完的命令有回显吗 |
| `KillShell` | 把停不下来的命令掐掉 / 终止挂后台那个进程 / 跑太久的不用等了 |
| `WebFetch` | 把网址正文抓下来（与现有「这个地址写了什么」不同问法） |

出题的三条硬规矩（写进 `gold.yaml` 的注释）：

1. **不点工具名、不出现工具名里的英文词**——否则关键字那一路白送分，测的就不是中文化。
2. **干扰项必须同域**：问"读文件"要与 `Grep`/`Glob` 竞争，而不是与 `querySales` 竞争；
   问"定时"的三兄弟（`schedule_task`/`list_schedules`/`cancel_schedule`）互为干扰。
3. **每题一个 gold**。多 gold 会让 `ir_measures` 的 `R@k` 变成"覆盖比例"，与票 09 自研的
   "命中任一"分道扬镳——要用多 gold 得先统一口径，本票不做。

## 同时要修的

- 删掉 `tools-memory-preference`（死题）。
- 把 `tools-system-status`、`tools-recall-previous`、`tools-intent-remind` 从 `zh-en` 移到
  `business`——它们测的是中文描述，放错档会让 `zh-en` 的分数虚高。
- `ToolRetrievalExportTest` 加一条断言：**qrels 里出现的每个 docno 都必须在 `ToolCatalog` 里**。
  这条能抓住"题写错工具名"，而 `ToolGoldTest` 只校验格式、抓不到。
- `ToolGoldTest` 加一条：**`zh-en` 档的 gold 工具必须落在"英文描述"那一组里**（常量集合）。
  这条抓的是这次发现的分类错误；工具描述中文化之后要同步改这个集合。

## TDD 用例清单

`claw4j-eval` 现在 7 文件 / 最大 11 方法（上限 14 / 26）。

| 判什么行为 | 用例 | 先红的样子 |
| --- | --- | --- |
| `zh-en` 至少 20 题 | ✅ `ToolGoldTest#zhEnHasEnoughQuestions` | 5 题时红（实测） |
| `zh-en` 的 gold 都在英文描述那一组 | ✅ `ToolGoldTest#zhEnOnlyTargetsEnglishDescriptions` | `tools-system-status` 在名单外，实测红 |
| qrels 里的工具名都真的在目录里 | ✅ `ToolRetrievalExportTest#everyGoldToolExistsInCatalogue` | 先抓出 `MemoryView`，修完又抓出 **4 道 mail/github 死题**（实测，见下） |
| 空手不算命中 | ✅ `ToolGoldTest#emptyResultIsNotAHit` | 原实现给空结果记 R@1 = **1.0**，实测红 |

前两条是纯数据用例，进常规门禁；第三条要工具目录，带 `@Tag("eval")`，手动跑。

复核：2026-11-26 `./mvnw -o -pl claw4j-eval -am test -Dtest=ToolGoldTest`（7 条全绿）+
`./eval/tools/run.sh`（导出 4 条全绿，退出 0，`--cross-check` 5 档一致）。

## 落地记录（2026-11-26）

### 出题

`gold.yaml` 从 20 条变 38 条：base 4 / business 12 / zh-en 20 / no-search 2。

- `zh-en` 19 道新题：长尾六个（`AskUserQuestionTool` / `TaskOutput` / `Skill` / `TodoWrite` /
  `BashOutput` / `KillShell` 各三种问法）+ `WebFetch` 变体，加上原有的 `tools-webfetch-address`。
- 移 3 道进 `business`（描述是中文的那三个）、删 1 道死题（`MemoryView`）。
- **目录断言顺手抓出第二批死题**：`tools-mail-unread` / `tools-mail-send` / `tools-github-issues` /
  `tools-github-pr-ci` 指向 `mail_*` / `github_*`，评测上下文没有 MCP，这四题也永远 miss，
  把 `business` 白扣 4/12。换成指向 `querySales` / `list_schedules` / `cancel_schedule` /
  `recall_session` 的四种问法（原题留在缺口里，等评测接 MCP 再补回来）。

### 自研指标那个 bug（本票发现，本票修）

`ToolRetrievalEval.Hits#matched` 原实现 `noCandidate || within(k)`——不看这题该不该有候选，于是
**"索引一个字都没返回"拿满分**。`regex` 下 20 道 `zh-en` 里有 3 道返回空，自研记成 R@1 0.150，
第三方口径 0.000。修法：先看 `goldEmpty`，零候选只在"不该有候选"的题上算命中。

发现路径值得记：**是分档对不上暴露的**。总表上自研 0.158 / 第三方 0.056 只差 0.10，看着像分母
问题（38 vs 36）；按分类切开之后 `zh-en` 一侧 0.150 / 0.000，才看出不是分母。

### 新基线（38 条题，36 条进指标）

```
配置                  R@1      R@3      R@5       RR  nDCG@10
hybrid-0.0        0.333    0.556    0.722    0.480    0.540
hybrid-0.5        0.167    0.444    0.611    0.331    0.400
hybrid-1.0        0.056    0.278    0.333    0.165    0.208
lucene            0.139    0.250    0.361    0.218    0.253
regex             0.056    0.278    0.333    0.165    0.208
no-search 零候选率 0.500（1/2；给了候选：tools-chitchat-thanks）
```

分档（第三方口径）：

```
配置          zh-en R@1  zh-en R@5  zh-en RR      business R@5
hybrid-0.0       0.200      0.600     0.352          0.917
hybrid-0.5       0.100      0.500     0.212          0.833
hybrid-1.0       0.000      0.100     0.033          0.750
lucene           0.000      0.200     0.070          0.750
regex            0.000      0.100     0.033          0.750
```

**这一档终于能说话了**：关键字那一路 `zh-en` R@5 只有 0.100（20 题中 2 题），纯向量 0.600。
旧题库是 0.60 对 0.20——不是检索变差了，是旧的那 5 道里有 3 道在测中文问中文。

分档是手算的（`run_ir.py` 只出总表），算法：按 `gold.yaml` 的分类把 qrels 与 run 各切一份，
再喂 `ir_measures.calc_aggregate`。

### 顺带回答票 11 留下的问题：中文化到底值不值

题库加厚之后**收益可测**了：`hybrid-0.0` 的 `zh-en` 关/开各跑三遍——

```
关   R@1 0.200 0.200 0.200   R@5 0.600 0.600 0.600
开   R@1 0.350 0.250 0.400   R@5 0.750 0.800 0.750
```

`R@5` 3~4 格，远超 0.05 容差；`business`（中文描述）与 `regex`/`lucene`（读原文）一格没动，
与"只喂向量"的设计一致。但**开的那一侧不可复现**——翻译不落表，每个进程重译，措辞不同、
embedding 跟着不同。所以票 11 那句"没有可测收益"是对 20 题那版成立、对现在不成立，已回写。

## 基线要重钉

题库从 20 条变 **38** 条（base 4 / business 12 / zh-en 20 / no-search 2），
**所有分数与 `baseline.json` 全部作废**。收票时：

1. `./eval/tools/run.sh --update-baseline` 重钉；
2. `eval/tools/README.md` 的基线表整段替换，旧数字标"旧题库（20 条），已作废"；
3. 票 11 的「第二版实跑」也基于旧题库，加一行指向本票——**不要拿新旧题库的分数直接比**。

## 缺口（本票不做）

- **多 gold 的口径**：见上，先不做。真要做得先决定 `R@k` 用"命中任一"还是"覆盖比例"。
- **题的客观性**：这 19 道是我按工具语义写的，不是从真实失败日志里捞的。上线后应该用
  `claw4j.tool.search.calls` 的 miss 与 DEBUG 名单替换掉一部分。
- **20 题仍然小**：一格 0.05，能判"中文化有没有用"，判不了"哪个工具最该中文化"。
- **MCP 那四道题待回补**：`mail_*` / `github_*` 的原题从这里删掉了（评测上下文没接 MCP）。
  接上之后应按原问法补回 `business`，那才是"业务工具"这一档完整的样子。
- **分档不进报告**：`run_ir.py` 只出总表，分档要手算。要常驻的话，导出应多写一份
  `qid → category`，再让 `run_ir.py` 分组——本票没做，因为手算一次就够写结论。
- **开中文化时不可复现**：译文不落表（票 11 的 V9 缺口），每次重译让 `R@1` 在 0.25~0.40 之间抖。
  要拿它做回归门，先得把译文钉住。

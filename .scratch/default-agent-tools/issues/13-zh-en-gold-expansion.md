# 票 13：加厚 zh-en 题库——5 题判不了中文化，还藏着一道死题

**Status:** open

> 依赖票 09（题库与自研指标）、票 12（`ir_measures` 口径与基线）。

## 由来

票 11 收窄中文化之后复测，`zh-en` 五道题逐格相同，于是写"没有可测收益"。但那条结论的
**精度不够**：5 题里一格就是 0.20，任何真实收益都淹没在量化误差里。票 09 与票 12 的缺口里
都记着同一件事——先加厚这一档，再谈它值不值。

2026-11-26 拉真实工具清单时又发现一道**死题**：`tools-memory-preference` 的 gold 是
`MemoryView`，而 `ToolCatalog` 里根本没有这个工具（19 个工具里没有它）。这题**永远 miss**，
把 `zh-en` 的 R@1 白扣一格。它不是"检索不好"，是"题写错了"。

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

| 工具 | 题面方向（不点工具名、不出现英文关键词） |
| --- | --- |
| `Task` | 让另一个助手去查一件事 |
| `TaskOutput` | 刚才派出去那件事有结果了吗 |
| `Skill` | 用现成的技能把这份 PDF 转成文字 |
| `TodoWrite` | 把这三步记到清单里，做完一项划掉 |
| `AskUserQuestionTool` | 动手前先问清楚我要哪种风格 |
| `BashOutput` | 后台那个命令现在跑到哪了 |
| `KillShell` | 把刚才那个停不下来的命令掐掉 |
| `schedule_task` | 每天早上九点提醒我看报表 |
| `list_schedules` | 我设了哪些定时的事 |
| `cancel_schedule` | 把每天九点那个提醒取消 |
| `recall_session` | 我们之前定的那个口径是什么（与现有题不同问法） |
| `systemStatus` | 你是哪个模型、什么版本（与现有题不同问法） |

出题的三条硬规矩（写进 `gold.yaml` 的注释）：

1. **不点工具名、不出现工具名里的英文词**——否则关键字那一路白送分，测的就不是中文化。
2. **干扰项必须同域**：问"读文件"要与 `Grep`/`Glob` 竞争，而不是与 `querySales` 竞争；
   问"定时"的三兄弟（`schedule_task`/`list_schedules`/`cancel_schedule`）互为干扰。
3. **每题一个 gold**。多 gold 会让 `ir_measures` 的 `R@k` 变成"覆盖比例"，与票 09 自研的
   "命中任一"分道扬镳——要用多 gold 得先统一口径，本票不做。

## 同时要修的

- 删掉 `tools-memory-preference`（死题），换一道指向真实工具的题。
- `ToolRetrievalExportTest` 加一条断言：**qrels 里出现的每个 docno 都必须在 `ToolCatalog` 里**。
  这条能抓住"题写错工具名"，而 `ToolGoldTest` 只校验格式、抓不到。

## TDD 用例清单

`claw4j-eval` 现在 7 文件 / 最大 11 方法（上限 14 / 26）。

| 判什么行为 | 用例 | 先红的样子 |
| --- | --- | --- |
| `zh-en` 至少 20 题 | `ToolGoldTest#zhEnHasEnoughQuestions` | 现在 5 题，断言 `>= 20` 当场红 |
| qrels 里的工具名都真的在目录里 | `ToolRetrievalExportTest#everyGoldToolExistsInCatalogue` | 把 `MemoryView` 留着时红——它不在 19 个里 |
| 死题不再出现 | 同一条（`doesNotContain("MemoryView")` 只是表象，真判据是上一条） | —— |

第一条是纯数据用例，进常规门禁；第二条要工具目录，带 `@Tag("eval")`，手动跑。

## 基线要重钉

题库从 20 条变 35 条，**所有分数与 `baseline.json` 全部作废**。收票时：

1. `./eval/tools/run.sh --update-baseline` 重钉；
2. `eval/tools/README.md` 的基线表整段替换，旧数字标"旧题库（20 条），已作废"；
3. 票 11 的「第二版实跑」也基于旧题库，加一行指向本票——**不要拿新旧题库的分数直接比**。

## 缺口（本票不做）

- **多 gold 的口径**：见上，先不做。真要做得先决定 `R@k` 用"命中任一"还是"覆盖比例"。
- **题的客观性**：这 15 道是我按工具语义写的，不是从真实失败日志里捞的。上线后应该用
  `claw4j.tool.search.calls` 的 miss 与 DEBUG 名单替换掉一部分。
- **20 题仍然小**：一格 0.05，能判"中文化有没有用"，判不了"哪个工具最该中文化"。

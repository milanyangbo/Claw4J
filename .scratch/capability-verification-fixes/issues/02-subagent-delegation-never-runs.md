# 02: 子 Agent 派不出活——三次尝试，`Task` 的计数全是 0

**What to build:** 一句话的事：**查清**为什么对话路径上 `Task` 一次都没执行，把根因写死在这张票里，
并且——如果根因在我们自己的代码——修掉它并补一条守门用例。如果根因是"模型没发 tool_call"，
那结论是**文档与提示词**的问题，票 03 里改判据，本票只留证据。

**Blocked by:** 无。

**Type:** investigation

**Status:** ready-for-agent

**Demo:** 用户说「派 capacity-triage 去看一下现在的容量」，`claw4j.tool.calls{tool=Task,outcome=ok}`
从 0 变成 1，日志里有一行子 Agent 的派发记录，回答里带堆内存与线程数。

## 已经排掉的三种可能（真实例上验的，不是推测）

| 猜想 | 怎么排除的 | 结论 |
| --- | --- | --- |
| 档位把它挡了 | `--claw4j.tools.profile=coding` 起实例，启动日志列出被挡的 7 个名字，里面**没有** `Task` | 不是档位 |
| 权限拒了 | `Task/{ok,error,denied}` 三个计数**都是 0**。拒绝也会计数（`MeteredToolCallback` 在 `collectToolCallbacks` 里对所有保留下来的工具一律包一层） | 不是权限 |
| 根本没注册 | `--claw4j.tools.allow=Task` 起实例：`工具档位 FULL：清单 1 个，挡掉 19 个（…）`，而挡掉的名单里没有 `Task` → 它就是留下来的那一个 | **注册了、进了清单** |

## 还没排掉的（这正是本票要做的事）

计数全 0 只能说明 `SubagentDelegation#call` 没被调用。往下有三条互斥的岔路，差别在"谁没做"：

1. **模型没发 tool_call**——它自称"调用失败/返回为空"，实际一次都没调。此前 `summarize` 抓到过
   两次这种自述（一次真的调过、一次真的没调），所以不能凭它的话判定。
2. **发了，但名字不对**（`task` / `subagent_task` / 带命名空间前缀）——`ToolCallingManager` 找不到
   这个工具，回给模型一句错误，而这**不经过回调**、**不计入 `claw4j.tool.calls`**。零计数与
   "模型说失败"两边同时成立，这条最像。
3. **发了、名字也对，但在进回调之前被挡**（工具调用预算熔断、检索后没把它注入当轮清单）。

**用一次 DEBUG 复跑分开它们**：`--logging.level.org.springframework.ai=DEBUG`（重点看
`ToolSearchToolCallingAdvisor` 与 `ToolCallingManager` 的"找到哪些工具/要调哪个工具"两行），
同一个问句跑三遍。判据：

- 日志里没有任何 `tool_calls` → 岔路 1；
- 有 `tool_calls` 但名字不是 `Task` → 岔路 2（顺带看它为什么这么猜：检索结果里名字的呈现方式）；
- 有 `Task` 且有"达到上限/跳过执行" → 岔路 3。

## 要改的文件

| 文件 | 改什么 |
| --- | --- |
| `.scratch/capability-verification-fixes/issues/02-*.md` | 把复跑拿到的实际日志片段贴进来，定根因 |
| `docs/capability-tests.md` 第 6 节 | 现在写的是"派一个子 Agent 出去"，没有可判定的期望值。改成与实测一致的那一条 |
| 只有岔路 3 成立才动代码 | 动的是 `ToolSearch` / `ToolCallBudget` 那一侧，本票不预先授权 |

## 已知的一处可疑（不作为结论，只作为复跑时优先看的地方）

`Task` 靠**检索**才能被模型看见（`ToolSearch.SYSTEM_SUFFIX`：模型一开始只看得见 `toolSearchTool`）。
所以"派活"这件事在这套装配里是**两步**：先检索出 `Task`，再调用它。而 `Task` 的描述由
`SubagentDelegation.description(definitions)` 生成，内容是 Subagent 的名字与它能干什么——
它与用户那句「派 capacity-triage 去看容量」有多大词面重合，决定了第一步能不能成。
若复跑显示"检索没返回 Task"，那是**索引与描述**的问题（`ChineseToolIndex` 相邻两字一组），
修法是改描述文案，不是改索引算法。

## 验收判据

本票以"证据 + 根因一句话"为交付物。若定到岔路 2/3 并且要动代码，再补 TDD 用例清单——
到时候按 `.scratch` 里既有票的格式追加，不在动手前先编用例名。

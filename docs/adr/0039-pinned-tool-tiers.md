# ADR-0039：常驻工具分层——只读的留下，通用工具改成"点名才给"

**日期**：2026-11-27
**状态**：accepted（票 29 落地；退场条件见文末）

## 背景

`pinned: [Read, Grep, Glob, Bash, WebFetch, TodoWrite]` 这六个工具每轮都在清单里，
不经过检索。票 28 把 240 条"不该调"的 BFCL 问句走了一遍真实链路（58 个工具、top5 候选）：
**56 条误调，其中 26 条调的是这四个通用工具**——Bash 9、AskUserQuestionTool 7、
WebFetch 6、Skill 4。

而线上比评测更糟：这六个是**恒可见**的，评测里它们还要先挤进 top5 才被看见。
所以"给候选设门槛"这种检索侧改动，对线上最危险的那几个工具**一点用都没有**。

## 裁决

1. **常驻只留纯只读的三个**：`Read` / `Grep` / `Glob`。
2. `Bash` / `WebFetch` / `TodoWrite` 移出常驻，改成"命中触发词才进"。
3. 触发词用已有的 `rules`（`ToolSearchProperties.Rule`，命中置顶），不新增机制。
4. 第二阶段加一道反向闸：**未命中就从候选里剔除**；专用候选不足 3 个时补位，
   避免"什么都搜不到"。
5. 通用工具从"永远看得见"变成"要说一句才看得见"，但 `toolSearchTool` 一直在——
   **挡掉的是默认值，不是能力**。

## 依据（OpenClaw 的对应物）

| OpenClaw | 我们 |
| --- | --- |
| 系统提示词首句 `Available tools are policy-filtered.` | 只有 `Mode` 与 `pinned`，没有 per-tool 准入 |
| `TOOL_DIRECTORY_HYDRATION_KEYWORDS`：run/command/shell/build/test/git → exec；search/current/price → web；url/fetch → fetch | 空着的 `rules` 就能表达同一件事，只是没人填 |
| `expandDirectoryHydrationGroups`：点亮一个就把同族兄弟一起点亮 | 我们的 `categories` 已有分组，可按组扩展（票 30 再定） |
| profile（minimal / coding / messaging / full） | 暂不做，与本裁决正交 |

## 代价

- **该调没调**：88 道 gold 里有 22 道答案是通用工具（`在仓库里跑一遍单元测试`→Bash、
  `把这个网址的正文抓下来`→WebFetch、`列个待办`→TodoWrite）。触发词漏一个就是另一种错，
  比误调更难发现，所以验收里有一条硬断言钉着这 22 道。
- **`base` 档可能再掉**：它现在是 0.500，全表最弱。这次动的是它最依赖的那几个工具。
- **词表会漏**：用户说"看看这个文件多大"要 Bash 的 `ls`，但不含任何触发词。安全网是两个：
  候选不足时兜底、以及 `toolSearchTool` 让模型自己搜回来。

## 退场条件

量完如果不成立，回滚到六个常驻、`rules` 清空。判据（票 29 的验收线）：

1. 240 条 `bfcl` 没到 **84%**；
2. 22 道通用工具题掉了 **2 道以上**；
3. `base` 档 R@5 低于 **0.500**。

任一条成立就回滚，不在这里继续加码。

# ADR-0043：控制类工具不进检索闸，默认常驻

**日期**：2026-11-27
**状态**：accepted（`AskUserQuestionTool` 与 `TodoWrite` 回到 `pinned`）

## 背景

票 29（ADR-0039）按"对任何话题都能凑合"这一条，把常驻的六个工具砍到三个，`Bash` / `WebFetch` /
`TodoWrite` 改成"检索命中触发词才给"，`AskUserQuestionTool` 同批加了 `gate: true`。

票 32 复核时发现这条路对后两件**结构性不通**。模型手上只有 `toolSearchTool` + 三个只读工具时：

- 反问这件事，模型需要它的时候正在**组织回答**，不会先想到"去检索一个反问工具"；
- 就算它想起来了，闸的判据是**模型自己改写的检索词**，用户原话里的"让我选"在改写后通常就没了。

现场（`/tmp/claw4j-r3.log`，探针用户 `pr1`，明确要求"先找找看有没有能给我出选择题的工具"）：

```
工具准入闸：query=出选择题 让用户点选 选项 交互式提问               拿掉=[AskUserQuestionTool]
工具准入闸：query=ask user question multiple choice options select  拿掉=[AskUserQuestionTool, TodoWrite, Bash]
```

第二条 query 是模型自己写对的、关键字那一路也真召回了 `AskUserQuestionTool`，仍被闸拿掉；模型于是
如实回答"我找了两轮，手上没有这类工具"。B7 同因：用户说"让我选"，模型搜的是
`deployment environment release pipeline`，工具进不了候选，它转身用 `Bash` 翻文件，六次预算烧完，
最后把 `Total tool call limit (6) exceeded` 原文当成回答返回。

## 判断：这两件回常驻，`Bash` / `WebFetch` 不回

### 1. 它们是控制类，不是能力类

`AskUserAutoConfiguration` 自己的注释就是判据："提问不读任何数据，它只决定**这一轮要不要停下来等人**"。
`TodoWrite` 同理——它不产出业务结果，只把"先做什么、到哪了"变成可反复交上来的东西。

能力类（`querySales` / `WebFetch` / 音乐 / 菜谱）回答"这个系统能做什么"，用语义检索找是对的：用户问的事
决定该给哪个能力。控制类回答"这次对话怎么走"，**跟话题无关**，用话题检索来决定给不给，本身就错配。
这也正是 ADR-0042 的结论：「反问属会话策略，不在工具检索那一维」——把它留在检索闸里，等于用检索那一维
去管一件不在这一维的事。

### 2. 当年移出的证据不对等

票 28 的 240 题里：`Bash 9 / WebFetch 6 / AskUserQuestionTool 7 / Skill 4 / TodoWrite 0`。

- `Bash` 9 条、`WebFetch` 6 条是"拿它算个数、拿它查个实时事实"，确实该收。**保持移出。**
- `AskUserQuestionTool` 的 7 条是在"题面完整、不该反问"的题上判的。真实场景的输入本来就有模糊边界，
  那种反问是我们要的行为——这 7 条记的是**评测口径的局限**，不是工具的错。
- `TodoWrite` 是 **0 条**。当初移出只是为了凑"六个减到三个"，没有证据。

### 3. 常驻的代价要分清

| 工具 | 误调的代价 |
| --- | --- |
| `TodoWrite` | 花一次调用预算（整轮 6 次里的 1 次）。误调 0 条，等于免费 |
| `AskUserQuestionTool` | **这一轮真的停下来等人**（默认 900 秒，`claw4j.tools.ask.timeout-seconds`）|
| `Bash` / `WebFetch` | 动了这台机器 / 出了网。这才是不能常驻的那一类 |

`AskUserQuestionTool` 的代价最高，所以它需要的不只是"藏起来"——查过 `PendingQuestions.await`：超时与
被中断都返回 `Outcome.noAnswer()`，那一轮继续，**不会挂死**；`claw4j.ask{status=noanswer|busy|rejected}`
已经在记。也就是说误调最坏的结果是"等一个超时然后接着答"，可见、可调、不死。这个代价可以接受。

## 后果

- `claw4j.advisor.tool-search.pinned` = `[Read, Grep, Glob, AskUserQuestionTool, TodoWrite]`。
- `rules` 里那两条 `gate: true` 保留：常驻走 `addPinned`，不经过检索，两条规则对这两件不再起作用；
  删掉反而会丢掉"没配常驻的部署还能靠检索拿到"这一层。
- 关掉 ask 的部署（`claw4j.tools.ask.enabled=false`）要把名字从 `pinned` 里删掉，否则每轮一条告警。
- **BFCL 五档的 `irrelevance` 会掉**：那 240 题全是完整问句，"该问的时候问了"在它上面一律判错。
  这个掉要记在账上，不当能力退步——判断"该不该反问"得靠 B7 那类用例，不是 BFCL。
- 顺带修掉一个与测试无关的现场缺陷：撞预算时框架把超限当成"一代回答"，英文原文直接进了 `text`
  字段（B7 / td1 两处现场）。现在三条判定兜住它（`finishReason` / `partialToolResponses` / 原文特征），
  命中的是哪条会打进 debug 日志。

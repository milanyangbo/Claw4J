# 33 控制类工具回常驻 + 撞预算的收尾

**Status:** in_progress（2026-11-27 22:05 开票）

**Depends on:** 32（注入工具登记）、29（准入闸）、ADR-0039 / ADR-0042

## 为什么

票 29 按「对任何话题都能凑合」把六个常驻砍到三个，`AskUserQuestionTool` / `TodoWrite` 改成「检索命中触发词才给」。
票 32 复核时发现这条路对这两件是**结构性不通**的：

- 它们不是「这个系统能做什么」（能力），是「这次对话怎么进行」（控制）。模型需要反问时，它正在组织回答，
  不会先去检索「有没有一个反问工具」；而闸的判据是**模型自己改写的检索词**，用户说的「让我选」在改写后
  通常就没了。现场（`/tmp/claw4j-r3.log`）：

  ```
  工具准入闸：query=出选择题 让用户点选 选项 交互式提问               拿掉=[AskUserQuestionTool]
  工具准入闸：query=ask user question multiple choice options select  拿掉=[AskUserQuestionTool, TodoWrite, Bash]
  ```

  第二条 query 是模型自己写对的、关键字那一路也真召回了 `AskUserQuestionTool`，仍被拿掉；模型于是如实
  回答「我找了两轮，手上没有这类工具」。B7 同因，最后还去用 `Bash` 翻文件，六次预算烧完。

- 当年移出的证据**不对等**：票 28 的 240 题里 `Bash 9 / WebFetch 6 / AskUserQuestionTool 7 / Skill 4 /
  TodoWrite 0`。其中 Ask 那 7 条是「题面本身完整、不该反问」的题判的——在那种题上反问确实算误调，但
  **真实场景里用户输入常常是模糊的**，这一条恰恰是我们要的行为；TodoWrite 则是 0 条误调，当初移出只是为了
  凑「六个减到三个」。

- ADR-0042 已经裁过「反问属会话策略，不在工具检索那一维」。把它留在检索闸里，等于用检索那一维去管一件
  不在这一维的事，与那条裁决自相矛盾。

另外一件与测试无关的现场缺陷：撞预算时框架把超限当成「一代回答」返回，`text` 字段就是英文原文
（B7：`Total tool call limit (6) exceeded for this turn...`；td1：`Tool call limit (3) exceeded for tool 'Read'...`），
用户既没得到答案，也看不出做到哪了。

## 做什么

### ① 两件回常驻

`claw4j.advisor.tool-search.pinned` 从 `[Read, Grep, Glob]` 改为 `[Read, Grep, Glob, AskUserQuestionTool, TodoWrite]`。

`rules` 里那两条 `gate: true` 保留（常驻走 `addPinned` 那条路，不经过检索，两条规则不再影响它们，删了反而
丢掉「没配常驻的部署还能靠检索拿到」这一层）。两件都是 `matchIfMissing=true` 的默认装配，不会出现
「配了名字但工具不存在」的每轮 WARN。

### ② 撞预算的收尾

`ChatClientAgentPort.cappedText` 现在只看 `finishReason`，实测没生效（用户拿到的是英文原文）。改为三选一命中即算超限：

1. `finishReason == ToolCallLimitExceededException.FINISH_REASON`；
2. 该代回答带着 `METADATA_PARTIAL_TOOL_RESPONSES`（框架只在超限时挂这个）；
3. 文本形如 `Tool call limit (` / `Total tool call limit (`（框架 `ToolCallLimits.check` 的两句）。

第 3 条是兜底，加它是因为前两条依赖框架把元数据一路带回来，而实测没带回来；纯文本匹配会因框架改措辞
静默失效，所以**三条按顺序试，命中的是哪条打进 debug 日志**，下次框架升级能从日志看出该删哪条。

回给用户的话改成两句：先说停了，再说已经做完几次、下一步该怎么说更具体。已完成次数从第 2 条的
partial responses 取；取不到就不提数字。

### ③ 误调反问的收尾：查完不动代码

`PendingQuestions.await` 超时/被中断都返回 `Outcome.noAnswer()`，那一轮继续，**不会挂死**；等待时长由
`claw4j.tools.ask.timeout-seconds`（默认 900）控制，指标 `claw4j.ask{status=noanswer|busy|rejected}` 已经在记。
所以「干等 15 分钟」是**默认值选择**，不是缺陷，改默认值反而会伤到真的要慢慢想的场景。这一半只记结论。

## 验收

1. 单测：`pinned` 里含这两件；`cappedText` 三条判定各有一条用例（用真 `ToolCallLimitExceededException`
   造那一代回答，不手写 JSON）。
2. B7（反问）：`/api/chat/questions` 出现一条待答问题，`answer` 返 204，回答里出现「预发」。
3. B8（计划）：`TodoWrite` 至少 2 次。
4. BFCL 五档重跑，**并事先讲清**：那 240 题都是完整问句，`irrelevance` 会因「该问的时候问了」而掉，
   这个掉要记在账上，不当能力退步。
5. 撞预算现场复现一次，确认用户拿到的是中文那句。

## 复核（2026-11-27 22:25，`claw4j_accept_33` / 日志 `/tmp/claw4j-33.log`）

### 落了什么

| # | 文件 | 改了什么 |
| --- | --- | --- |
| 1 | `application.yml` | `pinned` 加 `AskUserQuestionTool`、`TodoWrite`；`rules` 那两条保留（常驻走 `addPinned`，不经检索） |
| 2 | `ChatClientAgentPort` | `cappedText` 三路判定：`finishReason` → `METADATA_PARTIAL_TOOL_RESPONSES` → 原文特征；命中哪路进 debug 日志 |
| 3 | `ChatPipelineTest` | 新增一条：模型照抄框架那句超限原文时也要换掉（24 条全绿） |
| 4 | `ApplicationAssemblyTest` | 常驻断言按新名单改（5 个），`doesNotContain` 只剩 Bash / WebFetch（25 条全绿） |
| 5 | `docs/adr/0043-control-tools-stay-pinned.md` | 裁决与证据 |
| 6 | `README-capability-tests.md` | B7 拆成"链路通不通 / 模型选不选"两半；B8 去掉"要先检索"的过时说明 |

**票 32 留下的一处 P0，顺手修了**：`PrincipalMemoryAdvisor.withIndex` 用
`augmentSystemMessage(String)` 拼索引，而 Spring AI 的 `Prompt:252` 是
`systemMessage.mutate().text(newSystemText)`——**整段替换，不是追加**。于是开了记忆的每一轮，
人设和那段记忆说明都被覆盖掉，模型拿到的是一个只剩 `MEMORY.md` 的系统提示。
`SessionMemoryTest#memoryToolsComeFromTheLibrary` 一直红着（上一轮我只跑了三个测试类，没看见）。
现在改成把现有正文拼回来，测试 23/23 绿；实测探针（用户 `p1` 问"人设怎么写的"）能答出运维助手那一段。

### 实测

| 验什么 | 结果 |
| --- | --- |
| B8 `TodoWrite` | ✅ **3 次**（Read 2，答案对：第一行 `---`、文件职责对）—— 票 32 之后第一次真绿 |
| B7 链路（点名探针 `q34`） | ✅ 问题登记（两个选项、推荐预发）→ `answer` **204** → 那一轮回「你选了**预发**」 |
| B7 不点名（`q33`） | ⚠️ 工具在清单里（无 pinned 告警）、但它**自己在正文写两个选项**，没调工具。pin 解决的是"拿得到"，不是"愿不愿意用" |
| 撞预算 `cap33` | ✅ 用户收到「这一步做了太多次…」；日志 `撞上工具调用上限（判定依据 message）` |
| 人设 | ✅ 探针答出运维助手人设 |

**`判定依据 message` 这条要记**：只有第三路（文本兜底）命中，说明框架那代超限回答的元数据
**真的没回到 `ChatClientAgentPort`**（这就是之前用户拿到英文原文的根因）。前两路留着是因为它们是
"正路"，但当前链路上不起作用；哪天日志里变成 `finishReason`，说明元数据回来了，第三路可以删。

### BFCL（全量两档，`--no-baseline`）

| 配置 | simple_python | irrelevance |
| --- | --- | --- |
| 裸跑（不挂提示词） | 95.00% | 75.83% |
| 当前五句（含票 32 的"先去找一遍"） | 94.75% | **77.08%** |
| 票 31 当时的"新五句"（第三句是"直接说明做不到"） | — | **84.58% / 84.17%** |

裸跑从 77.08 → 75.83 说明模型这几天有约 1.2 点的漂移；即便如此，**84.58 → 77.08 这 7.5 点
是"先去找一遍"那句话带来的**，方向也和预期一致：它鼓励"再找一次"，而 `irrelevance` 考的是
"不该动手时别动手"。

而它**想换来的行为并没有换来**：B2-4 仍是红的（模型不检索就直接说做不到），B7 也需要靠常驻才拿得到工具。
所以这句话现在是**净损失**——建议第三句回退到票 31 的措辞（"这里的工具做不了这件事，就直接说明做不到，
不要拿相近的工具硬做。"）。**没有改**，等裁决：这是产品可见的提示词，不是我一个人能定的。

### 还没做

- B2-4（抓网页）仍红：模型看见用户给了 URL，仍然先下"我没有抓取工具"的结论。跟 B7 是同一类
  （工具在清单里它也不伸手），属模型行为；`WebFetch` 仍然是"检索才给"，没有常驻。
- C/D/E/F/G/H/I/J 各节没动（要换实例参数）。

## 追加：第三句回退（2026-11-27 22:18，用户裁决）

`SYSTEM_SUFFIX` 第三句改回票 31 的措辞：

```
**这里的工具做不了这件事，就直接说明做不到，不要拿相近的工具硬做。**
```

BFCL 全量两档（`--with-prompt --no-baseline`，导出后的 `system-prompt.txt` 447 字节）：

| 第三句 | simple_python | irrelevance |
| --- | --- | --- |
| 裸跑（对照） | 95.00% | 75.83% |
| "先去找一遍；确实没有，就直说做不到" | 94.75% | 77.08% |
| **"直接说明做不到"（已回退）** | **93.50%** | **84.17%** |

- `irrelevance` 回到 84.17%，与票 31 记的 84.58 / 84.17 一致——**这是一次复现，不是新成绩**。
- `simple_python` 93.50% 对裸跑的 95.00%（400 题里 6 道），与票 31 记的 93.75% 同一量级；
  两版提示词之间 93.50 vs 94.75 差 5 道题，在这档的噪声范围内，不据此下结论。
- 两项都低于绝对门（95% / 90%）：`irrelevance` 仍是主要缺口，记在这里，不在本票处理。

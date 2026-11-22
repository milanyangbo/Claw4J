# 03 · 提示词预算：让每轮塞进去的东西有个上限

**Status:** resolved
**Blocked by:** 无。02 的隐藏块会用到这里的预算器，但可以先落地这一张。

## 对照表

| | OpenClaw | 我们现在 | 差在哪 | 改哪边 |
| --- | --- | --- | --- | --- |
| 有没有上限 | 有。bootstrap 单文件 20000 字符、合计 60000；技能段 `maxSkillsPromptChars`；上下文按 `maxBytes` 截断 | 没有任何上限（`PromptComposition` 拼多长算多长） | 超了不报错，只让模型"忘事" | 我们这边补 |
| 超了怎么办 | 截断并**注入一句固定的提示**（内置、不可配，且故意不说哪个文件被截） | 无 | 无 | 照抄这个行为 |
| 稳定/易变 | 缓存边界之上放稳定段；日期时区走 turn context，日切不重写历史 | 日期**拼在 system 里**，粒度到天 | 日切一次前缀全换，缓存失效 | 时间移出 system |
| 可观测 | `/context`、`/status` 能看到每段占多少 | 无 | 改了不知道动了多少 | 打一条结构化日志 + 指标 |

## 做法

1. `prompt/PromptBudget`：三个上限（`systemMaxChars` / `blockMaxChars` / `totalMaxChars`），
   默认 20000 / 8000 / 60000（对齐它的 bootstrap 数与"合计"）。配置可覆盖。
2. `prompt/BudgetedText`：截断 + **一句内置提示**（"部分内容被截断，请直接读取原文件"），
   不说截了哪一段。
3. `PromptComposition.compose` 拆成 `PromptParts(stable, variable)`：时间从 `stable` 移到
   `variable`，`variable` 走每轮附言而不是 system。core 零框架，只能靠返回形状表达。
4. 每轮打一条日志：`claw4j.prompt.chars{segment}`，并在超限时 WARN 一次（同会话只报一次）。

## 验收（先跑红）

- 同一天两次请求，`stable` 段的字节**完全相同**；跨天也只有 `variable` 变。
- 塞一个 25000 字符的人设 → `stable` 被截到上限，且末尾带那句固定提示；
  日志里有 `truncated=true` 与原始长度。
- 合计超限时优先截 `variable`，`stable` 的最后一段被保到上限为止。
- 现有 A–F 的验收用例一条都不许因为改拼接而变。

## 不做什么

- 不做 projection 切换（`thread_bootstrap` + epoch 指纹）：那是为持久化 native thread
  准备的，我们的会话历史在库里，没有那个后端。
- 不做"自动砍技能描述再砍条目"：技能元数据现在在**工具描述**里，不在提示词里；
  等它进提示词（另一件事）再做分级截断。

## TDD 用例清单

裁决：`无需 ADR：`本票只加字节上限、截断提示与一条日志，**不改提示词的分段结构**；
stable/variable 拆分与投影切换属于另一件事，另开票另裁决。

| 判什么行为 | 用例 | 先红的样子 |
| --- | --- | --- |
| 稳定段跨天逐字节不变 | ✅ `stableNeverChangesAcrossDays` | 日期拼在整段里，跨天缓存键全换 |
| 跨天只有每轮段变 | ✅ `rolloverOnlyTouchesVariable` | 稳定段里出现日期 |
| 超限截断并带固定提示 | ✅ `overBudgetStableIsTruncatedWithNotice` | 原样放行，或截了但不提示 |
| 合计超限先截每轮段 | ✅ `totalBudgetTrimsVariableFirst` | 先截稳定段，把人设砍掉 |
| 超限只 WARN 一次 | ⏳ 实现里是一次性 AtomicBoolean，未单独立用例 | 每轮刷一条 WARN |

复核：2026-11-22 `./mvnw -o -pl claw4j-core -am test -Dtest=PromptCompositionTest`（13 条）全绿。

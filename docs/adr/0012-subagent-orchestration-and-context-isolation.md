# Subagent 编排：独立上下文窗口，只回传结论，委派不提权

---
status: accepted
date: 2026-10-24
---

委派（把一个多步骤任务交给专职 Subagent）实现为**父 Agent 的一个工具**（`Task`），
执行时新开一个独立上下文窗口：**子看不见父的对话，父只拿到一个结论字符串**。
子能碰哪些工具由它定义文件里的白名单决定；子那一侧照样过鉴权、照样吃 3/6 调用预算，
用的是**父那个身份**。

## 为什么不是"再配一个 ChatClient"

ADR-0008 把 Subagent 编排列为四项做深之二，做深的点是**上下文隔离**——
"起了第二个 Agent"只要 `new ChatClient` 就有，它没有断言可写，
也回答不了"长任务为什么要把中间过程挡在外面"。隔离落在三处，每处都有测试指着：

| 隔离 | 做法 | 盯它的测试 |
| --- | --- | --- |
| 子看不见父的对话 | 每次委派从头建一个 `ChatClient`，只把 `task` 那一段喂进去 | 有测试守着：只回传结论 |
| 父拿不到子的中间轨迹 | 回传的是 `content()` 一个字符串，子的工具调用与工具结果留在子窗口 | 同上 + 端到端断言：回传给父的只有结论 |
| 子的系统提示词不回流 | 正文只在子窗口里出现一次；父那边的工具描述里只有名字与描述 | 同上（正向对照：正文在子侧出现过） |

第三条顺带让"委派"也吃到了 Skill 那套渐进式披露：`Task` 工具的描述里是每个
Subagent 的 name + description，十份流程说明不会常驻在父的提示词里。

## Considered Options

**用社区包现成的 `TaskTool` + `ClaudeSubagentExecutor`**
否决。它确实给了"读 markdown front matter、按 `tools` / `disallowedTools` 过滤工具、
`call().content()` 只回结论"这套形状（本项目的定义文件格式就是照它来的），
但它的 `TaskFunction` 是 `Function<TaskCall, String>`：`FunctionToolCallback` 拿不到
`ToolContext`，**委派时拿不到 Principal**，于是子链上那道鉴权挂不上去——
"父越不过的界，派个专职 Agent 就过去了"正是这条路。
而且它的 `ChatClient.Builder` 由外部传入，用 Spring 注入那份就等于把调用预算退回 40/150
（ADR-0008 那条"换了 builder 会静默退回默认值"的坑）。
只借它的 `MarkdownParser` 与 front matter 字段名，执行器自己写。

**子链上挂整条父链（含记忆与脱敏）**
否决。记忆 Advisor 会把子的中间过程写进会话——刚从正门隔离掉的东西从侧门漏回去，
而且落库的是"子问了什么"这种没人会去看的内容。脱敏在父链出站前还会做一次，
子在中间打码只会让它认不出自己查到的值（模型要靠原始值判断是哪一单）。
子链只挂 `AuthorizationAdvisor`。

**把身份放进 ThreadLocal 传下去**
否决。`ChatClientAgentPort` 那条注释已经记过一次：ThreadLocal 在"流式 + 换线程"下会掉，
而掉身份的失败姿势是**放行**。这里改成 `ChatClientAgentPort` 把 Principal 放进
prompt 的 `toolContext`——委派发生在工具内部，那里拿不到 Advisor 上下文，只能从
`ToolContext` 取；取不到就拒绝这次委派。

**子 Agent 用便宜模型**
否决（本轮）。同一个 `ChatModel` 沿用父实例：换模型要再引一套 key 与装配，
而"委派省成本"这件事在演示量级上没有意义。定义文件里写 `model` 会被忽略
（front matter 只取认识的几个键），真要分模型是加一张表的事。

## 委派不提权

子链上挂的就是父那个 `Principal` 对象本身（`PrincipalCapturingAdvisor` 断言
`isSameAs`）。于是 guest（只看得到 `region`）派出去的 Subagent 去查 `sales_order` 时，
被挡在**工具执行之前**——断言同时盯着"工具一次都没被调用"，
因为"先跑完再过滤结果"的那一刻越权已经发生了。

`Task` 这个工具自己登记的权限是 `delegate_task` 动作、**零张业务表**。
零张表不是"没有约束"，而是声明"委派这个动作本身不碰库"：
真正碰库的是子要调的那个工具，那一步按它自己的登记表鉴权。
这里替它登记一张表，"委派"就变成一张能绕过子链鉴权的通行证。
所以 `read-only` 与 `guest` 也都给了 `delegate_task`——给不给不改变它们能看到什么。

## 调用预算

子那一侧用与父同一个构造入口：`ToolCallingAdvisor.builder().toolCallingManager(
ToolCallBudget.toolCallingManager(observations))`，3 / 6 照旧。
委派不能变成绕过预算的口子，否则一个坏掉的脚本在子里循环时，
父这一侧的预算看起来还一点没动。

## 边界

- **不嵌套**：定义白名单里写 `Task` 按定义错误失败；"继承全部工具"那条路上也把
  `Task` 过滤掉。嵌套委派会让上下文隔离与预算变成一串说不清的递归。
- **不并行**（M3）：一次委派一个子 Agent。
- **不跨进程**（A2A，M3）：子 Agent 与父在同一个 JVM 里。
- **白名单点名了却没装上的工具**按失败处理，失败发生在**首次委派**而不是启动时：
  装配期展开 `ObjectProvider<ToolCallback>` 会把正在创建的 `Task` 自己卷成循环依赖，
  所以工具集用 `Supplier` 惰性取。代价是这条配置错误晚一步暴露，
  失败姿势仍然是拒绝并点名缺哪个工具。

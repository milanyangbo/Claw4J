# 03: Task 换成社区包的 TaskTool + TaskOutputTool

**What to build:** 待定。用户 2026-11-16 选的是「换成社区包的 TaskTool + TaskOutputTool」，
但核过 jar 之后发现那条路上有一处会直接让子链鉴权失效。先记事实，形态由用户裁决。

**Blocked by:** 身份如何过到子链（下面三选一）。

**Type:** 替换现有工具实现（会动 `Task` 工具、子 Agent 执行链、可能的 pom）

**Status:** open

## 现场一：用户列的那个类不存在

`spring-ai-agent-utils:0.13.0` 里没有 `TaskToolCallbackProvider`。有的是：

| 类 | 形态 |
| --- | --- |
| `tools.task.TaskTool` | `builder(…).build()` → `ToolCallback`（工具名 `Task`） |
| `tools.task.TaskOutputTool` | `builder(…).build()` → `ToolCallback`（取后台任务结果） |
| `common.task.subagent.{SubagentType,SubagentResolver,SubagentExecutor,TaskCall}` | 接缝 |

现有 `SubagentDelegation.TOOL_NAME == "Task"`，所以换过去不是"多一个工具"，是**换掉同一个名字**。

## 现场二（关键）：`SubagentExecutor` 拿不到身份

```java
public interface SubagentExecutor {
    String getKind();
    String execute(TaskCall taskCall, SubagentDefinition subagent);   // 没有 ToolContext
}
```

`TaskTool.build()` 用的是 `FunctionToolCallback.builder("Task", TaskFunction)…`
（`TaskTool.java:280-286`），`TaskFunction` 只收 `TaskCall`。Spring AI 把身份放在
`ToolContext` 里（`ChatClientAgentPort` 写入、`SubagentDelegation.call(toolInput, toolContext)`
读出），而这条路**中途把 ToolContext 丢了**。

后果不是"少一个字段"：`SubagentRunner` 现在靠 `Principal` 过子链的 `AuthorizationAdvisor`。
拿不到身份，子 Agent 那一侧要么拒绝一切，要么被迫放行——后者等于把「子能碰哪些表、哪些工具」
整条边界作废。

`run_in_background=true` 时更糟：`DefaultTaskRepository.putTask(...)` 把任务交给别的线程，
连 `ThreadLocal` 抓一把这种妥协也一起丢。

## 三个形态

| 形态 | 做法 | 代价 |
| --- | --- | --- |
| A（推荐） | 保留自研 `Task`。要后台执行就自己加一个 `TaskOutput`，走同一条 `collectToolCallbacks` | 拿不到库里的 `resume`（按 agent ID 续跑）、`model` 覆盖、Claude 那四个内置子 Agent 定义 |
| B | 换 `TaskTool`，`SubagentExecutor` 由我们实现，身份靠包一层 `ToolCallback` 在 `call()` 期间塞 `ThreadLocal` | 同步路径成立；**后台路径必丢身份**，除非另做一套"在 TaskCall 里带 token 再换回 Principal"的机制 |
| C | 换，并且接受子链不带身份（子 Agent 用部署级身份跑） | 与 ADR-0007 相反，不建议 |

## 缺口

- 换成库的 `Task` 之后，工具参数从 `{subagent, task}` 变成
  `{description, prompt, subagent_type, model, resume, run_in_background}`：
  `examples/subagents/*.md` 的 `tools` / `disallowedTools` 里点名的 `Task` 不受影响，
  但**提示词与 Skill 正文里写的调用方式会全部过期**，要一并改。
- 库里 `TaskTool` 的描述模板把四个 Claude 内置子 Agent 写死在文本里（只在 kind 是
  `CLAUDE` 时注入），换过来之后模型会看到两套"可用 agent"说法，需要重写 `taskDescriptionTemplate`。

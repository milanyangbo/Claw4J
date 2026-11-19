# 03: Task 换成社区包的 TaskTool + TaskOutputTool

**What to build:** `Task` 与 `TaskOutput` 换成社区包的 `TaskTool` + `TaskOutputTool`，
`SubagentExecutor` 由我们实现（库的扩展点），身份靠包一层 `ToolCallback` 在 `call()` 期间放好。

**Blocked by:** 无。用户 2026-11-19 裁决：**"用官方标准库"是本项目的要求**，按形态 B 做，
并且 B 列的那条代价（后台丢身份）要一并解决。

**Type:** 替换现有工具实现（会动 `Task` 工具、子 Agent 执行链、可能的 pom）

**Status:** resolved（2026-11-19，形态 B + 身份随任务搬进后台线程）

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

## TDD 用例清单

| 判什么行为 | 用例名 | 先红的样子 | 过没过 |
| --- | --- | --- | --- |
| 工具形状来自库：参数是 `TaskCall` 那一组（`subagent_type` / `prompt` / `run_in_background`），我们那套 `subagent` / `task` 不再有 | `SubagentDelegationTest#toolShapeComesFromTheLibrary` | 现在 schema 是 `{subagent, task}` | ✅ |
| 后台派出立刻回 `task_id`，`TaskOutput` 之后拿得到结论 | `SubagentDelegationTest#backgroundTaskKeepsTheParentIdentity` | 现在没有后台 | ✅ |
| 后台线程里也带着父的身份（`ThreadLocal` 在那里是空的） | 同上（丢了就回"没有权限"，这条红） | 形态 B 原样落地就是这条红 | ✅ |
| 派给不存在的 `subagent_type`：报错点名那个名字 | `SubagentDelegationTest#malformedOrUnknownDelegationIsRejectedWithGuidance` | —— | ✅ |
| 没写 `prompt`：拒收而不是派一个空任务 | 同上 | 库的 `TaskCall` 每个字段都可空 | ✅ |
| 没带身份的委派一律拒绝，子 Agent 一次都不启动 | `SubagentDelegationTest#delegationWithoutPrincipalIsDenied` | —— | ✅ |
| 成功委派只回传结论，且包成数据块 | `SubagentDelegationTest#delegationReturnsOnlyTheConclusion` | 库回来的是 JSON 字符串，`<prompt-data>` 会丢 | ✅ |
| 定义目录空 / 定义写坏 / 白名单点名不存在的工具：语义不变 | `SubagentDelegationTest#configuredDirectoryWithoutDefinitionsFails` 等 | —— | ✅ |
| 子 Agent 的清单仍按父的画像过滤，且 `Task` / `TaskOutput` 都不给它 | `SubagentDelegationTest#subagentToolsRespectTheDeploymentProfile` | 漏一个就等于给子 Agent 开了"再派一层" | ✅ |

**复核：2026-11-19 `./scripts/verify.sh` 全绿。** 上面九条逐条对到 `SubagentDelegationTest`（22 条）里。

## Answer（2026-11-19 收票）

**形态 B，并且把 B 那条代价补掉。**

- 工具：`TaskTool.builder().subagentTypes(new SubagentType(resolver, executor)).subagentReferences(...)
  .taskRepository(repo).taskDescriptionTemplate(...)`，kind 用我们自己的 `CLAW4J`
  ——库里四份内置 Claude 定义因此不会被塞进来。
- 定义：`SubagentDefinition` 实现库的 `SubagentDefinition` 接口；`SubagentReferenceResolver`
  按 kind 认领、读 md。**文件格式不变**，`examples/subagents/*.md` 不用改。
- 执行：`SubagentRunner implements SubagentExecutor`，里面还是原来那条"独立窗口 + 预算 + 父的清单"。
- 身份：`SubagentDelegation` 从"自己解析参数"缩成一层装饰器，在一次 `call` 的进出之间把
  `ToolContext` 里的身份放进 `ThreadLocal`。作用域是一次调用，不跨流式分段。
- **B 的那条代价**：后台任务在别的线程上跑，`ThreadLocal` 必丢。`SubagentTaskRepository`
  在 `putTask` 那一刻把身份取出来，进后台线程前再装回去——身份是**提交时**的，不是执行时线程碰巧有的。
- 结论文本：库的 `FunctionToolCallback` 会把返回值 JSON 化，所以装饰器把那段引号还原回纯文本；
  数据块的包装（`PromptData`）搬进执行器，形状与换库之前一致。

## 缺口

- ~~换成库的 `Task` 之后，工具参数从 `{subagent, task}` 变成~~（**已交付**：改的是文档与提示词；
  `{description, prompt, subagent_type, model, resume, run_in_background}`：
  `examples/subagents/*.md` 的 `tools` / `disallowedTools` 里点名的 `Task` 不受影响，
  提示词与 Skill 正文里写的调用方式随后一起改了，见 `README-adding-capabilities.md` §5 与
  `docs/capability-tests.md` §6。）
- **`resume` / `model` 两个参数现在是"接了但不生效"**：库的 schema 里有，我们的执行器不用它们
  （续跑要把子窗口历史存下来，换模型要另一套 key 与配额）。它们不会报错，只是照常从头跑——
  这与本仓库"配了不生效比不支持更坏"的口径冲突，等真要做续跑时一起处理。
- 库里 `TaskTool` 的描述模板是给 Claude 那套写的（讲 resume、讲并行、举例 code-reviewer）。
  已换成我们自己的模板，只借它的 `%s` 位置放注册表。

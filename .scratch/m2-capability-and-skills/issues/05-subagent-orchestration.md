# 05: Subagent 编排

**What to build:** 让长任务能被委派出去：Orchestrator 派一个专职 Subagent 在**独立上下文窗口**里跑，它只把结论回传父 Agent，中间过程不回传（GLOSSARY 的 Subagent 定义）。

**Blocked by:** 01, 04

**Status:** closed

**Demo:** 一个需要好几步的问题被委派给 Subagent：父 Agent 拿到的只有结论，它的上下文里看不到子 Agent 的工具调用轨迹；同时演示子 Agent 用不了它定义白名单之外的工具。

- [x] 委派：父 Agent 把任务交给 Subagent，只回传结论
- [x] 上下文隔离：子 Agent 的中间轨迹（工具调用过程）不进父上下文，有一条断言指着
- [x] 子 Agent 可用工具由定义里的白名单决定（`tools` / `disallowedTools`）—— 委派不等于把全部能力交出去
- [x] 端到端（`AgentPort`）：一次提问经过委派后仍拿到正确结论；流式路径同样成立
- [x] 子 Agent 也受调用预算约束（3 / 6）—— 委派不能变成绕过预算的口子
- [x] 变异检验：把中间轨迹一起回传 → 隔离那条断言红

## 落点

- 主代码 `claw4j-agent` 的 `io.github.claw4j.agent.subagent`：`SubagentDefinition`（定义解析 + 白/黑名单挑选，
  点名了没装上的工具或挑完为空都失败）、`SubagentCatalog`（目录加载，不存在或空目录即失败）、
  `SubagentRunner`（独立窗口 + 3/6 预算 + 只挂鉴权 Advisor + 只回 `content()`）、
  `SubagentDelegation`（`Task` 工具回调，描述里只有元数据）、`SubagentAutoConfiguration`
  （配 `claw4j.subagents.directory` 才启用，登记 `delegate_task` 零表权限）。
- `ChatClientAgentPort` 把 Principal 也放进 prompt 的 `toolContext`：委派发生在工具内部，
  那里拿不到 Advisor 上下文；取不到身份就拒绝这次委派（ADR-0012）。
- 委派不提权：子链上是**父那个 `Principal` 对象本身**（`PrincipalCapturingAdvisor` 断言 `isSameAs`），
  guest 派出去查 `sales_order` 时被挡在**执行之前**（断言工具一次都没被调用）。
- 委派不嵌套：白名单里写 `Task` 按定义错误失败，"继承全部"那条路上也把它过滤掉。
- 工具集用 `Supplier` 惰性取：装配期展开 `ObjectProvider<ToolCallback>` 会把正在创建的 `Task` 自己
  卷成循环依赖。代价是"白名单点名了没装上的工具"在首次委派时才失败，姿势仍是拒绝并点名。
- 收集子那一侧的工具时**复用了 `AgentAutoConfiguration.collectToolCallbacks`**：
  第一版只收了单回调形态，`querySales`（来自 provider）在子那边就成了"没有这个工具"——
  01 号票那个坑的第三次出现，收工具只该有一处实现。
- 测试 33 条（组件级 25 + 真模型端到端 4 + 装配 4）；变异六条全红：中间轨迹一起回传
  （组件级与端到端各红一条）、去掉白名单过滤、子链不吃预算、不传身份、
  无身份照常委派、描述里塞进正文、空目录不失败（部分合并跑）。

**注意:** ADR-0008 把 Subagent 编排列为四项做深之二。做深的点是**上下文隔离**，不是"起了第二个 Agent"——后者只要配个新 ChatClient 就有，前者才有断言可写。

**不要做:** 跨进程 Subagent（A2A，M3）；并行多个 Subagent。

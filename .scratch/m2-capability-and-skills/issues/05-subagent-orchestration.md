# 05: Subagent 编排

**What to build:** 让长任务能被委派出去：Orchestrator 派一个专职 Subagent 在**独立上下文窗口**里跑，它只把结论回传父 Agent，中间过程不回传（GLOSSARY 的 Subagent 定义）。

**Blocked by:** 01, 04

**Status:** ready-for-agent

**Demo:** 一个需要好几步的问题被委派给 Subagent：父 Agent 拿到的只有结论，它的上下文里看不到子 Agent 的工具调用轨迹；同时演示子 Agent 用不了它定义白名单之外的工具。

- [ ] 委派：父 Agent 把任务交给 Subagent，只回传结论
- [ ] 上下文隔离：子 Agent 的中间轨迹（工具调用过程）不进父上下文，有一条断言指着
- [ ] 子 Agent 可用工具由定义里的白名单决定（`tools` / `disallowedTools`）—— 委派不等于把全部能力交出去
- [ ] 端到端（`AgentPort`）：一次提问经过委派后仍拿到正确结论；流式路径同样成立
- [ ] 子 Agent 也受调用预算约束（3 / 6）—— 委派不能变成绕过预算的口子
- [ ] 变异检验：把中间轨迹一起回传 → 隔离那条断言红

**注意:** ADR-0008 把 Subagent 编排列为四项做深之二。做深的点是**上下文隔离**，不是"起了第二个 Agent"——后者只要配个新 ChatClient 就有，前者才有断言可写。

**不要做:** 跨进程 Subagent（A2A，M3）；并行多个 Subagent。

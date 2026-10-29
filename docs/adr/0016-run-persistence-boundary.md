# Run 的持久化边界：step 落库，工具循环不落库

---
status: accepted
date: 2026-10-29
---

一次提问不再等于"一轮回答"，而是一个可以暂停、可以续跑、可以查的 **run**。
run 与 step 落库，**工具循环内部的检索与自纠错不落库**。

> 实现状态：本条是裁决，尚未落代码，由 A01 / A02 那两张票实现。

## Context

现在的问题是可以用三行代码说完的：

- `ChatClientAgentPort.reply()` 执行一次 `prompt().call()`，`stream()` 是一条 `.blockLast()`
  的阻塞订阅。两者都到模型答完为止，**没有任何地方能表达"这件事还没做完"**。
- 工具循环在 `ToolSearchToolCallingAdvisor` 里跑，上限写在 `ToolCallBudget` 里：
  每工具 3 次、整轮 6 次，是**代码里的常量**，不是按任务算的额度。
- 于是"把 148 张表逐个看一遍"这种任务只有两种结局：塞进一轮被预算截断，
  或者靠用户再问一句"继续"——而"继续"在系统里是**一段新对话**（会话 id 由 principal 派生，
  见 `SessionKeys.sessionIdOfOwner`），不是同一件事的下一步。

后遗症是连锁的，而且都不在这两行代码里看得出来：

1. **审批只能在这一轮内等着**。工具调用是同步的，等人批准要占住一条线程与整条对话。
2. **定时任务要另写一条"跑 Agent"的路径**，预算、审计、终止各做一遍。
3. **记账与评测没有载体**。301 条用例证明装配成立，没有一条能回答"这一件事花了多少"。

已经存在的去重只有 `InboundMessage.eventId`，它管的是通道重推同一个事件，管不到"模型自己决定再试一次"。

## Considered Options

**不建状态，把计划与预算都塞进会话消息**
否决。两处会把它吃掉：`SessionCompactor` 会把超阈值的事件压成一段摘要，计划正是最先被概括掉的那类内容；
而且塞在消息里等于没有查询面，"这件事到哪一步了"要翻对话才能回答。

**每一次工具调用落一行（事件溯源）**
否决。三个理由，任一都足够：

- 与会话表重复。会话已经有完整消息流，再存一份模型所见的参数，等于多一处永久副本；
  而第三方 MCP 的工具参数里可能有真实凭据与个人信息，多一处副本就多一处泄露面。
- 恢复要重放整个 prompt。推理一旦依赖"当时的消息序列"，框架或模型版本一变就不可回放。
- 75% 的那些行（检索、命中的候选项、失败的自纠错）在任何场景下都没人读。

**run / step 两级：step = 一次工具循环（采纳）**
step 是"模型的一次完整动作"的粒度，而不是"一次工具调用"的粒度。
这个粒度和我们从 ADR-0010 继承的东西是同构的：工具循环只有一种，行为不随工具数量变化，
所以把状态记在它的**外面**，而不是把它拆开记在里面。

**顺带否决：把 run 绑在 chatId 上**
`InboundMessage.chatId` 的定义就是"通道侧对这条会话的标识"。同一件事完全可以 HTTP 发起、
飞书批准、企微收结果——绑了 chatId 就跨不过去。见 ADR-0018。

## Decision

两张表，从 V6 起排在 `claw4j-agent` 模块（它已经带着 V3~V5）：

```
agent_run    id / owner / principal_digest / goal / status / plan_ref / budget_used / created_at / updated_at
agent_step   id / run_id / seq / input / tool_names / result_summary / artifact_ref / status / created_at
```

- **工具循环不落库**。一次 step 内部的"检索 → 调用 → 自纠错"在内存里跑完。
  `ToolCallBudget` 的 3/6 语义从"一轮"变成"一个 step"，run 之上另有步数上限（A04 那张票的事）。
- **`owner` 沿用会话 id**（`SessionKeys.sessionIdOfOwner(principal)`），不改会话表。
  一个人可以同时有多件事：一条 web 对话 + 一个每周定时任务，这是 session 与 run 的**一对多**。
- **run 绑 principal，不绑通道**。投递目标单独一张 `agent_run_delivery(run_id, channel, channel_ref)`，
  一对多，理由与形状写在 ADR-0018。
- **状态机**：`running ⇄ suspended`，二者后可进 `done / failed / budget_exhausted`。
  只有 `suspended` 能被**外部事件**唤醒：审批通过、定时到点、用户说的下一句。
  `budget_exhausted` 是终态，允许追加预算后重进 `running`（A04）。
- step 表里**不存工具参数原文**，只存 `tool_names` 与摘要；参数的哈希用途由 ADR-0017 定义。

## Consequences

代价（写下来，免得以后变成"意外"）：

- `OutboundMessage` 要加 `runId`，否则调用方不知道该去查哪个 run；这是一次**改 domain 的破坏性变更**，
  所有 `AgentPort` 的实现与调用方都要动——这也是整条链路里唯一一次碰别人接口的地方。
- 每次入口都要先回答"是起一个新 run，还是续一个既有的"，分支一旦写错，表现是"答非所问且 history 丢一半"。
- 多两张表，多一点写放大：每个 step 一次 insert + 一次 update。

收益：审批、定时、审计、评测、记账从此都有同一个挂载点；进程重启后能接着跑。

明确不做：不做事件溯源回放（见备选 2）；不在 step 里落工具参数原文；
不做 run 的历史版本——目标改了就新建一个 run，不给旧 run 打补丁（否则 step 序列会失去因果）。

与另外两条的界线：本条定"表里有什么"；**幂等**归 ADR-0017，**结果怎么回到人**归 ADR-0018。

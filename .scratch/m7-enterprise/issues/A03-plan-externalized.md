# A03: 计划外置——模型记得"做到第几步"

**What to build:** 计划写进 run 的 step（带状态：待做 / 已做 / 跳过，带序号），不放在上下文里。
会话被压缩之后，模型仍能从库里读到"还剩什么没做"。今天 `recall_session` 召得回原文，
召不回进度——差别就在这张表。

**Blocked by:** A02

**Status:** resolved

- [x] 会话压缩超过阈值后，仍能答出未完成项
- [x] 把第 2 步标 done 再 resume，模型不重做第 2 步
- [x] 计划 step 带状态与序号，可被 A08（长 run 上下文）复用为结构化前缀

## Comments

- 2026-10-31 收票。**写计划的不是框架，是模型**：为此给模型一个进程内工具 `update_plan`，
  与 `recall_session` 同一个形状（`ToolCallback` + 一条权限登记）。
  框架自己猜不出计划——它只看到模型说了什么，而计划是模型对"打算怎么干"的声明。
- **工具不接 `run_id` 参数**：runId 由入口（`ChatClientAgentPort.promptOf`）写进
  `ToolContext`（`ContextKeys.RUN_ID`）。让模型传 id 等于给它一张"往别人那件事上写计划"的
  通行证，而挡住它的代价是每次调用多一次往返查 owner。
- **状态约束按 kind 分支**（V7，新增迁移而不是改 V6）：`kind = 'plan'` 允许
  pending / done / skipped，其余 kind 仍只允许 done / failed。
  不回头改 V6 是因为它已经在跑过的环境里应用过了，改内容要 clean 才能过校验和。
- **"第 N 步"是计划内序号，不是 `seq`**：计划被重写过之后它的 seq 从当时那一步接着往下走，
  而模型手里的"第 2 步"指清单里的第二个。`agent_run.plan_ref`（V6 留的那一列）
  现在记的是**当前这版计划的起始 seq**，读计划只取它之后的那些行。
- **重写计划不删旧的**：旧的那版不再被 `planOf` 读到，但行还在库里——
  审计要能回答"当时打算怎么干"（P07 的投影读的就是这张表）。
  这也是为什么不把旧版标成"跳过"：那会把"被改主意淘汰"和"决定不做"混成一个状态。
- **recap 里计划自成一栏**（在"已发生 N 步"之前）：`计划（共 3 步，还有 2 步没做）`
  加逐行 `第 N 步 [已做|待做|跳过] …`。缺了它的表现不是答不上来，
  而是把第 2 步重做一遍——看起来跟"接着做"一模一样。
- 工具回执是给人（模型）读的文本，末尾一定写"还剩 N 步没做：第 X、Y 步"。
  只回 JSON 让模型自己去数，等于把"还剩什么"又交回给自然语言。
- 用例：SQL 缝 5 条进 `RunSchemaSqlTest`（约束按 kind 分支 / 计划内序号 ≠ seq /
  重写后旧版不被读到但仍在库里 / 工具写读闭环 / 没有 run 时拒绝）；
  主链路 1 条进 `ChatPipelineTest`（resume 的提示词里带着计划与未完成项）。
  全量 326 绿、跳过 0。
- 装配：`update_plan` 跟着 run 一起装配（`RunPersistenceConfiguration`），
  `claw4j.run.enabled=false` 时它从工具清单里消失——模型面前少一个工具，
  比"调了才发现写不进去"好。动作名 `update_plan` 已加进 `read-only` 的 actions
  （`guest` 不给）。工具总数 20 → 21，README 与 `docs/capability-tests.md` 已同步。
- 留给 A08 的接口：`RunStore.planOf(runId)` 返回的就是"带状态与序号的计划"，
  可以直接当结构化前缀用，不需要再从自然语言的 step 里解析结构。
- 没做的（有票才做）：计划本身没有预算上限（A04）；跨 run 的计划模板没有（无票）。

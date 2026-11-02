# A02: resume(runId)——"继续"是同一件事的下一步

**What to build:** 一件一轮做不完的事，中间断了回来再说一句"继续"，它接着做而不是从头重来。
被恢复的执行不新建 run：同一个 runId、序号接着涨。三个入口（用户再说一句、HTTP 的 resume
入口、审批通过后的服务端续跑）走同一个方法——这是 P02 依赖它的原因。

**Blocked by:** A01

**Status:** resolved

- [ ] 断点后说"继续"，落的是同一个 runId、seq 连续，回答内容是"下一步"而不是从头再来
- [ ] HTTP resume 入口可用，三个入口共用同一个方法
- [ ] 真模型闭环用例："断点 → resume → seq 连续"（mock 只证明装配）
- [ ] 指标与库行回读判定，不问模型措辞

## Comments

- 2026-10-30 收票。**resume 的唯一形状是 `InboundMessage.runId`**：三个入口
  （HTTP 带 runId 再说一句 / 流式同理 / P02 审批通过后的服务端续跑）都只是"构造一条带
  runId 的 InboundMessage"，主链路里没有第二个分支——P02 到点直接复用，不用改这里。
- 落点：
  - `InboundMessage` 加第 6 个分量 `runId`（保留 5 参 / 3 参便捷构造，调用点不用改）。
  - `RunStore.resume(runId, owner)`：`JdbcRunStore` 里一条 where 同时挡三件事
    （不存在 / `owner` 不等 / `status = 'suspended'`）——**等审批的 run 只由审批放行**，
    一句"继续"不能解锁，这是 P02 那道门的锁。指标 `claw4j.run.resumed` / `.resume_rejected`。
  - `ChatClientAgentPort.openRun(...)`：带 runId 就 resume，接不上则 **另开一个并 warn**，
    不报错（runId 是调用方的提示不是义务），也绝不接别人的 run。
  - 接上时给模型加一段 recap（目标 + 最近 5 步、每步截断 120 字）：会话记忆记的是
    "说过的话"，不是"做到第几步"；没有这段，"继续"在跨通道或压缩之后就是重做一遍。
- **不另开 `/api/runs/{id}/resume` 端点**：那要把认人再写一遍，两处认人迟早只对一处改。
  runId 作为 `/api/chat` 请求体的可选字段，同步与流式同时生效，代价为零。
- 用例：主链路 6 条并进 `ChatPipelineTest`（含真模型闭环"断点→resume→seq 1,2"）；
  SQL 缝 3 条进 `RunSchemaSqlTest`（复用 / 别人的与不存在的 / suspended 不唤醒）；
  HTTP 缝 1 条进 `HttpChatControllerTest`。全量 320 绿、跳过 0。
- 留给 P02 的接口：服务端续跑 = `new InboundMessage(principal, chatId, pendingStepInput,
  now, null, runId)`，无需新开方法。

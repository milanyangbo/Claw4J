# 出站投递：run 不绑通道，结果靠轮询与主动推送

---
status: accepted
date: 2026-10-29
---

> **被 0027 部分取代（2026-11-06）**：run 已判定为"一轮的句柄"，不落任何表。
> 本文里"持久 run / step 落库 / 按 runId 续跑 / 审计长在 step 上 / 投递登记表"的部分
> 已被 [ADR-0027](0027-run-is-a-handle-not-an-entity.md) 取代，代码里那两张表也已经删了。
> 正文保留当时的推理——它记录的是"为什么当初这么想"，不是现状。

新增 core 层的投递 SPI。**run 绑 principal，不绑通道**；投递目标单独一张表，一对多。
IM 通道主动把结果推给人，HTTP 侧由客户端轮询 `GET /api/runs/{id}`。

> 实现状态：本条是裁决，尚未落代码，由 A02 / A05 那两张票实现。

## Context

现在的回答只能回到调用者手里，形状上有三处证据：

- `OutboundMessage(chatId, text, finalChunk)`：出站消息唯一的目标就是"从哪条会话来"。
- `AgentPort.reply()` 返回一条消息，`stream()` 往 `Consumer` 里灌——两者都是**请求-响应式**。
- `ChatClientAgentPort.stream()` 是一条 `.blockLast()` 的**阻塞订阅**：
  调用方的线程一直被占着，直到模型说完为止。

于是"连接还在"这件事本来就靠不住，指望它把后续结果送回去更靠不住。而需要它的场景已经排队了：

- 审批通过之后由服务端续跑（ADR-0016 的 suspended → running），结果要给**发起审批那条消息的人**；
- 定时任务到点自己醒来，当时没有人在线等着看答案；
- 子 Agent 委派（ADR-0012）跑得久，父 Agent 已经先把一轮答完了。

更麻烦的是：**能力其实已经有了，但放错了地方**。飞书的
`FeishuReplySender.send(chatId, text)` → `FeishuOpenApiReplySender` 走的是 IM 的
`CreateMessage` 接口（`receive_id_type=chat_id`），与当前请求没有任何耦合——它天生就能推。
问题在于这个接口活在 `claw4j-channel` 里，`claw4j-core` 的 `spi` 目录下只有
`ChatChannel` 与两个异常，**没有任何"把话说给这个人"的抽象**。

所以缺的不是推的能力，是**一个与通道无关的收件地址**：这次结果要送到哪。

## Considered Options

**维持请求-响应式，异步结果只落日志**
否决。等于做了定时与审批，但用户看不到结果——比不做更糟，因为系统会"假装做完了"。

**客户端维持长连接 / SSE 重连**
不作为默认方案否决，留到 M9。理由：`stream()` 是阻塞式的，加上长连接要改线程模型；
中间的 IM 网关、负载均衡、移动端断网重连，每一环都会让"连接还在"变成假命题。
在 P0 阶段把这个复杂度压进来，会让 A01/A02 两张票失去焦点。

**HTTP 轮询 + IM 主动推送（采纳）**
结果落在 run 上。IM 有人就推过去；HTTP 侧客户端拿着 runId 来查。

**让定时/审批结果跑一条"第二次问答"的路**
否决（这是最容易想到的偷懒写法）：它会重新走一遍工具循环、重新鉴权、
重新压缩一次会话，出来的还不是原来那件事的续集——而且会绕开 ADR-0017 的幂等键，
因为 run_id 不同。正确做法是照 ADR-0016 的 `resume(runId)` 走同一条路径。

## Decision

- 在 `claw4j-core` 的 `spi` 下新增 **`OutboundDelivery`**，与 `ChatChannel` 平级：
  抽象是"把这段文本送到这个 run 的主人那里"，不知道 IM，也不知道 HTTP。
- 投递目标独立成表 `agent_run_delivery(run_id, channel, channel_ref, created_at)`，
  与 run **一对多**。入站时登记"这条消息从哪个通道的哪个会话来"，
  于是同一件事可以 HTTP 发起、飞书批准、企微收结果，不需要改表。
- **HTTP**：新增 `GET /api/runs/{id}`，返回 run 状态、step 列表与完整文本，客户端轮询。
  `OutboundMessage` 加 `runId` 字段（与 ADR-0016 是同一个改动），否则客户端不知道该查哪个。
- **飞书**：复用现有的 `FeishuReplySender`，不需要新写转发逻辑；要补的只是入站时把 chat_id
  登记成 `channel_ref`。它的失败约定（一律抛 `IllegalStateException`）保持不动——
  那个 javadoc 已经写清楚了为什么不能吞：吞掉的话群里没有回答、日志里也没有痕迹。
- **通知策略**落在 run 上，三档：`done_only`（默认）/ `silent` / `state_changes`。
  定时任务默认 `silent`（它有自己的交付路径，不该每步都吵人）；
  委派与后台任务默认 `done_only`。这一档对齐的是 OpenClaw 的 task notify policy——
  那是它 `tasks` 系统里少数几个值得照搬的设计。

## Consequences

- **代价**：客户端要改（轮询）；多一张关联表；延迟从"立刻"变成"下一次轮询"。
  这个量级要写进 `capability-tests.md`，别让人误以为流式坏了。
- **收益**：定时、审批续跑、子 Agent 委派终于能闭环。而且这个 SPI 是以后接任何新通道
  **必须回答**的问题——不实现就是"这个通道收不到异步结果"，
  这张"哪些通道支持推"的表应该进 README 的能力矩阵，而不是埋在实现里。
- **依赖方向不变**：`channel → core ← agent`（ADR-0010）。新 SPI 挂在 core，实现挂在各通道模块，
  接线仍在 `app`。
- **明确不做**：WebSocket 与 SSE 重连（M9 的 P19 再重审）、邮件与短信投递、
  投递失败的自动重试队列（重试策略放在各通道自己那里，因为它取决于那个平台的限流规则）。

与另外两条的界线：ADR-0016 定 run 落在哪张表；ADR-0017 定副作用怎么不重复；本条定结果怎么回去。
这三条合起来才是"一件能跨请求做完的事"。

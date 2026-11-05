# 03 · 定时任务用自己的会话，每次运行新段

**Status:** ready-for-agent
**Blocked by:** 01（需要段）

## 为什么

定时任务现在跑在**创建者那段对话**里。四行代码闭环，谁都能复核：

```text
ChatClientAgentPort:451   delivery.register(run.id(), message.channel(), message.chatId())
ChatClientAgentPort:603   new DeliveryTarget(channel, message.chatId())      // = addressOf
ScheduleTools:181         address.map(DeliveryTarget::channelRef).orElse(null)  // 存成任务的收件地址
ScheduleScanner:176       new InboundMessage(owner, task.channelRef() == null ? task.id() : task.channelRef(), …)
ChatClientAgentPort:240   sessionId = SessionKeys.sessionIdOf(owner, channel, chatId)
```

于是 `sessionId(定时任务) ≡ sessionId(创建它的那段对话)`——不是"看起来像"，是同一个字符串。

后果四条，前三条用户当天就能看见：

1. 早上九点那次任务的问答**出现在用户自己的聊天记录里**，并进入之后每一轮提示词。
2. 反过来：定时任务继承用户私聊的上下文作答。"总结今天订单"这句的提示词里，
   可能有十分钟前聊的合同条款。
3. **预算互吃。** 预算按会话累计（ADR-0025），cron 烧掉的工具调用计入用户那份 6 次。
   用户一次工具都没调，下一句被回"预算用尽"。
4. 压缩把用户对话挤进摘要：保留窗口是最近 20 条，cron 每天塞两条进去。

OpenClaw 的两种做法都是显式的：`isolated` → 会话键 `cron:<jobId>`，**每次运行一个新 sessionId**；
`main` → 往主会话塞一条 **system event**。我们现在是第三种：用主会话的键、塞一条长得像用户提问的记录、
还跨运行累积。三种里最差的一种。

`ScheduleFlowTest:392` 那条注释（"定时任务的 chatId 固定，两次运行落在同一段会话里，
预算是合成 2"）说明作者知道"两次运行共用一段"，但没意识到那一段就是用户的对话——
测试里 `createTask` 传的是 `"oc_sched"`（第 419 行），一个谁都不用的 id，所以这个等号在测试里永远看不出来。

## 一个不能踩的坑：`chatId` 同时是投递地址

最省事的改法是把 `ScheduleScanner.message(...)` 里的 chatId 换成 `cron:<taskId>`。**不能这么改。**

`chatId` 还担着"结果送到哪儿"：`chatId` 进 `addressOf`（`:603`）→ 登记成这一轮的收件地址 →
审批卡片、续跑结果、`notifyPolicy` 的推送全走它（`agent_run_delivery`）。改了它，审批卡片会推到
一个不存在的会话里，而这一轮看起来一切正常。

**这正是这条发现要拆开的两件事**：一个字段同时表示"送到哪儿"和"这是哪段话"。
所以这张票的真动作不是改一个值，是**给会话加一个独立的入参**。

## 落点

### 1. `InboundMessage` 多一个"会话标识"

```text
InboundMessage(principal, chatId, text, …, channel, notifyPolicy, conversationId)
```

`conversationId` 为 null 时按老口径用 `chatId` 派生会话（所有现有调用方不用改）；
非 null 时它就是这段对话的身份，**投递仍用 `chatId`**。

它是 record，紧凑构造器在 `InboundMessage.java:41`，另有四个便捷构造器（`:51`、`:56`、`:61`、`:65`）。
新字段**加在 record 头的最末尾**，四个便捷构造器各自透传 `null`——这样现有调用点一处都不用改，
只有 `ScheduleScanner` 那一个地方传真值。加在中间会让四个构造器全部错位，而错位在编译期
只报"参数类型不匹配"，改起来比读一遍更慢。

`ChatClientAgentPort` 两处（`:240`、`:287`）改一行：

```java
String address = message.conversationId() == null ? message.chatId() : message.conversationId();
```

lane 的键跟着它（02 的 `addr:` 用同一个值）——否则同一件事在"会话"和"锁"两处会算出两个名字。

### 2. 定时任务的会话标识

```text
cron:<taskId>:<本次触发的 epochMilli>
```

- 前缀 `cron:` 与 OpenClaw 的 `cron:<jobId>` 同形，一眼能看出这不是人的对话；
- **每次运行带自己的时刻**，所以天然是新段，不需要走指针（`conversation_pointer` 只服务"人"这条路）；
- 同一次运行里如果有续跑/审批，标识不变（run 上记着 `session_id`）。

`ScheduleScanner.message(...)` 里把两个用途分开写：

```java
new InboundMessage(owner, 收件地址, goal, now, …, channel, policy, "cron:" + task.id() + ":" + now.toEpochMilli())
```

### 3. 连带改一条现有断言

`ScheduleFlowTest:392` 现在断言"两次运行合起来是 2 次工具调用"。改完之后**每次运行各是 1**
（新段 = 新额度），那条注释与断言都要改——它不是被本票弄坏的，是被本票修正的。

## 怎么测（`app` 模块有余量）

`ScheduleFlowTest` 现在 14 个方法，能加；文件数 10 已经顶格，所以**加方法，不加文件**。

## 不做什么

- **不做 `sessionTarget: main`**（往主会话塞 system event）：那是另一种产品语义
  （"提醒我一句"而不是"跑一件事"）。我们现在只有"跑一件事"这一种定时任务。
- **不给 cron 建指针**：它的段由触发时刻决定，不需要"上次到哪儿"。
- **不改通知策略**：`NotifyPolicy` 与投递地址都不动。

## 验收判据

- [ ] **两条的会话不同**：同一 `chatId` 先说一句，再用 `schedule_task` 建任务并触发，
      `select count(distinct session_id) from claw4j_run.agent_run` = 2。
      反例：只改 `chatId` 不改 `conversationId` → 数字仍是 1，红。
- [ ] **定时那条不含用户 chatId**：`select session_id from … where goal = <任务目标>` 的结果里，
      `session_id` 形如 `…:cron:<taskId>:<millis>`，不含 `oc_sched` / `http:ethan`。
      反例：会话标识拼成 `chatId + ":cron"` → 红。
- [ ] **两次运行不共享历史**：第二次运行的提示词里没有第一次的问答。
      反例：标识不带时刻（`cron:<taskId>` 固定）→ 第二次能看到第一次，红。
- [ ] **结果仍送到原地址**：`Recorder` 收到的那条文本落在 `oc_sched`。
      反例：把 `chatId` 改成本票的会话标识 → 投递到不存在的地址，红。
- [ ] **审批卡片仍落在原地址**：定时任务触发一个需要审批的工具时，卡片的
      `agent_run_delivery` 里那条仍是 `oc_sched`。反例：同上。
- [ ] **额度不再合成**：`ScheduleFlowTest:392` 那条改成"每次运行各 1 次"。
      反例：不改断言 → 红（而且它本来就该红，因为它断的是一个错的行为）。
- [ ] `./scripts/verify.sh` 全绿，跳过 0，覆盖率不低于 60%。

## 收票时要补

- `P04-scheduler.md` / `P05-schedule-task-tools.md` 各补一节"修正记录"（状态保持 `resolved`，
  按 A04 的先例），写清"定时任务曾借用创建者的会话，改由本票修正"。
- `docs/design/session-memory.md` 或 ADR-0026 的落地记录里补一句：cron 的会话键形状。

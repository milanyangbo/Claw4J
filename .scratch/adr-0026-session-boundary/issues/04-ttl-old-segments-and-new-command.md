# 04 · TTL 只删旧段；`/new` 手动结束

**Status:** ready-for-agent
**Blocked by:** 01、02（`/new` 要与轮换进同一把锁）

## 为什么

`spring.ai.session.time-to-live: 30d`（`application.yml:43`）配的是"行上的到期时间"，
`SessionCleanup` 每小时删过期行。问题不在 30 天这个数，在**它在删谁**：

- 到期时间写在建行那一刻，**不随活动刷新**（`JdbcSessionRepository` 的全部 SQL 常量里，
  没有任何一条 UPDATE 触及 `expires_at`，只有 `event_version` 的乐观锁与 archived 标记）；
- 会话 id 又永远不变，所以"每天都在用的那段对话"与"三天前的那段"在库里长得一样，
  区别只有一个时间戳。

结果：**每天用的人，第 30 天打开聊天，记忆突然清空。** 不是"昨天那段结束了"，是"什么都没有了"。
而清理是后台任务、不挑时机，可能正好在两人对话中间把行删掉。

01 落地之后这件事自动修好一半：活跃对话永远在**新段**（新行）上，旧段才到期。
本票做的是把另一半补上——让"结束"这件事**用户也能触发**。

## 落点

### 1. TTL 的行为，不改数值

段让活跃对话永远是新行，所以 `SessionCleanup` 一行代码都不用改：它继续删 `expires_at < now` 的行，
而那个行现在天然只可能是旧段。

**30 天不改。** 留存口径是《数据安全法》那条线上的承诺（`04-session-ttl-policy` 的"不要做"里写着：
不归档、过期就是删，因为留存期不能没有终点）。ADR-0026 里那句"可以降到 7 天"是**可以**，
不是决定——真的要改，单独一张票，改的时候要连带改 README 与能力测试文档里那句留存说明。

### 2. `event_version` 的那把乐观锁与删除的先后

清理任务删的是整行（`on delete cascade` 连事件一起删）。如果它正好删掉一个**正在被写**的段
（01 之后不该发生，但降级路径下可能），追加事件会拿到"父行不存在"。
`FaultTolerantSessionService` 把 `DataAccessException` 吞成"记忆没写进去"，这一轮照常回答——
这是可接受的失败姿势，本票只要求**确认它真的被吞了**，不要让异常冒到 `ChatClientAgentPort`。

### 3. `/new`：只做 HTTP 一个头

```text
X-Claw4j-New-Session: true
```

`HttpPrincipalResolver` 已经在解析 `X-Claw4j-Chat-Id`（`:54-55`），加一个布尔头是同一条路。
行为：在 lane 里**强制轮换**一次，这一轮用新段（旧段留在库里，到期照删）。

实现上给指针 store 加一个 `rotate(owner, channel, chatId, now)`（把 `touched_at` 拨到很早、
或者直接 upsert 一个新 segment——两条路都行，取实现时最简单的那条，但**必须在 lane 里**）。

**IM 侧不做 `/new`**：飞书/钉钉/Slack 的斜杠命令要各自的注册与权限，而"用户想换一件事说"
在这几个通道里没有稳定表达（群里发 `/new` 到底是想给谁换？）。等真的有人要再做，
那时要回答的第一个问题是"群里谁有权换这段对话的段"。

### 4. `idle` 降级成兜底

01 删掉 `claw4j.run.budget.idle-minutes` 之后，`spentOfSession` 里那段空闲判定保留，
但它是**兜底**：正常情况下段换了、键就变了、计数天然归零；只有指针写失败（降级到无段 id）时，
才靠它防止额度无限累计。本票要保证这条兜底读的是 `claw4j.session.reset.idle-minutes`
——一个值，一个来源。

## 怎么测

- `SessionMemoryTest` 已 20 个方法、`RunSchemaSqlTest` 已 20 个——本票的 TTL 用例**置换**
  `RunSchemaSqlTest` 里与"过期删除"有关的那条（它现在断的是老行为），`/new` 的用例放进
  `claw4j-channel` 的 `HttpChatChannelTest`（有位置）或 `SessionMemoryTest` 的置换里。
- 时钟用可控的 `Clock`，不要 `Thread.sleep`。

## 不做什么

- **不改 TTL 数值、不归档到另一张表**（理由见上）。
- **不做跨段召回**：换段之后上一段召不回来，这是 ADR-0026 认过的代价。
- **不做会话列表 / 会话切换 UI**：`/new` 只保证"能换"，不保证"能回去"。
- **不做 `/stop`、`/compact`**：与"结束一段对话"无关，是另外两件事。

## 验收判据

- [ ] **活跃对话跨过 TTL 仍然在**：短 TTL + 可控时钟，连续三次（每次间隔小于 TTL）说话，
      然后推进时钟超过 TTL 但继续说话——当前段的行必须还在。
      反例：把段去掉（回到 01 之前）→ 这条红。
- [ ] **旧段到点被删**：最后一段之后再推进 TTL + 清理一次，旧段的行消失，
      当前段不受影响。反例：清理任务改成"按 user_id 删"→ 当前段也被删，红。
- [ ] **清理撞上正在写的段不会 500**：清理与一轮写并发，断言这一轮仍返回回答。
      反例：把 `FaultTolerantSessionService` 的 catch 收窄 → 偶发 500。
- [ ] **`/new` 之后段立刻换**：带 `X-Claw4j-New-Session: true` 的那一轮用新段，
      且新段看不到上一段的历史。反例：只清空 `touched_at` 而不换段 → 段不变，红。
- [ ] **`/new` 与并发消息串行**：`/new` 与同一对话的另一条消息同时到达，只能有一个新段。
      反例：强制轮换绕开 lane → 两条消息落在两段，红。
- [ ] **`idle` 只有一个来源**：`grep claw4j.run.budget.idle-minutes` 在仓库里零命中，
      `spentOfSession` 的兜底读的是 `claw4j.session.reset.idle-minutes`。
- [ ] `./scripts/verify.sh` 全绿，跳过 0，覆盖率不低于 60%。

## 收票时要补

`04-session-ttl-policy.md` 补一节"修正记录"：它的判据（过期会被删）没变，
但"谁会过期"变了——活跃对话不再过期，因为它已经在新的段上。状态保持 `resolved`。

# 票 12 `occurredAt` 记事件时刻，不是落库时刻

**Status:** resolved
Blocked by: —
裁决来源：`docs/adr/0037-audit-alignment-with-openclaw.md`（2026-11-23 第四批修订）。

## 对照（原文）

```ts
// src/audit/audit-event-store.ts:89 —— 绑的是调用方给的事件时刻
occurred_at: input.occurredAt,
```

它的事件时刻由生产者给：运行事件的 `occurredAt` 是产生那一刻，入站消息是**到达那一刻**
（`durationMs` 量的就是从那之后到处理完的耗时，`docs_cli_audit.md:318-321`）。

我们这边 `at` 列有默认值 `now()`，而 `AuditLedger` 的 `INSERT` 里没有这一列——所以写进去的是
**落库时刻**。同步写时两者通常同毫秒，但有三处会真的分叉：一条入站消息处理了 8 秒（它的
"到达时刻"比落库早 8 秒）、一场排在事务后面等的写、以及 30 分钟窗口扫出来的孤儿补写。

## 做什么

1. `AuditEvent` 加 `occurredAt`（`Instant`）：四个工厂在**构造那一刻**取 `Instant.now()`，
   入站消息工厂改成 `now - durationMs`（那是它进来的时刻，与它量耗时的起点一致）。
2. `AuditLedger.INSERT` 显式绑 `at`，不再依赖列默认值——重放/批量写时保留事件原本的时刻。
3. 孤儿补写保持 `now()`：补写这一行是"我们发现它没了下文"的时刻，不是"它结束"的时刻，
   而后者本来就不可知。这条写进「缺口」。

## TDD 用例清单

| 判什么 | 用例名 | 先红的样子 | 结果 |
| --- | --- | --- | --- |
| 一条入站消息的 `at` 是"到达时刻"：比 `durationMs` 早那么多 | `MessageAuditTest#inboundRowCarriesArrivalTime` | 落库时刻时它约等于 now，比 now-800ms 晚 800ms | ✅ |
| 写库不改写事件时刻：造一条"两小时前发生"的事件，读回来还得是那个时刻 | `BuildContractTest#auditQueryReturnsNewestFirst` | 绑 `now()` 时差两小时 | ✅ |

一条进 `MessageAuditTest`（6 → 9 个方法，另两条是票 13 的），另一条并进
`BuildContractTest` 的既有方法（它已 26 个方法，不新增）。

## 复核

复核：2026-11-23 `./scripts/verify.sh` 全绿（失败 0 / 错误 0 / 跳过 0）。**跑过两次反例**：
把 `AuditEvent#arrivalOf` 改成 `Instant.now()`，`inboundRowCarriesArrivalTime` 红在
「到达时刻早于落库时刻约 800 毫秒」（实际差 0）；把 `AuditLedger` 的 `at` 绑成
`Timestamp.from(Instant.now())`，`auditQueryReturnsNewestFirst` 红在「写库不许把事件时刻改写成落库时刻」。
两次都改回来绿。

## 缺口

- **孤儿补写的 `at` 是补写时刻**：那一行的"真正结束时刻"无从知道，写补写时刻至少诚实
  （它说的是"我们什么时候判定它没了下文"）。OpenClaw 的补偿走的是成对写入，没有这个问题。
- `occurredAt` 仍是 `Instant` 落库 → 读侧转毫秒；库里的精度是微秒，报文是毫秒，与它同。

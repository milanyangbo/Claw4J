# 票 10 读接口的参数：时间窗 + 冲突就说出来

**Status:** resolved
Blocked by: —
裁决来源：`docs/adr/0037-audit-alignment-with-openclaw.md`（2026-11-23 第三批修订）。

## 对照

| OpenClaw | 改之前 | 改成 |
| --- | --- | --- |
| 参数里有 `after` / `before`（**Unix 毫秒**，闭区间，`ledgers.md:25-26`） | 没有时间窗，只能靠游标一页页翻 | `Filter` 加 `after` / `before`，SQL 用 `at >= / <= to_timestamp(ms/1000)` |
| 参数冲突直接报错：`kind=message` 与 `sessionKey`、`kind≠message` 与 `direction`/`channel`、没给 `kind` 却同时给 `sessionKey` 与 `direction`/`channel`（`audit-activity.ts:30-53`） | 无条件 AND，组合错了返回空页 | `Filter#conflict()` 判三条，`AuditController` 回 400 + 一句人话 |
| `cursor` 是不透明字符串（`ledgers.md:29`） | `Long` 参数 | 字符串进、字符串出；认不出来回 400，而不是"从头查" |

## 做什么

1. `AuditQuery.Filter` 加 `after` / `before`（`Long`，毫秒闭区间）与 `conflict()`。
2. `JdbcAuditQuery` 把时间窗写进 where；`at` 是 timestamptz，毫秒在库里换算。
3. `AuditController`：`cursor` / `nextCursor` 用字符串；参数冲突与坏游标都回 400 +
   `{"error": "…"}`（`Problem`），并且**不去查库**。

## TDD 用例清单

| 判什么 | 用例名 | 先红的样子 | 结果 |
| --- | --- | --- | --- |
| 时间窗两个边界都传到底层过滤 | `HttpEndpointsTest#anAuditPageIsReadable` | 不加参数时 `filter.after()` 恒 null | ✅ |
| 三种冲突组合各自 400，且不查库 | `HttpEndpointsTest#conflictingFiltersAreRejected` | 无条件 AND 时三种都返回 200（假查询拿到空页） | ✅ |
| 坏游标 400 | `HttpEndpointsTest#anUnknownCursorIsRejected` | 解析失败当成"从头查"时返回 200 | ✅ |

## 复核

复核：2026-11-23 `./scripts/verify.sh` 全绿（失败 0 / 错误 0 / 跳过 0）。**跑过反例**：
把 `Filter#conflict()` 改成恒返回空，`conflictingFiltersAreRejected` 红在
「参数冲突时不该查库」——那说明没有校验时它真的会去查，然后回一张空页。

## 缺口

- **没有 `operator.read` 这一层**：它要求 scope 才给查；我们整个 http 包没有鉴权。
  这不是"字段对齐"能解决的——得先有 operator 身份与授权表，单开一张票。
- `after` / `before` 只筛 `at`（写库时刻），不筛"事件原本发生的时刻"：我们没有第二个时间列。

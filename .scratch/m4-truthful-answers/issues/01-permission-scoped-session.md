# 01: 会话按「人 + 权限」隔离

**What to build:** 会话 id 与 `ai_session.user_id` 从裸 `userId` 改成 `userId@权限指纹`，
让一个人被降权之后拿到的是"什么都想不起来"，而不是"还记得不该记的"。

**Blocked by:** 无。但它是 02 的前提之一：不先把会话边界收紧，02 改得再好也只是让模型少抄一点。

**Status:** resolved

**Demo:** 同一个 userId、两个角色各问一句 → 库里出现两条会话、外键不同；
`guest` 问"上周华东区成交额"拿到的是鉴权拒绝，而不是那个数字。

## 为什么

2026-10-27 的真实试验里，同一个人用 `guest` 问同一句，`guest` 读不到销售表，
却把 `128,400.00` 报了出来。不是越权调工具（那会被 `AuthorizationAdvisor` 拦），
是**从同一段会话历史里念的**——会话 id 原本只由 `userId` 派生，换角色不换会话。
ADR-0007 画的边界在"会话"这一层被绕过去了。

## 做了什么

- [x] `io.github.claw4j.agent.PermissionFingerprint`：`tables + actions` 排序后 SHA-256。
      与语义缓存的权限指纹**同一份实现**（`CacheAdvisor.fingerprintOf` 委托它）——
      两处各自演化，迟早出现"缓存认 A、会话认 B"这种没人能复现的组合。
- [x] `SessionKeys.ownerOf(principal)` = `userId@短指纹`（前 16 位十六进制，64 bit），
      `sessionIdOf` = `claw4j:user:` + owner。拆出 `sessionIdOfOwner` 让一轮请求只算一次指纹。
- [x] `ChatClientAgentPort`：`SESSION_ID_CONTEXT_KEY` 与 `USER_ID_CONTEXT_KEY` 都用归属键。
- [x] `RecallSessionTool.recallToolsFor` 也传归属键：`CrossSessionRecallTools` 走
      `findEventsByUserId`（`WHERE user_id = ?`），传裸 `userId` 的话会话记忆关了前门、召回又把后门开着。
- [x] 用例（并进 `SessionMemoryTest` / `RecallSessionTest`，不新增测试文件——文件数上限是 10）：
      会话 id 同权限稳定、换权限就变；权限集合顺序不影响指纹；建会话时外键是归属键；
      降权后召不回旧会话。
- [x] 变异：`ownerOf` 去掉指纹 → 3 条红（换权限、稳定性、降权召回）；
      `USER_ID_CONTEXT_KEY` 传裸 `userId` → 1 条红（外键形状那条）。

## 真链路（2026-10-27，固定端口 18080，先 `truncate claw4j_cache.semantic`）

```
[read-only] 上周华东区成交额多少  → 99,999.00 元 + 三条口径（真查了库）
[guest]     上周华东区成交额多少  → 没有权限：角色 guest 不允许访问表 customer、order_item、sales_order
[read-only] 我刚才问你的是什么    → 复述上一问与 99,999.00（同权限的记忆还在）
```

库里的会话——同一个 userId，两条：

```
claw4j:user:u-permprobe2@4022bbd3cf82bfeb | u-permprobe2@4022bbd3cf82bfeb | 2 事件（guest）
claw4j:user:u-permprobe2@49425b8be3fedc3e | u-permprobe2@49425b8be3fedc3e | 4 事件（read-only）
```

指标 `claw4j.tool.calls{tool=querySales}` = `ok` 1 次 + `denied` 1 次：
guest 那轮是**去查库被拒**，不是从历史里念数字——这正是这条票要的结果。

## 代价

- 权限一调整，历史就断；**不含 `@` 的老会话读不到**（本项目自己之前的数据）。
  这是刻意选的：只丢记忆，不泄数据。
- 会话内仍然不分话题（`SessionKeys` 里那笔账，本票没动这条维度）。

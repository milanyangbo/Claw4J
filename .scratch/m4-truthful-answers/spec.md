Status: draft（范围待定：本文件只记录"为什么会有这个阶段"，不承诺做几件）

# M4：回答经得起追问

## Problem Statement

2026-10-27 把 M3-05 的语义缓存接上真实问法（7 次提问、同一份业务数据、同一套角色配置），
缓存本身按设计工作：`hits=1`、四种 miss 原因各自出现。但这次试验暴露出的三件事**与缓存无关**：

1. **模型会抄会话记忆里的旧数字，不查库。** 四天前问"上周华东成交额"，当时答 128,400 是对的；
   今天问同一句，它把四天前那句话原样念了一遍（今天是 99,999）。7 次提问里
   `claw4j.tool.calls{querySales}=1`——只有一次真查了库。会话 TTL 是 30 天，
   于是一个"当时正确"的答案被当成了事实。
2. **换权限不换会话。** 同一个人用 `guest` 再问，`guest` 读不到销售表，却把 128,400 报了出来——
   不是越权调工具（那会被鉴权拦），是从同一段历史里念的。会话 id 原本只由 `userId` 派生。
3. **纯问答也进缓存。** "你好，你能做什么"进了 `claw4j_cache.semantic`，留一小时。

三者的关系一句话：**缓存不产生错误，它只是把上游的错原样复制一份，还多留一小时。**

## Solution

- ②（会话按权限隔离）已经做了：`claw4j-agent.PermissionFingerprint` + `SessionKeys.ownerOf`，
  会话 id 与 `ai_session.user_id` 都变成 `userId@权限指纹`。见
  `issues/01-permission-scoped-session.md`。
- ①（不抄旧数字）待定做法，见 `issues/02-stale-numbers-from-session-memory.md`。
- ③ 决定不处理，理由见 `issues/03-question-only-cache-entries.md`。

## 不在这个阶段

- 多话题并行（会话内再分话题）。那是 `SessionKeys` 里记了很久的那笔账，与"权限"不是同一个维度。
- 语义缓存的阈值/淘汰策略调整。真实问法下 0.93 的实际效果是"只有原句重复才命中"，
  这是当初选的"宁漏不误"，没有新证据要改。

# 票 16：译文落表——同一个工具只译一次，评测才可复现

**Status:** open

> 依赖票 11（`zh-description` 与 `ToolDescriptionTranslator`）、票 13（收益已可测）。

## 由来

票 13 的第三版测量把票 11 的结论翻过来了：中文化对向量那一路**确实有收益**（`zh-en` 的
`R@5` 0.600 → 0.750/0.800/0.750）。但同时暴露一个新问题：

**开的那一侧不可复现**。关着跑三次，`zh-en R@1` 是 0.200 / 0.200 / 0.200；开着跑三次，是
0.350 / 0.250 / 0.400。原因不是检索，是**译文**：翻译只在进程内缓存，每次跑评测都重新译一遍，
措辞不同 → embedding 不同 → 名次跟着变。

后果有两层：

1. **不能当回归门**：一个自己会抖的指标，挂上门就是每天误报。
2. **钱白花**：每次部署重译几十个工具。

票 11 把落表记成"等成本可感再说"。现在理由变了：**不是省钱问题，是结论能不能重复的问题。**

## 裁决

用户 2026-11-26 同意落表（"3. 可以的"）。这是 schema 变更，表结构按下面这份；要改就先改票面。

**无需 ADR**：票 11 已经在 `docs/adr/0021-explicitly-not-doing.md` 的「领先 2：工具可见性」下
修订过一条——本地化只改入库文本、不改工具名/权限/计量。本票只回答"这份译文存在哪"，
不扩大它的作用域，所以是那张 ADR 的执行细节，不是新决定。

```sql
-- V9__tool_description.sql
create table if not exists claw4j_tool.tool_description (
    tool_name     text        primary key,
    source_hash   char(64)    not null,   -- sha256(原文)，原文变了 hash 就变
    source_text   text        not null,   -- 留着原文：排障时要看"当时译的是哪一版"
    zh_text       text        not null,
    updated_at    timestamptz not null default now()
);
```

schema `claw4j_tool` 已由 V8 建（票 07），本票只加一张表。

## 怎么用

`ToolDescriptionTranslator#translateReferences` 的缓存从 `ConcurrentHashMap` 换成
**「先查表、查不到再译、译完写回」**：

1. 算出 `sha256(description)`；
2. `select zh_text from claw4j_tool.tool_description where tool_name=? and source_hash=?`
   —— 命中就直接用；
3. 未命中才调 utility 模型，译成功后 `insert ... on conflict (tool_name) do update`；
4. **译失败不写表**：把原文当译文存进去，以后永远不会再译，表面上看是"缓存命中"。

进程内那层缓存留着（挡住同一批工具在一个进程里被问两次），表是跨进程的那一层。

## 失败口径

- 表不存在 / 没有 `JdbcTemplate`（比如单元测试）→ **退回纯内存缓存 + 一条 WARN**，不抛。
  理由与票 11 一致：中文化是增强，缺了只是命中率低一点。
- 写表失败（权限、连接断）→ 同上，只告警。**读失败与写失败要分开告警**：一个是"没省到钱"，
  一个是"下次还得译"，混成一条就分不出该修哪边。

## TDD 用例清单

`claw4j-agent` 已到顶（14 文件 / `ModelAndToolSelectionTest` 26 方法），所以先合并再放新的：
`zhDescriptionOffLeavesDescriptionsAlone` 与 `zhDescriptionFeedsVectorWingOnly` 合成一条
"中文化的作用域与缓存"（同一入口 `translateReferences`、同一种断言"模型调用了几次"）→ −1。

| 判什么行为 | 用例 | 先红的样子 |
| --- | --- | --- |
| 同一个工具第二次不再调模型（跨实例） | `ModelAndToolSelectionTest#translationIsReusedAcrossInstances` | 只靠进程内缓存时，新实例调用计数为 1，断言红 |
| 原文变了要重译 | 并入上一条（第二段：换 description，断言又译一次） | 只按 `tool_name` 命中时，原文变了仍返回旧译文，断言红 |
| 译失败不写表 | 并入上一条（第三段：模型抛异常后，再译一次要重新调模型） | 把原文当译文存进去时，第二次"命中"了，断言红 |
| 表读不出来时退化成内存缓存 | `ModelAndToolSelectionTest#translationFallsBackToMemoryWhenTableMissing` | 直抛时整轮请求挂掉 |

复核：<待补，收票时写日期与命令>

## 收票时要量的一件事

开/关各跑三次，看**开的那一侧还抖不抖**：`zh-en R@1` 如果收敛到同一个数，本票的目的达到，
才可以考虑把它挂进回归门（那是另一张票：门只该盯稳定的指标）。

## 缺口（本票不做）

- **批量翻译**：一次请求译多个工具能减少调用次数，但要处理模型输出格式的失败路径。
- **译文的失效清理**：工具下架后表里的行会留着。几十行无所谓，上百行时再开。
- **反向（中文→英文）**：同样的机制，等真有中文描述的工具要喂英文索引时再说。

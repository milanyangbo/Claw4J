# 票 16 线格式：报文与参数改叫 `executionId`（推翻票 15 的做法三）

**Status:** resolved
Blocked by: —
裁决来源：`docs/adr/0038-rename-run-to-execution.md`（决定二已改写成"线格式也改"）。

## 为什么翻

票 15 留着 `runId`，理由是"它的闭对象里那一格必填、参数表里只有这个名"。这条理由只对**照抄它的
schema** 成立，但那一格承载的是**我们的**执行编号——它的 `runId` 是"可能跨轮共享的关联"，我们没有
那一层。名字留在边界上，读报文的人就会以为我们也有一层叫 run 的东西（这正是 ADR-0038 背景里
那个"同名不同物"）。所以：**边界跟着我们的名字走，不跟着它的名字走**。

拼法照本仓的线格式：`/api/chat` 的 `AskResponse` 与 `/api/approvals` 的 `ApprovalView` 早就是
`executionId`（票 15 一起改的）。同一个标识在同一套 API 里有两个拼法，比"偏离它"更难解释。

代价写清楚：报文/参数不再是它 `audit.activity` 的实例，严格客户端按它的 schema 解析会红。

## 做什么

1. `AuditEventView`：组件 `runId` → `executionId`；JSON 键随之变（Jackson 按组件名出键，无需注释）。
2. `AuditController`：`@RequestParam("runId")` → `@RequestParam("executionId")`。
3. 用例：报文里出现 `"executionId":"exec-…"`，且整页 JSON 不含 `runId`。
4. 文档：ADR-0038 决定二与代价、ADR-0037 第二批的两段、`capability-tests.md` 的接口行与参数说明。
   历史 ADR（0016/0018/0019/0020/0023/0024/0025/0027）不改正文——那是记录。

## TDD 用例清单

| 判什么 | 用例名 | 先红的样子 | 结果 |
| --- | --- | --- | --- |
| 报文那一格叫 `executionId`，整页没有 `runId` | `HttpEndpointsTest#anAuditPageCarriesTheVariantDiscriminator` | 组件还叫 `runId` 时 JSON 里是 `"runId"` | ✅ |
| 读侧返回的值仍是那一行的执行编号 | `HttpEndpointsTest#anAuditPageIsReadable` | —（值不动，只改键） | ✅ |

## 复核

复核：2026-11-23 `./scripts/verify.sh` 全绿（失败 0 / 错误 0 / 跳过 0）。**跑过反例**：把
`AuditEventView` 的组件名改回旧名，`anAuditPageCarriesTheVariantDiscriminator` 红在
「执行编号沿线格式就叫 executionId」——报文里出的是旧名。

## 缺口

- **不再是它的实例**：报文与参数名偏离 OpenClaw 的 `audit.activity`（`runId`）。要按它那份
  schema 解析的客户端得自己映射一次。

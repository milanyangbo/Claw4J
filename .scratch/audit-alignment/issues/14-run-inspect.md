# 票 14 （已撤除）`audit.run.inspect`：一次 run 的全景

**Status:** wontfix
Blocked by: —
裁决来源：`docs/adr/0038-rename-run-to-execution.md`（决定三）。

## 为什么撤除

它建成之后又被撤掉，原因不是实现错了，是**这个能力在我们的形状里没有对应物**：

```ts
// docs/gateway/protocol/ledgers.md:124-127
Its closed request selects exactly one `executionId` for exact inspection or one `runId` for
bounded execution discovery. One run match resolves directly; multiple matches return an
explicit `ambiguous` result with at most 50 candidates and require exact execution selection.
```

它的 `run.inspect` 是 **run 级**的：传 `runId` 意味着"发现这次 run 下的多个执行"，一个 run 对应
多条时只回 `ambiguous`、要你选 `executionId`。我们没有"一次 run 下多个执行"这回事——一个执行编号
就是一次执行（ADR-0038）——照抄出来的端点只能覆盖它的 `executionId` 分支，名字却叫 run。

当时留下的实现（端口 `AuditRunInspect`、`JdbcAuditRunInspect`、`AuditRunView`、
`GET /api/audit/runs/{runId}`、两条读侧用例）一并删除。

## 撤除前已经验到的事实（保留，供将来恢复时用）

- 回执合并顺序照它的 `admission → approval → message → generic → cron`；我们只有三类有生产者。
- **审批行挂在发起那一轮，执行发生在下一轮**（`approval_request.execution_id` 是发起方的编号），
  所以查执行那一轮时要靠执行行上的 `approval_id` 接回去——只按执行编号查会说"没有任何审批"。
  这条结论与端点是否恢复无关，写在 `docs/capability-tests.md` 的审计节里。
- `identity` 只能报 `unsupported`：没有采集执行身份（它的 `logging.audit.executionIdentity`
  的对应物），而它的 schema 为这种情况留了状态（`audit-run.ts:251-255`）。

## 恢复条件

引入"跨轮共享的 run"（网关侧的路由/恢复生命周期）之后，`run.inspect` 才有它原本的含义。
届时按 ADR-0038 的退场条件恢复，并且**必须带 `ambiguous` 分支**——那是它存在的理由。

## 缺口（撤除后，这些能力我们没有）

- **单次取证的多面合并**：现在只能 `GET /api/audit?runId=<执行编号>` 按时间线看，或者自己 join
  `approval_request` / `execution_decision_fact`。
- **覆盖度**（`enforced` / `attribution-only`）：没有这一层标注，读的人得自己判断哪条是闸、
  哪条只是记录。
- **`operator.read`**：撤除不改变这条——整个 http 层没有鉴权。

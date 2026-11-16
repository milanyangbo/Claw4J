# 09: 默认预算与本仓自己的示范流程规模不匹配

**What to build:** 待裁决。读 `weekly-digest` / `weekly-sales` / 周报类业务的实测结果，
定一个方向让"多轮取数"的业务流能跑完，而不是跑一半被预算拦下。

**Blocked by:** 无。

**Type:** decision

**Status:** needs-decision

**Demo:** `t '派 weekly-digest 写一份上周的周报'` 能出三区 + 合计 185900，或文档明确说这条路
在默认预算下走不完、给出该改的配置。

## 现场（2026-11-16 实测，业务流程验证）

| 流 | 默认配置 | 结果 |
| --- | --- | --- |
| `t '按 weekly-sales 的口径，给我上周各区成交额'` | `tool-call-limit: 6` | `Total tool call limit (6) exceeded for this turn.` |
| 同上 | `tool-call-limit: 20` | 通，三区 + 合计 185900 |
| `t '派 weekly-digest 写一份上周的周报'` | `tool-call-limit: 20` | **仍不通**：`querySales 的调用次数用超了（本轮上限 3 次）` |

两道预算：

- `claw4j.agent.tool-call-limit: 6`（**可配**，注释说它是"这一轮绕了太多次"的止损点）；
- `ToolCallBudget.MAX_CALLS_PER_TOOL = 3`（**不可配**，注释："正常一次查到，留两次给自纠错"）。

## 三条口径的冲突

`examples/subagents/weekly-digest.md` 要求"查本周与上周**同口径**"；三区再各查一次就是 4~6 次。
而单工具 3 次是硬上限——**示范资产与安全阀互相矛盾**，不是模型绕远路。

## 三个可选方向（要一次裁决，别混着改）

| 方向 | 改什么 | 代价 |
| --- | --- | --- |
| A | 改 `weekly-digest.md`：要求"用一条 SQL（条件聚合）一次取两窗、按区分组"，把 4~6 次压到 1~2 次 | 不动安全阀；但示范子 Agent 从此绑定"一条 SQL 解决"的写法，复杂问题仍会撞上限 |
| B | 把 `MAX_CALLS_PER_TOOL` 提到 5~6 或改成可配 | 直接解决问题；代价是原本用来防"同一工具绕圈"的那道闸变松，且它明确写着"不可配"是有理由的 |
| C | 只把整轮默认从 6 提到 10~12，单工具不动 | 缓解 Skill 那条流，`weekly-digest` 仍然撞 3 次上限——不彻底 |

我倾向 **A**（安全阀是刻意的，示范资产该按它的约束来写），但这是产品口径，等你定。

## 附带一处（不论选哪个都该改）

预算耗尽时**用户拿到的是内部英文错误**：`Total tool call limit (6) exceeded for this turn.
No further tool calls are allowed.`（HTTP 200）。配置注释写的是"回一句'这一步做了太多次'"，
实际回的是框架原文。要么在 `ToolCallBudget` 那一层换成那句中文，要么承认这是框架措辞。

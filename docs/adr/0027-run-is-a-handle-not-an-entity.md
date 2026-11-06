# run 只是一轮的句柄，不是一个实体

---

status: accepted
date: 2026-11-06

---

这份 ADR 拆掉本项目自己的一个设计：`run`。它不是"实现得不够好"，而是**它本来就不该存在**。

## Context

`run` 现在承载七类职责。逐条对照 OpenClaw，只有第一类它有对应物。

| # | 我们拿 run 干什么 | OpenClaw 的对应物 | 判定 |
| --- | --- | --- | --- |
| 1 | 一轮运行的句柄（`OutboundMessage.runId`） | `agent` RPC / `chat.send` 返回 `{ runId }`，`agent.wait` 等它，`chat.abort` 可带可不带 | **有，保留** |
| 2 | 状态机五态（含 `SUSPENDED`） | 无。只有生命周期事件 `start` / `end` / `error` | **删** |
| 3 | 跨请求续跑（`resume` / `reopen` / `suspend` / `InboundMessage.runId`） | 无。连续性靠 `sessionKey`，用户消息里没有 runId | **删** |
| 4 | 步骤流水与审计（`agent_step` + `audit_trail` 视图 + 七列） | 有审计，但是**事件流水**：四类 action、元数据 only、30 天 / 10 万行、工具调用只存单向指纹 | **形态不对，重建** |
| 5 | 计划（`plan_ref` / `planOf` / `setPlan` / `markPlan`） | 有，叫 **goal**：挂在会话上，六态，模型侧 `get_goal` / `update_goal`，每个回合注入一行 | **形态不对，重建** |
| 6 | 投递（`agent_run_delivery` / `result` / `notify_policy`） | 投递是会话与任务上的配置；没有"一件事在多个通道被找到"这种登记表 | **删** |
| 7 | 预算（三项累计） | 无 run 级预算。goal 带 **token budget**，`budget_limited` 是 goal 的状态 | **并进 goal** |

根因一句话：**我是从"平台能力得有个东西可挂"出发的，先造了 run，再往里塞；OpenClaw 从"会话是唯一的上下文载体"出发，所以它不需要第二层实体。**

这个差别会一路显形：

- 一件"事"只在有请求等它的时候存在，于是要给它编状态、编续跑、编投递登记——这三样都是为了让它活过请求；
- 而会话本来就活过请求，上下文、目标、预算放在会话上，那三样一个都不需要。

ADR-0020 的判断没有错（"平台能力不另开旁路"），错的是它挑的锚点。它当年选 run，是因为
`AgentPort` 当时只有"你问、我答、答完结束"这一种形状，而平台能力要的正是"答完之后还有事"。
ADR-0024 与 ADR-0026 已经把"答完之后"这件事交给会话了，run 作为锚点的理由随之消失。

## Decision

### 一、`runId` 只活一次调用

对外仍叫 `runId`（与 OpenClaw 同名同义），但语义收窄成四条：

- 一次运行一个 UUID，在模型被调用之前就生成；
- 随回答返回。OpenClaw 里它就是 `chat.abort` 收的那个值、`openclaw audit --run` 过滤的那个值——
  这两面（按 id 中止、按 id 查审计）本项目**还没做**，各自是一件事，不在这一版里；
- **不落状态、不能续跑、不承载目标 / 预算 / 投递**；
- 审计按它关联（`openclaw audit --run <id>` 就是按它过滤）。

字段名不改，因为改名换不来任何东西，而"runId 能续跑"这个错觉才是要拆的。
票 05 一度把它改成 `turnId`（理由是"每轮一个，更像 turn"），与这条决定相反；
实现已经改回，本文与代码现在一致。

### 二、审计照 OpenClaw 的形状重建

新建 `audit_event`，一个会话一个流水。照它的六条：

1. **四类 action**：`agent.run.started`、`agent.run.finished`、`tool.action.started`、`tool.action.finished`；
2. 每条记 identity / ordering / provenance / action / status / 归一化错误码 / actor，
   其中 **`runId` 必填**——它的 schema 就是这么定的。没有轮次的事件不属于任何一次运行，
   我们不写它，也不让库里出现一行按 runId 查不到的审计；
3. **不存**提示词、消息体、工具参数、工具结果、命令输出、原始错误文本；
4. 工具调用 id 只存**单向指纹**；工具名必须是模型可见的那个紧凑名，认不出就记 `unknown`；
5. 状态是**闭合词表**：`started` / `succeeded` / `failed` / `cancelled` / `timed_out` / `blocked` / `unknown`；
6. **错误码也是闭合词表**，九个值，逐字照它的 `AuditEventErrorCodeSchema`：`run_failed` /
   `run_cancelled` / `run_timed_out` / `run_blocked` / `tool_failed` / `tool_cancelled` /
   `tool_timed_out` / `tool_blocked` / `tool_outcome_unknown`。前缀就是归属，`agent_run` 的事件配
   `tool_*` 说明写的人弄错了层。这不是"顺便列几个字符串"：按 `error_code` 过滤时，
   词表外的值一个都命中不了，而它们的缺席看起来像"没发生过"；
7. **30 天 + 10 万行**上限，启动时、每小时、每次写之后各清一次。

删掉 `agent_step` 表与 `audit_trail` 视图。ADR-0023 的"执行真相只有一份"这句话本身没错，
错在把真相放在"某个 run 的第几步"上——真相是**事件**，事件不需要父亲。

### 三、目标（goal）挂在会话上

计划不再是 step 的一种 kind，而是会话的一个属性：

- 一个会话同时最多一个目标；
- 六态：`active` / `paused` / `blocked` / `budget_limited` / `usage_limited` / `complete`；
- 模型侧三个工具：`create_goal`（可带 token budget）、`get_goal`、`update_goal`；
  其中 `update_goal` **只能标 `complete` 与 `blocked`**——暂停、恢复、清除、改写是操作员的按钮，
  模型不该能悄悄改掉这一段对话的走向；
- **只有 `active`** 的目标在每个带人类角色的回合注入一行"进行中的目标：…"；其余状态不注入，
  于是"操作员叫停"是持续生效的，直到有人恢复它；
- `/goal` 命令族：`start`（`set` / `create`）、`edit`、`pause`、`resume`、`complete`（`done`）、
  `block`（`blocked`）、`clear`、`status`；
- **`/new` 与 `/reset` 清掉当前会话的目标**——它们本来就意味着"从头开始"。

删 `agent_run.plan_ref` 与 `Agent_step` 里 `kind = 'plan'` 的那一类行。

### 四、审批与投递都按对话地址，不按 run

- `approval_request` 去掉 `run_id`，改存 `session_key` + `channel` + `chat_ref`（回到哪段对话）；
- 批准之后：**在同一个会话里开新一轮**，prompt 是 recap + 审批结论；不是恢复旧 run；
- 删 `agent_run_delivery`、`agent_run.result`、`agent_run.notify_policy`；结果回到发起那段对话；
- 定时任务的结果投递用任务自己的 `channel_ref`（它已经这么存了）。

"同一件事可以在多个通道被找到"这个能力随之消失。它本来就是续跑设计带来的副产品：
一件事能跨请求活着，才需要登记它在哪几个通道出现过。OpenClaw 靠会话寻址，不靠事件寻址。

### 五、预算是 goal 的属性

`budget_limited` 是 goal 的状态，不是 run 的状态。**只有 `/goal resume` 会开新窗口，
而且只对 `budget_limited` 开**——其余状态（含 `complete`）恢复时用量继续累计；
`clear` 把目标整个删掉，那才是这一份账的终点。

保留"一轮最多 N 次工具调用"这道闸，但它**不是额度**，是失控熔断，理由不对称：
OpenClaw 有 `agents.defaults.timeoutSeconds`（默认 48 小时）兜住跑飞，我们没有 per-run 超时，
所以需要一个更早的止损点。这条要写进配置注释，避免下一个人把它当预算调大。

## Considered Options

**留着 run，只把 `SUSPENDED` 与 `resume` 删掉**
否决。状态机一删，剩下的 `agent_run` 就是一张"每轮一行的空表"，它唯一还有用的三列
（`plan_ref` / `result` / `notify_policy`）都该挂别处。留一张只为兼容而存在的表，
代价是每个读代码的人都要问一次"这张表到底谁在写"。

**把 run 改造成"工作流"，学 Temporal**
否决，理由与 ADR-0020 相同：单实例形态、为一张表引入一整套运行时。

**只做审计重建，计划与投递维持现状**
否决。计划的现状是"step 里的一类行 + `plan_ref` 指当前版本"，它比 goal 复杂得多，
而且没有"暂停 / 阻塞 / 达成"这三个人能按的按钮；投递的现状是"一件事可以在多个通道被找到"，
那是续跑的副产物。留一半，等于把最贵的两处留着。

## Consequences

迁移顺序**先建替代、再删旧的**，否则中间态是"审计断了、结果丢了"：

1. 审计事件流水（`audit_event` 上线，`ToolCallAudit` 改写到它）—— **已落地**
2. goal 挂会话（模型工具、`/goal` 命令族、每回合注入、`/new` 清目标）—— **已落地**
3. 审批与投递按对话地址（拆掉续跑，删两个 `runId`）—— **已落地**
4. 预算并进 goal —— **已落地**
5. 删 `agent_run` / `agent_step` / `GET /api/runs` 与相关代码 —— **已落地**（`V24`）

五步都走完了。落地的终点：`claw4j_run` 只剩 `agent_schedule` / `approval_request` /
`audit_event` 三张表；一轮的编号是本地生成的 `runId`，不落表；`GET /api/runs` 没了，
按 id 取值这个能力随之消失（记在 `docs/agents/guardrails.md` 的已知偏差里）。

配置面同时收敛两处，都要在实施时删掉，留着就是"配了不生效"：

- `claw4j.audit.retention`（90 天）删除。30 天 + 10 万行是照 OpenClaw 的**固定上限**，不是部署参数；
- `claw4j.run.budget` 整段重写：token 额度归 goal，工具调用次数改名成熔断（票 04）；
- `claw4j.run.enabled` 改名成 `claw4j.persistence.enabled`——它管的是"要不要装审计 / 审批 / 调度
  这几样需要库的东西"，而 run 已经不在里面了（票 05）。

对既有文档的影响：

| ADR | 关系 |
| --- | --- |
| 0016（run / step、暂停与续跑） | **推翻"暂停与续跑"**；保留"一次模型循环 = 一条记录"的粒度观 |
| 0017（幂等与重放） | 不变。意图表按"会话 + 参数指纹"判，与 run 无关 |
| 0018（异步结果怎么回到人） | 原则保留，锚点从 run 换成对话地址 |
| 0020（平台能力挂 run） | **原则保留**（不另开旁路），锚点从持久 run 换成"同一条执行路径 + 会话" |
| 0023（审计长在 step 上） | **推翻**。审计不在 step 上，在独立的事件流水上 |
| 0024（不钉任务进度前缀） | 结论不变，理由更强：run 根本活不过一轮 |
| 0025（预算挂会话） | 再进一步：预算成为 goal 的属性 |
| 0026（会话边界 / lane / 命名） | 不变。goal 就挂在它管的那个会话上 |

## 重开条件

- 真出现"一件事必须停三天等外部系统回调"的诉求时，重新引入的应该是**工作流**（有状态、有租约、
  有补偿），不是把 run 复活。复活 run 只会把这七类职责再长回来一遍。
- 真需要按"一件事"做成本分摊时，重新讨论。当前口径是"会话 + 目标"。

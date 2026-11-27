# 标准实例这一批的实跑结果（2026-11-27 23:15–23:30）

实例：`--spring.profiles.active=local --claw4j.channel.http.users='*' --claw4j.advisor.cache.enabled=false
--claw4j.skills.directory=examples/skills --claw4j.subagents.directory=examples/subagents
--claw4j.agent.directory=examples/agents`，库 `claw4j_accept_33`。
人设跑了两轮：`sales`（messaging 档位）→ `ops`（full 档位），因为 B3/B4/B5 要 `Skill`/`Bash`。
日志 `/tmp/claw4j-batch.log`（sales）、`/tmp/claw4j-batch-ops.log`（ops）。

## 绿

| 用例 | 证据 |
| --- | --- |
| A4 人设 | `这个部署的 agent 是 sales（…），人设来自 examples/agents；时区 Asia/Shanghai；工具画像 MESSAGING（来自 人设文件的 tier）`；换 `ops` 后同一行变 `FULL` |
| B3（数字与口径） | 华东 128,400 / 华北 31,500 / 华南 26,000 / 合计 185,900；口径 `sum(order_item.amount)` 且排除 `CANCELLED` |
| B4 技能脚本 | `ops` 下 `Skill` + `Bash` 各 1 次成功，脚本输出逐字（`# 售后政策摘要样本`、华南 5 天…） |
| B5a capacity-triage | 堆 259MB/8192MB 约 3.2%、线程 143，`Task` ok；不给扩容数值 |
| B5b weekly-digest | 185,900 + 三区，`Task` ok |
| B6.0 知识库启动行 | `111 份文档…现有 440 段`、`混合检索 开、重排 开` |
| B6.1 单跳 | 华东 7 天 / 华南 5 天、按签收日、带 `[1]` `[5]` |
| B6.2 跨文档 | 按折后价/实际金额退，不是标价 |
| B6.3 查不到 | 明确"查不到"，并把"常规备货量 300 台"界定成备货策略而非库存 |
| D3 种子日期 | 128400 等与文档一致，种子未过期 |
| E1 MCP 掉线 | `java-http → DOWN(15 秒后重试)`，应用照常起 |
| F1 模型层启动行 | `窗口=65536`、`模型角色：main=deepseek/deepseek-chat；utility=继承 main`、压缩行同为 65536、`route=deepseek/deepseek-chat` |
| I1 审计四行 | 4 行按 `sequence` 倒序、13 位毫秒、`actor` 是对象、`runId`/`argsDigest` 各 0 次 |
| I2 形状 | `agent_run` 无 `toolName/direction/channel`；`tool_action` 有 `toolName` |
| I3 参数冲突 | 四种组合全 `400` |
| I4 翻页 | 页1 2 条 `nextCursor="137"`、页2 2 条 `nextCursor=null` |
| I5 幂等 | `INSERT 0 1` / `INSERT 0 0` / `having count>1` 空 |
| I7 调用编号 | `toolCallId` 只在 `tool_action`，`sha256:…`；`agent_run` 为 null |
| I8 子 Agent 独立运行 | `subagent:weekly-digest` 6 行、`subagent:incident-triage` 6 行、`subagent:capacity-triage` 4 行，各自 executionId |
| J1 会话列表 | 只有自己的 1 条、id `claw4j:user:j1:http:http:j1`、title 是首条用户消息 |
| J2 逐轮 | 4 条正序（user/assistant 交替）、`hasMore=false` |
| J3 归属 | 别人的 id **404**；再问一句仍同一 id、`createdAt` 不动、`lastActivityAt` 前进、2 条→4 条 |

## 红

| 用例 | 现场 | 判断 |
| --- | --- | --- |
| **B1 两个时间窗** | 上周（`t1`）✅ 128,400；本周（`t2`）、三周前（`t3`）都答"我手上没有能查成交额的工具"，**这两轮日志里没有 `工具检索 hybrid` 行**（整个 `sales` 实例只有 `t1` 与 `k1` 两次检索） | 模型这一轮没检索就下结论。同一句式 `t1` 搜了、`t2`/`t3` 没搜 → 先按**方差**记，重复几次拿到比例再谈改机制 |
| **B3 的 Skill 那半** | 数字与口径全对（185,900、`sum(order_item.amount)` 排除 `CANCELLED`），但 `Skill` 一次没调（`querySales` 1 次） | **假红，题面错**：口径来自人设 `examples/agents/glossary/metrics.md`，知识库 `glossary.md` 开头就写"只给定义，不给数字"，`querySales` 也能出数——问口径逼不出 Skill。题面已改成"按固定格式出周报" |
| **B9 检索增量** | `calls` 全程 6 次、全 `hit`、`results` 次次顶满 5（机制对）；问"北京今天天气"那轮（`http:b9b`）**没有检索行**，答"我这边能用的工具里没有天气数据源" | 机制 ✅ / **触发** ❌：这一轮模型没检索，跟检索质量无关 |
| **G1 计划落库** | 这一轮 `Read` 1 次、`TodoWrite` **0 次** | **假红，题面错**：我给的是单步题（问文件第一行是不是 `---`），本来就不该列卡；文档 G1 原本没写题面，已补一条多步骤的 |
| **G2 意图落库** | 模型回"已记下"，`claw4j_session.standing_intents` **0 行**，整轮 0 次工具调用、**也没有检索行** | 与 B1/B9 同型：没检索就没有 `intent` 工具，于是嘴上答应。归触发类 |

## 没跑

| 用例 | 为什么 |
| --- | --- |
| A3 通道准入 | 要另起 `users=t1,ethan` 的窄名单实例 |
| E2 超时 | `simulateSlowCall` 在 `claw4j-example-mcp-server`（`java-http` 那个 MCP），本环境该 server DOWN |
| E3 预算 | 票 33 已实测过（撞上限后用户收到中文那句、日志 `判定依据 message`），本次没有重跑 |
| I6 消息行 | 要 `--claw4j.audit.messages=true` 重启 |
| I9 五个计数 | 未逐条对 |
| G3 提示词预算 | 未跑 |
| K6 的截断观测 | 7000 字下照常回答（✅），但"注入那份截到 6000 字符"在系统提示里，日志看不到；只有代码路径确认（`MAX_INDEX_CHARS=6000`），没有直接观测证据 |
| C / E2 / F2–F8 / H | 要换实例参数或停 MCP，属另一批 |

## 两条踩到的前提，已回填文档

1. **B3 / B4 / B5 要在 `--claw4j.agent.default=ops`（`tier: full`）下跑**。`sales` 是 `messaging` 档位，
   实测它在 `sales` 下答"没有 summarize 技能，也没有能跑脚本的工具"——那是**真话**，`Bash` 被档位挡住了。
   文档「0. 共同前提」那行本来就是 `default=ops`，是我第一轮起错了人设，不是文档缺。
2. **`/api/sessions/{id}/messages` 的字段是 `turns`，不是 `messages`**。按 `messages` 取会得到 0 条，
   看起来像"逐轮读不出来"，其实是字段名。已补进 `README-capability-tests.md` 的 J2 判据。

## 这一批的结论

五条红里，**两条是我题面错**（B3 只问口径、G1 是单步题），**三条是同一处**：模型没先检索就宣布
"我没有这个工具"（B1 两个窗、B9b、G2）。没有一条能指向"检索质量"或"落库坏了"。

所以这一批**不需要为它改机制**。要动机制之前先补证据：B1 三个窗各问 5 次、B9b 与 G2 各问 5 次（每次换用户），
数出"几次里挂几次"。挂 5/5 是机制缺口，挂 1/5 是模型方差，两者的修法完全不同。

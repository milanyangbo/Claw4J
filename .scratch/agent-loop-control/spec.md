# Agent 循环的两件控制能力：能被叫停，能被看见

`spring-ai-agent-utils` 里有一对件，本仓一个都没接：`InterruptAdvisor`（协作式取消）
与 `ToolCallListener`（工具调用观测）。这一批只做这两件，不扩。

## 为什么要接

| 它解决什么 | 我们现在的状态 | 判据来源 |
| --- | --- | --- |
| 一轮跑太久停不下来 | 只有 `claw4j.agent.tool-call-limit`（**次数**）与目标的 token 预算（**钱**），**没有墙钟** | ADR-0027:108「OpenClaw 有 `agents.defaults.timeoutSeconds`，我们没有 per-run 超时」 |
| 工具调用在外面看不见 | 有 `MeteredToolCallback`（计数 / 审计 / span），但那是**我们自己的**装饰器，部署方想插一个观测点只能改代码 | — |

两条都已按 `AGENTS.md`「先找现成的」核过 jar：`org.springaicommunity.agent.advisors.InterruptAdvisor`
与 `org.springaicommunity.agent.tools.ToolCallListener(s)` 就在已经在用的那个包里，不需要引新依赖。

## 共同判据

1. **默认路径不变**：不配任何新键时，除了多一次 clock 调用，行为与今天逐字相同。
   —— 唯一的例外是超时有默认值（见票 01 的论证），这一条要在票面写清而不是含糊过去。
2. **中断不重试**。`InboundDispatcher#failed` 对任何异常都会「放回队列、30 秒后再跑」，
   一轮 10 分钟的超时被重试三次就是半小时——比没有超时更坏。
3. **配置键进 `application.yml` 的 `claw4j.agent` 段**（与 `tool-call-limit` 同处），
   两份 local 配置不重复写。
4. 门禁三条照旧：`./scripts/verify.sh` 全绿、票面用例可 grep 到、`docs/capability-tests.md` 对应节跑通。

## 两张票

- `01-interrupt-advisor.md` — 整轮墙钟 + 协作式取消
- `02-tool-call-listener.md` — 部署方的工具观测接缝

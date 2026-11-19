# 01: 整轮墙钟——用库的 `InterruptAdvisor` 让一轮停得下来

**What to build:** 给每一轮加一个墙钟上限（`claw4j.agent.turn-timeout`），做法照库的
`InterruptAdvisor`：**每次向模型发起新请求之前**轮询一个信号，到点就抛 `TurnInterruptedException`
结束这一轮。到点之后，这一轮要**有回复地结束**，不是抛异常穿过队列层。

**Blocked by:** 无。

**Type:** task

**Status:** resolved（2026-11-19）

## 对照表（`AGENTS.md` 要求：先出这四列，再动手）

| OpenClaw 的机制 | 我们现在 | 差在哪 | 改哪边 |
| --- | --- | --- | --- |
| `agents.defaults.timeoutSeconds`（默认 48 小时），由 `runEmbeddedAgent` 的中止计时器执行（ADR-0027:108 已记） | 只有 `claw4j.agent.tool-call-limit`（**次数**，默认 6）与目标 token 预算（**钱**）；**没有墙钟** | 一个"在工具里打转但每次都成功"的循环，次数到 6 就停；但一个"单次工具调用卡住"（`exec` 跑了个不返回的命令、MCP 端点挂住）次数还没到，**永远不返回** | **加**：`claw4j.agent.turn-timeout`，用库的 `InterruptAdvisor` |
| `chat.abort { runId }`：按 runId 中止（ADR-0027 记录，`.scratch/adr-0027/05-delete-run` 判"不做"） | 没有 | 用户主动停止这一条**不在本票**（ADR-0026 票 04 已判不做 `/stop`） | 不改。本票只做墙钟，`interruptSignal` 只有一个来源 |

**默认值为什么不照抄 48 小时**：OpenClaw 那个数是给"跑几十小时的长任务"兜底的，我们的轮次是
交互式的（有人在通道那头等）。48 小时等于没有，而这一票的全部意义就是"停得下来"。
取 **10 分钟**：`tool-call-limit` 是 6 次往返，正常几十秒到几分钟；10 分钟覆盖"读大文件 + 六次往返"
的现实上限，同时让"跑飞"在一个可等的窗口内结束。写 `0` 关掉。

## 三处必须一起想清楚

### 1. signal 必须按轮，所以 advisor 每轮传一次

`InterruptAdvisor` 的 signal 是构建期固定的 `BooleanSupplier`，而截止时刻是**每一轮**算的。
`ChatClient` 是单例、通过 `defaultAdvisors` 挂的，全局挂一个实例就只能表达"全部署停"。

所以走 **per-request**（`ChatClient.prompt().advisors(...)`）。已核实框架行为：
`DefaultChatClient.buildAdvisorChain()` 把 default 与 request 的 advisor 一起交给
`DefaultAroundAdvisorChain.Builder#pushAll`，而 `pushAll` 末尾调 `reOrder()`（`OrderComparator.sort`），
**排序对两者一视同仁**。库的 `DEFAULT_ORDER` 是 `HIGHEST_PRECEDENCE + 400`，我们的工具循环是 `+300`
——排在内层，于是**每次工具往返之后、下一次模型请求之前**都会重新 `before` 一次，正是它文档里写的形状。

### 2. 超时不能被当成可重试的失败

`InboundDispatcher#failed` 对任何异常都「放回队列、30 秒后再跑，最多三次」。
一轮 10 分钟的超时被重试三次就是半小时，**比没有超时更坏**。

所以中断在 `ChatClientAgentPort` 里**就地收口**：捕获 `TurnInterruptedException`、审计落
`RUN_TIMED_OUT`（这个错误码已有，不改 schema）、`closeTurn` 照常写、返回一句说明。
不 rethrow 到队列层。

### 3. 落审计要落对码

`AuditErrorCode` 已经有 `RUN_TIMED_OUT`（运行超时）与 `RUN_CANCELLED`（运行被取消）。
现在的 `catch (RuntimeException | Error)` 一律记 `RUN_FAILED`。要在 catch 里先判类型。

## TDD 用例清单

| 判什么行为 | 用例名 | 先红的样子 |
| --- | --- | --- |
| 到点就中止，且这一轮**有回复**（"已中止"），不是抛异常 | `ChatPipelineTest#turnStopsAtTheDeadlineWithAReason` ✅ | 现在没有墙钟：慢工具会一直跑到工具自己返回 |
| 超时**不重试**：`reply()` 返回一个 Turn，不抛 | `ChatPipelineTest#timeoutIsNotRethrownToTheQueue` ✅ | 抛出去就会被 `failed()` 放回队列，三次 = 半小时 |
| 写 `0` 关掉：慢工具照常跑完 | `ChatPipelineTest#zeroTurnTimeoutKeepsTheOldBehaviour` ✅ | —— |
| 审计落 `RUN_TIMED_OUT`，不是 `RUN_FAILED` | `ChatPipelineTest#timeoutIsAuditedAsTimedOut` ✅ | 现在一律 `RUN_FAILED`，运维分不清"跑飞"和"模型报错" |
| 检查发生在**每次工具往返之后**，不是只在开跑时 | `ChatPipelineTest#deadlineIsRecheckedBetweenToolRounds` ✅ | 挂在 tool loop 外面就只查一次，一次长工具循环照样跑完 |

**复核：2026-11-19 `./scripts/verify.sh` 全绿。** 五条都落在 `ChatPipelineTest`（26 条）里。

## 验收判据

| 判什么 | 判据 |
| --- | --- |
| 默认值生效 | 不配任何键，10 分钟到点中止；日志里有一行说明 |
| 关得掉 | `claw4j.agent.turn-timeout: 0` 之后慢工具跑完 |
| 不重试 | 超时那一轮 `attempts` 不涨（日志里没有"处理失败（第 n / 3 次）"） |
| 门禁 | `./scripts/verify.sh` 全绿 |

## 缺口

- **用户主动停止（`/stop`、`chat.abort`）不做**：ADR-0026 票 04 与 ADR-0027 已判，本票不重开。
  `interruptSignal` 的接法留着一个来源：本轮截止时刻。将来真要做，把 signal 改成
  「截止时刻到了 **或** 这个 runId 被要求停止」即可，位置不变。
- **流式的开头没有被"每轮重查"覆盖**：中断在流式里以 Flux 的 error signal 到达，
  收口与非流式共用一处；但"流式里第二次模型请求之前会不会重查"没有单独用例（模型流式路径
  只发一帧文本，造不出两轮往返）。
- **流式没有独立验证**：中断在流式里以 Flux 的 error signal 到达，收口逻辑与非流式共用一处
  （`catch (RuntimeException | Error)` 里那条判型分支），但"流式能不能立刻停"取决于模型 SDK
  何时把信号读出来——本票只保证"停在两次模型请求之间"。

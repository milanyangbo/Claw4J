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

## 只接库的能力，不接管它之后发生的事

`InterruptAdvisor` 本身只做一件事：每次模型请求之前问一次 signal，为真就抛
`TurnInterruptedException` 结束这一轮。**抛出去之后怎么处理不归它管**，库的文档明写"由 `call()` 的
调用方决定（丢弃 / 部分保存 / 安静结束）"。

这一票只做到"能停"这一步，不改后续语义：

| 做了什么 | 没做什么（以及为什么） |
| --- | --- |
| 每轮传一个 `InterruptAdvisor`，signal 是这一轮的截止时刻 | 不改 `InboundDispatcher#failed` 的重试：超时对它是"一次普通失败"，三次重试是既有的失败语义 |
| `claw4j.agent.turn-timeout` 一个键，`0` 关掉 | 不新增审计错误码落点、不加自定义收尾文案、不给流式补白——那些都是"接管" |
| 到点由库抛异常结束这一轮 | 不做 `/stop`（ADR-0026 票 04 已判），不做按 runId 中止（ADR-0027） |

**signal 必须按轮，所以 advisor 每轮传一次**：库的 signal 是构建期固定的 `BooleanSupplier`，
而截止时刻是每一轮算的。`ChatClient` 是单例、走 `defaultAdvisors` 的话只能表达"全部署停"。

per-request 传的 order 仍然生效——已核实框架：`DefaultChatClient.buildAdvisorChain()` 把 default 与
request 的 advisor 一起交给 `DefaultAroundAdvisorChain.Builder#pushAll`，而 `pushAll` 末尾的
`reOrder()`（`OrderComparator.sort`）对两者一视同仁。库的 `DEFAULT_ORDER` 是
`HIGHEST_PRECEDENCE + 400`，我们的工具循环是 `+300`，排在内层，于是**每次工具往返之后、
下一次模型请求之前**都会重新问一次。

## TDD 用例清单

| 判什么行为 | 用例名 | 先红的样子 |
| --- | --- | --- |
| 到点由库抛 `TurnInterruptedException` 结束这一轮，不再发下一次模型请求 | `ChatPipelineTest#deadlineStopsTheTurnBeforeTheNextModelRequest` ✅ | 现在没有墙钟：慢工具会一直跑到工具自己返回 |
| 检查发生在**每次工具往返之后**，不是只在开跑时 | `ChatPipelineTest#deadlineIsRecheckedBetweenToolRounds` ✅ | 挂在 tool loop 外面就只查一次，一次长工具循环照样跑完 |
| 写 `0` 关掉：钟推得再远也照常答完 | `ChatPipelineTest#zeroTurnTimeoutKeepsTheOldBehaviour` ✅ | —— |

**复核：2026-11-19 `./scripts/verify.sh` 全绿。** 三条都落在 `ChatPipelineTest` 里。

## 验收判据

| 判什么 | 判据 |
| --- | --- |
| 默认值生效 | 不配任何键，10 分钟到点抛 `TurnInterruptedException` |
| 关得掉 | `claw4j.agent.turn-timeout: 0` 之后慢工具跑完 |
| 门禁 | `./scripts/verify.sh` 全绿 |

## 缺口

- **超时之后走的是既有失败路径**：异常穿到 `InboundDispatcher#failed` → 放回队列、30 秒后再跑、
  最多三次。也就是说一个必然超时的请求会被跑三遍。要做成"超时即终止、给用户一句说明"，
  那是改失败语义，不属于"接库的能力"，另开票。
- **流式没有独立用例**：中断在流式里以 Flux 的 error signal 到达，与非流式共用同一条
  `catch (RuntimeException | Error)`；"停在两次模型请求之间"这件事两条路径都在，
  但流式那条没有单独造出两轮往返。
- **用户主动停止（`/stop`、`chat.abort`）不做**：ADR-0026 票 04 与 ADR-0027 已判。
  signal 的接法留着位置：将来把「截止时刻到了」换成「截止到了 **或** 这个 runId 被要求停止」即可。

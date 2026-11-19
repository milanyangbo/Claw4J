# 02: 工具观测接缝——接库的 `ToolCallListener`

**What to build:** 让部署方能插一个 `ToolCallListener` bean，观测每一次**真实执行**的工具调用
（前后 + 成功/失败 + 收尾）。装饰由库的 `ToolCallListeners.wrapAll` 做，我们只决定套在哪一层。

**Blocked by:** 无。

**Type:** task

**Status:** resolved（2026-11-19）

## 先说清它在我们这儿**不是**什么（这一节是这张票的主要产出）

核过 jar 与框架之后，有三件事必须先写下来，否则接进来会被当成"又多一层观测"：

1. **它替代不了 `MeteredToolCallback`**。listener 的回调签名是
   `beforeCall(String toolName, String toolInput)`——**没有 `ToolContext`**。而我们的审计行要
   `runId` / `principal` / `sessionKey`，三样都只在 `ToolContext` 里。所以计量 + 审计 + span 仍然留在
   `MeteredToolCallback`，listener 套在它外面。
2. **`onError` 对我们没有行为增量**。框架的 `DefaultToolExecutionExceptionProcessor` 默认
   `alwaysThrow=false`（`DefaultToolCallingManager` 的静态字段就是 `builder().build()`），也就是
   **工具异常今天已经作为结果回灌给模型**了。listener 返回字符串只是把同一件事换个地方做，
   却多了一处「谁决定错误策略」——不引入。
3. **它是有代价的接缝**：库的文档明写 listener 抛异常**不被兜住**——`beforeCall` 抛会阻止工具执行，
   `afterCall` 抛会丢掉一个**已经成功**的结果（副作用已经发生，模型可能重试）。所以它只适合
   "自己绝不抛"的观测。

## 那为什么还要接

因为**部署方今天没有地方插观测**。`MeteredToolCallback` 是本仓内部的接缝（要改代码才能加一个动作），
而 listener 是库定义的公开扩展点：接一个 bean 就能把 `tool_use` / `tool_result` 推给 UI、写到外部
审计、或按工具算耗时分布，不用碰任何工具实现。这与 `docs/design/extension-points.md` 里
"删掉那条依赖，核心照常构建、照常启动"是同一个判据——**没有 bean 就没有这一层**。

## 套在哪一层（顺序是有理由的）

```
ApprovalRequiredToolCallback（审批闸，最外）
  └─ ToolCallListeners.wrapAll（本票）
       └─ MeteredToolCallback（计量 + 审计 + span）
            └─ 真工具
```

- **在审批闸之内**：被审批拒掉的调用不是"被调了"（审计里它是 `blocked`，与 `tool_failed` 分开），
  listener 不该看见它。
- **在计量之外**：listener 量的是一次完整调用（含计量与审计那点开销），这正是部署方想看的数。

## TDD 用例清单

| 判什么行为 | 用例名 | 先红的样子 |
| --- | --- | --- |
| 有 bean 时，每次真实执行都过一遍 listener（名字与入参原样） | `ObservabilityTest#listenerSeesEveryExecutedToolCall` ✅ | 现在插不进去 |
| 没有 bean 时那层不存在：清单里还是原来那些对象 | `ObservabilityTest#noListenerBeanLeavesTheToolListUntouched` ✅ | 这条用 `isSameAs` 钉住：没有 bean 时不该多一层对象 |
| 套上之后工具定义与元数据不变（名字、schema、`returnDirect`） | `ObservabilityTest#listenerKeepsTheToolDefinitionIntact` ✅ | 装饰器改坏定义的话，模型看到的 schema 会变 |
| 失败照常抛给框架的处理器，listener 只是看见 | `ObservabilityTest#listenerObservesFailureWithoutSwallowingIt` ✅ | 吞掉就成了第二处错误策略 |

**复核：2026-11-19 `./scripts/verify.sh` 全绿。** 四条都落在 `ObservabilityTest`（21 条）里；
装配那一行在 `AgentAutoConfiguration#toolCatalog`，包装逻辑抽成 `withListener` 让这四条能直接验。

## 验收判据

| 判什么 | 判据 |
| --- | --- |
| 零行为变化 | 不注册 listener bean 时，`ObservabilityTest` 与 `ToolGovernanceTest` 全绿且用例数不变 |
| 文档 | `README-adding-capabilities.md` 加一节"接一个工具观测器"（bean + 四个回调各自什么时候来 + **不许抛**） |
| 门禁 | `./scripts/verify.sh` 全绿 |

## 缺口

- **装配这一步没有集成用例**：四条验的是 `withListener` 本身（定义不变、失败不吞、null 原样），
  "`toolCatalog` 真的把它接在审批闸之内"靠代码本身一处调用点保证。要起一个真容器来验这段装配
  的成本远高于它能抓到的问题——那段只有一个 `if`。
- **子 Agent 的工具调用不经过这一层**：子链的清单由 `SubagentAutoConfiguration` 自己收
  （`collectToolCallbacks` 的另一条调用点）。要覆盖得把那处的装配也串上，属于另一张票。
- **`afterCompletion` 在 `beforeCall` 抛时不会被调用**（库的行为），所以 listener 不能靠它做配对清理；
  文档里写清。

# 票 19：子链的工具被包两层，同一次调用写两遍审计

**Status:** resolved

## 由来

2026-11-24 全量复跑时，`claw4j.audit.duplicates` 不是 0：每委派一次子 Agent 恰好涨 2。
加了临时探针（`DUP-PROBE` / `TOOL-PROBE`，已删）后拿到铁证：

```
TOOL-PROBE enter systemStatus id=call_00_uJYniISKtyJI63SSvuet5249   ← 同一次调用进了两遍
  stack=[MeteredToolCallback, DefaultToolCallingManager, …]
TOOL-PROBE enter systemStatus id=call_00_uJYniISKtyJI63SSvuet5249
  stack=[MeteredToolCallback, UntrustedToolCallback$Sanitizing, MeteredToolCallback, …]
```

同一个 `toolCallId` 被写了两遍 started / finished。第二遍被 `(source_id, source_sequence)` 的
唯一键挡住（`written == 0` → `duplicates` 计数），所以**数据没错，但每次子链调用都多写一遍**：
多一次 INSERT、指标虚高、`duplicates` 从"重放信号"变成"每条子链调用都有"。

## 根因

`AgentAutoConfiguration.collectToolCallbacks` 无条件包一层：

```java
.map(callback -> (ToolCallback) new MeteredToolCallback(callback, registry, audit, tracer, observations))
```

子链的 `subagentToolCallbacks` 也走这条收集路径，而它拿到的 `ToolCallbackProvider` 交出来的工具
**已经带着一层包装**（`Sanitizing(Metered(原始))`），于是变成
`Metered(Sanitizing(Metered(原始)))`——外面那层是子链包的，里层那层是提供方带的。

父链只包一层，是因为它从 bean 那一侧拿到的是未包的实例。同一条收集路径，两侧的输入不同，
包出来的层数就不同——而这件事**没有任何地方会报错**，只表现为 `duplicates` 慢慢涨。

## 裁决

**无需 ADR**：让收集变成幂等（已经是 `MeteredToolCallback` 的不再包）。不改契约、不改 schema。

## 改哪

`AgentAutoConfiguration.collectToolCallbacks` 的 map 里加一个 `instanceof` 判断。

## TDD 用例清单

| 判什么行为 | 用例名 | 先红的样子 |
| --- | --- | --- |
| 收集对已包过的工具不再包一层（同一实例原样返回） | `SubagentDelegationTest#collectToolCallbacksDoesNotWrapAnAlreadyMeteredTool` | 改前 `assertThat(twice).isSameAs(once)` 红（拿到的是新的包装实例） |

## 缺口

- 端到端只验到"子链委派一次之后 `duplicates` 不再涨"，没有为它单开一条用例：
  `duplicates` 是进程级计数，落成用例要起完整容器；本票用探针实测 + 上面的单测两头夹住。

## 复核

复核：2026-11-24 `./scripts/verify.sh` 全绿。**两头夹住**：

- 单测：`SubagentDelegationTest#collectToolCallbacksDoesNotWrapAnAlreadyMeteredTool` 先红
  （`isSameAs` 失败：拿到的是新的包装实例），改完绿；同一用例里还钉了线上那条形状
  ——`Sanitizing(Metered(…))` 再收集也原样返回。
- 实测：修复前每委派一次 `claw4j.audit.duplicates` **+2**（`dup-probe2/3.log`）；
  修复后连派两次（capacity-triage + weekly-digest）全程 **0**（`dup-fixed2.log`）。

修的是两层：`collectToolCallbacks` 认得出"已经包过"，`UntrustedToolCallback` 加 `unwrap`
让它能穿透净化器看到里面那层。只看最外层类型对线上那条路径无效——第一次修就是这么漏的。

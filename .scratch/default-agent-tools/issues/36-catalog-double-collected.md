# 票 36：工具清单被自己的台账重复收一遍

**Status:** in_progress（2026-11-27 23:05 开票）

**依据**：ADR-0030（工具策略分层）、ADR-0010（模块依赖方向）。**无需新 ADR**：这是修复一处循环收集，
不改变任何架构决定。

## 现象

触发 `incident-triage`（只写了 `disallowedTools`、没写 `tools` 的那种子 Agent 定义）时，子 Agent
**启动即失败**，父链把库的报错原样回给用户：

```
结论：incident-triage 没能跑起来，所以没有初筛结论。
派发时工具注册表报重名冲突（systemStatus、Read、BashOutput 等一批工具各注册了两份），subagent 启动即失败。
```

同一次运行里的两个数对得起来：子 Agent 的工具面 **102 个名字、每个正好两遍**（`world_clock` 2、
`word_define` 2、`weather_now` 2…），而父链那一行是 `Indexed 66 tools`（按名字建索引，重名被吃掉，
所以父链看不出问题）。

## 根因

`AgentAutoConfiguration.collectToolCallbacks` 收两个来源：

```java
callbacks.orderedStream().forEach(collected::add);                                  // 单个工具 bean
providers.orderedStream().forEach(p -> collected.addAll(List.of(p.getToolCallbacks())));  // 成组工具
```

`ToolCatalog` 恰好**是这条收集路径的产物，同时又实现了 `ToolCallbackProvider`**
（`claw4j-capability/.../tool/ToolCatalog.java:38`），于是它也出现在 `providers` 里，把刚收好的那一份
再交一遍 —— 每个工具两份。

父链没炸是因为下游按名字建 map（披露层 `Map<String, ToolCallback>`），重名互相覆盖；子链把这份清单
直接交给 `ChatClient.defaultToolCallbacks(...)`，Spring AI 建工具注册表时按名字校验唯一性，于是启动失败。

## 做什么

`collectToolCallbacks` 收 provider 时跳过 `ToolCatalog` 自己：**台账不是货源**。这一句要写在注释里，
否则下一个人看到"为什么特判一个类型"会以为在打补丁。

## 不做什么

- 不在 `ToolCatalog` 上去掉 `ToolCallbackProvider`：MCP server 那条路按 provider 取工具（`AgentAutoConfiguration:241`
  的注释写了这个 bean 同时是 MCP 的货源），改了会断另一头。
- 不做"按名字去重"的兜底：那会把"两个不同的工具撞了名"这种真配置错误一起吃掉。名单唯一性由测试钉住。

## 验收

| # | 验什么 | 判据 |
| --- | --- | --- |
| 1 | 收集不会再把自己交的那份收第二遍 | 单测：把一个 provider 交回刚收好的那份（模拟 `ToolCatalog`），结果仍是 1 个而不是 2 个 |
| 2 | 名字在收集结果里唯一 | 单测断言 `doesNotHaveDuplicates` |
| 3 | 子 Agent 真跑得起来 | 实测：派 `incident-triage`，日志里工具面每个名字只出现一次，且父链拿到的是子 Agent 的结论而不是报错 |

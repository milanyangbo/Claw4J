# 长期记忆：记的是"这个人是谁"，与会话记忆是两条轴

> 设计说明：为什么这么做、否决了什么。要看结论去 [README](../README.md)，要看决策去 [ADR](../adr/)。
> 相关的另一条轴在 [会话记忆](session-memory.md)。

会话记忆记得住"这一轮接得上上一轮"，记不住"上周他说过他偏好 `static final`"。后者的麻烦在于
它**不属于任何一段对话**：压缩按"对话里说了什么"概括，概括完这类事实就没了；换一段对话更彻底，
连事件都不在同一个会话里。所以它是另一层：模型自己判断哪句话值得永久留下，写进文件，下次还在。

## 库给了什么，缺什么

用 `spring-ai-agent-utils` 的 `AutoMemoryTools`（6 个文件工具：`MemoryView` / `MemoryCreate` /
`MemoryStrReplace` / `MemoryInsert` / `MemoryDelete` / `MemoryRename`）与 `AutoMemoryToolsAdvisor`
（把工具与一段记忆系统提示注入每一轮）。本项目已经引了这个包，不需要新依赖。

它缺的只有一件：**记忆根目录是构建期一个固定字符串**。`Builder` 只有四个旋钮
（`order` / `memoriesRootDirectory` / `memorySystemPrompt` / `memoryConsolidationTrigger`），
6 个 `@Tool` 方法都不吃 `ToolContext`——多用户部署下所有人共用一份记忆。

另外两件它按自己的单用户假设处理、在本仓必须另说的事：注入发生在 `before()` 里直接往
`toolOptions` 塞 callback（绕过工具清单那一层），以及落盘是明文（会话表那边有脱敏，这里没有）。

## 形状

```
claw4j.agent.memory.directory  ← 配了才有，不配就没有（不给默认值）
└── <安全化 userId>-<sha256 前 12 位>/
    ├── MEMORY.md            ← 索引，模型自己维护
    └── <事实>.md            ← 带 YAML front matter（name / description / type）
```

`PrincipalMemoryAdvisor` 只做分发：从 `request.context()` 取 `ContextKeys.PRINCIPAL`
（`ChatClientAgentPort` 放进 `toolContext` 的就是同一个 map），取到就转给为那个人的目录建好的
库 advisor，取不到就**原样返回**——没有身份就没有记忆，不 fallback 到公共目录。库那套逻辑
（系统提示、工具注入、按名字去重、根目录渲染）一行没改。

**目录名两个成分都不能省**：安全化（`userId` 来自通道，飞书 open_id、邮箱、工号都可能，里面有
`/` 时 `Path.resolve` 会把根目录整个换掉），以及哈希后缀（安全化多对一，`a/b` 与 `a:b` 都会
变成 `a_b`，撞进同一层就是互相读对方的记忆）。

**order = `HIGHEST_PRECEDENCE + 3`**：在 persona（`+2`）之后——记忆块要拼在最终系统提示的尾部；
在脱敏（`+200`）与缓存（`+250`）之前。**不能用库的默认值**：那份默认正好也是 `+200`，与
`PiiRedactionAdvisor` 撞上，而两个 advisor 同 order 时谁先谁后由 bean 名字决定。

## 明确不做的四件

- **落盘不脱敏**。记忆是模型挑过的"事实"，脱敏会把"API 基地址"这类本来就该原样记住的东西打坏；
  代价是磁盘上有一份明文，缓解只有"目录 0700、不进容器、不给子 Agent"。要脱敏得改写 6 个工具
  各自的入参 JSON，库一改版就断——留到真的开始出现号码时再说。
- **不受工具清单管**。这 6 个工具是 advisor 注入的，`toolsBySender`、沙箱 `bundle-mcp`、画像过滤
  都拦不住。"这个 sender 不许写记忆"现在表达不出来；要表达得让请求上下文带上"本轮允许的工具名"
  再求交集，那是改公开契约，另开票。
- **子 Agent 没有记忆**。子链一个 Advisor 都不挂（ADR-0012 修订），所以它也看不到、写不了。
  让一次性执行体改父的长期状态是另一种错。
- **不整理**。`memoryConsolidationTrigger` 没接，默认恒 false——记忆只增不减。等它长到需要
  整理时再接，现在接等于凭空多一个"什么时候整理"的策略要论证。

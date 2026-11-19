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

## 明确不做的三件

- **落盘不脱敏**。记忆是模型挑过的"事实"，脱敏会把"API 基地址"这类本来就该原样记住的东西打坏；
  代价是磁盘上有一份明文，缓解只有"目录 0700、不进容器、不给子 Agent"。要脱敏得改写 6 个工具
  各自的入参 JSON，库一改版就断——留到真的开始出现号码时再说。
- **不受工具清单管**。这 6 个工具是 advisor 注入的，`toolsBySender`、沙箱 `bundle-mcp`、画像过滤
  都拦不住。"这个 sender 不许写记忆"现在表达不出来；要表达得让请求上下文带上"本轮允许的工具名"
  再求交集，那是改公开契约，另开票。
- **子 Agent 没有记忆**。子链一个 Advisor 都不挂（ADR-0012 修订），所以它也看不到、写不了。
  让一次性执行体改父的长期状态是另一种错。

## 整理：跑库的 dream 周期

记忆只增不减是这套机制的默认状态：每段对话都可能新建一份文件、往 `MEMORY.md` 加一行，而
`MEMORY.md` 每轮进上下文（库的提示词写着超过 200 行会被截断）。库为此带了整套
`dream` 包，直接用，不自己写策略：

| 库的件 | 管什么 |
| --- | --- |
| `DreamTriggers.hoursAndSessions(h, n)` | 什么时候整理：距上次够久 **且** 又攒够 n 轮，两个条件同时成立 |
| `DreamState` | 计数落在 `<记忆目录>/.dream-state.json`——按人一个目录，于是天然按人一份 |
| `AutoDreamService` | 跑一次 dream 周期：带着记忆工具进那个目录合并、精简、删过时的 |
| `AutoDreamAdvisor` | 挂在收尾（`after`）上，判完就派一个后台任务，**请求本身一个字不改** |
| `DreamLock` | 按目录的锁，同一份记忆不会有两个 dreamer 同时改（15 分钟算过期） |

默认参数是 `hoursAndSessions(24, 5)`——照库注释里写的 Claude Code 那套双条件启发式。
关掉用 `claw4j.agent.memory.dream=false`，传给库的是 `DreamTriggers.manualOnly()`；
不自己写一个"永远返回 false"的空实现。

三处与"照抄"不同的地方，都是因为我们自己按人分目录：

- **每人一份 dream advisor**（`PrincipalDreamAdvisor` 做分发）。库那份把目录写在字段里，
  一份只能服务一个人；`before` 空着，只挂 `after`。
- **目录要先建出来**。库的 `.dream-state.json` 直接写进记忆目录、不替我们建；不建的话第一个
  用户的第一次收尾就在 `after` 里抛，把一轮正常的回答弄挂。建不出来就**关掉这个人的整理**
  并记一条 warn——记忆是附加信息，不是可用性依赖（与 `FaultTolerantSessionService` 同一个口径）。
- **身份从响应里取**。`after` 只拿到 `ChatClientResponse`，好在框架的 `ChatModelCallAdvisor`
  会把请求上下文原样放进响应（流式由聚合器累积）。读不到就什么都不做：不计数、不整理，不猜。

**它仍然是"尽力而为"**：dreamer 是后台的一次独立调用，可能失败（模型错、token 用完、锁被占），
失败只落一条日志与 `.dream-state.json` 里的状态，不影响用户的下一轮。能断言的只有"到点会派、
不会每轮都派、派的是这个人自己的目录"——断言不了"整理质量如何"，那是提示词的事。

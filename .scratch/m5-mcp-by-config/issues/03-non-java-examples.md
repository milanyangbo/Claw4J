# 03: examples 里那些"非 Java"的能力，逐类说清怎么接进来

**What to build:** README 一节 + `docs/design/extension-points.md` 一节，把
`examples/` 下四类素材（Python MCP server、Python/bash 脚本包、`SKILL.md`、子 Agent 定义）
各自**靠什么机制**接进宿主说清楚；顺手补上 `examples/subagents/` 里那份根本不存在的定义。

**Blocked by:** 无（01 定的配置键名会被引用）。

**Status:** resolved

## 为什么

`examples/` 是四类素材，README 从来没有一处回答"它们各自怎么变成模型手上的能力"。
读者看到 `examples/mcp/*.py` 会以为"项目支持 Python 插件"，看到 `scripts/*/run.sh` 会以为
"脚本就是工具"，而两者的接入路径完全不同：一个走 MCP 协议（跨进程、有官方 SDK），
一个走本项目的脚本层（子进程 + 沙箱 + 只给 `PATH`）。

更要紧的是一条**事实性错误**：`examples/README.md` 写着
`claw4j.subagents.directory=examples/subagents` 就能用，而那个目录是空的。
按 `SubagentCatalog` 的判据，空目录是**启动失败**（"配了却空着，说明这些 Agent 没被部署"）。
文档承诺比代码走得远——这个仓库的第一大类缺陷。

## 要做

- [x] `examples/subagents/capacity-triage.md`：一份真定义（markdown + front matter），
      白名单只给 `systemStatus`，于是**零额外配置**就能加载（不依赖数据库、不依赖其他目录）。
- [x] README 一节：四类素材 → 接入机制 → **官方框架对应什么**。核心那句要写死：
      Spring AI 让模型"动手"的官方通道只有两条——Java 方法（`@Tool` /
      `MethodToolCallbackProvider`）与 MCP（`SyncMcpToolCallbackProvider`）。
      所以非 Java 的东西要变成工具，只有三种结局：包成 MCP server、包成可执行程序由脚本层跑、
      或者写成 Skill（它不改能力，只给做法）。
- [x] `docs/design/extension-points.md` 加一节「语言不是扩展点的维度」，写清
      哪一层的"语言无关"是**协议层面**的（MCP）、哪一层是**文件格式层面**的（Skill / Subagent）、
      哪一层是**本项目自己的接缝**（脚本包，官方没有对应物）。
- [x] `examples/README.md`：MCP 那一节补一句"第三方 server 也可以纯靠配置接"，
      并把 subagent 那行改成真实存在的文件。

## 判据

- 文档里每条启用命令都能原样跑（键名与目录都真实存在，逐条核对）。
- **不把本项目的接缝说成官方能力**：脚本包、Skill、Subagent 三层的机制是 Claw4J 自己实现的，
  Spring AI 没有对应抽象；写成"官方支持"就是又一处"文档比代码走得远"。
- 新加的 subagent 定义要能被 `SubagentDefinition.parse` 读通（front matter 只有
  `name` / `description` / `tools` / `disallowedTools` 四个键，列表项用 `- 名字`）。

## 不要做

- 不引入 Spring AI 之外的"多语言 SDK"概念：跨语言这件事由 MCP 协议解决，不需要再发明一层。
- 不为演示往仓库里塞 node_modules 或第三方源码。
- 不把 `examples/` 挪进构建：它是素材，不是模块。

## 实现记录

### 落地

- `examples/subagents/capacity-triage.md`：第一份真实存在的子 Agent 定义。
  front matter 只有 `name` / `description` / `tools`，白名单里只有 `systemStatus`——
  那个工具**不挂任何开关**（`SystemStatusAutoConfiguration` 的取向），
  所以这份定义零额外配置就能加载：不用数据库、不用别的目录先配好。
- README 新增「非 Java 的东西怎么变成"能力"」：四类素材 → 接入机制 → 官方还是本项目接缝，
  并给了一句能记住的分界（协议层面 / 文件格式层面 / 能不能起进程）。
- `docs/design/extension-points.md` 新增第五节「语言不是扩展点的维度」，
  并把开头的扩展点表从四行补到六行（加了脚本包与 Subagent）。
- `examples/README.md`：MCP 一节补"别人写的 server 不用写代码"，新增 Skill / Subagent 一节。

### "官方支持"这句话核对过

`spring-ai-mcp-2.0.2.jar` 里确实有 `SyncMcpToolCallbackProvider` / `AsyncMcpToolCallbackProvider` /
`McpToolUtils`——即"MCP 工具 → `ToolCallback`"这条官方桥。所以文档里敢写
"Spring AI 让模型动手的官方通道只有两条：Java 方法与 MCP"。
而脚本包、Skill、Subagent 三处的机制在本仓库自己的包（`capability/script`、`capability/skill`、
`agent/subagent`）里，Spring AI 没有对应抽象——文档按"本项目接缝"标注，没有抬成官方能力。

### 没验证的（如实记）

`capacity-triage.md` 只做过**格式上**的对照（front matter 只有四个允许的键、列表项写成 `- 名字`），
**没有真的被 `SubagentCatalog.load` 读一遍**：本轮环境既不能打包 fat jar（repackage 要改名，被拦），
也不能起应用做一次真模型委派。下一轮第一件事应该是把它读一遍——
`SubagentCatalog` 对"配了却空着/解析不出"都是启动失败，一份写错的定义会让整个应用起不来。

顺带记一条**文档债**：`examples/subagents/` 之前是空目录，而 `examples/README.md` 已经写着
"配 `claw4j.subagents.directory=examples/subagents` 就能用"——按 `SubagentCatalog` 的判据，
那个状态是会启动失败的。文档比代码走得远，这一份票顺手补上了。


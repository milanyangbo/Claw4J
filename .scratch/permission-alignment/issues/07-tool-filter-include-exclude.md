# 07: 存在性过滤——`toolFilter.include / exclude`

**What to build:** 给"工具存不存在"这一层一个显式的开关，形状照它的 `toolFilter`：
每个 provider（MCP server、脚本包、技能生成器、业务示例）都能配 `include` / `exclude`，
规则是同一条——**`include` 非空则必须命中，`exclude` 命中即排除**，条目支持 `*` 通配。
过滤发生在**工具被投影成工具之前**，因此被排除的工具不会进清单、不会进权限登记、也不会出现在
`toolNames()` 里。

先纠正一个前提（这一票的由来）：**`permissions()` 不是"暴露哪些工具"的开关**。它是
`Map<String, ToolPermission>`，作用只有两个——往权限登记表里写一条、给档位登记表提供工具名。
真正决定"暴露哪些"的是各 provider 的装配条件（模块引没引、包在不在、server 导出了什么）。
所以这一票不是"把 `permissions()` 改成 filter"，是**补上一层它现在没有的过滤**。

**顺带必做的一件（票 04 依赖它）**：把"本 provider 提供哪些工具"从 `permissions().keySet()`
里拆出来，独立成 `toolNames()`。现在五处档位登记借的是权限表的 keySet
（`ScheduleTools` / `ConfiguredMcpServers` / `ScriptPackage` / `SkillGenerator` / `McpToolProvider`
内部同步 `callbacks` 与 `declared`），04 删 `ToolPermission` 时会一起炸掉。
`toolNames()` 取的是**过滤之后**的名字，即"真的存在的工具"——这与它的语义一致：
被 `toolFilter` 排掉的工具不该有任何登记。

**Blocked by:** 02（档位集合化会同时改这批登记点，先做完再动，避免同一批文件改两遍）。

**Type:** task

**Status:** resolved

**裁决来源：** ADR-0030 §裁决二的"存在性 vs 可见性"一段；形状与语义照 OpenClaw 的
`mcp.servers.<name>.toolFilter`（`cli/mcp.md`：*filter discovered MCP tools before they become
OpenClaw tools*）与 `bundle-mcp` 占位（`CORE_TOOL_PROFILES`）。此处不重复论证。

## 与"档位"和"策略"的分工（三层，别混）

| 层 | 回答什么 | 我们的落点 | 被筛掉的还能回来吗 |
| --- | --- | --- | --- |
| ① 存在性 | 这个工具**有没有** | 本票的 `toolFilter` | **不能**——它没成为工具，`allow` 无从作用 |
| ② 画像 | 这**类**工具允不允许 | 票 02 的 `profiles` + 外部工具桶 | 换档或 `alsoAllow` |
| ③ 策略 | 具体哪几个**可见** | 票 03 的十一条管线 | 改 `allow` / `deny` |

## 要动的地方（预计）

| 位置 | 改什么 |
| --- | --- |
| `claw4j-core/**`（或 `capability/**/tool`）新增 `ToolFilter` | 值对象：`include` / `exclude` + `matches(String)`；纯函数、可单测 |
| `McpServerProperties.Server` | 加 `toolFilter`。**同一 record 里的 `permission` 与 `tools`（按工具覆盖权限）由票 04 删掉**，本票不要为它们留位置、也不要把 `toolFilter` 塞进那个 `Permission` record 里 |
| `McpToolProvider#install(Server, List<Tool>)` | **过滤点就在这里**：`listTools()` 之后、建 callback 与 `declared` 之前筛一遍，让 `server.tools` / `callbacks` / `toolNames()` 三者一致 |
| `ScriptPackageToolProvider` / `SkillGeneratorToolProvider` / `ExampleSales` 的配置段 | 各加一个 `toolFilter`，在 `getToolCallbacks()` 返回前过滤；`toolNames()` 只含存活工具 |
| 各 provider 新增 `toolNames()` | 返回过滤后的工具名集合；五处 `ToolTierRegistry.sameFor(provider.permissions().keySet(), …)` 逐处切过来（票 04 要删 `permissions()`） |
| `claw4j-app/src/main/resources/application*.yml` | 给 example-sales 与 MCP 各写一段示例配置 |

三条判据：

- **名字用 server 的原名**，不加 `server__` 前缀（我们现在的 MCP 工具就是原名，见
  `install` 里 `tool.name()`）；条目支持 `*`。
- **与被排除的工具重名 = 配置错误**：`tools` 里点名了一个被 `toolFilter` 排除的工具时，
  现有的"点名了它没有导出的工具"校验会自然报错——错误信息要补一句"可能被 `toolFilter` 排除了"，
  否则人会以为 server 换了版本。
- **过滤只做一次**：过滤后的集合就是 `server.tools`，重连拿到的仍是服务端全量，重连后再筛。

## 联测：`claw4j-example-mcp-server`

它现在导出三个工具：`runtimeSnapshot`、`cronNext`、`simulateSlowCall`。
用 `JavaMcpServerProcessTest` 的手法真起一个子进程，验证四组：

| 配置 | 期望 |
| --- | --- |
| 不配 `toolFilter` | 三个都在 |
| `include: [runtimeSnapshot, cronNext]` | 只有两个；`simulateSlowCall` 的 callback 与权限都不存在 |
| `exclude: [simulateSlowCall]` | 同上（换一种写法，结果一致） |
| `include: [runtime*]` | 命中 `runtimeSnapshot` |

## TDD 用例清单

| 判什么行为 | 用例名 | 先红的样子 |
| --- | --- | --- |
| `include` 非空时只有命中的留下 | `ToolFilterTest#includeKeepsOnlyMatches` ✅ | 还没有这个类型 |
| `exclude` 命中即排除，`include` 为空时其余全留 | `ToolFilterTest#excludeWinsWhenNoInclude` ✅ | —— |
| `*` 通配按整串匹配（`runtime*` 不匹配 `xcronNext`） | `ToolFilterTest#globMatchesWholeName` ✅ | 容易用 `contains` 实现 → 这条红 |
| MCP：`include` 两个，第三个的 callback 与名字登记都不存在 | `McpToolTest#includeFiltersCallbacksAndPermissions` ✅ | 现在全量暴露 |
| MCP：`exclude` 一个，等价于上一个 | `McpToolTest#excludeRemovesTool` ✅ | —— |
| MCP：`tools` 里点名一个被排除的工具 → 启动失败且提示可能被 filter 排除 | ⏳ 无对象：票 04 删掉了 `Server.tools`，没有"点名的工具"可撞 | 错误信息会误导成"server 没导出" |
| MCP：重连后重新按 filter 筛（不是筛一次的快照） | `McpToolTest#filterReappliedWhenTheServerComesBack` ✅ | 容易在首次 listTools 后就不再筛 |
| 脚本包：`exclude` 掉一个包，`toolNames()` 里也没有它 | ⏳ 无对象：脚本包整层删除（oss 票 02，退化成技能里的脚本） | 现在按 provider 全量登记 |
| example-sales：`exclude: [querySales]` 之后清单里没有它 | `SalesToolFilterTest#excludedBusinessToolIsAbsent` ✅ | —— |
| 没配 `toolFilter` 时清单与改前逐个工具相同 | `McpToolTest#noFilterKeepsEveryTool` / `SalesToolFilterTest#noFilterKeepsTheBusinessTool` ✅ | 这条最容易漏 |
| 配置真的绑得上（`@DefaultValue` 那层不出 null） | `ToolFilterBindingTest`（三条，`claw4j-app`）✅ | 缺省不写时 NPE 在第一轮请求里 |
| 通配在 MCP 上也按整串 | `McpToolTest#globFiltersByName` ✅ | —— |

## 验收判据

| 判什么 | 判据 |
| --- | --- |
| 真进程 | ✅ 联测那四组全绿：`claw4j-example-mcp-server` 的 `ToolFilterIntegrationTest`（一个子进程、四次连接，真 HTTP） |
| 名字清单一致 | ✅ 被过滤的工具在 `toolNames()` 里查不到；四处档位登记（MCP 两条、脚本、生成器、定时任务）都改用它；`McpToolAutoConfiguration` 的外部工具名也一样 |
| 门禁 | ✅ `./mvnw clean verify` 九模块全绿 |
| 清单 | ✅ `README-capability-tests.md` §2 与 `docs/capability-tests.md` §2.4 各加了一组"被 `tool-filter` 排掉的工具不在清单里"的验法（配置片段 + 该看到什么 + 与权限拒绝的区别） |

**复核：2026-11-18 `./mvnw clean verify` 九模块全绿。** 12 行逐条对到真实用例（二审重对，
把原来记的三个类名改成实际存在的；表里两行标 `⏳`，去向见缺口）：
`ToolFilterTest` 9 条、`McpToolTest` 里的 filter 那 5 条、`SalesToolFilterTest` 2 条、
`ToolFilterBindingTest` 3 条、`ToolFilterIntegrationTest` 1 条（真进程四组）。
**二审更正**：`McpToolFilterTest` 已经不存在——它被 `6d0dde5` 并回了 `McpToolTest`（那时上限抬到 26，
单开一个文件不再是必需的）。上面几行的用例名跟着改成 `McpToolTest#…`。

## 缺口

- **`toolFilter` 只做"名字"，不做"参数级"**：它的 `toolFilter` 也只到名字与 `*`，参数级靠工具实现自己。
- **没有 `openclaw mcp tools --include/--exclude` 那条 CLI**：我们改配置即生效，不加一条改配置的命令。
- **内置工具（`Read`/`Grep`/`Bash`）不挂 filter**：它们的暴露由装配条件与画像决定，不引入第三个开关。
- **"点名一个被排除的工具 → 启动失败"没有对象**：票 04 删掉了 `Server.tools`，配置里不再有
  "这个 server 应该导出哪几个"的声明，于是没有东西可与 filter 之后的清单对不上。它原本防的是
  "写错了名字还以为配上了"，现在那个位置本来就空着——真要防，等重新出现显式声明时再说。
- **脚本包的 filter 用例没有对象**：脚本包整层删除（oss 票 02，退化成技能里的脚本），
  技能不走 `toolNames()` 这条路。

## 实现记录（与票面的三处偏离）

1. **过滤落在 `McpToolProvider#install`，不是构造期**：`listTools()` 之后、建 callback 与权限表之前筛一遍。
   `install` 每次 `contact()` 成功都会走，于是重连拿到的是重新筛过的清单，不是第一次的快照。
   端点与连接只需透出 `toolFilter()`（`McpServerEndpoint.of(...)` 新增一个五参工厂，老的两参/四参默认 `none()`）。
2. **MCP 的用例当时单开了一个文件**（`McpToolFilterTest`）：`McpToolTest` 与 `McpServersConfigTest` 都是 20 个方法顶格，
   塞进去就破上限。它复用 `McpToolTest` 里的进程内 server 与 `CalcTools`，不另造对端。
   **后来并回去了**（`6d0dde5`）：上限抬到 26 之后，为这五条单开一个文件不再划算，而"一类行为一个文件"
   本来就是 `docs/agents/testing.md` 的取向。配置绑定那一层仍放在 `claw4j-app` 的 `ToolFilterBindingTest`
   ——四个配置对象要在同一个 classpath 上都看得见。
3. **脚本层与生成器从 `@Value` 换成了绑定对象**（`ScriptPackageProperties` / `GeneratorProperties`）：
   `tool-filter` 是嵌套结构，`@Value` 表达不了；而给同一段前缀单开一个 `@ConfigurationProperties` 又会出现两套读法。
   两个类的默认值逻辑（评审目录、发布目录、审计文件"落在暂存区旁边"）原样搬进去，没改行为。

**联测时撞到的一件事**：给 `claw4j-example-mcp-server` 加 test-scope 的 `claw4j-capability` 之后，
`JavaMcpServerProcessTest` 立刻红了——清单里多出 `Read`/`Bash`/`Grep` 八个工具，因为子进程继承了测试 classpath，
Spring AI 的 MCP server 自动配置把 Claw4J 的内置工具也交了出去。修法是子进程 classpath 去掉其它 `claw4j-*` 条目
（`JavaMcpServerProcessTest#serverClasspath`）：这不是掩盖问题，而是还原发布形状——jar 里本来就没有 Claw4J。

## 缺口（新增两条）

- **`tool-filter` 只作用于工具来源，不作用于内置工具**：`Read`/`Grep`/`Bash` 的暴露仍由装配条件与画像决定，
  不引入第三个开关（与票面一致）。
- **过滤之后一个都不剩不是启动失败**：只打一条 WARN（`MCP server X 导出的 N 个工具全被 tool-filter 排除了`）。
  `include` 写错一个字的症状与"这个 server 没有工具"太像，但把"有意排空"也判成失败会让一个合法配置起不来。

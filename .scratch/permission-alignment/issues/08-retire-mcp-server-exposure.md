# 08: 下线出站暴露（`claw4j.mcp.server.*`）

**What to build:** 把"本进程当 MCP server、把工具交给外部客户端"这一整块功能删掉。
不是删权限、留个壳——**功能不存在了**。

**Blocked by:** 无。独立可做，与权限那几张票没有依赖关系。

**Type:** task

**Status:** resolved

**裁决来源：** ADR-0030 §裁决二末尾（为什么整块下线）、§先定口径·两步走表、§不做。用户 2026-11-18 裁决。

## 为什么删（不是因为"多一层权限"）

那层"交哪几个"的挑选**又是一次可见性判定**：

```text
交出去的只能是清单里的工具 →
  ├ 交全部：等于清单，没有新增信息，只是把 Bash 也默认交出去
  └ 交一部分：就是第二份 allow 列表，与 tools.allow 争同一个问题，
              而且默认值两难（空=功能不可用，全=最危险的东西默认出门）
```

它也没有对位可抄：`openclaw mcp serve` 暴露的是**会话通道**（让外部客户端读写它的聊天），
不是把 agent 工具交出去。

## 删什么

| 位置 | 内容 |
| --- | --- |
| `claw4j-capability/**/mcp/expose/` | 整个包：`McpExposure`、`McpExposureProperties`、`McpServerExposureAutoConfiguration` |
| `claw4j-app/src/main/resources/application-mcp-stdio.yml` | **整个文件**（它就是为这条 server 存在的 profile） |
| `application.yml` / `application-local.yml` | `claw4j.mcp.server` **整段**（不是只删 `tables` 一行——键全都没了，配上去会启动失败或静默失效，两种都难查） |
| `ApplicationAssemblyTest` | 断言那三个 bean 的用例 |
| `claw4j-capability/pom.xml` | **依赖不动**：`spring-ai-starter-mcp-client` 传递引的 `mcp-core` 同时提供 client 与 server 侧类，client 还在用；只改那段"client 与 server 两侧都用这一条"的注释 |
| 文档 | **只有四处**：`README.md:110,115`、`GLOSSARY.md:369,380`、`docs/design/extension-points.md:736`（同行 149/735 讲的是 `capability.mcp.servers`，复数，不改）、`docs/adr/0020-platform-capabilities-ride-on-run.md:263`（"OpenClaw `mcp serve` 那种反向用法"） |

**文档里两个同名不同物的坑**：`docs/design/capability-layers.md`、`docs/capability-tests.md`、
`README-capability-tests.md` 里出现的全是 `capability.mcp.servers`（复数、入站），**一个字都不用改**；
ADR-0014 讲的是连别人的 CLI，也不改。全文搜 `mcp.server` 会把这些全带出来——因为 `mcp.servers`
里含 `mcp.server` 这个子串。

## 别删错（这一票最容易踩的）

```text
claw4j.mcp.server.*    单数 = 本进程当 server，把工具交给外部      → 删
claw4j.mcp.servers.*   复数 = 连别人的 MCP server，把工具拿进来    → 保留
```

后者是**入站**：`McpServerProperties`、`McpToolProvider`、`McpEndpoints`、`ProcessStdioMcpEndpoint`
全在这条路上，票 07 的 `toolFilter` 也挂在它上面。两个键一个少一个 `s`，删错就是把入站能力一起废掉。

## TDD 用例清单

| 判什么行为 | 用例名 | 先红的样子 |
| --- | --- | --- |
| 三个 bean 不再存在 | `BuildContractTest#outboundExposureIsGone` ✅ | 现在有三个 → 红 |
| 全仓看不到 `McpExposure` / `McpExposureProperties` / `McpServerExposureAutoConfiguration` | 同一条 ✅（`Class.forName` 三个类名 + 扫所有 `AutoConfiguration.imports` 里没有 `mcp.expose.`） | 现在都在 |
| 连别人的 MCP server 照常可用 | `McpToolTest` 11 条 + `McpServersConfigTest` 20 条 + `McpToolFilterTest` 6 条 ✅ | 删过头把 client 侧一起删了 → 红 |
| 配置里没有 `claw4j.mcp.server` 绑定 | 同一条 ✅（读 `src/main/resources/application.yml` 源文件，断言不含 `mcp.server` 与 `MCP_SERVER_ENABLED`） | 段还在 → 红 |
| `claw4j.mcp.servers` 的样例配置仍能启动 | `McpServersConfigTest` ✅ | 同上 |

## 验收判据

| 判什么 | 判据 |
| --- | --- |
| 类真的没了 | ✅ `grep -r "McpExposure" --include=*.java .` 只剩 `BuildContractTest` 里那三个**字符串**（`Class.forName` 的入参），没有 import 与类型引用 |
| 配置真的没了 | ✅ `application.yml` 里 `mcp.server` / `MCP_SERVER_ENABLED` 都没有；`application-mcp-stdio.yml` 与 `logback-mcp-stdio.xml` 已删 |
| 入站没误删 | ✅ `claw4j.mcp.servers.*` 的配置与 `McpToolTest` / `McpServersConfigTest` / `McpToolFilterTest` 全绿 |
| 依赖没多删 | ✅ `spring-ai-starter-mcp-client` 仍在（客户端要用）；只改了 pom 里那段"client 与 server 两侧都用这一条"的注释 |
| 文档 | ✅ 四处：`README.md` 整节删除、`GLOSSARY.md` 两条词条删除、`extension-points.md` 第八章改成存根 + 总结表那一行删除、ADR-0020 的 P11 段改写为"已下线" |
| 清单不受影响 | `README-capability-tests.md` §2 与 `docs/capability-tests.md` §2 讲的是**连别人的** server，**不改**（这也是最容易误改的两处） |
| 门禁 | ✅ `./mvnw clean verify` 九模块全绿 |
| 清单 | ✅ 两份清单未改动（§2 讲的是连别人的 server）；`git diff` 里这两份文件只有票 07 加的那一节 |

**复核：2026-11-18 `./mvnw clean verify` 九模块全绿。** 5 行逐条对上：`BuildContractTest#outboundExposureIsGone`
一条覆盖前两行与第四行（类不在 / 配置段不在 / 注册表里没有 `mcp.expose.`），入站那两行由
`McpToolTest` / `McpServersConfigTest` / `McpToolFilterTest` 全绿证明。

## 影响（写在这里，不写进缺口）

- **外部程序不能再通过 MCP 调本进程的工具**。这是功能下线，不是能力缺口，所以 ADR 记在「不做」而不是「缺口」。
- **`claw4j.mcp.servers.*` 不受影响**：连别人的 MCP server 是本项目一直在用的能力（ADR-0014 的外部 CLI、m5 的配置化端点）。

## 实现记录（与票面的四处偏离）

1. **防回潮的断言放在 `BuildContractTest`，不是 `ApplicationAssemblyTest`**：类删掉之后测试编不过，
   不能再按类型断言"容器里没有它"。改成两条：三个类名走 `Class.forName`（拿到"真的不在"这个肯定答案），
   再扫**全 classpath 的** `AutoConfiguration.imports` 断言没有 `mcp.expose.`——后者还能抓住
   "配置类被加回来但忘了注册"这种半吊子恢复。顺带守一条正向：`ConfiguredMcpServersAutoConfiguration`
   仍在注册表里（入站没被删错）。
   读配置读的是 `src/main/resources` 的**源文件**：增量构建里 `target/classes` 会留着已删的资源，
   拿 classpath 判"文件不存在"会在本机红、clean 之后绿，方向正好是反的。
2. **`logback-mcp-stdio.xml` 一起删了**（票面只点了 yml）：它是同一个 profile 的日志配置，
   没有 profile 引用它就是一段死配置，留着还会让人以为"这个 profile 还在"。
3. **`extension-points.md` 第八章留了 6 行存根，没有整章删除**：删掉之后第八～十四章的编号与
   交叉引用都要重编，而这一章存在的价值（"为什么这条不做"）正好是存根里那三行。
   存根里点明反方向（`capability.mcp.servers`）还在——这是这一票最容易删错的地方。
4. **修正了 ADR-0030 自己的文档清单**：原来那一行列了 7 处，其中 `capability-layers.md` /
   `capability-tests.md` / `README-capability-tests.md` / ADR-0014 讲的是 `mcp.servers`（复数、入站），
   照它执行就会误删入站文档。已按票面改成四处，并写明"全文搜 `mcp.server` 会把复数那几份带出来"。

**连带变化**：`McpToolTest` 从 20 条降到 11 条（出站那 9 条与其专用替身 `ProbeTool` / `Connected` /
`textOf` / `catalogOf` / `serve` / `connect` 一起删），`ApplicationAssemblyTest` 从 20 条降到 18 条。
两条用例文件的类注释都改了——留着"两个方向都在这个文件里"会让下一个人去找那半。

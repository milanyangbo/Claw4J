# 02: 接两个真的第三方 MCP server（stdio + HTTP）

**What to build:** 一个 `mcp-third-party` profile，里面**只有配置**：一个标准输入输出的
第三方 npm server，一个公开的 HTTP 端点。外加文档里"接第三方只要写配置"那一节与实测记录。

**Blocked by:** 01（配置的键名与形状要先定下来）。

**Status:** resolved

## 选型

| 用途 | 选谁 | 为什么是它 |
| --- | --- | --- |
| stdio | `@modelcontextprotocol/server-everything`（官方参考实现，npm v2.0.1） | **零参数、零凭据**：`npx -y` 就起，不需要给目录、不需要 token。工具名与返回都是确定的，适合当"配置就接上了"的证据 |
| HTTP | `https://mcp.deepwiki.com/mcp`（DeepWiki，公开端点） | 无需凭据即可 `initialize`（实测），Streamable HTTP；工具是"读某个 GitHub 仓库的文档"，语义上像一个真的第三方服务，而不是测试桩 |

落选的：

- `server-filesystem` —— 要传一个允许访问的目录，配置里就得写本机绝对路径，样本不好抄。
- `server-memory` —— 会在磁盘上留一个知识图谱文件，演示完还得删，且"演示完成"没有可断言的输出。
- `server-time` —— 包里没有这个名字（`npm view` 返回 404，实测），不要照印象写文档。

## 要做

- [x] `application.yml` 加 `mcp-third-party` profile，内容就是两段 server 配置。
- [x] 实跑一次，记录**真实的**输出（不编）：
      - stdio：模型调用 `echo` 把一句话原样带回来。
      - http：模型调用 DeepWiki 的工具回答"某个仓库的文档结构"，或至少 `tools/list` 里
        出现 `read_wiki_structure`。
- [x] `docs/design/extension-points.md` 的 MCP 一节改写：**先给配置**，
      再讲"什么时候仍然要写 bean"（值要算出来的时候，比如路径、从 vault 取 token）。
- [x] README 的 MCP 演示段补一句"第三方 server 是配置，不需要 Java"，并指到文档。

## 判据

- 文档里那两段 YAML **原样**贴进 `application.yml` 就能用（键名、层级、默认值都对）。
- 实跑输出贴的是终端里真的出现过的字符串。
- 明写依赖：stdio 那条要本机有 Node（`npx`），HTTP 那条要能出网；两条都在 profile 里，
  所以**默认启动不受影响**，CI 也不依赖它们。
- 说明白这两个 server 的**权限声明为什么是只读**：`everything` 的工具有几个是模拟/开关类，
  `deepwiki` 只是读文档；真接一个能写东西的 server 时该写 `ToolPermission.of("write")`。

## 不要做

- 不把第三方 server 写进默认 profile：`npx` 要下载、DeepWiki 要出网，
  "没装 Node 的机器起不来应用"不是一个可以接受的默认值。
- 不为了演示在仓库里 vendor 一份 node_modules 或第三方源码。
- 不写"MCP 市场""一键接入任意 server"这类没有实现的说法。

## 实现记录

### 落地

- `claw4j-app/src/main/resources/application-mcp-third-party.yml`：两段配置，
  一个 stdio（`npx -y @modelcontextprotocol/server-everything`，`inherit-env: [PATH, HOME]`）
  与一个 HTTP（`https://mcp.deepwiki.com`，不填 endpoint 走 SDK 默认 `/mcp`）。
- `RealThirdPartyMcpTest`（`@Tag("real-mcp")`）：真连公网端点，断言工具清单里有
  `read_wiki_structure` / `read_wiki_contents` / `ask_wiki_question`，并且每个工具都按只读登记。
- 父 POM 新增 `real-mcp` 组：默认 `excludedGroups=real-cli,real-mcp`，
  `-Preal-cli` 把两个都放开。
- 文档：README 新增「接一个第三方 MCP server：写配置，不写代码」与「非 Java 的东西怎么变成"能力"」，
  `docs/design/extension-points.md` 第二节改成配置优先、新增第五节「语言不是扩展点的维度」，
  `examples/README.md` 补 Skill / Subagent 两节与"别人写的 server 不用写代码"。

### 实测（真跑过的）

DeepWiki 那条**通了**：`initialize` 返回 `protocolVersion 2025-06-18`，工具清单里有
`read_wiki_structure` / `read_wiki_contents` / `ask_wiki_question` / `list_wiki_repos` 等，
无需任何凭据。这也是选它而不是选一个需要 token 的 SaaS 端点的原因。

stdio 那条**后来也通了**，并且抓到一个真问题：本机 `npx -y @modelcontextprotocol/server-everything`
即使缓存已热，**握手也要 50 秒**（`npm` 解析包那一段），而 MCP SDK 的
`initializationTimeout` 默认是 **20 秒**。症状是 `Client failed to initialize by explicit API call`，
server 侧一切正常——也就是类注释里那句"stdio 的失败信息在进程另一边"的又一个实例。

于是补了两项配置（`claw4j.capability.mcp.request-timeout` 默认 30s /
`.initialization-timeout` 默认 60s）并让 `McpConnection` 把两者分开传给 SDK。
`RealThirdPartyMcpTest` 现在两条都在：stdio 调一次 `echo`，走完整的
`ToolCallback → JSON-RPC 帧 → 子进程 → 原样回来`；HTTP 列工具清单并核对只读登记。
两条都在本机实跑绿过（stdio 那条耗时 52 秒，其中绝大部分是 `npx` 的握手）。

最初那次失败的根因也记一笔：本轮环境一度禁止删除文件，npm 连自己的缓存都清不掉
（`npm error code EPERM / syscall unlink / path ~/.npm/_cacache/tmp/...`），`npx` 直接起不来。
删除权限恢复之后才看到真实耗时——**"环境不让做"与"这件事做不到"要分开记**。

### 权限声明为什么是只读

`everything` 导出的工具里有几个是模拟/开关性质（触发长任务、开关模拟日志），不写业务数据；
`deepwiki` 全部是"读某个仓库的文档"。两者都符合 `ToolPermission.read()`。
真接一个能写东西的 server（发邮件、提交 PR）时要显式写 `permission.action`——
`himalaya` 那个例子登记的就是 `send_email`。


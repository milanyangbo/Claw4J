# 票 14：评测上下文接上两个示例 MCP，把那四道题接回来

**Status:** open

> 依赖票 13（题库换版时删掉了这四道）。

## 由来

票 13 的目录断言抓出四道死题：`tools-mail-unread` / `tools-mail-send` / `tools-github-issues` /
`tools-github-pr-ci` 的 gold（`mail_list_unread` / `mail_send` / `github_issue_list` /
`github_pr_checks`）不在当轮目录里，因为评测上下文没接 MCP。当时的处理是换成现有工具的四种问法。

用户裁决：**去接 MCP，而不是绕开它**。绕开的代价是 `business` 这一档少了"外部系统工具"这一类，
而它恰恰是最容易检索不到的一类（名字长、描述带英文、同域干扰多）。

## 裁决

无需 ADR：只改评测上下文的装配与题库，不动线上装配、不动默认配置。

## 接哪两个

仓库里已经有现成的两个零第三方依赖的 Python MCP server（`examples/mcp/`），工具名与那四道题
**正好对上**：

| server | 文件 | 暴露的工具 |
| --- | --- | --- |
| `github` | `examples/mcp/gh_mcp_server.py` | `github_issue_list`、`github_pr_checks`、`github_api` |
| `mail` | `examples/mcp/himalaya_mcp_server.py` | `mail_list_unread`、`mail_send` |

关键事实（读过源码，不是猜的）：工具清单在 `serve(...)` 里**静态声明**，`require_gh()` 只在
**调用**时才检查本机装没装 CLI。所以评测只需要"连上并列工具"，本机不需要装 `gh` / `himalaya`。

配置形状照 `application-local.yml` 里那两段：`command: python3` + `args: [<仓库内相对路径>]`。
评测上下文用 `@DynamicPropertySource` 注入，路径取 `TestPaths.repositoryRoot()`。

## 要等的那个坑

`McpToolProvider` 的连接是**异步 + 退避重连**的（票 02 / 07 的设计），所以
`@BeforeAll` 里立刻取 `ToolCatalog` 很可能还没有这五个工具。必须**有界等待**：
轮询目录直到出现 `mail_list_unread`（或超时 30 秒后失败并说清"哪个 server 没连上"）。

不能"等不到就跳过这几题"——那等于把本票要验的东西悄悄取消，而报告上看不出来。

## 题库怎么回补

四道原题按票 13 之前的样子恢复，同时**删掉票 13 加的那四道替代题**（`tools-sales-detail` /
`tools-schedule-daily-only` / `tools-schedule-stop-one` / `tools-recall-decision`）：

- 总数保持 38 条（base 4 / business 12 / zh-en 20 / no-search 2）；
- 工具目录 19 → 24（多出 5 个 MCP 工具），**所有分数与 `baseline.json` 全部作废**，收票时重钉；
- 干扰项变多，所以各档 `R@5` 大概率会掉一点——那不是退化，是题目变难了，写在收票记录里。

## TDD 用例清单

| 判什么行为 | 用例 | 先红的样子 |
| --- | --- | --- |
| 两个 server 的工具真的进了目录 | `ToolRetrievalExportTest#mcpToolsReachTheCatalogue` | 注入前红：24 个里没有 `mail_list_unread` |
| 四道题的工具都在目录里 | 现有 `ToolRetrievalExportTest#everyGoldToolExistsInCatalogue` | 回补题目但 MCP 没接上时红（这就是本票的验收） |
| 等不到就说清哪个 server 没连上 | 同一条（超时分支抛异常，消息含 server 名） | 静默跳过时绿——所以这条要断言"消息里有 `mail`" |
| `business` 那一档恢复成 12 道、且含 4 道外部系统题 | `ToolGoldTest#businessCoversExternalSystems` | 替代题还在时红 |

复核：<待补，收票时写日期与命令>

## 缺口（本票不做）

- **不真调这些工具**：评测只测检索层，`gh` / `himalaya` 没装也不影响。
- **不接会出网的 server**（`npx @modelcontextprotocol/server-everything`、deepwiki）：评测要可复现，
  出网的东西会引入与检索无关的抖动。
- **`github_api` 没有题**：它是"任意端点"的工具，问法太宽，出了题也判不准，先空着。

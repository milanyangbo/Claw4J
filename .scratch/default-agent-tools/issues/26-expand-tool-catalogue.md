# 票 26：把工具目录从 24 个扩到 51 个——先有足够多的工具，评测才有意义

**Status:** open（待批）
**裁决来源：** 用户 2026-11-26「我觉得目前 tool 和 mcp 还是有点少，可以多增加一些吧，至少增加一倍多」
「然后在此基础上做评测吧」。
**no ADR needed**（沿用 ADR-0003 的能力三层：能包成 MCP 的走 MCP，需要只读约束与 SQL 守卫的留在 Java 侧）。

## 为什么现在这 24 个不够

`eval/tools/retrieval/reports/catalogue.txt` 里是 24 个：

| 来源 | 个数 | 工具 |
| --- | --- | --- |
| 内建（Spring AI community + 自研） | 15 | Read / Grep / Glob / TodoWrite / WebFetch / Bash / BashOutput / KillShell / Task / TaskOutput / Skill / AskUserQuestionTool / recall_session / intent / systemStatus |
| 调度 | 3 | schedule_task / list_schedules / cancel_schedule |
| MCP `github` | 3 | github_issue_list / github_pr_checks / github_api |
| MCP `mail` | 2 | mail_list_unread / mail_send |
| example-sales（Java） | 1 | querySales |

三个具体问题：

1. **随机取 5 个的命中率是 5/24 ≈ 0.21**，绝对门 0.80 是它的近 4 倍——门槛看着高，其实一半靠运气。
2. **同族里只有一个工具**：github 三个、mail 两个，而且没有一组"必须区分"的近义工具。检索层最难的
   那种判断（`github_pr_checks` vs `github_pr_view`）现在根本没被考过。
3. **业务侧只有一把锤子**：`querySales` 收一条 SQL。模型得先知道表结构才能写对 SQL，而"表结构"
   这件事没有任何工具能回答。

## 目标：24 → 51（+27）

新增的四组，每一组都**真能干活**，不写占位工具。占位工具会让评测变成"测名字起得好不好猜"，
不是测检索。

### 1. MCP `github`：3 → 11（+8）

`examples/mcp/gh_mcp_server.py`，全走 `gh` 只读子命令（本机已装 gh 2.100.0 并认证）：

| 工具 | 一句话 |
| --- | --- |
| `github_pr_list` | 列一个仓库的 PR（按状态/时间） |
| `github_pr_view` | 看某个 PR 的标题、正文、评审状态 |
| `github_pr_diff` | 看某个 PR 的代码改动 |
| `github_issue_view` | 看某个 issue 的正文与评论 |
| `github_repo_view` | 看仓库概况（语言、star、默认分支） |
| `github_run_list` | 列 Actions 最近几次运行 |
| `github_run_view` | 看某次运行失败的 job 与日志尾巴 |
| `github_release_list` | 列最近的 release |

新增与现有 3 个的区分点：`issue_list` 是列表 / `issue_view` 是单条；`pr_checks` 是 CI 状态 /
`pr_view` 是内容 / `pr_diff` 是改动。

### 2. MCP `mail`：2 → 8（+6）

`examples/mcp/himalaya_mcp_server.py`，走 `himalaya`。**本机没装 himalaya**（`which himalaya` 为空），
所以这 6 个在跑通前只到"返回一句怎么装"。但 mail 现有那 2 个也是这样——工具存在、能被检索到、
调用时如实说缺依赖，这与现状一致，不新增不确定性。

| 工具 | 一句话 |
| --- | --- |
| `mail_search` | 按主题/发件人/正文关键词搜邮件 |
| `mail_read` | 读某一封邮件的全文 |
| `mail_reply` | 回复某一封（有副作用） |
| `mail_move` | 把邮件移到别的文件夹（有副作用） |
| `mail_list_folders` | 列账户下的文件夹 |
| `mail_download_attachment` | 把某封邮件的附件存到本地 |

### 3. 新 MCP `git`：8（+8）

新建 `examples/mcp/git_mcp_server.py`，与 gh / himalaya 同一形态（`mcp_stdio.Tool` + `run_cli`），
包本机 `git`。选它而不是接 `server-git` 那个 npx 官方实现，理由有两条：**启动 0.2 秒 vs 36 秒**
（`application-local.yml` 里已经为 npx 的超时写过注释），以及**描述可以写成中文**——评测里
"中文问句 × 英文描述"那一档（zh-en）需要英文描述做对照，两个都在才有得比。

| 工具 | 一句话 |
| --- | --- |
| `git_status` | 工作区有哪些改动、哪些已暂存 |
| `git_log` | 最近若干次提交（作者、时间、摘要） |
| `git_diff` | 某个范围的代码改动（工作区 / 两次提交之间） |
| `git_show` | 看某次提交改了哪些文件 |
| `git_blame` | 某一行/某个文件每行的最后一次改动 |
| `git_branch_list` | 本地与远端分支 |
| `git_stash_list` | 暂存栈里有几条 |
| `git_conflict_list` | 当前冲突的文件 |

### 4. Java `sales` 增补：1 → 6（+5）

`claw4j-example-sales` 里加一组 schema 探查工具，**复用现有 `ReadOnlySqlExecutor` + `SalesSqlGuard`**，
不新开执行路径。都是 `SELECT`，能过现有守卫（`information_schema` / `pg_catalog` 查询首关键字是
`select`）；`EXPLAIN` 过不了守卫，所以不做——不为一个新工具去放宽只读校验。

| 工具 | 一句话 |
| --- | --- |
| `listTables` | 这个库有哪些表、各属于哪个 schema |
| `describeTable` | 某张表有哪些字段、类型、是否可空 |
| `sampleRows` | 某张表的前若干行（看数据长什么样） |
| `columnDistribution` | 某一列有哪些取值、各出现多少次 |
| `tableRowCount` | 各表大约多少行（判断该不该扫它） |

这条线补的是真实工作流：**先看有哪些表 → 再看字段 → 再写 SQL**。现在模型只能直接猜表名。

## 必须同步做的一件事：题库 50 → 80

`gold.yaml` 现在 50 题只覆盖 22 个工具（`Task` 与 `github_api` 都没题）。新增 27 个工具后，
如果不扩题，新工具在评测里**等于不存在**——catalogue 变长了，`R@5` 的分母没变，分数还会因为
候选变多而虚降。

做法：每个新工具至少 1 题，高频的（`github_pr_*`、`describeTable`）2 题，中文问法、不点工具名。
`ToolGoldTest` 里加一条硬断言：**catalogue 里每个工具都至少有一道题**——不写这条，下次加工具
还会忘。

## 先说清楚会变差的地方

**`R@5` 会掉，我不知道会掉多少。** 候选池从 24 涨到 51，五选一的难度上升；同族工具又互相像
（`github_pr_view` / `github_pr_diff` / `github_pr_checks` 三个描述只差几个词）。现有绝对门
`R@5 ≥ 0.80` 有可能跌破，跌了就如实报红，不改门。

这不是坏事——它正好把"同族区分"这个真难点第一次摆到台面上。但结论要提前说死：**这一票不承诺
分数变好，只承诺工具变真**。

## TDD 用例清单

| 判什么行为 | 用例 | 先红的样子 |
| --- | --- | --- |
| 三个 MCP server 的工具名唯一、描述非空、required 都在 properties 里 | 新建 `examples/mcp/test_servers.py`（`unittest`，零第三方依赖） | 现在没有这个文件；加完工具后名字打错（如 `mail_readd`）没有任何东西会红 |
| 新 server 能被 MCP 协议列出 | 同上，跑 `tools/list` 的 JSON-RPC 帧 | 没这个 server |
| schema 探查工具在真库上返回表/字段 | `claw4j-example-sales` 新增 `SchemaToolsTest`（Testcontainers） | 类不存在 |
| 探查工具不绕过只读守卫 | 同上：给一条 `drop table` 走 `describeTable` 之外的入口 | 新入口若自己拼 SQL，守卫就绕过去了 |
| 每个工具都有题 | `ToolGoldTest#everyCataloguedToolHasAQuestion`（新增） | 现在 `Task`、`github_api` 无题，这条一写就红 |
| 目录里认得的工具与新增一致 | `ToolRetrievalExportTest` 的 required 集合（338 行） | 现在只钉了 4 个名字 |

复核：`./scripts/verify.sh` 全绿；`./eval/tools/retrieval/run.sh` 出新的 catalogue 与 `R@5`。

## 缺口

- **mail 那 6 个在本机跑不通**（没装 himalaya）。它们能进目录、能被检索，但调用只返回"怎么装"。
  与现有 2 个同状态，不算新增风险；但如果哪天要量"工具真的能干活"，mail 这一族得先装 himalaya。
- **不做 `EXPLAIN`**：守卫不允许，且放宽守卫的代价大于多一个工具。
- **不接 npx 官方 server**：启动 36 秒会让 `run.sh` 的初始化超时，且描述是英文的、与现有中文工具体系
  不成套。要用的话是另一张票（连同超时与 `inherit-env` 一起改）。
- **不加 `Write` / `Edit`**：它们在 `pinned` 之外、默认档不开。开它们改的是能力面（能改文件），
  与"把目录变长"不是一件事。

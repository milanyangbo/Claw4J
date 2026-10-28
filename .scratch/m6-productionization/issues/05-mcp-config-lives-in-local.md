# 05: 接哪几个 MCP server 是这台机器的事

**What to build:** 删掉 `application-mcp-demo.yml` 与 `application-mcp-third-party.yml`，
内容并入 `application-local.yml.example`（随仓库的模板）与 `application-local.yml`（本机，被忽略）。

**Blocked by:** `01`（它先删了 `McpDemoConfiguration`，这里接着处理剩下的那份"演示配置"）。

**Status:** resolved

## 为什么

`01` 把 `McpDemoConfiguration` 删了，但"演示用的那三个 server"并没有消失——它们变成了
`application-mcp-demo.yml`，仍然住在框架的 `src/main/resources` 里，名字还叫 demo。
**"框架里没有 demo"不是把 demo 类换成 demo 配置就叫完成了。**

更根本的一点：这两份文件回答的是同一个问题——**这台机器要连哪几个 MCP server**。
它跟"数据库连哪个""飞书 open_id 是谁"是同一类信息：部署环境的事，不是框架的事。
分成两个 profile 有三个具体代价：

- 同一台机器想同时接"仓库自带的"和"别人写的"，命令行要 `local,mcp-demo,mcp-third-party` 三连，
  而且下次启动还得记住上次点的是哪一套；
- `mcp-demo` 这个名字会让人以为"接 MCP 要开一个开关"，而它其实只是一段 YAML；
- 两份文件里各有一半真话：一份讲相对路径与 CWD，一份讲超时与 Node 依赖，看的人要拼。

## 要做

1. 内容并入 local：仓库自带的三个 Python server 默认开着（只要 `python3`）；
   `npx` 的 stdio 与公网 HTTP 两条默认**注释着**（要 Node 与出网，不该成为本地启动的硬门槛）。
2. 注释块必须**能机械解开**：块内只有配置行，说明放在块上方与行尾；从 `# everything:`
   到文件末尾逐行删掉行首的 `# ` 即生效，缩进自动对齐。
3. `git rm` 两份 profile 文件；改掉 `README`、`CONTRIBUTING`、`examples/README`、
   `docs/design/capability-layers.md`、`docs/design/extension-points.md` 与两处测试 javadoc 里的引用。
4. 本机 `application-local.yml` 跟着同步（它被忽略，但内容必须一致，否则"改了没生效"）。

## 判据

- `--spring.profiles.active=local` 一条命令起来，三个 Python server 都连上。
- 真模型调一次 `demo_echo` 能回话（不是看日志推断）。
- 把注释块按"删掉行首 `# `"机械解开后能起来，且五个 server 全连上（含 npx 与公网 HTTP）。
- 全量门禁全绿。

## 不要做

- 不新增 profile，也不新增 Java 类——`01` 刚刚证明这件事用配置就够。
- 不改 MCP 的键名与默认值。
- 不把 npx 那条默认打开：那会让"没装 Node 的机器起不来"成为本地开发的默认值。

## 实现记录

**做了什么**

1. `application-local.yml.example` 新增 `claw4j.capability.mcp` 一节：三个 Python server 开着；
   末尾是注释着的 `everything`（npx）与 `deepwiki`（Streamable HTTP），块内没有说明行，
   说明都放在块上方与行尾——这样"删掉行首 `# `"就是字面意义上的解注释。
2. `git rm` 两份 profile 文件。`claw4j-app/src/main/resources/` 现在只剩
   `application.yml`、`application-local.yml.example`、`application-local.yml`。
3. 本机 `application-local.yml` 由模板复制而来，只填真实 `open_id`（它被 `.gitignore` 忽略）。
4. 引用全部改掉：`README`（4 处 + 前置加 `python3`）、`CONTRIBUTING`（冒烟命令与门禁表）、
   `examples/README`（3 处）、`docs/design/capability-layers.md`（2 处）、
   `docs/design/extension-points.md`（参照实现的链接）、`McpServersConfigTest` 与
   `RealThirdPartyMcpTest` 的 javadoc。

**判据核对**

| 判据 | 结果 |
| --- | --- |
| 只带 `local` 起来，三个 Python server 都连上 | 成立：`Started Claw4jApplication in 4.042 seconds`，`MCP server started` × 3，无 ERROR |
| 真模型调 `demo_echo` 能回话 | 成立：`/api/chat` 回 "合并后仍然跨进程" |
| 机械解注释后五个 server 全连上 | 成立：启动 64 秒，4 条 stdio（3 Python + npx）+ 1 条 HTTP，无 `连不上` |
| 全量门禁全绿 | 成立：**301 条**，失败 0、错误 0、跳过 0；`claw4j-channel` 63.8% / `channel-core` 86.3% |

**顺带纠正了一条不成立的文档说法**

npx 那条一解开就失败：`MCP server everything 连不上：Client failed to initialize by explicit API call`，
超时值是 `within 30000ms`。而配置里明明写着 `initialization-timeout: 90s`——文档一直说
"接更慢的启动器就调大后者"（`README` 与 `capability-layers` 都这么写）。三组对照：

| `request-timeout` | `initialization-timeout` | 结果 |
| --- | --- | --- |
| 默认 30s | 90s | 失败，`within 30000ms` |
| 120s | 1s | 失败，`within 1000ms` |
| 120s | 90s | 成功，启动 64 秒 |

单测 `npx -y @modelcontextprotocol/server-everything --help` 冷启动 **36 秒**——它比默认的
30 秒 `request-timeout` 慢，所以只调握手那个根本轮不到。**两个都得按启动器的真实启动时间给足。**
`README`、`capability-layers` 与模板里那段注释都已按上表改写（capability-layers 留了这张表）。

**踩到的坑（同一个坑，第三次）**

排查时我用 `grep "initialization-timeout\|request-timeout" /tmp/...` 查临时配置，返回空，
于是差点得出"那个 90s 没被解开"的错误结论。本机 `grep` 是 toybox，不支持 BRE 的 `\|`，
**不报错、静默返回 0 行**。搜索一律走 Grep 工具，这个坑已经在全局 deny 里禁掉了 `Bash(grep:*)`，
但新开的 shell 里它仍然在——判据是"同一件事连续查三次都是空，先怀疑工具"。

**没做的**

`claw4j-app` 的 `src/main/resources` 里仍留着 `application-local.yml.example`——它是"模板"，
必须随仓库走（真实的 `application-local.yml` 被忽略）。若以后要彻底不带 MCP 示例，
该由一个独立的 examples 装配来做，而不是塞回框架资源里。

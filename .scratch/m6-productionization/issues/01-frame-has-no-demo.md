# 01: 框架里不留演示类

**What to build:** 删掉 `claw4j-app/McpDemoConfiguration`，`examples/mcp/` 的三个 Python server
改成 `application-mcp-demo.yml` 里的一段配置；顺带清掉代码里写死的示例目录。

**Blocked by:** 无（`ConfiguredMcpServersAutoConfiguration` 已在 M5 落地）。

**Status:** resolved

## 为什么

一个生产框架里出现 `*DemoConfiguration`，本身就是一句错误的话：它暗示"接一个 MCP server
要写一个 Java 类"。事实不是——M5 之后一段配置就够了。留着这个类，读代码的人会照着它写。

第二件事是它里面的 `DIRECTORY_PROPERTY`：默认值 `examples/mcp` 是仓库的目录结构，
用 `@Value("${claw4j.mcp.demo.directory:examples/mcp}")` 取出来再 `toAbsolutePath()`。
框架不认识"仓库根在哪"，这类默认值不该由框架提供。真正需要的语义只有一条：
**stdio 子进程继承宿主的 CWD，所以配置里的相对路径按 CWD 解析**。这不需要 Java 代码。

## 要做

1. `claw4j-app/src/main/resources/application-mcp-demo.yml`：三个 server（`demo` / `github` / `mail`），
   `args` 用相对路径，环境变量走默认的 `inherit-env: [PATH]`。
2. `mail` 那条按工具覆盖权限：兜底 `read`，只有 `mail_send` 是 `send_email`。这是"混装 server"
   的真实例子，比注释里那段没有真去连的形状更有说服力。
3. 删 `McpDemoConfiguration.java`。
4. 文档里的启动命令从 `--spring.profiles.active=mcp-demo --claw4j.mcp.demo.demo-server=true`
   改成 `--spring.profiles.active=mcp-demo`。

## 判据

- 删掉那个类之后 `claw4j-app` 主代码只剩 `Claw4jApplication`。
- 用 `mcp-demo` profile 起应用，三个 server 都连上（工具清单为空会直接启动失败，
  所以"起来了"就是"清单拿到了"）。
- 不要再有任何 `@Value("${...:examples/...}")` 这种把仓库结构写进框架的默认值。

## 不要做

- 不把这三条 server 放进默认 profile：它们依赖本机 python3、gh、himalaya，
  "没装这些就起不来应用"不是产品默认值。
- 不为省事把权限写成整条 `read`：`mail_send` 是写动作。
- 不在框架里新增任何"检测示例目录存不存在"的代码。

## 实现记录

### 落地

- 新增 `claw4j-app/src/main/resources/application-mcp-demo.yml`：`demo` / `github` / `mail` 三条，
  `command: python3`，`args` 是相对路径。环境变量不写（默认 `inherit-env: [PATH]`）。
- `mail` 那条按工具覆盖：兜底 `action: read`，`tools.mail_send.action: send_email`。
- 删掉 `McpDemoConfiguration.java`。`claw4j-app` 主代码现在只剩 `Claw4jApplication`
  （测试另有 3 个文件）。
- 文档同步：`examples/README.md`（启动命令 + 说明"这里没有对应的 Java 类"）、
  `docs/design/capability-layers.md`（去掉 `--claw4j.mcp.demo.demo-server=true`）、
  README 的 MCP 演示段（本来就只有 profile，无需改）。

### 验证

1. **三个脚本自己先说通**（不经过 Java）：用 `initialize` + `tools/list` 三帧喂进去，
   分别拿到 `claw4j-demo` / `claw4j-github` / `claw4j-mail` 与
   `[demo_echo, demo_now]` / `[github_issue_list, github_pr_checks, github_api]` /
   `[mail_list_unread, mail_send]`。
2. **应用带 `mcp-demo` 起**：日志里三次 `StdioClientTransport: MCP server started`，
   随后 `Started Claw4jApplication in 4.13 seconds`。
   这条本身就是"工具清单拿到了"的证据——`McpToolProvider` 在清单为空时会直接抛异常导致启动失败。

### 没做的（如实记）

- **没有门禁用例**。上面第 2 步是手工跑的，靠的是"起不来就红"这个隐含断言；
  仓库里没有一个测试会在 `examples/mcp/*.py` 被改名时报警。
  要做到那一步，得有一条 `real-cli` tagged 用例去读这份 yml 并真连三个 server，
  代价是它依赖本机 python3。这一步留着，没有偷懒的理由，只是这轮没排进去。

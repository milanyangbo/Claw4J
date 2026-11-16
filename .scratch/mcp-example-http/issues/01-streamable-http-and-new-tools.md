# 01: 示例 MCP server 改成 Streamable HTTP，工具换成三个「有逻辑」的

**What to build:** `claw4j-example-mcp-server` 不再是一个跑 stdio 的 `main`，而是一个真正的
Spring Boot 应用：`spring-ai-starter-mcp-server-webmvc`，`/mcp` 上说 Streamable HTTP，
端口默认 8082（`CLAW4J_EXAMPLE_MCP_PORT` 可覆盖）。工具换成 `runtimeSnapshot` /
`cronNext` / `simulateSlowCall`，**与 `examples/mcp/demo_mcp_server.py` 的 `demo_echo` /
`demo_now` 不重名也不干同一件事**。

**Blocked by:** 无。

**Type:** feat（示例模块的传输方式与工具集）

**Status:** resolved

**裁决来源：** [`docs/design/extension-points.md`](../../../docs/design/extension-points.md)
「MCP：接一个 server 的两种传输」；传输取 Streamable HTTP 而不取 SSE，理由与
`HttpMcpEndpoint` 类注释一致（2025 版协议以它取代 SSE）。用户 2026-11-16 当场拍定：
走 HTTP、工具要「逻辑复杂一点、别跟 Python 那个重复」。

## 为什么改

- stdio 那个样本只能证明「能 fork 一个进程」，而 MCP 的另一半（HTTP、会话 id、
  request-timeout）一直没有可跑的对端。接第三方 server 才是这条路的常态。
- 原来的 `echo` / `serverTime` / `processId` 与 Python 那个 `demo_echo` / `demo_now`
  在做同一件事（回显、报时间、报 pid），两份样本摆在 `examples/` 下反而让人分不清该抄哪份。
- 改成「先起 server、再起宿主」之后，`McpToolProvider` 的退避重试才有观测点：
  server 不在 → `broken → DOWN(15 秒后重试)` → 起好之后自动 `UP`。

## 工具

| 工具 | 干什么 | 为什么是它 |
| --- | --- | --- |
| `runtimeSnapshot` | host / pid / 启动时刻 / 已处理调用数 / JVM / 时区 | 证明跨了进程；`pid` 不同、`startedAt` 变了 = 换了一个实例（重启后重连是否真的连上了新的，靠它判） |
| `cronNext(expression, count)` | 5 字段 cron 解析，返回未来 N 次触发时间（Asia/Shanghai） | 真的算一遍，不是回显；日与周同时限制时**明确拒绝**，不假装 Unix 的 OR 语义 |
| `simulateSlowCall(millis)` | 睡指定毫秒再返回实际等待 | 卡 `request-timeout`（默认 30s）的边界：慢 ≠ 不可用，也 ≠ 会话丢失 |

三者都不是「回显一句话」，也都不与 Python 那两份重名。

## TDD 用例清单

| 判什么行为 | 用例名 | 先红的样子 |
| --- | --- | --- |
| cron 表达式解析：`*`、`a-b`、`*/n`、`a,b`、周日的 0 与 7 等价 | `CronScheduleTest#parsesTheFiveFieldForms` | 把 `7` 当独立星期几 → 周日那条取不到 |
| 日与周同时限制 → 明确拒绝，不静默按某一种算 | `CronScheduleTest#refusesDayAndWeekdayTogether` | 去掉那条校验 → 返回一串时间，看着完全正常 |
| 未来 N 次是真的未来、且按时间递增 | `CronScheduleTest#nextRunsAreStrictlyIncreasing` | 从"当前这一分钟"起算而不是下一分钟 → 首条等于现在或倒序 |
| server 是独立进程、HTTP 上握手成功、工具清单是那三个 | `JavaMcpServerProcessTest#serverRunsAsItsOwnProcess` | 端口不通/清单为空 → 该条红（ADR-0002：清单为空当失败） |
| 连着调两次 `runtimeSnapshot`：pid 相同、调用数递增 | 同上（同一条用例里） | 每次调用都新建 server → pid 变化，红 |
| 关掉 server 进程后它真的消失 | 同上（用 `ProcessHandle` 等） | 不 kill 子进程 → 超时红 |

## 验收判据

| 判什么 | 判据 |
| --- | --- |
| 真的走 HTTP | server 起在 8082，`POST /mcp` 有响应；进程里没有 stdio 传输 |
| 协议版本 | 客户端 `initialize` 成功，`serverInfo.name` 是 `claw4j-http-demo` |
| 工具不重复 | `grep -r "demo_echo\|demo_now" claw4j-example-mcp-server` 无命中 |
| 门禁 | `scripts/verify.sh` 全绿 |

## 缺口

- 会话丢失那一路（`McpTransportSessionNotFoundException` → 重开 + 重放一次）没有专门的
  用例：要制造它得让 server 侧销毁会话，SDK 没有公开入口。手工验法是重启 server 之后再调
  一次工具，能成功即说明重连生效。
- **运行期掉线没被当成"掉线"（本次实测发现，未修）**：server 在跑的时候被 kill，下一次工具调用
  抛 `java.net.ConnectException`，`McpConnection.callTool` 认不出这是连接级的失败（它只认会话丢失），
  于是既不 `lost()` 也不排重试——状态停在 `UP`，异常一路冒到 MVC，接口回 **500 空 body**。
  再下一次调用又自己好了（HTTP 传输懒重建），所以现场表现是"偶发 500"，很难和模型侧的问题区分。
  设计文档写的是"调用它按普通工具失败处理（`tool_failed`）"，代码没做到——先记在这里，
  修它要动 capability 层的失败分类，属于另一件事。
- 因此"退避重试"今天只在**启动期连不上**这一条路上生效；运行期掉线没有退避。

## 实现记录

- 模块：`spring-ai-mcp` + 手写 `main` → `spring-ai-starter-mcp-server-webmvc` +
  `@SpringBootApplication`。端点 `/mcp`（Streamable HTTP），端口 8082（`CLAW4J_EXAMPLE_MCP_PORT` 可覆盖），
  `serverInfo.name = claw4j-http-demo`。`logback.xml`（把日志改到 stderr 的那份）随之删掉——
  那是 stdio 的约束。
- 工具：`OperationalTools`（`@Component` + `@McpTool`，由注解扫描器收进清单）。
  `runtimeSnapshot` / `cronNext` / `simulateSlowCall`，与 `demo_mcp_server.py` 的
  `demo_echo` / `demo_now` 不重名、不干同一件事。`DemoJavaTools` 与其用例删除。
- `CronSchedule`：五字段解析（`*`、`n`、`a-b`、`*/n`、`a-b/n`、逗号列表；`0` 与 `7` 都是周日），
  按分钟迭代求解、上限四年；**日与周同时限制直接拒绝**，不假装 Unix 的并集语义。
- 用例：`CronScheduleTest`（3 条）、`OperationalToolsTest`（5 条）、
  `JavaMcpServerProcessTest` 改写成 HTTP——随机空闲端口、`java -cp <java.class.path>` 起子进程、
  `HttpClientStreamableHttpTransport` 连、断言清单/pid/计数/关掉后进程消失。
- 覆盖率：模块 91.3%（3 个文件、最大方法数 5）。
- **变异验证**：把 `0`/`7` 的归一去掉 → 只有周日那条红（`parsesTheFiveFieldForms:41`）；
  删掉"日与周同时限制"那段校验 → 只有 `refusesDayAndWeekdayTogether` 红。
- 端到端（`--spring.profiles.active=local`，仓库根）：server 起来后启动应用，日志
  `java-http → UP(工具 3 个)`；问 `用 runtimeSnapshot 报一下 pid` 回的是 server 进程的
  pid（与宿主不同）与 `handledCalls`。

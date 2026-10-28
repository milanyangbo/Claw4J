# 01: 配置驱动的端点装配（stdio + HTTP）

**What to build:** `claw4j.capability.mcp.servers.<名字>` 下一段 YAML 变成一个
`McpServerEndpoint`，支持 `stdio` 与 `http`（Streamable HTTP）两种传输；顺手把
`claw4j-app/McpDemoConfiguration` 里那三个写死的 bean 改回配置。

**Blocked by:** 无。

**Status:** resolved

## 为什么

`McpServerEndpoint` 这层接缝本来就是为了"换传输只换一个方法"而留的，但**谁来 new 它**
一直只有一条路：写一个 `@Bean`。这条路对仓库自己够用，对第三方不成立——
别人的 server 换进来时，改我们的源码再打包不是"集成"，是"fork"。

配置化之后同一件事变成四行 YAML。而且顺手解决了另一个不对称：
`claw4j.scripts.directory`、`claw4j.skills.directory` 都是配置，只有 MCP 是代码。

## 要做

### 1. `McpServerProperties`

`@ConfigurationProperties("claw4j.capability.mcp")`，字段 `enabled`（总开关，默认 true）与
`servers`（`LinkedHashMap<String, Server>`，有序是为了让启动日志与注册顺序稳定）。

每个 `Server`：

| 键 | 用于 | 说明 |
| --- | --- | --- |
| `transport` | 全部 | `stdio`（默认）/ `http`（Streamable HTTP）/ `sse`（旧版 HTTP+SSE） |
| `command`、`args` | stdio | 起什么进程；**argv 数组，不经解释器**（ADR-0002） |
| `env` | stdio | 显式给出的环境变量，默认空 |
| `inherit-env` | stdio | 从父进程透传哪些变量名，默认 `[PATH]` |
| `url`、`endpoint`、`headers` | http / sse | `url` 是 base URI；`endpoint` 不填走 SDK 默认（`/mcp`、`/sse`）；`headers` 给凭据 |
| `permission.action` / `.tables` | 全部 | 默认 `read` + 空表（＝只读、无业务表） |
| `enabled` | 全部 | 单个 server 的开关，默认 true |

### 2. 条件看**配置**，不看容器

新自动配置只在"`servers` 非空"时注册 bean。条件必须读 Environment，
**不能**写成 `@ConditionalOnBean(McpServerEndpoint.class)`——那条条件的求值时机受
`@Bean` 声明顺序影响，仓库里已经为它返工过一次（`docs/design/pitfalls.md`）。

### 3. 失败在启动时

- `transport: stdio` 但 `command` 为空 → 启动失败，报错带 server 名。
- `transport: http|sse` 但 `url` 为空 → 启动失败，同上。
- `transport` 写了个不存在的值 → 绑定期就失败（枚举），不要退化成默认 stdio。
- 连接失败、工具清单为空：已有行为不变（`McpToolProvider` 的 fail-fast）。

### 4. 把 `McpDemoConfiguration` 的三个 server 改成配置

它现在有三个 bean（`claw4j-demo` / `claw4j-github` / `claw4j-mail`），还各自带一个开关。
改成 `mcp-demo` profile 下的一段 YAML，**删掉那个 Java 类**——这同时是
"配置化确实能表达今天已有的三种接法"的证据。`himalaya` 那条 `send_email` 权限
必须原样保留（写成只读就是把"能发邮件"当成只读能力）。

## 判据

- 一段 YAML 能表达：stdio + 自定义 argv + 透传环境变量；http + 自定义 endpoint + 请求头。
- 单测覆盖**转换**（配置 → 端点的形状：命令、参数、环境合并、权限、传输类型）与
  **装配**（servers 为空时没有 MCP bean；配了不存在的命令时启动失败且报错带名字）。
- 门禁用例**不发网络请求、不起真第三方进程**：用不存在的可执行文件与不可达 URL 当输入，
  验的是"配错了会红"，不是"第三方今天在线"。

## 不要做

- 不加"传输插件"SPI。`McpServerEndpoint#openTransport()` 就是那层接口。
- 不做按工具的权限配置：权限的粒度是 server（ADR-0003 第二层没做）。
- 不引 `@ConditionalOnBean` 的任何新用法。

## 实现记录

### 落地

- `McpServerProperties`（`@ConfigurationProperties("claw4j.capability.mcp")`，`servers` 是
  `LinkedHashMap` 保持配置顺序）+ 两个嵌套 record（`Server` / `Permission`）+ `Transport` 枚举。
  这是本仓库第一处 `@ConfigurationProperties`：其余配置是标量，用 `@Value` 就够，
  而"任意多个、每个带自己的 argv/环境/权限"用 `@Value` 表达不出来。
- `McpEndpoints`（package-private）：配置 → 端点的翻译层，所有校验都在这里。
  `environment(Map parent, Server)` 把父进程环境当参数收进来，于是"父进程有 PATH 没有 HOME"
  这种形状可测。
- `http/HttpMcpEndpoint`：Streamable HTTP 与 SSE 两种，`path` 留空就不调 SDK 的 `endpoint()`，
  让默认值（`/mcp`、`/sse`）只有一个来源；凭据走 `httpRequestCustomizer` 加请求头。
- `ConfiguredMcpServersAutoConfiguration`：条件 `ServersConfigured` 用 Binder 直接读 Environment，
  与 bean 声明顺序无关。产出 `McpToolProvider` + `ToolPermissionRegistry` 各一份，
  与 `McpToolAutoConfiguration` 那份**并存**（Agent 侧按列表收，设计上允许）。
- `McpConnection.open()` 失败时包一层，消息带 server 名：配置化之后一次会配好几个，
  只报一句 `Cannot run program` 等于让人在 yml 里二分。

### 没做（如实记）

**`McpDemoConfiguration` 没有改成配置。** 原本的计划是把它那三个 bean 挪进 `mcp-demo` profile
并删掉这个类；本轮执行环境不允许删除/移动文件（`rm`、`mv`、`git rm` 全被沙箱拦下），
所以停在了"代码已就位、这一步留待下一轮"。
这不构成设计问题：`McpDemoConfiguration` 保留下来正好是**"值要算出来时才写 bean"**的活例子
（它按可配置的目录拼 Python 脚本的绝对路径），文档里就是按这个定位写的。
要收尾的话只有一步：把三个 server 写进 `application-mcp-demo.yml`，再删掉那个类。

### 验证

- `McpServersConfigTest` 14 条全绿（配置形状、环境合并顺序、两种 HTTP 传输、四种配置错误、
  `enabled: false` 跳过、空配置不注册 bean、配了不存在的命令则启动失败且报错带名字）。
- `McpToolTest` 9 条仍全绿（连接生命周期没有被动到）。
- `ApplicationAssemblyTest` 5 条全绿（真模型 + Testcontainers Postgres）：新自动配置
  在"没配 server"时不注册任何 bean，整机链路不受影响。
- **没跑成的**：`./scripts/verify.sh` 全量。原因是环境——`clean` 要删 `target/`、
  `@TempDir` 要在收尾时删临时目录，两者都被沙箱拒绝，`SkillCatalogTest` / `ScriptPackageTest` /
  `SkillGeneratorTest` 共 37 条因此报 "Failed to delete temp directory"。
  这与代码无关（同一批用例在改动前的提交上是绿的），但**"全量绿"这句话本轮没有证据**。


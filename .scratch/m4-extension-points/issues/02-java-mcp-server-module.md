# 02: Java 版 MCP server 独立成 `claw4j-example-mcp-server`

**What to build:** 一个能 `java -jar` 独立运行、用 stdio 说 MCP 协议的 Java server，
放在新模块 `claw4j-example-mcp-server`。它导出两个工具（`echo`、`serverTime`），
与 `examples/mcp/*.py` 平级：**只能通过协议被接进来，谁都不 import 谁**。

**Blocked by:** 无

**Status:** resolved

## 为什么

两个问题，一个症状：

1. **Java 写 MCP server 这件事，仓库里只有一段测试替身。** `InProcessStdioMcpServer` 在
   `claw4j-capability` 的**主代码**里，实际只有 `McpToolTest` 用它。它是替身，却进了发行物。
2. **`examples/mcp/` 三个 server 全是 Python。** 于是"接一个 MCP server"这件事在读者眼里
   等于"写 Python"，而这条链路的协议与语言无关。

顺带把 "MCP 那一层不绑定 Python、也不绑定本进程" 变成能看见的东西。

## 要做

- [ ] `InProcessStdioMcpServer` 与 `PipedStdioClientTransport` 从 `capability/src/main` 挪到
      `capability/src/test`（**包名不变**，所以 `McpToolTest` 的 import 一行都不用改）
- [ ] 新模块 `claw4j-example-mcp-server`，包 `io.github.claw4j.example.mcp.server`
- [ ] `McpServerApplication#main`：`McpServer.sync(StdioServerTransportProvider(System.in, System.out))`
      + `SyncMcpToolProvider` 把 `@McpTool` 对象变成 tool spec；**工具类与 `SystemStatusTools` 同一副长相**
- [ ] 模块用 `spring-boot-maven-plugin` 的 `repackage` 打成可执行 jar（`java -jar` 直接跑）
- [ ] 一条**真子进程**的用例：起 `java -cp <本模块完整 classpath> ...McpServerApplication`，
      用 SDK 的 `StdioClientTransport` 连上去，`initialize` → `listTools` → `callTool` → 核对回值
- [ ] `application-local.yml.example` 或 `examples/README.md` 里给一条可复制的连接配置

## 判据

- 用例里 server 是**另一个进程**（断言 `Process.isAlive()`，并断言 classpath 里有本类的 class 文件），
  不是进程内管道——否则它证明不了"跨进程"这件事，只是把替身换了个写法。
- `close()` 之后子进程要真的退出：用例在 `finally` 里 `destroyForcibly` 并断言 5 秒内结束。
  子进程泄漏在 CI 上表现为"某台机器越跑越慢"，很难查。
- 变异一：把 `tools(...)` 传成空列表 → `listTools` 那条必须红（这正是 ADR-0002 的 fail-fast 语义：
  连接是通的、但一件能力都没有，必须报错而不是静默）。
- 变异二：`main` 里不阻塞（`build()` 之后直接返回）→ 真进程那条用例必须红。
  **这一条要先实测**：`StdioServerTransportProvider` 是否自带非守护线程决定 `main` 要不要显式等待，
  别照印象写。

## 注意

- **`surefire` 默认给测试的是一条 manifest-only 的 booter jar**，`java.class.path` 指向它。
  把这条路径当子进程的 `-cp` 传下去，能不能加载到类取决于 manifest 而不取决于目录结构——
  本模块因此在 `pom.xml` 里关掉 `<useManifestOnlyJar>`，并在注释里写清原因。
- 子进程的 **JAVA_HOME 用 `System.getProperty("java.home")`**，不要读环境变量：
  surefire 里那个变量可能为空（本机就踩过）。
- 不能因为"跑得慢"把它归进 `real-cli` 组。它只依赖 JDK，没有外部 CLI，**属于回归基线**。

## 不要做

- 不做 HTTP/SSE 传输的 server（那是另一个部署形态，先只做 stdio）。
- 不做 MCP 的 resources / prompts / sampling——**这一版只需要 tools**，多做的每一件都要有测试盯着。
- 不把 `McpConnection` / `McpToolProvider` 这类 client 侧的代码搬进新模块。

## 实现记录（2026-10-28）

### 结果

`claw4j-example-mcp-server` 落地：`DemoJavaTools`（`echo` / `serverTime` / `processId`）、
`McpServerApplication`（stdio，`spring-boot-maven-plugin` 打成 `java -jar` 可执行的包）、
`logback.xml`（出口只走 stderr）。**pom 里没有任何 `io.github.claw4j` 依赖**。

测试替身归位：`InProcessStdioMcpServer` 与 `PipedStdioClientTransport` 从 `capability/src/main`
挪到 `capability/src/test`（包名没动，`McpToolTest` 的 import 一行没改）。

### 第一条真红：INFO 日志把协议帧顶掉了

第一次跑进程级用例，客户端报：

```
Client failed to initialize by explicit API call
```

server 侧看起来一切正常——因为它确实正常：SDK 自己打的那行
`Client initialize request - Protocol: 2025-06-18 ...` **写进了 stdout**，
客户端拿第一行当 JSON 解析，失败。修法是把日志出口改到 stderr（`logback.xml`），
并把这一条写进类注释与集成指南——它属于"不报错、只表现为连不上"的那类坑。

### 变异一：不导出工具 → 红

```java
.tools(java.util.List.of())   // 变异
```
`JavaMcpServerProcessTest` 红在 `listTools` 上。server 照常握手、照常回响应，
只是清单是空的——这正是 ADR-0002 要求调用方 fail-fast 的那种状态。

### 变异二：`main` 直接返回 → **不红**，推翻了一条注释

票面要求"先实测 `main` 返回会不会让进程退出"。实测结论：**不会**。
把 `new CountDownLatch(1).await()` 删掉，用例照样全绿——传输用的是非守护线程。
所以原注释里那句"SDK 的传输线程是守护线程，main 一返回 JVM 就退出"是**编的**。

改法不是加回去，而是**删掉那个闩**：客户端关 stdin → 传输线程收尾 → 进程自然退出，
而"关掉客户端后子进程必须真的没了"已经由 `awaitility` 那条断言盯着。
多留一个 `await()` 等于多一条测试看不见的、决定进程寿命的依赖。

### 覆盖率：0% → 75%

进程级用例把 server 起在**另一个 JVM**，jacoco 只看得见自己那一个，于是模块覆盖率第一次是 **0.00**，
闸直接判红（这是它该做的事）。补 `DemoJavaToolsTest`（3 条：echo / 钉住的时钟 / pid 是当前进程）
之后 75%。分工也因此更清楚：工具自己算得对由单元用例管，协议与进程边界由进程级用例管。

### `surefire` 的 classpath

进程级用例要把"本模块完整 classpath"传给子进程，而 `surefire` 默认给的是 manifest-only 的 booter jar。
模块里关掉 `<useManifestOnlyJar>false</useManifestOnlyJar>`，理由写在 pom 的注释里。
子进程的 java 用 `System.getProperty("java.home")`，不读 `JAVA_HOME`（surefire 里可能是空的）。

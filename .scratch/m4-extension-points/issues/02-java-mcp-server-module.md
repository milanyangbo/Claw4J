# 02: Java 版 MCP server 独立成 `claw4j-example-mcp-server`

**What to build:** 一个能 `java -jar` 独立运行、用 stdio 说 MCP 协议的 Java server，
放在新模块 `claw4j-example-mcp-server`。它导出两个工具（`echo`、`serverTime`），
与 `examples/mcp/*.py` 平级：**只能通过协议被接进来，谁都不 import 谁**。

**Blocked by:** 无

**Status:** claimed

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

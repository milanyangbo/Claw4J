# 03: stdio 子进程只拿显式声明的环境变量

**What to build:** `ProcessStdioMcpEndpoint` 的类注释写着"不传就是空表，子进程拿不到应用进程的
环境，应用的模型 key、数据库口令都不会漏过去"。这句话今天**是假的**：SDK 的
`StdioClientTransport.connect()` 用 `new ProcessBuilder()` 起进程，再
`environment().putAll(params.getEnv())`，父进程环境照样继承（`mcp-core 2.1.0`
`StdioClientTransport.java:145-148`，没有 `clear`）。本票二选一：把承诺兑现，或者把承诺改掉。

**Blocked by:** 无。

**Type:** task

**Status:** ready-for-agent

**Demo:** 配一个 stdio server，`inherit-env` 只写 `PATH`，父进程里有一个 `CLAW4J_CANARY`
（或任何没声明过的变量）——子进程打印出来的环境里**没有**它。

## 裁决来源

- `ProcessStdioMcpEndpoint` 类注释（环境一节）与 `McpServerProperties.Server#inheritEnv` 的注释：
  承诺的是"白名单"，不是"在继承的基础上加几个"。
- `docs/adr/0002-script-execution-via-mcp-with-sandbox.md`：子进程边界上，环境是四条约束之一。
- 同仓已有正确写法：`capability/process/LocalProcessRunner.java:72-75`
  （`environment().clear()` 再 `putAll`）。本票只是让 MCP 那一路与它同一个口径。

## 怎么做

8 行：一个 `StdioClientTransport` 的子类，覆盖 `protected ProcessBuilder getProcessBuilder()`，
在其中 `environment().clear()`。父类 `connect()` 随后 `putAll(params.getEnv())`，
最终子进程环境 = 显式声明的那几个。

这不是"自己拼传输"：进程、管道、帧、线程全部仍由 SDK 的 `StdioClientTransport` 负责，
本票只补它没有给出的一个钩子。**若判断不值得加这 8 行，就删掉注释里那句承诺**，
并在这里记一句"环境不隔离，靠部署层收"——两条都行，**只有"注释说隔离、代码不隔离"不行**。

## 要改的文件

| 文件 | 改什么 |
| --- | --- |
| `mcp/stdio/`（新类或端点内的静态内部类，二选一） | 8 行子类；`openTransport()` 改用它 |
| 测试 | 见下；`McpServersConfigTest` 已满 20 条，**合并**两条纯函数用例腾一个位置 |

## 验收判据（TDD 用例清单）

| 判什么行为 | 用例名 | 先红的样子（反例） |
| --- | --- | --- |
| 真起一个子进程：声明过的变量在，没声明过的**不在** | `McpServersConfigTest#theChildProcessOnlySeesDeclaredVariables`（新） | 去掉 `environment().clear()`，只有这一条红 |
| `inherit-env` 只给 `PATH` 时子进程拿得到 `PATH` | 同上（同一次断言里两条） | 把 `clear()` 挪到 `putAll()` 之后，只有这一条红 |
| 显式声明的键覆盖透传进来的同名键 | `McpServersConfigTest` 现有"env 覆盖 inherit"那条（不改） | — |

"真起一个子进程"的做法：`command: sh`、`args: ["-c", "env > \"$OUT\""]`，`OUT` 由声明的 env 给，
`initialization-timeout` 给 1 秒（它不是 MCP server，握手必然失败，失败不影响它先把文件写出来）。
断言文件里有 `PATH=` 与 `OUT=`，没有父进程独有那个变量（取 `System.getenv()` 里第一个不是
`PATH` 的键，把它从声明里排除）。

## 门禁

`./scripts/verify.sh` 全绿、跳过 0；`claw4j-capability` 10 个测试文件、≤20 条 `@Test`。

## 不要做

- 不动 `inherit-env` 的配置形态与默认值（默认仍 `PATH`）。
- 不做"按 server 单独给一组环境"的新配置层。
- 不在这一票里改 `SandboxPolicy`：脚本层那条已经是对的，不合并两者。
